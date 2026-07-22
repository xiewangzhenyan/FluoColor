package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.repository.ArrayResultRepository
import com.muc.fluocolorquant.domain.result.ArrayResultErrorCode
import com.muc.fluocolorquant.domain.result.ArrayResultLoadResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 历史面板使用的只读运行摘要，所有字段都来自 DetectionRun 冻结记录。 */
data class ArrayRunModelVersion(val analyteName: String, val version: Int)

data class ArrayRunHistoryItem(
    val runId: String,
    val timestampEpochMillis: Long,
    val status: String,
    val measurementCount: Int,
    val reliablePercent: Double?,
    val modelVersions: List<ArrayRunModelVersion>,
    val reasonSummary: String?
)

/** 阵列结果页面状态，损坏快照与普通数据库错误必须分别展示。 */
sealed interface ArrayResultUiState {
    data object Loading : ArrayResultUiState
    data class Success(
        val snapshot: ArrayResultSnapshot,
        val history: List<ArrayRunHistoryItem> = emptyList(),
        val switchingRunId: String? = null,
        val historyReadFailed: Boolean = false
    ) : ArrayResultUiState
    data object NotFound : ArrayResultUiState
    data class CorruptSnapshot(val errorCode: ArrayResultErrorCode) : ArrayResultUiState
    data object Error : ArrayResultUiState
}

/** 新阵列结果页面只通过仓库读取已经冻结的运行快照。 */
@HiltViewModel
class ArrayResultViewModel @Inject constructor(
    private val repository: ArrayResultRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<ArrayResultUiState>(ArrayResultUiState.Loading)
    val uiState: StateFlow<ArrayResultUiState> = _uiState.asStateFlow()
    private var loadedRunId: String? = null
    private var loadJob: Job? = null
    private val gson = Gson()

    fun load(runId: String?) {
        if (runId.isNullOrBlank()) {
            loadedRunId = null
            _uiState.value = ArrayResultUiState.NotFound
            return
        }
        if (loadedRunId == runId && _uiState.value !is ArrayResultUiState.Error) return
        loadJob?.cancel()
        loadedRunId = runId
        _uiState.value = ArrayResultUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                _uiState.value = when (val result = repository.loadSnapshot(runId)) {
                    is ArrayResultLoadResult.Success -> {
                        val historyResult = runCatching {
                            repository.getProjectRuns(result.snapshot.projectId)
                        }
                        val history = historyResult.getOrDefault(emptyList())
                        ArrayResultUiState.Success(
                            snapshot = result.snapshot,
                            // 单个项目的运行记录规模通常很小，直接在当前协程中完成稳定排序和摘要映射。
                            // 这里不切换到固定 Dispatchers.Default，避免测试调度器无法控制该异步任务，
                            // 同时也避免一次轻量列表转换引入不必要的线程切换开销。
                            history = history
                                .sortedByDescending { it.timestamp.time }
                                .map(::mapRunHistoryItem),
                            historyReadFailed = historyResult.isFailure
                        )
                    }
                    is ArrayResultLoadResult.Failure -> result.errorCode.toUiState()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                _uiState.value = ArrayResultUiState.Error
            }
        }
    }

    /** 只切换当前页面读取的 runId，不更新 Project 或任何历史 DetectionRun。 */
    fun selectRun(runId: String) {
        val current = _uiState.value as? ArrayResultUiState.Success ?: return
        if (runId.isBlank() || runId == current.snapshot.runId || current.switchingRunId != null) return
        loadJob?.cancel()
        _uiState.value = current.copy(switchingRunId = runId)
        loadJob = viewModelScope.launch {
            try {
                when (val result = repository.loadSnapshot(runId)) {
                    is ArrayResultLoadResult.Success -> {
                        if (result.snapshot.projectId != current.snapshot.projectId) {
                            _uiState.value = ArrayResultUiState.CorruptSnapshot(
                                ArrayResultErrorCode.PROJECT_RUN_MISMATCH
                            )
                            return@launch
                        }
                        loadedRunId = runId
                        _uiState.value = current.copy(
                            snapshot = result.snapshot,
                            switchingRunId = null
                        )
                    }
                    is ArrayResultLoadResult.Failure -> {
                        _uiState.value = result.errorCode.toUiState()
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                _uiState.value = current.copy(switchingRunId = null)
            }
        }
    }

    fun retry() {
        val runId = loadedRunId ?: return
        loadedRunId = null
        load(runId)
    }

    private fun mapRunHistoryItem(run: DetectionRun): ArrayRunHistoryItem {
        val qc = parseJsonObject(run.siteQcSummaryJson)
        val total = qc?.intValue("total") ?: run.wellsDetected ?: 0
        val reliable = qc?.intValue("reliable")
        val reliablePercent = if (reliable != null && total > 0) {
            reliable.toDouble() / total * 100.0
        } else {
            null
        }
        val modelVersions = run.effectiveConfigSnapshotJson?.let { json ->
            runCatching {
                TemplateProjectSnapshotCodec.decode(json).analytes.map { analyte ->
                    ArrayRunModelVersion(
                        analyteName = analyte.analyte.name,
                        version = analyte.analysisModel.model.version
                    )
                }
            }.getOrNull()
        }.orEmpty()
        val reasonSummary = when {
            !run.errorMessage.isNullOrBlank() -> run.errorMessage
            run.status == "RetakeRequired" -> "RETAKE_REQUIRED"
            run.status == "SignalOnlyCompleted" -> signalOnlyReason(run.concentrationModelUsed)
                ?: "SIGNAL_ONLY"
            else -> null
        }
        return ArrayRunHistoryItem(
            runId = run.runId,
            timestampEpochMillis = run.timestamp.time,
            status = run.status,
            measurementCount = total,
            reliablePercent = reliablePercent,
            modelVersions = modelVersions,
            reasonSummary = reasonSummary
        )
    }

    private fun signalOnlyReason(modelUsageJson: String?): String? {
        val root = parseJsonObject(modelUsageJson) ?: return null
        val reasons = mutableSetOf<String>()
        for ((_, value) in root.entrySet()) {
            val entry = value.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val reasonArray = entry.get("reasons")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            reasonArray.forEach { reason ->
                if (reason.isJsonPrimitive) reasons += reason.asString
            }
        }
        return reasons.joinToString().takeIf(String::isNotBlank)
    }

    private fun parseJsonObject(json: String?): JsonObject? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull()
    }

    private fun JsonObject.intValue(name: String): Int? {
        return get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()
    }

    private fun ArrayResultErrorCode.toUiState(): ArrayResultUiState {
        return when (this) {
            ArrayResultErrorCode.RUN_NOT_FOUND,
            ArrayResultErrorCode.PROJECT_NOT_FOUND -> ArrayResultUiState.NotFound
            ArrayResultErrorCode.DATABASE_READ_FAILED -> ArrayResultUiState.Error
            else -> ArrayResultUiState.CorruptSnapshot(this)
        }
    }
}

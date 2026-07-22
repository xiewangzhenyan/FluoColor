package com.muc.fluocolorquant.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.domain.detection.GridCarrierRoute
import com.muc.fluocolorquant.domain.detection.GridDetectionBlockReason
import com.muc.fluocolorquant.domain.detection.GridDetectionCoordinator
import com.muc.fluocolorquant.domain.detection.GridDetectionOutcome
import com.muc.fluocolorquant.domain.detection.GridDetectionRequest
import com.muc.fluocolorquant.domain.detection.GridDetectionRouteResolver
import com.muc.fluocolorquant.domain.detection.GridDetectionStage
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URLDecoder
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 微流控检测网关的可测试 UI 状态；所有用户文案由 Compose 根据枚举读取资源。 */
sealed interface GridDetectionUiState {
    data object ResolvingProject : GridDetectionUiState
    data object LegacyPlate : GridDetectionUiState

    data class Processing(
        val stage: GridDetectionStage
    ) : GridDetectionUiState

    data class Completed(
        val runId: String,
        val measurementCount: Int,
        val signalOnlyAnalyteIds: Set<String>,
        val frameQcIssueCount: Int = 0
    ) : GridDetectionUiState

    data class RetakeRequired(
        val runId: String
    ) : GridDetectionUiState

    data class Blocked(
        val reasons: Set<GridDetectionBlockReason>
    ) : GridDetectionUiState

    data class Error(
        val reason: GridDetectionUiError
    ) : GridDetectionUiState
}

enum class GridDetectionUiError {
    INVALID_ARGUMENTS,
    PROJECT_NOT_FOUND,
    SNAPSHOT_MISSING_OR_INVALID,
    IMAGE_LOAD_FAILED,
    EXECUTION_FAILED
}

/**
 * 检测入口网关 ViewModel。
 *
 * 它先读取项目不可变快照，再决定是否创建旧孔板页面。微流控项目直接调用新的协调器，
 * 因而不会实例化旧 [DetectionViewModel]，也不会加载 YOLO/PyTorch 模型。
 */
@HiltViewModel
class GridDetectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val coordinator: GridDetectionCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow<GridDetectionUiState>(
        GridDetectionUiState.ResolvingProject
    )
    val uiState: StateFlow<GridDetectionUiState> = _uiState.asStateFlow()

    private var lastProjectId: String? = null
    private var lastImageUri: String? = null
    private var running: Boolean = false

    fun start(projectId: String?, imageUri: String?) {
        if (projectId.isNullOrBlank() || imageUri.isNullOrBlank()) {
            _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.INVALID_ARGUMENTS)
            return
        }
        if (running || projectId == lastProjectId && imageUri == lastImageUri &&
            _uiState.value !is GridDetectionUiState.Error
        ) {
            return
        }
        lastProjectId = projectId
        lastImageUri = imageUri
        execute(projectId, imageUri)
    }

    /** 仅在重拍/执行失败状态下重新运行同一项目和图片。 */
    fun retry() {
        val projectId = lastProjectId ?: return
        val imageUri = lastImageUri ?: return
        if (!running) execute(projectId, imageUri)
    }

    private fun execute(projectId: String, imageUri: String) {
        viewModelScope.launch {
            running = true
            _uiState.value = GridDetectionUiState.ResolvingProject
            try {
                val project = projectRepository.getProjectById(projectId)
                    ?: run {
                        _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.PROJECT_NOT_FOUND)
                        return@launch
                    }
                val snapshotJson = project.templateSnapshotJson
                    ?: run {
                        _uiState.value = GridDetectionUiState.Error(
                            GridDetectionUiError.SNAPSHOT_MISSING_OR_INVALID
                        )
                        return@launch
                    }
                val snapshot = runCatching { TemplateProjectSnapshotCodec.decode(snapshotJson) }
                    .getOrElse {
                        _uiState.value = GridDetectionUiState.Error(
                            GridDetectionUiError.SNAPSHOT_MISSING_OR_INVALID
                        )
                        return@launch
                    }
                val carrierType = CarrierType.fromCode(snapshot.carrierProfile.carrierType)
                if (carrierType == null) {
                    _uiState.value = GridDetectionUiState.Blocked(
                        setOf(GridDetectionBlockReason.UNSUPPORTED_CARRIER)
                    )
                    return@launch
                }
                if (GridDetectionRouteResolver.resolve(carrierType) == GridCarrierRoute.LEGACY_PLATE) {
                    _uiState.value = GridDetectionUiState.LegacyPlate
                    return@launch
                }

                val bitmap = loadBitmap(imageUri)
                    ?: run {
                        _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.IMAGE_LOAD_FAILED)
                        return@launch
                    }
                val outcome = coordinator.execute(
                    GridDetectionRequest(
                        project = project,
                        snapshot = snapshot,
                        endpointBitmap = bitmap,
                        endpointPath = imageUri,
                        operatorId = project.userId,
                        onStageChanged = { stage ->
                            _uiState.value = GridDetectionUiState.Processing(stage)
                        }
                    )
                )
                _uiState.value = when (outcome) {
                    GridDetectionOutcome.LegacyPlateRequired -> GridDetectionUiState.LegacyPlate
                    is GridDetectionOutcome.Blocked -> GridDetectionUiState.Blocked(outcome.reasons)
                    is GridDetectionOutcome.RetakeRequired -> {
                        GridDetectionUiState.RetakeRequired(outcome.runId)
                    }
                    is GridDetectionOutcome.Completed -> GridDetectionUiState.Completed(
                        runId = outcome.runId,
                        measurementCount = outcome.measurementCount,
                        signalOnlyAnalyteIds = outcome.signalOnlyAnalyteIds,
                        frameQcIssueCount = outcome.frameQcIssueCount
                    )
                }
            } catch (_: Exception) {
                _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.EXECUTION_FAILED)
            } catch (_: LinkageError) {
                // OpenCV/本地库若因设备 ABI 或安装损坏未能加载，应收敛为页面错误状态，
                // 不能让用户在点击“开始检测”后直接退出应用。正常构建由 Application
                // 统一初始化 OpenCV，本分支仅承担最后一道运行时保护。
                _uiState.value = GridDetectionUiState.Error(GridDetectionUiError.EXECUTION_FAILED)
            } finally {
                running = false
            }
        }
    }

    /** 支持 content/file URI 和历史项目保存的普通绝对路径。 */
    private suspend fun loadBitmap(rawUri: String): Bitmap? = withContext(Dispatchers.IO) {
        val decoded = runCatching { URLDecoder.decode(rawUri, Charsets.UTF_8.name()) }
            .getOrDefault(rawUri)
        val uri = Uri.parse(decoded)
        when (uri.scheme?.lowercase()) {
            "content", "file", "android.resource" -> {
                context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }
            null, "" -> BitmapFactory.decodeFile(File(decoded).absolutePath)
            else -> context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }
    }
}

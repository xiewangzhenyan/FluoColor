package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.repository.ArrayResultRepository
import com.muc.fluocolorquant.data.repository.LegacyPlateResultRepository
import com.muc.fluocolorquant.data.repository.ResultValidationRepository
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultErrorCode
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultLoadResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshotMapper
import com.muc.fluocolorquant.domain.result.validation.ResultValidationPoint
import com.muc.fluocolorquant.domain.result.validation.ResultValidationEngine
import com.muc.fluocolorquant.domain.result.validation.ResultValidationSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface Plate96ResultUiState {
    data object Loading : Plate96ResultUiState
    data class Success(
        val snapshot: Plate96ResultSnapshot,
        val validations: Map<String, ResultValidationSnapshot> = emptyMap(),
        val validationSavingAnalyteId: String? = null,
        val validationSaveFailed: Boolean = false
    ) : Plate96ResultUiState
    data object NotFound : Plate96ResultUiState
    data class InvalidSnapshot(val errorCode: Plate96ResultErrorCode) : Plate96ResultUiState
    data object Error : Plate96ResultUiState
}

/** 96孔板结果只读状态机；页面切换和重试不会触发定位、拟合或浓度重算。 */
@HiltViewModel
class Plate96ResultViewModel @Inject constructor(
    private val repository: ArrayResultRepository,
    private val legacyPlateRepository: LegacyPlateResultRepository,
    private val validationRepository: ResultValidationRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<Plate96ResultUiState>(Plate96ResultUiState.Loading)
    val uiState: StateFlow<Plate96ResultUiState> = _uiState.asStateFlow()

    private var loadedRunId: String? = null
    private var loadJob: Job? = null

    fun load(runId: String?) {
        if (runId.isNullOrBlank()) {
            loadedRunId = null
            _uiState.value = Plate96ResultUiState.NotFound
            return
        }
        if (loadedRunId == runId && _uiState.value !is Plate96ResultUiState.Error) return
        loadedRunId = runId
        loadJob?.cancel()
        _uiState.value = Plate96ResultUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val modernResult = repository.hasNewArrayResult(runId)
                val mapped = if (modernResult) {
                    Plate96ResultSnapshotMapper.map(repository.loadSnapshot(runId))
                } else {
                    // 旧历史只读恢复已保存的浓度和布局，不触发任何现代算法。
                    legacyPlateRepository.loadSnapshot(runId)
                }
                _uiState.value = when (mapped) {
                    is Plate96ResultLoadResult.Success -> {
                        val persisted = validationRepository.getLatestByRun(runId)
                        val legacyRecovered = if (modernResult) {
                            emptyMap()
                        } else {
                            recoverLegacyValidations(
                                snapshot = mapped.snapshot,
                                pointsByAnalyte = legacyPlateRepository.loadValidationPoints(runId)
                            )
                        }
                        Plate96ResultUiState.Success(
                            snapshot = mapped.snapshot,
                            // 用户在新版中保存的修订优先于旧表只读恢复结果。
                            validations = legacyRecovered + persisted
                        )
                    }
                    is Plate96ResultLoadResult.Failure -> Plate96ResultUiState.InvalidSnapshot(
                        mapped.errorCode
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                _uiState.value = Plate96ResultUiState.Error
            }
        }
    }

    fun retry() {
        val runId = loadedRunId ?: return
        loadedRunId = null
        load(runId)
    }

    /**
     * 保存当前分析物的预测精度验证。
     *
     * [referenceValues] 使用物理位点索引作为键；预测浓度始终从当前冻结结果读取，调用方
     * 无法传入或覆盖预测值，从边界上避免验证页面意外修改检测结果。
     */
    fun saveValidation(analyteId: String, referenceValues: Map<Int, Double>) {
        val current = _uiState.value as? Plate96ResultUiState.Success ?: return
        if (current.validationSavingAnalyteId != null) return
        val analyte = current.snapshot.arraySnapshot.analytes
            .firstOrNull { candidate -> candidate.analyteId == analyteId } ?: return
        val points = current.snapshot.wells.mapNotNull { well ->
            val reference = referenceValues[well.wellIndex]
                ?.takeIf(Double::isFinite) ?: return@mapNotNull null
            val predicted = well.site.measurements
                .firstOrNull { measurement -> measurement.analyteId == analyteId }
                ?.concentrationValue
                ?.takeIf(Double::isFinite) ?: return@mapNotNull null
            ResultValidationPoint(
                siteIndex = well.wellIndex,
                siteLabel = well.wellLabel,
                predictedValue = predicted,
                referenceValue = reference
            )
        }
        if (points.size < 2) return

        _uiState.value = current.copy(
            validationSavingAnalyteId = analyteId,
            validationSaveFailed = false
        )
        viewModelScope.launch {
            try {
                val saved = validationRepository.save(
                    runId = current.snapshot.runId,
                    analyteId = analyteId,
                    concentrationUnit = analyte.concentrationUnit,
                    points = points
                )
                val latest = (_uiState.value as? Plate96ResultUiState.Success) ?: current
                _uiState.value = latest.copy(
                    validations = latest.validations + (analyteId to saved),
                    validationSavingAnalyteId = null,
                    validationSaveFailed = false
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                val latest = (_uiState.value as? Plate96ResultUiState.Success) ?: current
                _uiState.value = latest.copy(
                    validationSavingAnalyteId = null,
                    validationSaveFailed = true
                )
            }
        }
    }

    /**
     * 将旧结果页的trueConcentration恢复为只读验证修订0。
     *
     * 这一步只重建回归与Bland–Altman展示，不修改旧孔位浓度；用户再次保存时会进入Room 14
     * 的正式追加式修订记录，因此历史科研数据仍保持不可变。
     */
    private fun recoverLegacyValidations(
        snapshot: Plate96ResultSnapshot,
        pointsByAnalyte: Map<String, List<ResultValidationPoint>>
    ): Map<String, ResultValidationSnapshot> = pointsByAnalyte.mapNotNull { (analyteId, points) ->
        if (points.size < 2) return@mapNotNull null
        val calculated = runCatching { ResultValidationEngine.calculate(points) }.getOrNull()
            ?: return@mapNotNull null
        val unit = snapshot.arraySnapshot.analytes
            .firstOrNull { analyte -> analyte.analyteId == analyteId }
            ?.concentrationUnit.orEmpty()
        analyteId to ResultValidationSnapshot(
            validationId = "legacy:$analyteId:${snapshot.runId}",
            runId = snapshot.runId,
            analyteId = analyteId,
            revision = 0,
            concentrationUnit = unit,
            points = points,
            regression = calculated.regression,
            blandAltman = calculated.blandAltman,
            processorVersion = "legacy-result-validation-adapter-v1",
            inputFingerprint = "legacy-well-result",
            createdAtEpochMillis = snapshot.arraySnapshot.runTimestampEpochMillis
        )
    }.toMap()
}

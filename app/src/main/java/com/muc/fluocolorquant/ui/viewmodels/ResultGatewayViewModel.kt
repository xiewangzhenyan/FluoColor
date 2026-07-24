package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.repository.ArrayResultRepository
import com.muc.fluocolorquant.data.repository.LegacyPlateResultRepository
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultLoadResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshotMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 新旧结果路由的最小状态；不得根据行列数猜测微流控或孔板。 */
sealed interface ResultGatewayUiState {
    data object Loading : ResultGatewayUiState
    data object NewArrayResult : ResultGatewayUiState
    data object NewPlate96Result : ResultGatewayUiState
    data object LegacyPlate96Result : ResultGatewayUiState
    data object LegacyResult : ResultGatewayUiState
    data object InvalidRun : ResultGatewayUiState
    data object Error : ResultGatewayUiState
}

/**
 * 结果网关只负责判断运行是否存在新 SiteMeasurement。
 *
 * 具体阵列 JSON、附件和测量加载由 [ArrayResultViewModel] 负责，避免网关重复读取大量
 * 数据；旧 WellResult 页面继续复用既有 ViewModel 和查询逻辑。
 */
@HiltViewModel
class ResultGatewayViewModel @Inject constructor(
    private val repository: ArrayResultRepository,
    private val legacyPlateRepository: LegacyPlateResultRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<ResultGatewayUiState>(ResultGatewayUiState.Loading)
    val uiState: StateFlow<ResultGatewayUiState> = _uiState.asStateFlow()
    private var loadedRunId: String? = null

    fun load(runId: String?) {
        if (runId.isNullOrBlank()) {
            loadedRunId = null
            _uiState.value = ResultGatewayUiState.InvalidRun
            return
        }
        if (loadedRunId == runId && _uiState.value !is ResultGatewayUiState.Error) return
        loadedRunId = runId
        _uiState.value = ResultGatewayUiState.Loading
        viewModelScope.launch {
            try {
                _uiState.value = if (!repository.hasNewArrayResult(runId)) {
                    // 旧结果先尝试严格的96孔板只读适配；其他旧运行继续进入原兼容页。
                    when (legacyPlateRepository.loadSnapshot(runId)) {
                        is Plate96ResultLoadResult.Success ->
                            ResultGatewayUiState.LegacyPlate96Result
                        is Plate96ResultLoadResult.Failure ->
                            ResultGatewayUiState.LegacyResult
                    }
                } else {
                    // 新结果按冻结载体协议分流；不能把“有SiteMeasurement”等同于微流控。
                    when (Plate96ResultSnapshotMapper.map(repository.loadSnapshot(runId))) {
                        is Plate96ResultLoadResult.Success -> ResultGatewayUiState.NewPlate96Result
                        is Plate96ResultLoadResult.Failure -> ResultGatewayUiState.NewArrayResult
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                _uiState.value = ResultGatewayUiState.Error
            }
        }
    }

    fun retry() {
        val runId = loadedRunId ?: return
        loadedRunId = null
        load(runId)
    }
}

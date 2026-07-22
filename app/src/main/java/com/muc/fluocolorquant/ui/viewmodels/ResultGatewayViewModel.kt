package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.repository.ArrayResultRepository
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
    private val repository: ArrayResultRepository
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
                _uiState.value = if (repository.hasNewArrayResult(runId)) {
                    ResultGatewayUiState.NewArrayResult
                } else {
                    ResultGatewayUiState.LegacyResult
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

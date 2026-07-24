package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.repository.ArrayResultRepository
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultErrorCode
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultLoadResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshotMapper
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
    data class Success(val snapshot: Plate96ResultSnapshot) : Plate96ResultUiState
    data object NotFound : Plate96ResultUiState
    data class InvalidSnapshot(val errorCode: Plate96ResultErrorCode) : Plate96ResultUiState
    data object Error : Plate96ResultUiState
}

/** 96孔板结果只读状态机；页面切换和重试不会触发定位、拟合或浓度重算。 */
@HiltViewModel
class Plate96ResultViewModel @Inject constructor(
    private val repository: ArrayResultRepository
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
                _uiState.value = when (val mapped = Plate96ResultSnapshotMapper.map(
                    repository.loadSnapshot(runId)
                )) {
                    is Plate96ResultLoadResult.Success -> Plate96ResultUiState.Success(mapped.snapshot)
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
}

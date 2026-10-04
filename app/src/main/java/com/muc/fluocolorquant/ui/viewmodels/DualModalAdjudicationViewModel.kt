package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.repository.DualModalAdjudicationRepository
import com.muc.fluocolorquant.data.repository.DualModalCurrentPairing
import com.muc.fluocolorquant.data.repository.DualModalPairOutcome
import com.muc.fluocolorquant.data.repository.DualModalPairingCandidate
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalAdjudicationEngine
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalIncompatibility
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 双模态卡片的页面状态。 */
sealed interface DualModalUiState {
    /** 光谱等不适用的运行不显示卡片。 */
    data object Hidden : DualModalUiState
    data object Loading : DualModalUiState
    data class Ready(
        val runId: String,
        val pairing: DualModalCurrentPairing?,
        /** 尚未打开配对面板时为空。 */
        val candidates: List<DualModalPairingCandidate>? = null,
        val loadingCandidates: Boolean = false,
        val working: Boolean = false,
        val notice: DualModalNotice? = null
    ) : DualModalUiState
    data object Error : DualModalUiState
}

/** 需要提示用户的一次性结果。 */
sealed interface DualModalNotice {
    data class Incompatible(val reasons: Set<DualModalIncompatibility>) : DualModalNotice
    data object LoadFailed : DualModalNotice
    data object SaveFailed : DualModalNotice
}

/** 结果页的双模态配对与判定；只读冻结快照，判定结果作为派生修订保存。 */
@HiltViewModel
class DualModalAdjudicationViewModel @Inject constructor(
    private val repository: DualModalAdjudicationRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<DualModalUiState>(DualModalUiState.Hidden)
    val uiState: StateFlow<DualModalUiState> = _uiState.asStateFlow()
    private var boundRunId: String? = null
    private var boundMode: String = ""
    private var job: Job? = null

    /** 结果页每加载一次运行快照调用一次；同一运行重复调用不会重新读取。 */
    fun bind(runId: String, detectionMode: String) {
        if (!supports(detectionMode) || runId.isBlank()) {
            job?.cancel()
            boundRunId = null
            _uiState.value = DualModalUiState.Hidden
            return
        }
        if (boundRunId == runId && _uiState.value !is DualModalUiState.Error) return
        job?.cancel()
        boundRunId = runId
        boundMode = detectionMode
        _uiState.value = DualModalUiState.Loading
        job = viewModelScope.launch {
            _uiState.value = try {
                DualModalUiState.Ready(runId = runId, pairing = repository.getCurrent(runId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                DualModalUiState.Error
            }
        }
    }

    fun retry() {
        val runId = boundRunId ?: return
        boundRunId = null
        bind(runId, boundMode)
    }

    fun loadCandidates() {
        val current = ready() ?: return
        if (current.loadingCandidates) return
        _uiState.value = current.copy(loadingCandidates = true)
        viewModelScope.launch {
            val candidates = try {
                repository.findCandidates(current.runId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                updateIfSameRun(current.runId) { copy(loadingCandidates = false, notice = DualModalNotice.LoadFailed) }
                return@launch
            }
            updateIfSameRun(current.runId) { copy(candidates = candidates, loadingCandidates = false) }
        }
    }

    fun pair(counterpartRunId: String) {
        val current = ready() ?: return
        if (current.working || counterpartRunId.isBlank()) return
        _uiState.value = current.copy(working = true, notice = null)
        viewModelScope.launch {
            val outcome = try {
                repository.pair(current.runId, counterpartRunId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                updateIfSameRun(current.runId) { copy(working = false, notice = DualModalNotice.SaveFailed) }
                return@launch
            }
            updateIfSameRun(current.runId) {
                when (outcome) {
                    is DualModalPairOutcome.Paired -> copy(pairing = outcome.pairing, working = false)
                    is DualModalPairOutcome.Incompatible ->
                        copy(working = false, notice = DualModalNotice.Incompatible(outcome.reasons))
                    is DualModalPairOutcome.LoadFailed -> copy(working = false, notice = DualModalNotice.LoadFailed)
                }
            }
        }
    }

    /** 用当前规则版本对同一对运行重新判定，结果追加为新修订。 */
    fun reAdjudicate() {
        val counterpart = ready()?.pairing?.counterpart ?: return
        pair(counterpart.runId)
    }

    fun unpair() {
        val current = ready() ?: return
        if (current.working || current.pairing == null) return
        _uiState.value = current.copy(working = true, notice = null)
        viewModelScope.launch {
            val pairing = try {
                repository.unpair(current.runId)
                repository.getCurrent(current.runId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                updateIfSameRun(current.runId) { copy(working = false, notice = DualModalNotice.SaveFailed) }
                return@launch
            }
            updateIfSameRun(current.runId) { copy(pairing = pairing, working = false) }
        }
    }

    fun dismissNotice() {
        val current = ready() ?: return
        _uiState.value = current.copy(notice = null)
    }

    private fun ready(): DualModalUiState.Ready? = _uiState.value as? DualModalUiState.Ready

    private fun updateIfSameRun(runId: String, change: DualModalUiState.Ready.() -> DualModalUiState.Ready) {
        val current = ready() ?: return
        if (current.runId == runId) _uiState.value = current.change()
    }

    private fun supports(detectionMode: String): Boolean =
        detectionMode.equals(DualModalAdjudicationEngine.COLORIMETRIC, ignoreCase = true) ||
            detectionMode.equals(DualModalAdjudicationEngine.FLUORESCENCE, ignoreCase = true)
}

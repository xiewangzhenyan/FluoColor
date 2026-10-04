package com.muc.fluocolorquant.ui.viewmodels

import com.muc.fluocolorquant.data.repository.DualModalAdjudicationRepository
import com.muc.fluocolorquant.data.repository.DualModalCurrentPairing
import com.muc.fluocolorquant.data.repository.DualModalPairOutcome
import com.muc.fluocolorquant.data.repository.DualModalPairingCandidate
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalAdjudication
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalAdjudicationEngine
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalAdjudicationSnapshot
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalIncompatibility
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalThresholds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 双模态卡片状态机的纯 JVM 测试；仓库用假实现，不访问 Room。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DualModalAdjudicationViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `光谱运行不显示双模态卡片`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = DualModalAdjudicationViewModel(FakeDualModalRepository())

        viewModel.bind("run-spectrum", "SPECTRUM")
        advanceUntilIdle()

        assertEquals(DualModalUiState.Hidden, viewModel.uiState.value)
    }

    @Test
    fun `比色运行绑定后进入未配对状态并可加载候选`() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeDualModalRepository()
        val viewModel = DualModalAdjudicationViewModel(repository)

        viewModel.bind("run-col", "COLORIMETRIC")
        advanceUntilIdle()
        val ready = viewModel.uiState.value as DualModalUiState.Ready
        assertNull(ready.pairing)

        viewModel.loadCandidates()
        advanceUntilIdle()
        val loaded = viewModel.uiState.value as DualModalUiState.Ready
        assertEquals(listOf("run-flu"), loaded.candidates?.map(DualModalPairingCandidate::runId))
    }

    @Test
    fun `配对成功后显示判定，不兼容时给出原因`() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeDualModalRepository()
        val viewModel = DualModalAdjudicationViewModel(repository)
        viewModel.bind("run-col", "COLORIMETRIC")
        advanceUntilIdle()

        repository.nextOutcome = DualModalPairOutcome.Incompatible(setOf(DualModalIncompatibility.SITE_LAYOUT_MISMATCH))
        viewModel.pair("run-other")
        advanceUntilIdle()
        val rejected = viewModel.uiState.value as DualModalUiState.Ready
        assertNull(rejected.pairing)
        assertEquals(
            DualModalNotice.Incompatible(setOf(DualModalIncompatibility.SITE_LAYOUT_MISMATCH)),
            rejected.notice
        )

        repository.nextOutcome = null
        viewModel.pair("run-flu")
        advanceUntilIdle()
        val paired = viewModel.uiState.value as DualModalUiState.Ready
        assertNotNull(paired.pairing)
        assertNull(paired.notice)
        assertEquals("run-flu", paired.pairing?.counterpart?.runId)
    }

    @Test
    fun `解除配对后回到未配对状态`() = runTest(mainDispatcherRule.testDispatcher) {
        val repository = FakeDualModalRepository()
        val viewModel = DualModalAdjudicationViewModel(repository)
        viewModel.bind("run-col", "COLORIMETRIC")
        advanceUntilIdle()
        viewModel.pair("run-flu")
        advanceUntilIdle()

        viewModel.unpair()
        advanceUntilIdle()

        val state = viewModel.uiState.value as DualModalUiState.Ready
        assertNull(state.pairing)
        assertTrue(repository.unpaired)
    }
}

/** 只记录调用并返回预设结果的假仓库。 */
private class FakeDualModalRepository : DualModalAdjudicationRepository {
    private var current: DualModalCurrentPairing? = null
    var nextOutcome: DualModalPairOutcome? = null
    var unpaired = false
    private val candidate = DualModalPairingCandidate(
        runId = "run-flu",
        projectId = "project-flu",
        projectName = "荧光项目",
        detectionMode = DualModalAdjudicationEngine.FLUORESCENCE,
        timestampEpochMillis = 2_000L
    )

    override suspend fun getCurrent(runId: String): DualModalCurrentPairing? = current

    override suspend fun findCandidates(runId: String): List<DualModalPairingCandidate> = listOf(candidate)

    override suspend fun pair(runId: String, counterpartRunId: String): DualModalPairOutcome {
        nextOutcome?.let { return it }
        val pairing = DualModalCurrentPairing(
            snapshot = DualModalAdjudicationSnapshot(
                adjudicationId = "adj-1",
                revision = 1,
                createdAtEpochMillis = 3_000L,
                inputFingerprint = "fp",
                adjudication = DualModalAdjudication(
                    ruleVersion = DualModalAdjudicationEngine.RULE_VERSION,
                    thresholds = DualModalThresholds(),
                    colorimetricRunId = runId,
                    fluorescenceRunId = counterpartRunId,
                    readings = emptyList()
                )
            ),
            counterpart = candidate.copy(runId = counterpartRunId)
        )
        current = pairing
        return DualModalPairOutcome.Paired(pairing)
    }

    override suspend fun unpair(runId: String): Boolean {
        unpaired = current != null
        current = null
        return unpaired
    }
}

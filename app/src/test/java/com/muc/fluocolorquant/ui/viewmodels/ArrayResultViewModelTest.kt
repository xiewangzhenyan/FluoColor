package com.muc.fluocolorquant.ui.viewmodels

import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.repository.ArrayResultRepository
import com.muc.fluocolorquant.data.repository.LegacyPlateResultRepository
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.result.ArrayCarrierResult
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayFrameResult
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultErrorCode
import com.muc.fluocolorquant.domain.result.ArrayResultLoadResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteGeometry
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultErrorCode
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultLoadResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshotMapper
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 结果网关和阵列页面状态机的纯 JVM 测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArrayResultViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeArrayResultRepository
    private lateinit var legacyPlateRepository: FakeLegacyPlateResultRepository

    @Before
    fun setUp() {
        repository = FakeArrayResultRepository()
        legacyPlateRepository = FakeLegacyPlateResultRepository()
    }

    @Test
    fun `存在SiteMeasurement时网关进入新阵列结果`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.hasNew = true
            val viewModel = ResultGatewayViewModel(repository, legacyPlateRepository)

            viewModel.load("run-new")
            advanceUntilIdle()

            assertEquals(ResultGatewayUiState.NewArrayResult, viewModel.uiState.value)
        }

    @Test
    fun `没有SiteMeasurement时网关保留旧结果页`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.hasNew = false
            val viewModel = ResultGatewayViewModel(repository, legacyPlateRepository)

            viewModel.load("run-legacy")
            advanceUntilIdle()

            assertEquals(ResultGatewayUiState.LegacyResult, viewModel.uiState.value)
        }

    @Test
    fun `新96孔板结果按冻结载体协议进入独立结果页`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.hasNew = true
            repository.loadResult = ArrayResultLoadResult.Success(plateSnapshot())
            val viewModel = ResultGatewayViewModel(repository, legacyPlateRepository)

            viewModel.load("run-plate96")
            advanceUntilIdle()

            assertEquals(ResultGatewayUiState.NewPlate96Result, viewModel.uiState.value)
        }

    @Test
    fun `旧96孔板运行经只读适配进入独立结果页`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.hasNew = false
            legacyPlateRepository.loadResult = Plate96ResultSnapshotMapper.map(plateSnapshot())
            val viewModel = ResultGatewayViewModel(repository, legacyPlateRepository)

            viewModel.load("run-legacy-plate96")
            advanceUntilIdle()

            assertEquals(ResultGatewayUiState.LegacyPlate96Result, viewModel.uiState.value)
        }

    @Test
    fun `阵列快照成功后进入Success状态`() = runTest(mainDispatcherRule.testDispatcher) {
        repository.loadResult = ArrayResultLoadResult.Success(snapshot())
        val viewModel = ArrayResultViewModel(repository)

        viewModel.load("run-new")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ArrayResultUiState.Success)
        assertEquals("微流控项目", (state as ArrayResultUiState.Success).snapshot.projectName)
    }

    @Test
    fun `损坏运行快照与数据库错误使用不同页面状态`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = ArrayResultViewModel(repository)
            repository.loadResult = ArrayResultLoadResult.Failure(
                ArrayResultErrorCode.CORRUPT_PG_GRID_GEOMETRY
            )

            viewModel.load("run-corrupt")
            advanceUntilIdle()

            assertEquals(
                ArrayResultUiState.CorruptSnapshot(ArrayResultErrorCode.CORRUPT_PG_GRID_GEOMETRY),
                viewModel.uiState.value
            )
        }

    @Test
    fun `项目运行历史按时间倒序且切换只读取目标快照`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.loadResultsByRunId["run-new"] = ArrayResultLoadResult.Success(
                snapshot(runId = "run-new", timestamp = 3_000L, modelVersion = 3)
            )
            repository.loadResultsByRunId["run-old"] = ArrayResultLoadResult.Success(
                snapshot(runId = "run-old", timestamp = 1_000L, modelVersion = 1)
            )
            repository.runs = listOf(
                detectionRun("run-old", 1_000L),
                detectionRun("run-new", 3_000L),
                detectionRun("run-middle", 2_000L)
            )
            val viewModel = ArrayResultViewModel(repository)

            viewModel.load("run-new")
            advanceUntilIdle()

            val initial = viewModel.uiState.value as ArrayResultUiState.Success
            assertEquals(listOf("run-new", "run-middle", "run-old"), initial.history.map { it.runId })
            assertEquals(3, initial.snapshot.analytes.single().modelVersion)

            viewModel.selectRun("run-old")
            advanceUntilIdle()

            val switched = viewModel.uiState.value as ArrayResultUiState.Success
            assertEquals("run-old", switched.snapshot.runId)
            assertEquals(1, switched.snapshot.analytes.single().modelVersion)
            assertEquals(listOf("run-new", "run-middle", "run-old"), switched.history.map { it.runId })
            assertEquals(listOf("run-new", "run-old"), repository.loadedRunIds)
        }

    private fun snapshot(
        runId: String = "run-new",
        timestamp: Long = 1_000L,
        modelVersion: Int? = null
    ): ArrayResultSnapshot {
        return ArrayResultSnapshot(
            runId = runId,
            projectId = "project-new",
            projectName = "微流控项目",
            runTimestampEpochMillis = timestamp,
            runStatus = "Completed",
            detectionMode = "COLORIMETRIC",
            carrier = ArrayCarrierResult(
                id = "carrier",
                name = "10×10微流控芯片",
                carrierType = "MICROFLUIDIC_CHIP",
                version = 1,
                siteShape = "CIRCLE",
                orientationMarkerJson = null
            ),
            rows = 10,
            columns = 10,
            analytes = modelVersion?.let { version ->
                listOf(
                    ArrayAnalyteResult(
                        analyteId = "analyte-cea",
                        name = "CEA",
                        displayOrder = 0,
                        concentrationUnit = "ng/mL",
                        reliableRangeMin = 0.0,
                        reliableRangeMax = 100.0,
                        modelId = "model-cea-v$version",
                        modelName = "CEA模型",
                        modelType = "STANDARD_CURVE",
                        modelVersion = version,
                        primaryFeature = "DELTA_E_2000",
                        processorName = "colorimetric-photometry",
                        processorVersion = "v1"
                    )
                )
            }.orEmpty(),
            sites = emptyList(),
            frame = ArrayFrameResult(
                locatorName = "pg-grid",
                locatorVersion = "2.1",
                rectifiedWidth = 100,
                rectifiedHeight = 100,
                chipRegionMethod = "test",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 1.0,
                    trusted = true,
                    observedRatio = 1.0,
                    geometryRmsePx = 0.1,
                    inlierCount = 100,
                    outlierCount = 0,
                    meanConfidence = 0.95
                ),
                qcIssues = emptyList(),
                frameQcJson = "{}"
            ),
            artifacts = emptyList(),
            effectiveConfigSnapshotJson = "{}",
            configurationDeviationJson = null,
            acquisitionMetadataJson = null,
            processingVersionJson = null,
            modelUsageJson = null,
            siteQcSummaryJson = null
        )
    }

    private fun detectionRun(runId: String, timestamp: Long): DetectionRun {
        return DetectionRun(
            runId = runId,
            projectId = "project-new",
            timestamp = Date(timestamp),
            detectionModelUsed = "pg-grid",
            concentrationModelUsed = null,
            status = "Completed",
            errorMessage = null,
            confThreshold = null,
            iouThreshold = null,
            wellsDetected = 100,
            effectiveConfigSnapshotJson = "{}",
            siteQcSummaryJson = "{\"total\":100,\"reliable\":90}"
        )
    }

    private fun plateSnapshot(): ArrayResultSnapshot {
        return snapshot(runId = "run-plate96").copy(
            projectName = "96孔板项目",
            carrier = ArrayCarrierResult(
                id = "plate96-carrier",
                name = "标准96孔板",
                carrierType = "PLATE",
                version = 1,
                siteShape = "CIRCLE",
                orientationMarkerJson = null
            ),
            rows = 8,
            columns = 12,
            sites = List(96) { index ->
                val row = index / 12
                val column = index % 12
                ArrayPhysicalSiteResult(
                    siteIndex = index,
                    rowIndex = row,
                    columnIndex = column,
                    siteKey = "R${row + 1}C${column + 1}",
                    enabled = true,
                    roleCode = "SAMPLE",
                    analyteId = null,
                    defaultSampleSlot = null,
                    sampleSlot = null,
                    overrideReason = null,
                    standardConcentration = null,
                    repeatGroup = null,
                    referenceScope = null,
                    geometry = ArraySiteGeometry(
                        rectified = GridPoint(column * 10.0, row * 10.0),
                        original = GridPoint(column * 10.0, row * 10.0),
                        confidence = 0.96,
                        source = GridPointSource.CANDIDATE_REFINED,
                        flags = emptySet()
                    ),
                    measurements = emptyList()
                )
            }
        )
    }
}

/** 轻量假仓库只返回最终领域结果，不复制 Mapper 逻辑。 */
private class FakeArrayResultRepository : ArrayResultRepository {
    var hasNew: Boolean = false
    var loadResult: ArrayResultLoadResult = ArrayResultLoadResult.Failure(
        ArrayResultErrorCode.RUN_NOT_FOUND
    )
    val loadResultsByRunId = mutableMapOf<String, ArrayResultLoadResult>()
    val loadedRunIds = mutableListOf<String>()
    var runs: List<DetectionRun> = emptyList()

    override suspend fun hasNewArrayResult(runId: String): Boolean = hasNew

    override suspend fun loadSnapshot(runId: String): ArrayResultLoadResult {
        loadedRunIds += runId
        return loadResultsByRunId[runId] ?: loadResult
    }

    override suspend fun getProjectRuns(projectId: String): List<DetectionRun> {
        return runs.ifEmpty { listOf(
            DetectionRun(
                runId = "run",
                projectId = projectId,
                timestamp = Date(1_000L),
                detectionModelUsed = null,
                concentrationModelUsed = null,
                status = "Completed",
                errorMessage = null,
                confThreshold = null,
                iouThreshold = null,
                wellsDetected = 0
            )
        ) }
    }
}

/** 网关测试只控制旧96孔板是否可适配，不访问Room或重新构造历史结果。 */
private class FakeLegacyPlateResultRepository : LegacyPlateResultRepository {
    var loadResult: Plate96ResultLoadResult = Plate96ResultLoadResult.Failure(
        Plate96ResultErrorCode.SOURCE_NOT_AVAILABLE
    )

    override suspend fun loadSnapshot(runId: String): Plate96ResultLoadResult = loadResult
}

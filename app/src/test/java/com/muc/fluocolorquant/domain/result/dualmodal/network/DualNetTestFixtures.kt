package com.muc.fluocolorquant.domain.result.dualmodal.network

import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayCarrierResult
import com.muc.fluocolorquant.domain.result.ArrayFrameResult
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteGeometry

/**
 * W2 训练版面的最小运行快照：每行第 0 列空白（比色参考位，不属于分析物），第 1–12 列为样本
 * S01–S12 或 12 个标准水平，第 13、14 列为 25 与 250 ng/mL 阳控。只填 DualNet 用到的字段。
 */
internal object DualNetTestFixtures {
    const val ANALYTE: String = "cea"
    val LEVELS: List<Double> = (0 until 12).map { k -> 0.05 * Math.pow(10000.0, k / 11.0) }

    fun run(
        runId: String,
        mode: String,
        standards: Boolean = false,
        trusted: Boolean = true,
        size: Int = DualNetSpec.GRID_SIZE,
        unit: String = "ng/mL",
        dropQcHighInRow: Int? = null,
        calibrationRunIds: List<String> = emptyList()
    ): ArrayResultSnapshot {
        val sites = (0 until size * size).map { index ->
            val row = index / size
            val column = index % size
            val (role, analyte, slot, concentration) = when {
                column == 0 -> Quad("BLANK", null, null, null)
                column == 13 && size == DualNetSpec.GRID_SIZE -> Quad("POSITIVE_CONTROL", ANALYTE, null, DualNetSpec.QC_MID_NOMINAL)
                column == 14 && size == DualNetSpec.GRID_SIZE && row == dropQcHighInRow -> Quad("SAMPLE", ANALYTE, "X", null)
                column == 14 && size == DualNetSpec.GRID_SIZE -> Quad("POSITIVE_CONTROL", ANALYTE, null, DualNetSpec.QC_HIGH_NOMINAL)
                column in 1..12 && standards -> Quad("STANDARD", ANALYTE, null, LEVELS[column - 1])
                column in 1..12 -> Quad("SAMPLE", ANALYTE, "S%02d".format(column), null)
                else -> Quad("SAMPLE", ANALYTE, "S%02d".format(column), null)
            }
            ArrayPhysicalSiteResult(
                siteIndex = index,
                rowIndex = row,
                columnIndex = column,
                siteKey = "R${row + 1}C${column + 1}",
                enabled = true,
                roleCode = role,
                analyteId = analyte,
                defaultSampleSlot = slot,
                sampleSlot = slot,
                overrideReason = null,
                standardConcentration = concentration,
                repeatGroup = null,
                referenceScope = null,
                geometry = ArraySiteGeometry(
                    rectified = GridPoint(column * 64.0 + 32.0, row * 64.0 + 32.0),
                    original = GridPoint(column * 64.0 + 32.0, row * 64.0 + 32.0),
                    confidence = 0.95,
                    source = GridPointSource.CANDIDATE_REFINED,
                    flags = emptySet()
                ),
                measurements = emptyList()
            )
        }
        val pointsJson = calibrationRunIds.joinToString(",") { id ->
            """{"analysisModelId":"m","concentration":1.0,"signalValue":1.0,"repeatIndex":0,"runId":"$id","excluded":false}"""
        }
        return ArrayResultSnapshot(
            runId = runId,
            projectId = "project-$runId",
            projectName = "项目 $runId",
            runTimestampEpochMillis = 1_000L,
            runStatus = "Completed",
            detectionMode = mode,
            carrier = ArrayCarrierResult("direct-carrier-$runId", "microfluidic-15x15", "MICROFLUIDIC_CHIP", 1, "SQUARE", null),
            rows = size,
            columns = size,
            analytes = listOf(
                ArrayAnalyteResult(
                    analyteId = ANALYTE,
                    name = "CEA",
                    displayOrder = 0,
                    concentrationUnit = unit,
                    reliableRangeMin = 0.0,
                    reliableRangeMax = 500.0,
                    modelId = "curve",
                    modelName = "曲线",
                    modelType = "CURVE_FIT",
                    modelVersion = 1,
                    primaryFeature = "signal",
                    processorName = "test",
                    processorVersion = "1"
                )
            ),
            sites = sites,
            frame = ArrayFrameResult(
                locatorName = "pg-grid",
                locatorVersion = "2.1",
                rectifiedWidth = size * 64,
                rectifiedHeight = size * 64,
                chipRegionMethod = "test",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 1.0,
                    trusted = trusted,
                    observedRatio = 1.0,
                    geometryRmsePx = 0.1,
                    inlierCount = sites.size,
                    outlierCount = 0,
                    meanConfidence = 0.95
                ),
                qcIssues = emptyList(),
                frameQcJson = "{}"
            ),
            artifacts = emptyList(),
            effectiveConfigSnapshotJson = """{"analytes":[{"analyte":{"id":"$ANALYTE"},"analysisModel":{"calibrationPoints":[$pointsJson]}}]}""",
            configurationDeviationJson = null,
            acquisitionMetadataJson = null,
            processingVersionJson = null,
            modelUsageJson = null,
            siteQcSummaryJson = null
        )
    }

    private data class Quad(val role: String, val analyte: String?, val slot: String?, val concentration: Double?)
}

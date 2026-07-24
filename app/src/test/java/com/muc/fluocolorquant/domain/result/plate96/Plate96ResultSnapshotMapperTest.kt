package com.muc.fluocolorquant.domain.result.plate96

import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.result.ArrayCarrierResult
import com.muc.fluocolorquant.domain.result.ArrayFrameResult
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 96孔板结果边界必须拒绝“碰巧有96个位点”的其他载体。 */
class Plate96ResultSnapshotMapperTest {

    @Test
    fun `标准圆形96孔板映射为A1到H12`() {
        val result = Plate96ResultSnapshotMapper.map(snapshot())

        assertTrue(result is Plate96ResultLoadResult.Success)
        val plate = (result as Plate96ResultLoadResult.Success).snapshot
        assertEquals(96, plate.wells.size)
        assertEquals("A1", plate.wells.first().wellLabel)
        assertEquals("A12", plate.wells[11].wellLabel)
        assertEquals("H12", plate.wells.last().wellLabel)
        assertEquals(12, plate.orientation.sourceRows)
        assertEquals(8, plate.orientation.sourceColumns)
        assertEquals(1, plate.orientation.quarterTurnsClockwise)
    }

    @Test
    fun `96个位点的微流控阵列不能伪装成96孔板`() {
        val result = Plate96ResultSnapshotMapper.map(
            snapshot().copy(
                carrier = snapshot().carrier.copy(carrierType = "MICROFLUIDIC_CHIP")
            )
        )

        assertEquals(
            Plate96ResultErrorCode.NOT_A_PLATE_CARRIER,
            (result as Plate96ResultLoadResult.Failure).errorCode
        )
    }

    @Test
    fun `孔位行列与标准索引不一致时拒绝重建`() {
        val source = snapshot()
        val broken = source.copy(
            sites = source.sites.map { site ->
                if (site.siteIndex == 11) site.copy(rowIndex = 1, columnIndex = 0) else site
            }
        )

        val result = Plate96ResultSnapshotMapper.map(broken)

        assertEquals(
            Plate96ResultErrorCode.INCONSISTENT_WELL_COORDINATE,
            (result as Plate96ResultLoadResult.Failure).errorCode
        )
    }

    private fun snapshot(): ArrayResultSnapshot {
        return ArrayResultSnapshot(
            runId = "plate-run",
            projectId = "plate-project",
            projectName = "96孔板测试",
            runTimestampEpochMillis = 1_000L,
            runStatus = "Completed",
            detectionMode = "COLORIMETRIC",
            carrier = ArrayCarrierResult(
                id = "plate96",
                name = "标准96孔板",
                carrierType = "PLATE",
                version = 1,
                siteShape = "CIRCLE",
                orientationMarkerJson = null
            ),
            rows = 8,
            columns = 12,
            analytes = emptyList(),
            sites = (0 until 96).map(::site),
            frame = ArrayFrameResult(
                locatorName = "plate96-yolo-circle",
                locatorVersion = "1.0",
                rectifiedWidth = 1200,
                rectifiedHeight = 800,
                chipRegionMethod = "YOLO_HOUGH_GRID",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 0.92,
                    trusted = true,
                    observedRatio = 0.92,
                    geometryRmsePx = 0.4,
                    inlierCount = 88,
                    outlierCount = 8,
                    meanConfidence = 0.91
                ),
                qcIssues = emptyList(),
                frameQcJson = "{}"
            ),
            artifacts = emptyList(),
            effectiveConfigSnapshotJson = "{}",
            configurationDeviationJson = null,
            acquisitionMetadataJson = """{"plate96Orientation":{"sourceRows":12,"sourceColumns":8,"quarterTurnsClockwise":1,"originCorner":"TOP_LEFT","userConfirmed":true}}""",
            processingVersionJson = null,
            modelUsageJson = null,
            siteQcSummaryJson = null
        )
    }

    private fun site(index: Int): ArrayPhysicalSiteResult {
        val row = index / 12
        val column = index % 12
        val point = GridPoint(column * 80.0 + 40.0, row * 80.0 + 40.0)
        return ArrayPhysicalSiteResult(
            siteIndex = index,
            rowIndex = row,
            columnIndex = column,
            siteKey = "R${(row + 1).toString().padStart(2, '0')}C${(column + 1).toString().padStart(2, '0')}",
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
                rectified = point,
                original = point,
                confidence = 0.9,
                source = GridPointSource.CANDIDATE_REFINED,
                flags = emptySet()
            ),
            measurements = emptyList()
        )
    }
}

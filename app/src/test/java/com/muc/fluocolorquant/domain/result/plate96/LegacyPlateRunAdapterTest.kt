package com.muc.fluocolorquant.domain.result.plate96

import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.model.WellResult
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 旧历史适配必须保持既有浓度和8×12索引，不得补跑现代算法。 */
class LegacyPlateRunAdapterTest {

    @Test
    fun `旧96孔板原样恢复A1到H12和冻结浓度`() {
        val result = LegacyPlateRunAdapter.map(source())

        assertTrue(result is Plate96ResultLoadResult.Success)
        val snapshot = (result as Plate96ResultLoadResult.Success).snapshot
        assertEquals(Plate96ResultSource.LEGACY_WELL_RESULT, snapshot.source)
        assertEquals(96, snapshot.wells.size)
        assertEquals("A1", snapshot.wells.first().wellLabel)
        assertEquals("H12", snapshot.wells.last().wellLabel)
        assertEquals(
            37.5,
            snapshot.wells[37].site.measurements.single().concentrationValue ?: Double.NaN,
            0.0
        )
        assertEquals(
            137.0,
            snapshot.wells[37].site.measurements.single().primaryFeatureValue ?: Double.NaN,
            0.0
        )
        assertNull(snapshot.orientation.quarterTurnsClockwise)
        assertNull(snapshot.orientation.originCorner)
        assertEquals(FittingFunction.LINEAR.identifier, snapshot.arraySnapshot.analytes.single().fittingFunction)
        assertEquals(2.0, snapshot.arraySnapshot.analytes.single().fittingParameters["a"] ?: Double.NaN, 0.0)
    }

    @Test
    fun `旧竖向项目不被擅自旋转成标准孔板历史`() {
        val source = source().let { it.copy(project = it.project.copy(rows = 12, columns = 8)) }

        val result = LegacyPlateRunAdapter.map(source)

        assertEquals(
            Plate96ResultErrorCode.NOT_A_LEGACY_PLATE96_RUN,
            (result as Plate96ResultLoadResult.Failure).errorCode
        )
    }

    @Test
    fun `旧范围布尔值不伪造成高于或低于量程方向`() {
        val source = source().copy(
            wellResults = source().wellResults.map { result ->
                if (result.wellIndex == 0) result.copy(isOutOfRange = true) else result
            }
        )

        val mapped = LegacyPlateRunAdapter.map(source) as Plate96ResultLoadResult.Success
        val measurement = mapped.snapshot.wells.first().site.measurements.single()

        assertNull(measurement.reliableRangeStatus)
        assertEquals("LEGACY_OUT_OF_RANGE", measurement.qc.quantificationStatus)
        assertEquals(1.0, measurement.concentrationValue ?: Double.NaN, 0.0)
    }

    private fun source(): LegacyPlateRunSource {
        val project = Project(
            id = "legacy-project",
            name = "旧96孔板",
            detectionMode = "COLORIMETRIC",
            recognitionType = "AUTO",
            imageUri = "content://legacy/plate.jpg",
            rows = 8,
            columns = 12,
            createTime = Date(900L),
            userId = "operator",
            lastRunTimestamp = Date(1_000L),
            analysisMethod = "CURVE_FIT"
        )
        val run = DetectionRun(
            runId = "legacy-run",
            projectId = project.id,
            timestamp = Date(1_000L),
            detectionModelUsed = "legacy-yolo",
            concentrationModelUsed = null,
            status = "Completed",
            errorMessage = null,
            confThreshold = 0.25f,
            iouThreshold = 0.45f,
            wellsDetected = 96
        )
        val analyte = Analyte(id = "cea", name = "CEA")
        val join = ProjectAnalyteJoin(
            projectId = project.id,
            analyteId = analyte.id,
            maxConcentration = 100.0,
            concentrationUnit = "ng/mL",
            fkTemplateId = null,
            fkCurveModelId = "curve-cea"
        )
        val curve = CurveModel(
            id = "curve-cea",
            name = "CEA线性曲线",
            function = FittingFunction.LINEAR,
            pixelType = PixelType.GREEN,
            parameters = mapOf("a" to 2.0, "b" to 1.0),
            metrics = mapOf("R2" to 0.99),
            dataPoints = listOf(0.0 to 10.0, 100.0 to 210.0)
        )
        val wells = List(96) { index ->
            WellResult(
                resultId = index + 1L,
                runId = run.runId,
                projectId = project.id,
                wellIndex = index,
                predictedConcentration = if (index == 37) 37.5 else index + 1.0,
                trueConcentration = null,
                isStandard = false,
                detectedRectLeft = (index % 12 * 10).toFloat(),
                detectedRectTop = (index / 12 * 10).toFloat(),
                detectedRectRight = (index % 12 * 10 + 8).toFloat(),
                detectedRectBottom = (index / 12 * 10 + 8).toFloat(),
                detectionConfidence = 0.9f,
                croppedImageIdentifier = null,
                pixelValueJson = if (index == 37) "{\"channel_g\":137.0}" else "{\"channel_g\":100.0}",
                fkAnalyteId = analyte.id,
                roleType = "SAMPLE"
            )
        }
        return LegacyPlateRunSource(
            run = run,
            project = project,
            wellResults = wells,
            projectAnalytes = listOf(join),
            analytesById = mapOf(analyte.id to analyte),
            curveModelsById = mapOf(curve.id to curve),
            artifacts = emptyList()
        )
    }
}

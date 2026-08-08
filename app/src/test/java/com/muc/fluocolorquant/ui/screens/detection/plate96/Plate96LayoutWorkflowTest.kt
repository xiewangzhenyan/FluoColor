package com.muc.fluocolorquant.ui.screens.detection.plate96

import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import com.muc.fluocolorquant.domain.detection.plate96.Plate96CircleCandidate
import com.muc.fluocolorquant.domain.detection.plate96.Plate96CircleObservation
import com.muc.fluocolorquant.domain.detection.plate96.Plate96CircleSource
import com.muc.fluocolorquant.domain.detection.plate96.Plate96GridAssembler
import com.muc.fluocolorquant.domain.detection.plate96.Plate96OrientationResolver
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import com.muc.fluocolorquant.ui.screens.detection.siteCoordinateLabel
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationAnalyte
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutAssignmentDraft
import com.muc.fluocolorquant.ui.viewmodels.evaluateArrayLayoutReadiness
import com.muc.fluocolorquant.ui.viewmodels.defaultArraySampleSlot
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationDraft
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationMethod
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationSnapshot
import com.muc.fluocolorquant.ui.viewmodels.mergePaintedAssignments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 96孔板布局页对通用阵列工作台的关键契约回归。 */
class Plate96LayoutWorkflowTest {
    @Test
    fun `样本未填写编号时使用物理孔号作为稳定默认编号`() {
        assertEquals(
            "A1",
            defaultArraySampleSlot(
                GridLayoutAssignmentDraft(0, 0, "cea", TemplateSiteRole.SAMPLE)
            )
        )
        assertEquals(
            "H12",
            defaultArraySampleSlot(
                GridLayoutAssignmentDraft(7, 11, "cea", TemplateSiteRole.SAMPLE)
            )
        )
        assertEquals(
            null,
            defaultArraySampleSlot(
                GridLayoutAssignmentDraft(0, 0, "cea", TemplateSiteRole.STANDARD)
            )
        )
    }

    @Test
    fun `标准曲线完成但孔位为空时明确阻止开始分析`() {
        val completed = GridAnalyteQuantitationDraft(
            analyteId = "cea",
            mode = GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE,
            selectedAnalysisModelId = "curve-cea",
            appliedSnapshot = AnalyteQuantitationSnapshot(
                analyteId = "cea",
                method = AnalyteQuantitationMethod.STANDARD_CURVE_RESOURCE,
                concentrationUnit = "g/ml",
                sourceResourceId = "curve-cea",
                processorVersion = "test",
                inputFingerprint = "fingerprint"
            )
        )

        val emptyLayout = evaluateArrayLayoutReadiness(emptyList(), listOf(completed), 96)
        assertEquals(0, emptyLayout.assignedSiteCount)
        assertEquals(1, emptyLayout.completedAnalyteCount)
        assertTrue(!emptyLayout.canStart)

        val assignedLayout = evaluateArrayLayoutReadiness(
            assignments = listOf(
                GridLayoutAssignmentDraft(
                    rowIndex = 0,
                    columnIndex = 0,
                    analyteId = "cea",
                    role = TemplateSiteRole.SAMPLE
                )
            ),
            quantitationDrafts = listOf(completed),
            totalSiteCount = 96
        )
        assertTrue(assignedLayout.canStart)
    }

    @Test
    fun `定位结果适配后保持标准8乘12圆孔和完整双位数标签`() {
        val circles = buildPlateCircles()
        val result = Plate96GridAssembler().assemble(
            sourceWidth = 1200,
            sourceHeight = 800,
            circles = circles,
            resolution = Plate96OrientationResolver().resolve(circles.toObservations())
        )

        val preview = result.toPlate96LayoutPreview(
            runId = "plate-layout-test",
            originalImageUri = "content://plate96/test",
            detectionMode = "colorimetric",
            analytes = listOf(GridLocalizationAnalyte("cea", "CEA", "ng/mL", 100.0))
        )

        assertEquals(8, preview.rows)
        assertEquals(12, preview.columns)
        assertEquals(96, preview.sites.size)
        assertEquals("A10", siteCoordinateLabel(0, 9))
        assertEquals("A12", siteCoordinateLabel(0, 11))
        assertEquals("H12", siteCoordinateLabel(7, 11))
        assertTrue(preview.sites.all { it.cropRegion?.shape == ArrayUnitShape.CIRCLE })
    }

    @Test
    fun `96孔板连续多笔绘制会累积且其他分析物不能静默覆盖`() {
        val firstStroke = mergePaintedAssignments(
            existing = emptyMap(),
            paintedSiteIndices = setOf(0, 1, 2),
            rows = 8,
            columns = 12,
            analyteId = "cea",
            role = TemplateSiteRole.SAMPLE,
            standardConcentration = null,
            sampleId = "S1",
            clearMode = false
        )
        val secondStroke = mergePaintedAssignments(
            existing = firstStroke.assignments,
            paintedSiteIndices = setOf(12, 13),
            rows = 8,
            columns = 12,
            analyteId = "cea",
            role = TemplateSiteRole.SAMPLE,
            standardConcentration = null,
            sampleId = "S1",
            clearMode = false
        )
        val protectedAttempt = mergePaintedAssignments(
            existing = secondStroke.assignments,
            paintedSiteIndices = setOf(1, 14),
            rows = 8,
            columns = 12,
            analyteId = "afp",
            role = TemplateSiteRole.STANDARD,
            standardConcentration = null,
            sampleId = null,
            clearMode = false
        )

        assertEquals(5, secondStroke.assignments.size)
        assertEquals(1, protectedAttempt.protectedSiteCount)
        assertEquals("cea", protectedAttempt.assignments.getValue(1).analyteId)
        assertEquals("afp", protectedAttempt.assignments.getValue(14).analyteId)

        val cleared = mergePaintedAssignments(
            existing = protectedAttempt.assignments,
            paintedSiteIndices = setOf(1),
            rows = 8,
            columns = 12,
            analyteId = null,
            role = TemplateSiteRole.SAMPLE,
            standardConcentration = null,
            sampleId = null,
            clearMode = true
        )
        val reassigned = mergePaintedAssignments(
            existing = cleared.assignments,
            paintedSiteIndices = setOf(1),
            rows = 8,
            columns = 12,
            analyteId = "afp",
            role = TemplateSiteRole.STANDARD,
            standardConcentration = null,
            sampleId = null,
            clearMode = false
        )

        assertEquals("afp", reassigned.assignments.getValue(1).analyteId)
        assertEquals(TemplateSiteRole.STANDARD, reassigned.assignments.getValue(1).role)
    }

    private fun buildPlateCircles(): List<Plate96CircleCandidate> = buildList {
        var index = 0
        repeat(8) { row ->
            repeat(12) { column ->
                val centerX = 75.0 + column * 88.0
                val centerY = 65.0 + row * 82.0
                add(
                    Plate96CircleCandidate(
                        candidateIndex = index++,
                        centerX = centerX,
                        centerY = centerY,
                        radius = 26.0,
                        confidence = 0.94,
                        source = Plate96CircleSource.HOUGH,
                        objectBounds = ArrayImageBounds(
                            centerX - 30.0,
                            centerY - 30.0,
                            centerX + 30.0,
                            centerY + 30.0
                        )
                    )
                )
            }
        }
    }

    private fun List<Plate96CircleCandidate>.toObservations(): List<Plate96CircleObservation> {
        return mapIndexed { observationIndex, circle ->
            Plate96CircleObservation(
                x = circle.centerX,
                y = circle.centerY,
                radius = circle.radius,
                confidence = circle.confidence,
                observationIndex = observationIndex
            )
        }
    }
}

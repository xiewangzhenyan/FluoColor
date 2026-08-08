package com.muc.fluocolorquant.ui.screens.settings.template

import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微流控模板草稿和阵列编辑的纯 Kotlin 契约测试。
 *
 * 这些行为不依赖 Compose 或 Android Context，后续项目创建、定位和结果页都可以复用
 * 同一套中性坐标与位点角色，避免孔板索引再次渗透到微流控链路。
 */
class TemplateWizardModelsTest {

    @Test
    fun `阵列坐标统一显示为中性R行C列名称`() {
        assertEquals("R01C01", TemplateSiteCoordinate(rowIndex = 0, columnIndex = 0).displayName)
        assertEquals("R10C10", TemplateSiteCoordinate(rowIndex = 9, columnIndex = 9).displayName)
        assertEquals("R15C15", TemplateSiteCoordinate(rowIndex = 14, columnIndex = 14).displayName)
    }

    @Test
    fun `矩形选择包含起点终点之间全部位点`() {
        val layout = TemplateArrayLayoutDraft(rows = 10, columns = 10)
            .selectRectangle(
                start = TemplateSiteCoordinate(1, 2),
                end = TemplateSiteCoordinate(3, 4)
            )

        assertEquals(9, layout.selectedSites.size)
        assertTrue(TemplateSiteCoordinate(1, 2) in layout.selectedSites)
        assertTrue(TemplateSiteCoordinate(3, 4) in layout.selectedSites)
    }

    @Test
    fun `批量分配会向全部选中位点写入分析物角色浓度和重复组`() {
        val assigned = TemplateArrayLayoutDraft(rows = 10, columns = 10)
            .selectRow(0)
            .applyToSelection(
                TemplateSiteDraft(
                    analyteId = "cea",
                    role = TemplateSiteRole.STANDARD,
                    standardConcentrationInput = "10",
                    repeatGroup = "STD-10"
                )
            )

        assertEquals(10, assigned.assignments.size)
        assigned.assignments.values.forEach { site ->
            assertEquals("cea", site.analyteId)
            assertEquals(TemplateSiteRole.STANDARD, site.role)
            assertEquals("10", site.standardConcentrationInput)
            assertEquals("STD-10", site.repeatGroup)
        }
    }

    @Test
    fun `保存校验仍要求载体但不再要求采集设备档案`() {
        val errors = completeDraft().copy(
            carrierProfileId = "",
            acquisitionProfileId = ""
        ).validateForPublication()

        assertTrue(TemplateWizardError.CARRIER_REQUIRED in errors)
        assertTrue(TemplateWizardError.ACQUISITION_PROFILE_REQUIRED !in errors)
    }

    @Test
    fun `仅信号模板不要求伪造浓度单位可靠范围或分析模型`() {
        val signalOnlyDraft = completeDraft().copy(
            acquisitionProfileId = "",
            analytes = completeDraft().analytes.map {
                it.copy(
                    analysisModelId = null,
                    concentrationUnit = "",
                    reliableRangeMinInput = "",
                    reliableRangeMaxInput = ""
                )
            }
        )

        val errors = signalOnlyDraft.validateForPublication()

        assertTrue(TemplateWizardError.ANALYSIS_MODEL_REQUIRED !in errors)
        assertTrue(TemplateWizardError.CONCENTRATION_UNIT_REQUIRED !in errors)
        assertTrue(TemplateWizardError.RELIABLE_RANGE_INVALID !in errors)
    }

    @Test
    fun `发布校验要求全部物理位点显式分配或禁用`() {
        val draft = completeDraft()
        val incompleteLayout = draft.layout.copy(
            assignments = draft.layout.assignments - TemplateSiteCoordinate(1, 1)
        )

        val errors = draft.copy(layout = incompleteLayout).validateForPublication()

        assertTrue(TemplateWizardError.LAYOUT_NOT_FULLY_ASSIGNED in errors)
    }

    @Test
    fun `比色模板发布前必须具有空白位或参考位`() {
        val draft = completeDraft()
        val withoutBlank = draft.layout.copy(
            assignments = draft.layout.assignments.mapValues { (_, site) ->
                if (site.role == TemplateSiteRole.BLANK) {
                    site.copy(role = TemplateSiteRole.STANDARD, standardConcentrationInput = "0")
                } else {
                    site
                }
            }
        )

        val errors = draft.copy(layout = withoutBlank).validateForPublication()

        assertTrue(TemplateWizardError.ANALYTE_BLANK_REQUIRED in errors)
    }

    @Test
    fun `荧光模板可以依靠局部背景环发布而不伪造空白位`() {
        val draft = completeDraft()
        val withoutBlank = draft.layout.copy(
            assignments = draft.layout.assignments.mapValues { (_, site) ->
                if (site.role == TemplateSiteRole.BLANK) {
                    site.copy(role = TemplateSiteRole.SAMPLE)
                } else {
                    site
                }
            }
        )
        val fluorescenceDraft = draft.copy(
            detectionMode = DetectionModality.FLUORESCENCE.code,
            analytes = draft.analytes.map {
                it.copy(fluorescenceChannel = FluorescenceChannel.GREEN)
            },
            layout = withoutBlank
        )

        val errors = fluorescenceDraft.validateForPublication()

        assertTrue(TemplateWizardError.ANALYTE_BLANK_REQUIRED !in errors)
    }

    private fun completeDraft(): TemplateWizardDraft {
        val assignments = mapOf(
            TemplateSiteCoordinate(0, 0) to TemplateSiteDraft(
                analyteId = "cea",
                role = TemplateSiteRole.SAMPLE
            ),
            TemplateSiteCoordinate(0, 1) to TemplateSiteDraft(
                analyteId = "cea",
                role = TemplateSiteRole.BLANK
            ),
            TemplateSiteCoordinate(1, 0) to TemplateSiteDraft(
                analyteId = "cea",
                role = TemplateSiteRole.STANDARD,
                standardConcentrationInput = "10"
            ),
            TemplateSiteCoordinate(1, 1) to TemplateSiteDraft(
                role = TemplateSiteRole.DISABLED,
                enabled = false
            )
        )
        return TemplateWizardDraft(
            templateName = "CEA 2×2 微流控模板",
            detectionMode = DetectionModality.COLORIMETRIC.code,
            readoutLayout = ReadoutLayout.GRID_SITES.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            carrierProfileId = "carrier-2x2",
            acquisitionProfileId = "device-v1",
            analytes = listOf(
                TemplateAnalyteDraft(
                    analyteId = "cea",
                    analysisModelId = "model-cea-v1",
                    concentrationUnit = "ng/mL",
                    reliableRangeMinInput = "0.1",
                    reliableRangeMaxInput = "100"
                )
            ),
            layout = TemplateArrayLayoutDraft(
                rows = 2,
                columns = 2,
                assignments = assignments
            )
        )
    }
}

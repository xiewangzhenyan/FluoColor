package com.muc.fluocolorquant.ui.screens.project

import com.muc.fluocolorquant.domain.project.TemplatePreflightCode
import com.muc.fluocolorquant.domain.project.TemplatePreflightIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模板优先新建项目表单的纯 Kotlin 验证测试。 */
class TemplateProjectFormStateTest {

    @Test
    fun `未选择模板或图片时不能提交`() {
        assertFalse(TemplateProjectFormState().canSubmit)
        assertFalse(
            TemplateProjectFormState(
                projectName = "项目",
                selectedTemplateId = "template-v1",
                templateReady = true
            ).canSubmit
        )
    }

    @Test
    fun `所有样本位都有映射后才允许提交`() {
        val base = TemplateProjectFormState(
            projectName = "CEA 芯片项目",
            selectedTemplateId = "template-v1",
            imageUri = "content://chip/1",
            templateReady = true,
            requiredSampleSites = listOf(
                TemplateSampleSiteField("R01C01", "CEA", "样本 1"),
                TemplateSampleSiteField("R01C02", "CEA", "样本 1")
            )
        )

        assertEquals(setOf("R01C01", "R01C02"), base.missingSampleSiteKeys)
        assertFalse(base.canSubmit)

        val completed = base.copy(
            sampleSlotMapping = mapOf(
                "R01C01" to "sample-001",
                "R01C02" to "sample-001"
            )
        )
        assertTrue(completed.missingSampleSiteKeys.isEmpty())
        assertTrue(completed.canSubmit)
    }

    @Test
    fun `预检阻断或提交中时创建按钮保持禁用`() {
        val ready = TemplateProjectFormState(
            projectName = "CEA 芯片项目",
            selectedTemplateId = "template-v1",
            imageUri = "content://chip/1",
            templateReady = true
        )
        assertTrue(ready.canSubmit)
        assertFalse(ready.copy(isSubmitting = true).canSubmit)
        assertFalse(
            ready.copy(
                preflightIssues = listOf(
                    TemplatePreflightIssue(TemplatePreflightCode.MODEL_MODALITY_MISMATCH)
                )
            ).canSubmit
        )
    }

    @Test
    fun `样本映射摘要按样本编号分组避免默认展示一百个输入框`() {
        val state = TemplateProjectFormState(
            requiredSampleSites = listOf(
                TemplateSampleSiteField("R01C01", "CEA", "样本 1"),
                TemplateSampleSiteField("R01C02", "CEA", "样本 1"),
                TemplateSampleSiteField("R02C01", "NSE", "样本 2")
            ),
            sampleSlotMapping = mapOf(
                "R01C01" to "sample-001",
                "R01C02" to "sample-001",
                "R02C01" to "sample-002"
            )
        )

        assertEquals(2, state.sampleGroups.size)
        assertEquals(2, state.sampleGroups.first { it.sampleSlot == "sample-001" }.siteKeys.size)
    }
}

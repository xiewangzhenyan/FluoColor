package com.muc.fluocolorquant.ui.screens.project

import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.project.DirectCarrierPreset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 直接新建表单只验证本次实验输入，并支持逐分析物独立单位。 */
class DirectProjectFormStateTest {

    @Test
    fun `多分析物分别具备单位后可以提交`() {
        val state = DirectProjectFormState(
            projectName = "肿瘤标志物荧光芯片",
            detectionModality = DetectionModality.FLUORESCENCE,
            carrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
            selectedAnalytes = listOf(
                DirectAnalyteSelection("cea", "ng/mL"),
                DirectAnalyteSelection("afp", "pg/mL")
            ),
            imageUri = "content://chip/1"
        )

        assertTrue(state.canSubmit)
    }

    @Test
    fun `任一分析物缺少单位时不能提交`() {
        val state = DirectProjectFormState(
            projectName = "多分析物项目",
            selectedAnalytes = listOf(
                DirectAnalyteSelection("cea", "ng/mL"),
                DirectAnalyteSelection("afp", "")
            ),
            imageUri = "content://chip/1"
        )

        assertFalse(state.canSubmit)
    }

    @Test
    fun `任一分析物最大浓度为空零或负数时不能提交`() {
        val base = DirectProjectFormState(
            projectName = "最大浓度校验",
            selectedAnalytes = listOf(DirectAnalyteSelection("cea", "ng/mL")),
            imageUri = "content://chip/1"
        )

        assertFalse(
            base.copy(
                selectedAnalytes = listOf(DirectAnalyteSelection("cea", "ng/mL", ""))
            ).canSubmit
        )
        assertFalse(
            base.copy(
                selectedAnalytes = listOf(DirectAnalyteSelection("cea", "ng/mL", "0"))
            ).canSubmit
        )
        assertFalse(
            base.copy(
                selectedAnalytes = listOf(DirectAnalyteSelection("cea", "ng/mL", "-1"))
            ).canSubmit
        )
        assertTrue(
            base.copy(
                selectedAnalytes = listOf(DirectAnalyteSelection("cea", "ng/mL", "25.5"))
            ).canSubmit
        )
    }

    @Test
    fun `同一分析物不能重复选择`() {
        val state = DirectProjectFormState(
            projectName = "重复分析物项目",
            selectedAnalytes = listOf(
                DirectAnalyteSelection("cea", "ng/mL"),
                DirectAnalyteSelection("cea", "pg/mL")
            ),
            imageUri = "content://chip/1"
        )

        assertFalse(state.canSubmit)
    }

    @Test
    fun `自定义阵列必须输入合法行列`() {
        val base = DirectProjectFormState(
            projectName = "自定义芯片",
            carrierPreset = DirectCarrierPreset.MICROFLUIDIC_CUSTOM,
            selectedAnalytes = listOf(DirectAnalyteSelection("cea", "ng/mL")),
            imageUri = "content://chip/1",
            customRowsInput = "",
            customColumnsInput = "15"
        )

        assertFalse(base.canSubmit)
        assertTrue(base.copy(customRowsInput = "10").canSubmit)
    }
}

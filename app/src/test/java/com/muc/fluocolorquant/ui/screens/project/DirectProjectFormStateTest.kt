package com.muc.fluocolorquant.ui.screens.project

import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.project.DirectCarrierPreset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 直接新建表单只验证本次实验输入，不依赖模板或资源生命周期。 */
class DirectProjectFormStateTest {

    @Test
    fun `荧光项目具备基本输入后可以直接提交`() {
        val state = DirectProjectFormState(
            projectName = "CEA 荧光芯片",
            detectionModality = DetectionModality.FLUORESCENCE,
            carrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
            selectedAnalyteId = "cea",
            concentrationUnit = "ng/mL",
            imageUri = "content://chip/1"
        )

        assertTrue(state.canSubmit)
    }

    @Test
    fun `自定义阵列必须输入合法行列`() {
        val base = DirectProjectFormState(
            projectName = "自定义芯片",
            carrierPreset = DirectCarrierPreset.MICROFLUIDIC_CUSTOM,
            selectedAnalyteId = "cea",
            concentrationUnit = "ng/mL",
            imageUri = "content://chip/1",
            customRowsInput = "",
            customColumnsInput = "15"
        )

        assertFalse(base.canSubmit)
        assertTrue(base.copy(customRowsInput = "10").canSubmit)
    }

    @Test
    fun `微流控比色必须指定阵列范围内的真实参考位`() {
        val base = DirectProjectFormState(
            projectName = "CEA 比色芯片",
            detectionModality = DetectionModality.COLORIMETRIC,
            carrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
            selectedAnalyteId = "cea",
            concentrationUnit = "ng/mL",
            imageUri = "content://chip/1",
            colorReferenceRowInput = "11",
            colorReferenceColumnInput = "1"
        )

        assertFalse(base.canSubmit)
        assertTrue(base.copy(colorReferenceRowInput = "1").canSubmit)
    }

    @Test
    fun `孔板比色继续使用旧检测流程无需填写微流控参考位`() {
        val state = DirectProjectFormState(
            projectName = "96 孔板比色",
            detectionModality = DetectionModality.COLORIMETRIC,
            carrierPreset = DirectCarrierPreset.PLATE_96,
            selectedAnalyteId = "cea",
            concentrationUnit = "ng/mL",
            imageUri = "content://plate/1",
            colorReferenceRowInput = "",
            colorReferenceColumnInput = ""
        )

        assertTrue(state.canSubmit)
    }
}

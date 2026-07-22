package com.muc.fluocolorquant.ui.screens.project

import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.project.DirectCarrierPreset
import com.muc.fluocolorquant.utils.math.GridLayoutPolicy

/**
 * 普通用户直接新建项目的表单状态。
 *
 * 表单只保留一次实验真正需要填写的信息，不包含模板发布状态、采集设备档案、模型 SHA、
 * 处理器版本等后台字段。比色微流控需要一个真实参考位，因此使用一基行列输入明确指定，
 * 而不是由程序静默伪造空白位。
 */
data class DirectProjectFormState(
    val projectName: String = "",
    val detectionModality: DetectionModality = DetectionModality.FLUORESCENCE,
    val carrierPreset: DirectCarrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
    val customRowsInput: String = "10",
    val customColumnsInput: String = "10",
    val selectedAnalyteId: String? = null,
    val concentrationUnit: String = "",
    val selectedAnalysisModelId: String? = null,
    val analysisModelSelectionExplicit: Boolean = false,
    val sampleId: String = "",
    val imageUri: String? = null,
    val colorReferenceRowInput: String = "1",
    val colorReferenceColumnInput: String = "1",
    val isSubmitting: Boolean = false
) {
    val rows: Int?
        get() = when (carrierPreset) {
            DirectCarrierPreset.PLATE_96 -> 8
            DirectCarrierPreset.MICROFLUIDIC_10_X_10 -> 10
            DirectCarrierPreset.MICROFLUIDIC_15_X_15 -> 15
            DirectCarrierPreset.MICROFLUIDIC_CUSTOM ->
                customRowsInput.toIntOrNull()?.takeIf(GridLayoutPolicy::isValidDimension)
        }

    val columns: Int?
        get() = when (carrierPreset) {
            DirectCarrierPreset.PLATE_96 -> 12
            DirectCarrierPreset.MICROFLUIDIC_10_X_10 -> 10
            DirectCarrierPreset.MICROFLUIDIC_15_X_15 -> 15
            DirectCarrierPreset.MICROFLUIDIC_CUSTOM ->
                customColumnsInput.toIntOrNull()?.takeIf(GridLayoutPolicy::isValidDimension)
        }

    val carrierType: CarrierType
        get() = if (carrierPreset == DirectCarrierPreset.PLATE_96) {
            CarrierType.PLATE
        } else {
            CarrierType.MICROFLUIDIC_CHIP
        }

    /** 只有微流控比色模式需要明确参考位，孔板继续沿用旧检测流程。 */
    val requiresColorReference: Boolean
        get() = detectionModality == DetectionModality.COLORIMETRIC &&
            carrierType == CarrierType.MICROFLUIDIC_CHIP

    val colorReferenceValid: Boolean
        get() {
            if (!requiresColorReference) return true
            val row = colorReferenceRowInput.toIntOrNull() ?: return false
            val column = colorReferenceColumnInput.toIntOrNull() ?: return false
            val validRows = rows ?: return false
            val validColumns = columns ?: return false
            return row in 1..validRows && column in 1..validColumns
        }

    /** 创建按钮只依赖本次实验输入，不再依赖任何资源的发布或归档状态。 */
    val canSubmit: Boolean
        get() = projectName.isNotBlank() &&
            !selectedAnalyteId.isNullOrBlank() &&
            concentrationUnit.isNotBlank() &&
            !imageUri.isNullOrBlank() &&
            rows != null &&
            columns != null &&
            colorReferenceValid &&
            !isSubmitting
}

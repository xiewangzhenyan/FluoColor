package com.muc.fluocolorquant.ui.screens.project

import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.project.DirectCarrierPreset
import com.muc.fluocolorquant.utils.math.GridLayoutPolicy

/** 单个已选分析物在新建项目阶段需要保留的最小配置。 */
data class DirectAnalyteSelection(
    val analyteId: String,
    val concentrationUnit: String,
    val maxConcentrationInput: String = "100"
) {
    /** 最大浓度用于结果色带、旧96孔板百分比显示和项目科研元数据。 */
    val maxConcentration: Double?
        get() = maxConcentrationInput.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
}

/**
 * 普通用户直接新建项目的表单状态。
 *
 * 新建阶段只确定项目、检测对象、载体、分析物及其单位和原始图片。孔位角色、样本编号、
 * 空白/质控/参考位以及每个分析物采用的定量方案，统一放到后续可视化孔位布局页面配置，
 * 避免用户尚未看到真实阵列时就被迫填写专业参数。
 */
data class DirectProjectFormState(
    val projectName: String = "",
    val detectionModality: DetectionModality = DetectionModality.FLUORESCENCE,
    val carrierPreset: DirectCarrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
    val customRowsInput: String = "10",
    val customColumnsInput: String = "10",
    val selectedAnalytes: List<DirectAnalyteSelection> = emptyList(),
    val imageUri: String? = null,
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

    /**
     * 创建按钮只依赖本次实验输入，不依赖模板、模型或资源生命周期。
     * 同一个分析物只允许出现一次；单位必须逐分析物填写，防止后续结果误用全局单位。
     */
    val canSubmit: Boolean
        get() = projectName.isNotBlank() &&
            selectedAnalytes.isNotEmpty() &&
            selectedAnalytes.all {
                it.analyteId.isNotBlank() &&
                    it.concentrationUnit.isNotBlank() &&
                    it.maxConcentration != null
            } &&
            selectedAnalytes.map(DirectAnalyteSelection::analyteId).distinct().size ==
            selectedAnalytes.size &&
            !imageUri.isNullOrBlank() &&
            rows != null &&
            columns != null &&
            !isSubmitting
}

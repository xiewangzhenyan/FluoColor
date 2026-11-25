package com.muc.fluocolorquant.ui.screens.project

import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.SpectrumLightSource
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.domain.project.DirectCarrierPreset
import com.muc.fluocolorquant.utils.math.GridLayoutPolicy

/** 新建项目工作流模式：自由配置参数，或直接套用已有实验模板（仅填项目名 + 实验图）。 */
enum class DirectProjectCreationMode {
    CUSTOM,
    FROM_TEMPLATE
}

/** 单个已选分析物在新建项目阶段需要保留的最小配置。 */
data class DirectAnalyteSelection(
    val analyteId: String,
    val concentrationUnit: String,
    val maxConcentrationInput: String = "100",
    val fluorescenceChannel: FluorescenceChannel = FluorescenceChannel.GREEN,
    val selectedReagentIds: Set<String> = emptySet()
) {
    /** 最大浓度用于结果色带、旧96孔板百分比显示和项目科研元数据。 */
    val maxConcentration: Double?
        get() = maxConcentrationInput.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
}

/**
 * 普通用户新建项目的统一表单状态（同时承载自由新建与一键从模板复用）。
 */
data class DirectProjectFormState(
    val creationMode: DirectProjectCreationMode = DirectProjectCreationMode.CUSTOM,
    val selectedTemplateId: String? = null,
    val projectName: String = "",
    val detectionModality: DetectionModality = DetectionModality.FLUORESCENCE,
    val carrierPreset: DirectCarrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
    val customRowsInput: String = "10",
    val customColumnsInput: String = "10",
    // 旧版自定义阵列固定为方形；默认继续使用方形，避免升级后无故改变用户工作流。
    val customSiteShape: SiteShape = SiteShape.SQUARE,
    // 光源是用户确认的采集元数据，不参与波长映射、平滑或寻峰算法。
    val spectrumLightSource: SpectrumLightSource = SpectrumLightSource.LED_WHITE,
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

    /** 位点形状是用户对真实载体的声明，不是 PG-Grid 从图片自动识别的结果。 */
    val siteShape: SiteShape
        get() = when (carrierPreset) {
            DirectCarrierPreset.PLATE_96 -> SiteShape.CIRCLE
            DirectCarrierPreset.MICROFLUIDIC_10_X_10,
            DirectCarrierPreset.MICROFLUIDIC_15_X_15 -> SiteShape.SQUARE
            DirectCarrierPreset.MICROFLUIDIC_CUSTOM -> customSiteShape
        }

    /**
     * 创建按钮只依赖本次实验输入，不依赖模板、模型或资源生命周期。
     * 同一个分析物只允许出现一次；单位必须逐分析物填写，防止后续结果误用全局单位。
     */
    val canSubmit: Boolean
        get() = if (creationMode == DirectProjectCreationMode.FROM_TEMPLATE) {
            projectName.isNotBlank() &&
                !selectedTemplateId.isNullOrBlank() &&
                !imageUri.isNullOrBlank() &&
                !isSubmitting
        } else {
            projectName.isNotBlank() &&
                selectedAnalytes.isNotEmpty() &&
                selectedAnalytes.all {
                    it.analyteId.isNotBlank() &&
                        it.concentrationUnit.isNotBlank() &&
                        it.maxConcentration != null
                } &&
                selectedAnalytes.map(DirectAnalyteSelection::analyteId).distinct().size ==
                selectedAnalytes.size &&
                !imageUri.isNullOrBlank() &&
                (detectionModality == DetectionModality.SPECTRUM ||
                    (rows != null && columns != null)) &&
                !isSubmitting
        }
}

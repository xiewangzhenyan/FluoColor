package com.muc.fluocolorquant.domain.result

/**
 * 结果页、PDF 与 CSV 共用的三级测量质量状态。
 *
 * 该状态只描述测量本身是否可靠，不再混入曲线标定范围或项目量程状态。
 * 范围状态、低信号、几何来源和光度 flags 仍作为独立证据保存在结果、详情和导出中。
 */
enum class ArrayMeasurementQualityLevel {
    /** 数值有限，且测量链没有硬性质量问题。 */
    VALID,

    /** 数值仍可展示，但成像、ROI、背景环或模型补位等证据表明测量本身需要复核。 */
    REVIEW,

    /** 当前显示维度没有有限数值，或证据表明该位点已经无法安全解释。 */
    UNAVAILABLE
}

/**
 * 统一判定位点的三级质量状态。
 *
 * `qualityReliable` 已由采样器综合严重饱和、严重裁切、空 ROI 和模型补位等硬性证据。
 * 普通 `photometryFlags`、低信号和范围外推不能再次把位点升级为“建议复核”，否则同一条
 * 软提示会在结果页被重复放大。旧快照可能缺少可靠性布尔值的正确冻结，因此仍使用比例
 * 字段兜底识别严重饱和或严重裁切。
 */
fun resolveArrayMeasurementQuality(
    displayValue: Double?,
    qualityReliable: Boolean,
    saturationRatio: Double?,
    roiClipRatio: Double?,
    annulusClipRatio: Double?
): ArrayMeasurementQualityLevel {
    if (displayValue?.isFinite() != true) {
        return ArrayMeasurementQualityLevel.UNAVAILABLE
    }

    val severePhotometryRisk = saturationRatio.isAtLeast(SEVERE_SATURATION_RATIO) ||
        roiClipRatio.isAtLeast(SEVERE_CLIP_RATIO) ||
        annulusClipRatio.isAtLeast(SEVERE_CLIP_RATIO)
    val needsReview = !qualityReliable || severePhotometryRisk
    return if (needsReview) {
        ArrayMeasurementQualityLevel.REVIEW
    } else {
        ArrayMeasurementQualityLevel.VALID
    }
}

/**
 * 使用完整结果领域对象判定质量，供详情页、PDF 和 CSV 直接复用。
 *
 * [site] 暂时保留在稳定调用边界中，几何来源仍可由各导出出口单独审计；当前质量等级
 * 只服从已经冻结的 `qualityReliable`，避免再次根据普通几何 flags 重复升级风险。
 */
@Suppress("UNUSED_PARAMETER")
fun ArraySiteMeasurementResult.resolveQualityLevel(
    site: ArrayPhysicalSiteResult,
    displayValue: Double? = concentrationValue ?: primaryFeatureValue
): ArrayMeasurementQualityLevel {
    val base = detail.basePhotometryOrNull()
    return resolveArrayMeasurementQuality(
        displayValue = displayValue,
        qualityReliable = qualityReliable,
        saturationRatio = base?.saturationRatio,
        roiClipRatio = base?.roiClipRatio,
        annulusClipRatio = base?.annulusClipRatio
    )
}

/** 不同检测模态共享同一个基础光度结构，质量分级只在领域层拆包一次。 */
private fun ArrayMeasurementDetail.basePhotometryOrNull() = when (this) {
    is ArrayMeasurementDetail.Colorimetric -> site.base
    is ArrayMeasurementDetail.Fluorescence -> site.base
    is ArrayMeasurementDetail.ColorimetricReference -> site
    is ArrayMeasurementDetail.LegacyUnparsed -> null
}

private fun Double?.isAtLeast(threshold: Double): Boolean {
    return this?.let { value -> value.isFinite() && value >= threshold } == true
}

private const val SEVERE_CLIP_RATIO: Double = 0.20
private const val SEVERE_SATURATION_RATIO: Double = 0.25

package com.muc.fluocolorquant.domain.detection.array

import android.graphics.Bitmap
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitRegion
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape

/** 通用规则阵列定位结果的首个稳定结构版本。 */
const val ARRAY_LOCALIZATION_SCHEMA_V1: String = "array-localization-v1"

/** 普通界面可选择的定位算法层级，不向用户暴露模型阈值或 OpenCV 参数。 */
enum class ArrayLocatorMode {
    AUTO,
    OBJECT_DETECTION,
    GEOMETRIC_SHAPE
}

/** 单个位点几何的最终来源，用于过程页解释真实观测与晶格补位。 */
enum class ArraySiteLocalizationSource {
    OBJECT_DETECTION,
    SHAPE_REFINED,
    CONTOUR_REFINED,
    GRID_IMPUTED,
    USER_ADJUSTED
}

/** 不改变主结果颜色的轻量几何状态标记。 */
enum class ArraySiteLocalizationFlag {
    OBJECT_DETECTION_ONLY,
    GRID_IMPUTED,
    LOW_GEOMETRIC_SUPPORT,
    USER_ADJUSTED
}

/** 使用 Double 保存的半开图像边界，避免定位阶段过早进行整数截断。 */
data class ArrayImageBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    fun requireValid(): ArrayImageBounds = apply {
        require(listOf(left, top, right, bottom).all(Double::isFinite)) { "图像边界必须为有限数值" }
        require(left >= 0.0 && top >= 0.0) { "图像边界左上角不能为负数" }
        require(right > left && bottom > top) { "图像边界必须具有正宽高" }
    }
}

/**
 * 局部背景环的几何定义。
 *
 * 背景环不复制像素掩膜；采样器按中心和内外半径即时生成，既能避免持久化巨大ByteArray，
 * 也能保证圆孔前景与周围局部背景使用同一个坐标系。
 */
data class ArrayBackgroundAnnulus(
    val center: ArrayImagePoint,
    val innerRadiusPx: Double,
    val outerRadiusPx: Double
) {
    fun requireValid(): ArrayBackgroundAnnulus = apply {
        require(innerRadiusPx.isFinite() && innerRadiusPx > 0.0) { "背景环内半径必须为正有限数值" }
        require(outerRadiusPx.isFinite() && outerRadiusPx > innerRadiusPx) {
            "背景环外半径必须大于内半径"
        }
    }
}

/** 通用阵列定位器的运行参数；96孔板会固定为8×12和圆形。 */
data class ArrayLocatorConfig(
    val canonicalRows: Int,
    val canonicalColumns: Int,
    val unitShape: ArrayUnitShape,
    val mode: ArrayLocatorMode = ArrayLocatorMode.AUTO,
    val confidenceThreshold: Float = 0.25f,
    val iouThreshold: Float = 0.45f
) {
    init {
        require(canonicalRows > 0 && canonicalColumns > 0) { "标准阵列行列必须大于0" }
        require(confidenceThreshold in 0f..1f) { "置信度阈值必须位于0到1" }
        require(iouThreshold in 0f..1f) { "IoU阈值必须位于0到1" }
    }
}

/** 单个位点同时冻结原图与标准方向工作图中的几何。 */
data class ArrayLocalizedSite(
    val siteIndex: Int,
    val displayLabel: String,
    val canonicalCoordinate: ArrayGridCoordinate,
    val sourceCoordinate: ArrayGridCoordinate,
    val normalizedCenter: ArrayImagePoint,
    val sourceCenter: ArrayImagePoint,
    val normalizedBounds: ArrayImageBounds,
    val sourceBounds: ArrayImageBounds,
    val normalizedBackgroundAnnulus: ArrayBackgroundAnnulus,
    val sourceBackgroundAnnulus: ArrayBackgroundAnnulus,
    val radiusPx: Double?,
    val confidence: Double,
    val source: ArraySiteLocalizationSource,
    val flags: Set<ArraySiteLocalizationFlag>,
    val normalizedRegion: ArrayUnitRegion,
    val sourceRegion: ArrayUnitRegion
) {
    fun requireValid(): ArrayLocalizedSite = apply {
        require(siteIndex >= 0) { "位点索引不能为负数" }
        require(displayLabel.isNotBlank()) { "位点标签不能为空" }
        normalizedBounds.requireValid()
        sourceBounds.requireValid()
        normalizedBackgroundAnnulus.requireValid()
        sourceBackgroundAnnulus.requireValid()
        require(radiusPx == null || radiusPx.isFinite() && radiusPx > 0.0) { "圆孔半径必须为正有限数值" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "位点置信度必须位于0到1" }
        if (source == ArraySiteLocalizationSource.GRID_IMPUTED) {
            require(ArraySiteLocalizationFlag.GRID_IMPUTED in flags) { "晶格补位必须携带对应标记" }
        }
        normalizedRegion.requireValid()
        sourceRegion.requireValid()
    }
}

/** 帧级定位统计只保存稳定数字，不保存面向用户的警告长文案。 */
data class ArrayLocalizationDiagnostics(
    val observedSiteCount: Int,
    val shapeRefinedSiteCount: Int,
    val imputedSiteCount: Int,
    val orientationScore: Double,
    val orientationAlternativeScore: Double,
    val orientationAmbiguous: Boolean,
    val meanConfidence: Double
) {
    fun requireValid(siteCount: Int): ArrayLocalizationDiagnostics = apply {
        require(observedSiteCount in 0..siteCount) { "真实观测位点数量越界" }
        require(shapeRefinedSiteCount in 0..observedSiteCount) { "形状精定位数量越界" }
        require(imputedSiteCount == siteCount - observedSiteCount) { "补位数量与观测数量不一致" }
        require(orientationScore in 0.0..1.0 && orientationAlternativeScore in 0.0..1.0) {
            "方向评分必须位于0到1"
        }
        require(meanConfidence in 0.0..1.0) { "平均置信度必须位于0到1" }
    }
}

/** 一次定位会话的不可变输出，后续布局和定量不得再次运行定位算法。 */
data class ArrayLocalizationResult(
    val schemaVersion: String = ARRAY_LOCALIZATION_SCHEMA_V1,
    val locatorName: String,
    val locatorVersion: String,
    val orientation: ArrayOrientationSnapshot,
    val imageTransform: ArrayImageTransformSnapshot,
    val sites: List<ArrayLocalizedSite>,
    val diagnostics: ArrayLocalizationDiagnostics
) {
    fun requireValid(): ArrayLocalizationResult = apply {
        require(schemaVersion == ARRAY_LOCALIZATION_SCHEMA_V1) { "不支持的阵列定位结构版本" }
        require(locatorName.isNotBlank() && locatorVersion.isNotBlank()) { "定位器名称和版本不能为空" }
        orientation.requireValid()
        imageTransform.requireValid()
        require(sites.size == orientation.canonicalSiteCount) { "位点数量必须等于标准阵列容量" }
        sites.forEachIndexed { expectedIndex, site ->
            site.requireValid()
            require(site.siteIndex == expectedIndex) { "位点必须按标准行优先索引排列" }
            require(site.canonicalCoordinate.rowIndex == expectedIndex / orientation.canonicalColumns) {
                "位点标准行号与索引不一致"
            }
            require(site.canonicalCoordinate.columnIndex == expectedIndex % orientation.canonicalColumns) {
                "位点标准列号与索引不一致"
            }
        }
        diagnostics.requireValid(sites.size)
    }
}

/** 规则阵列定位器统一入口；生产实现可以由YOLO、OpenCV或后续C++核心提供。 */
interface ArrayLocator {
    suspend fun locate(sourceBitmap: Bitmap, config: ArrayLocatorConfig): ArrayLocalizationResult
}

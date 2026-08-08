package com.muc.fluocolorquant.domain.detection.segmentation

import com.muc.fluocolorquant.data.enums.SiteShape

/**
 * 阵列单元在图像分割与裁切阶段使用的通用形状。
 *
 * 该枚举与载体档案中的 [SiteShape] 一一对应，但放在检测领域中可以避免裁切算法
 * 到处直接判断“96 孔板”或“15×15 芯片”。未来新增圆形微流控腔室或方形孔板时，
 * 只要载体档案声明了正确形状，就能复用同一条处理链。
 */
enum class ArrayUnitShape {
    CIRCLE,
    SQUARE,
    POINT,
    CUSTOM;

    companion object {
        /** 将冻结载体档案的稳定形状码转换为运行期裁切形状。 */
        fun fromSiteShape(siteShape: SiteShape): ArrayUnitShape = when (siteShape) {
            SiteShape.CIRCLE -> CIRCLE
            SiteShape.SQUARE -> SQUARE
            SiteShape.POINT -> POINT
            SiteShape.CUSTOM -> CUSTOM
        }
    }
}

/** 单元边界的来源；结果页和调试证据可据此区分真实分割与保守兜底。 */
enum class ArrayUnitRegionSource {
    /** Otsu、连通域和几何校验全部通过，边界来自当前图片。 */
    SEGMENTED,

    /** 当前单元对比度不足或分割异常，使用全阵列成功单元的中位尺寸居中补齐。 */
    FALLBACK_MEDIAN_GEOMETRY,

    /** 旧孔板等链路已经提供了可信圆/矩形几何，直接适配为通用区域。 */
    DETECTED_GEOMETRY
}

/**
 * 半开像素边界 `[left, right) × [top, bottom)`。
 *
 * 采用半开区间可以与 `Bitmap.createBitmap`、OpenCV `Rect` 和 Python/NumPy 切片保持
 * 完全一致，避免右边界是否包含产生一像素偏差。
 */
data class ArrayUnitBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun requireValid(imageWidth: Int? = null, imageHeight: Int? = null): ArrayUnitBounds = apply {
        require(left >= 0 && top >= 0) { "单元边界左上角不能为负数" }
        require(right > left && bottom > top) { "单元边界必须具有正宽高" }
        if (imageWidth != null) require(right <= imageWidth) { "单元右边界超出图像宽度" }
        if (imageHeight != null) require(bottom <= imageHeight) { "单元下边界超出图像高度" }
    }
}

/**
 * 单个位点的紧致区域。
 *
 * [foregroundMask] 使用边界局部坐标、按行优先保存：1 表示该像素属于单元本体，0 表示
 * 背景。方形单元默认整框为前景，圆形/自定义单元保留形状掩膜，因此“裁切框必须是矩形”
 * 不会迫使科学信号把圆孔四角背景也计算进去。
 */
data class ArrayUnitRegion(
    val siteIndex: Int,
    val rowIndex: Int,
    val columnIndex: Int,
    val shape: ArrayUnitShape,
    val bounds: ArrayUnitBounds,
    val source: ArrayUnitRegionSource,
    val foregroundMask: ByteArray
) {
    /** 判断一个矫正图全局坐标是否属于该单元前景。 */
    fun contains(x: Int, y: Int): Boolean {
        if (x !in bounds.left until bounds.right || y !in bounds.top until bounds.bottom) return false
        val localX = x - bounds.left
        val localY = y - bounds.top
        return foregroundMask[localY * bounds.width + localX].toInt() != 0
    }

    val foregroundPixelCount: Int
        get() = foregroundMask.count { it.toInt() != 0 }

    fun requireValid(imageWidth: Int? = null, imageHeight: Int? = null): ArrayUnitRegion = apply {
        require(siteIndex >= 0 && rowIndex >= 0 && columnIndex >= 0) { "单元索引和行列不能为负数" }
        bounds.requireValid(imageWidth, imageHeight)
        require(foregroundMask.size == bounds.width * bounds.height) {
            "单元前景掩膜尺寸必须等于边界宽高乘积"
        }
        require(foregroundMask.any { it.toInt() != 0 }) { "单元前景掩膜不能为空" }
    }
}

/** 一次完整阵列分割的统计与逐位点区域。 */
data class ArrayUnitSegmentationResult(
    val rows: Int,
    val columns: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val pitchPx: Double,
    val medianWidthPx: Int,
    val medianHeightPx: Int,
    val regions: List<ArrayUnitRegion>
) {
    val segmentedCount: Int
        get() = regions.count { it.source == ArrayUnitRegionSource.SEGMENTED }

    val fallbackCount: Int
        get() = regions.count { it.source == ArrayUnitRegionSource.FALLBACK_MEDIAN_GEOMETRY }

    fun requireValid(): ArrayUnitSegmentationResult = apply {
        require(rows > 0 && columns > 0) { "阵列分割行列必须大于 0" }
        require(imageWidth > 0 && imageHeight > 0) { "阵列分割图像尺寸必须大于 0" }
        require(pitchPx.isFinite() && pitchPx > 0.0) { "阵列分割 pitch 必须为正有限数值" }
        require(medianWidthPx > 0 && medianHeightPx > 0) { "中位单元尺寸必须大于 0" }
        require(regions.size == rows * columns) { "阵列区域数必须恒等于 rows × columns" }
        regions.forEachIndexed { index, region ->
            require(region.siteIndex == index) { "阵列区域必须按行优先连续排列" }
            require(region.rowIndex == index / columns && region.columnIndex == index % columns) {
                "阵列区域行列与索引不一致"
            }
            region.requireValid(imageWidth, imageHeight)
        }
    }
}

/**
 * Python `pg_unit_export.py` 的端侧参数快照。
 *
 * 默认值与 Python 实拍验证版本保持一致；圆形额外增加宽高比和填充率约束，用于排除
 * 孔板局部窗口中被 Otsu 误选的长条反光或文字边缘。
 */
data class ArrayUnitSegmentationConfig(
    val windowPitchRatio: Double = 0.45,
    val maximumCenterShiftPitchRatio: Double = 0.20,
    val minimumSizeMedianRatio: Double = 0.40,
    val maximumSizeMedianRatio: Double = 1.60,
    val minimumContrast: Double = 12.0,
    val minimumComponentAreaPx: Int = 9,
    val minimumCircleAspectRatio: Double = 0.65,
    val minimumCircleFillRatio: Double = 0.42,
    val maximumCircleFillRatio: Double = 0.95,
    val insetPx: Int = 0
) {
    init {
        require(windowPitchRatio in 0.0..<0.5) { "分割窗口半宽必须小于半个 pitch" }
        require(maximumCenterShiftPitchRatio in 0.0..<0.5) { "中心偏移比例必须小于半个 pitch" }
        require(minimumSizeMedianRatio > 0.0) { "最小尺寸比例必须大于 0" }
        require(maximumSizeMedianRatio >= minimumSizeMedianRatio) { "最大尺寸比例不能小于最小尺寸比例" }
        require(minimumContrast >= 0.0) { "最小局部对比度不能为负数" }
        require(minimumComponentAreaPx > 0) { "最小连通域面积必须大于 0" }
        require(minimumCircleAspectRatio in 0.0..1.0) { "圆形最小宽高比必须位于 0 到 1" }
        require(minimumCircleFillRatio in 0.0..1.0) { "圆形最小填充率必须位于 0 到 1" }
        require(maximumCircleFillRatio in minimumCircleFillRatio..1.0) { "圆形最大填充率范围无效" }
        require(insetPx >= 0) { "向内收缩像素不能为负数" }
    }
}

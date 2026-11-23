package com.muc.fluocolorquant.domain.detection.grid

import com.muc.fluocolorquant.utils.math.GridLayoutPolicy
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 手动标注会话的唯一几何实现。只采纳用户在原图上指定的物理孔号，不按屏幕坐标重排。
 * 透视下原图等分并不等于物理等距：必须先在芯片平面均分，再逐孔投影回原图。 */
object ManualGridGeometry {
    const val METHOD = "manual_corner_centers"
    const val VERSION = "1.0"

    /** 把校正平面上的圆投回原图。不是在照片上用统一像素半径画圆。 */
    fun circleOutline(center: GridPoint, radius: Double, inverse: DoubleArray): List<GridPoint> = List(24) { index ->
        val angle = 2 * kotlin.math.PI * index / 24
        RegularGridGeometry.project(inverse, GridPoint(center.x + radius * kotlin.math.cos(angle),
            center.y + radius * kotlin.math.sin(angle)))
    }

    fun circularRadius(grid: PgGridResult, diameterRatio: Double): Double {
        require(diameterRatio in 0.26..0.84) { "内径须经人工确认，范围为孔距的 26% 至 84%" }
        val pitch = RegularGridGeometry.estimatePitch(grid.sites.map { it.rectified }, grid.rows, grid.columns)
        return minOf(pitch.horizontalPx, pitch.verticalPx) * diameterRatio / 2
    }

    /** 人工确定的是孔中心而非前景亮暗。仅为后续 Otsu 裁切，在最多约 64 个规则位置比较
     * 中央小区与四侧间隙的亮度。不足两级灰度的差不投票；没有有效证据或平票沿用载体偏好。
     * 不改变点位、原图强度或荧光通道，也不将人工网格伪装成自动检出的点。 */
    fun samplingPolarity(grid: PgGridResult, luminance: (Int, Int) -> Double?): GridTargetPolarity {
        val inverse = grid.homography.inverse.toDoubleArray()
        val pitchX = (grid.rectifiedWidth - 1.0) / grid.columns
        val pitchY = (grid.rectifiedHeight - 1.0) / grid.rows
        fun estimate(center: GridPoint, radius: Double): Double? {
            val values = listOf(GridPoint(center.x - radius*pitchX, center.y), GridPoint(center.x + radius*pitchX, center.y),
                GridPoint(center.x, center.y - radius*pitchY), GridPoint(center.x, center.y + radius*pitchY))
                .mapNotNull { offset -> RegularGridGeometry.project(inverse, offset).let { luminance(it.x.roundToInt(), it.y.roundToInt()) } }.sorted()
            return if (values.size == 4) (values[1]+values[2])/2 else null
        }
        var votes = 0
        val stride = (grid.sites.size + 63) / 64
        for (index in grid.sites.indices step stride) {
            val center = grid.sites[index].rectified
            val inside = estimate(center, 0.08) ?: continue
            val outside = estimate(center, 0.38) ?: continue
            if (inside - outside > 2.0) votes++ else if (outside - inside > 2.0) votes--
        }
        return when { votes > 0 -> GridTargetPolarity.BRIGHT; votes < 0 -> GridTargetPolarity.DARK; else -> grid.targetPolarity }
    }

    fun build(width: Int, height: Int, config: PgGridLocatorConfig, corners: List<GridPoint>): PgGridResult {
        val rows = config.rows
        val columns = config.columns
        require(GridLayoutPolicy.isValid(rows, columns) && rows >= 2 && columns >= 2 && corners.size == 4) { "四角法需至少两行两列和四个不同角孔" }
        require(corners.all { it.x in 0.0..(width-1).toDouble() && it.y in 0.0..(height-1).toDouble() }) { "标注超出原图" }
        corners.indices.forEach { index ->
            val start = corners[index]
            val middle = corners[(index + 1) % 4]
            val end = corners[(index + 2) % 4]
            val firstX = middle.x - start.x
            val firstY = middle.y - start.y
            val nextX = end.x - middle.x
            val nextY = end.y - middle.y
            val length = hypot(firstX, firstY)
            // 图像 y 轴向下；要求依次顺时针，拒绝重复点、交叉、凹四边形及接近共线的病态输入。
            // 每个相邻角孔间必须至少有一个原始像素的孔距，否则四点无法分辨这个分辨率下的阵列。
            require(length >= if (index % 2 == 0) columns - 1.0 else rows - 1.0)
            require(firstX * nextY - firstY * nextX > 0.02 * length * hypot(nextX, nextY)) { "角孔不是顺时针凸四边形" }
        }
        val rectWidth = config.resolvedRectifiedWidth()
        val rectHeight = config.resolvedRectifiedHeight()
        val pitchX = (rectWidth - 1.0) / columns
        val pitchY = (rectHeight - 1.0) / rows
        // 角孔离矫正画布边缘预留半个孔距；这不是对实体芯片边距的假设，而是给边缘孔
        // 和局部背景环留出采样空间。外推区域超出原图时，下游沿用真实原图裁切 QC。
        val plane = RegularGridGeometry.generate(rows, columns, pitchX * (columns - 1), pitchY * (rows - 1), 0.0)
            .map { GridPoint(it.x + pitchX / 2, it.y + pitchY / 2) }
        val indices = listOf(0, columns - 1, plane.lastIndex, plane.size - columns)
        val anchors = indices.map(plane::get)
        fun transform(from: List<GridPoint>, to: List<GridPoint>): DoubleArray {
            val fit = RobustHomographyFitter.fit(from, to, expectedSiteCount = 4, iterations = 1)
            return (fit as? HomographyFitResult.Success)?.matrix ?: throw IllegalArgumentException("四角不能确定稳定单应")
        }
        val inverse = transform(anchors, corners)
        val forward = transform(corners, anchors)
        val boundary = listOf(GridPoint(0.0, 0.0), GridPoint(rectWidth-1.0, 0.0),
            GridPoint(rectWidth-1.0, rectHeight-1.0), GridPoint(0.0, rectHeight-1.0))
        val denominators = boundary.map { inverse[6] * it.x + inverse[7] * it.y + inverse[8] }
        require(denominators.all { it * denominators.first() > 0.0 && abs(it) > 1e-8 }) { "透视奇点落在采样画布内" }
        val sites = plane.mapIndexed { index, point ->
            val anchor = indices.indexOf(index)
            val original = if (anchor >= 0) corners[anchor] else RegularGridGeometry.project(inverse, point)
            GridLocalizedSite(GridSiteKey(index / columns, index % columns), index, point, original,
                confidence = 0.0, // 结构兼容占位，不为人工/推算孔编造算法置信度，界面不显示此百分比。
                source = if (anchor >= 0) GridPointSource.MANUAL_ANCHOR else GridPointSource.MANUAL_INTERPOLATED,
                flags = emptySet())
        }
        return PgGridResult(rows = rows, columns = columns, rectifiedWidth = rectWidth, rectifiedHeight = rectHeight,
            targetPolarity = config.targetPolarity, chipRegionMethod = METHOD,
            chipCorners = boundary.map { RegularGridGeometry.project(inverse, it) }, homography = GridHomography(forward.toList(), inverse.toList()),
            sites = sites, geometry = GridGeometryDiagnostics(model = METHOD, candidateSupportRatio = null, trusted = false,
                observedRatio = 0.0, geometryRmsePx = null, inlierCount = 0, outlierCount = 0, meanConfidence = 0.0),
            frameQc = emptyList(), locatorName = "Manual four-corner grid", locatorVersion = VERSION,
            manualAnchorCenters = corners.toList()).requireValid()
    }
}

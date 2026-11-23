package com.muc.fluocolorquant.domain.detection.array

import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantConfig
import com.muc.fluocolorquant.domain.detection.photometry.WellReadoutMode
import kotlin.math.roundToInt

/** 标准板保留 YOLO 契约；自定义圆孔仍使用项目独立的 R×C，不能仅凭孔数进入 96 孔板路由。 */
enum class ArrayAnalysisScope {
    CHIP, STANDARD_PLATE, ROUND_ARRAY;

    /** 这里只列能作为后续读数输入的算法，也是系统设置持久化的白名单。 */
    val algorithms: List<ArrayLocatorMode>
        get() = when (this) {
            CHIP -> listOf(ArrayLocatorMode.AUTO, ArrayLocatorMode.PROJECTION, ArrayLocatorMode.KMEANS, ArrayLocatorMode.SPOT_TOPOLOGY,
                ArrayLocatorMode.CIRCLES_GRID)
            STANDARD_PLATE -> listOf(ArrayLocatorMode.AUTO, ArrayLocatorMode.OBJECT_DETECTION,
                ArrayLocatorMode.GEOMETRIC_SHAPE, ArrayLocatorMode.HOUGH_GRID, ArrayLocatorMode.ELLIPSE_GRID, ArrayLocatorMode.CIRCLES_GRID)
            ROUND_ARRAY -> listOf(ArrayLocatorMode.AUTO, ArrayLocatorMode.HOUGH_GRID, ArrayLocatorMode.ELLIPSE_GRID,
                ArrayLocatorMode.CIRCLES_GRID, ArrayLocatorMode.PROJECTION, ArrayLocatorMode.KMEANS)
        }

    /** 对照入口共用同一规则阵列内核；不能将它们设为导致下次无法继续检测的默认值。 */
    val localizationChoices: List<ArrayLocatorMode>
        get() = algorithms + listOf(ArrayLocatorMode.DBSCAN, ArrayLocatorMode.LOG_SPOTS)

    companion object {
        fun from(profile: CarrierProfile): ArrayAnalysisScope = when {
            profile.carrierType == CarrierType.PLATE.code -> STANDARD_PLATE
            profile.siteShape == SiteShape.CIRCLE.code -> ROUND_ARRAY
            else -> CHIP
        }
    }
}

enum class MultiHoleStatistic { INDIVIDUAL, MEAN, MEDIAN }

/** 一次检测读取一份默认值的副本。本图覆盖不回写 DataStore，历史也不重新读取此配置。 */
data class ArrayAnalysisPreferences(
    val locator: ArrayLocatorMode = ArrayLocatorMode.AUTO,
    val readout: WellReadoutMode = WellReadoutMode.FULL_MEDIAN,
    val multiHoleStatistic: MultiHoleStatistic = MultiHoleStatistic.INDIVIDUAL,
    /** 阵列边距是工作平面边长的比例；匹配门限是相邻孔距的比例，不是相机像素。 */
    val gridMargin: Double = 0.085,
    val matchingDistance: Double = 0.42,
    /** 中心区的直径或边长相对于有效孔直径或边长；80% 边长约为 64% 面积。 */
    val centerScale: Double = 0.80
) {
    fun normalized(scope: ArrayAnalysisScope) = copy(
        locator = locator.takeIf { it in scope.algorithms } ?: ArrayLocatorMode.AUTO,
        gridMargin = gridMargin.takeIf { it in 0.05..0.16 } ?: 0.085,
        matchingDistance = matchingDistance.takeIf { it in 0.20..0.45 } ?: 0.42,
        centerScale = centerScale.takeIf { it in 0.50..0.90 }?.let { (it * 100).roundToInt() / 100.0 } ?: 0.80
    )

    fun photometry(): PgQuantConfig = PgQuantConfig(readoutMode = readout, centerScale = centerScale)

    /** 非默认取值的参数会进入处理器身份；取值完全相同的方案才可共用标定。 */
    fun readoutVersion(base: String): String = photometry().versionFor(base)
}

/** 同一张图只使用一个方案；设置页可分别保存不同载体和比色/荧光的下一次默认值。 */
data class ArrayAnalysisKey(val scope: ArrayAnalysisScope, val modality: DetectionModality) {
    init { require(modality != DetectionModality.SPECTRUM) { "光谱不使用阵列像素设置" } }

    companion object {
        val entries = ArrayAnalysisScope.entries.flatMap { scope ->
            listOf(DetectionModality.COLORIMETRIC, DetectionModality.FLUORESCENCE).map { ArrayAnalysisKey(scope, it) }
        }
    }
}

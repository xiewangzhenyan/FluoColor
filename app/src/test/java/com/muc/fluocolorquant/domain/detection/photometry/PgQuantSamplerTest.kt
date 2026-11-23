package com.muc.fluocolorquant.domain.detection.photometry

import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisPreferences
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisScope
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.array.ArrayMultiHoleStatistics
import com.muc.fluocolorquant.domain.detection.array.MultiHoleStatistic
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBitmapCropper
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBounds
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PG-Quant 配置和可靠性语义的纯 JVM 测试。 */
class PgQuantSamplerTest {

    @Test
    fun `圆孔和方孔中心区不共用方框且五区互不重叠`() {
        val bounds = ArrayUnitBounds(0,0,41,41)
        val center = GridPoint(20.0,20.0)
        fun geometry(shape: ArrayUnitShape, mode: WellReadoutMode) = WellReadoutGeometry(PgQuantConfig(readoutMode = mode), center,
            ArrayUnitBitmapCropper.detectedGeometryRegion(0,0,0,shape,bounds),20.0)
        assertEquals(0, geometry(ArrayUnitShape.SQUARE,WellReadoutMode.CENTER_MEAN).region(35,35))
        assertEquals(-1, geometry(ArrayUnitShape.CIRCLE,WellReadoutMode.CENTER_MEAN).region(35,35))
        val five = geometry(ArrayUnitShape.CIRCLE,WellReadoutMode.FIVE_REGION_MEAN)
        assertEquals(listOf(0,1,2,3,4),listOf(20 to 20,20 to 11,20 to 29,11 to 20,29 to 20).map { five.region(it.first,it.second) })
    }

    @Test
    fun `读取坏偏好会限界且中心比例进入曲线处理器身份`() {
        val loaded = ArrayAnalysisPreferences(locator=ArrayLocatorMode.OBJECT_DETECTION,gridMargin=Double.NaN,
            matchingDistance=.76,readout=WellReadoutMode.CENTER_MEAN,centerScale=.899).normalized(ArrayAnalysisScope.ROUND_ARRAY)
        assertEquals(ArrayLocatorMode.AUTO,loaded.locator)
        assertEquals(.085,loaded.gridMargin,.00001)
        assertEquals(.42,loaded.matchingDistance,.00001)
        assertEquals("v1-readout1-CENTER_MEAN-90",loaded.readoutVersion("v1"))
        assertEquals("v1",PgQuantConfig().versionFor("v1"))
        assertTrue(PgQuantConfig(readoutMode=WellReadoutMode.FULL_MEAN).versionFor("v1") != "v1")
    }

    @Test
    fun `两类载体共用对照算法但不允许写成默认值`() {
        for (scope in ArrayAnalysisScope.entries) {
            for (mode in listOf(ArrayLocatorMode.DBSCAN, ArrayLocatorMode.LOG_SPOTS)) {
                assertTrue(mode.isComparisonOnly && mode in scope.localizationChoices)
                assertFalse(mode in scope.algorithms)
                val settings = ArrayAnalysisPreferences(locator = mode, readout = WellReadoutMode.CENTER_MEAN, centerScale = .65)
                    .normalized(scope)
                assertEquals(ArrayLocatorMode.AUTO, settings.locator)
                assertEquals("v1-readout1-CENTER_MEAN-65", settings.readoutVersion("v1"))
            }
        }
    }

    @Test
    fun `少于两个有效孔不输出统计且离群值不影响中位数`() {
        val values = listOf(4.0,6.0,90.0,Double.NaN)
        assertEquals(6.0, requireNotNull(ArrayMultiHoleStatistics.summarize(values,null,MultiHoleStatistic.MEDIAN).value),.01)
        assertEquals(100.0/3,requireNotNull(ArrayMultiHoleStatistics.summarize(values,null,MultiHoleStatistic.MEAN).value),.01)
        assertEquals(null,ArrayMultiHoleStatistics.summarize(listOf(4.0),null,MultiHoleStatistic.MEAN).value)
        assertEquals(null,ArrayMultiHoleStatistics.fromSiteQc("{\"multiHoleStatisticsV1\":{\"mode\":\"MEAN\"}}"))
    }

    @Test
    fun `低SNR不单独判定图像质量不可靠`() {
        val qc = SitePhotometryQc.from(
            flags = setOf(PhotometryFlag.LOW_SNR),
            snr = 1.2,
            snrMinimum = 3.0
        )

        assertFalse(qc.signalDetectable)
        assertTrue(qc.qualityReliable)
    }

    @Test
    fun `轻度饱和保留结果并作为复核标志`() {
        val qc = SitePhotometryQc.from(
            flags = setOf(PhotometryFlag.SATURATED),
            snr = 25.0,
            snrMinimum = 3.0
        )

        assertTrue(qc.signalDetectable)
        assertTrue(qc.qualityReliable)
    }

    @Test
    fun `严重饱和由采样器显式判定为硬失败`() {
        val qc = SitePhotometryQc.from(
            flags = setOf(PhotometryFlag.SATURATED),
            snr = 25.0,
            snrMinimum = 3.0,
            hardFailure = true
        )

        assertTrue(qc.signalDetectable)
        assertFalse(qc.qualityReliable)
    }

    @Test
    fun `默认ROI和背景环比例与Python参考契约一致`() {
        val config = PgQuantConfig()

        assertEquals(0.18, config.roiRadiusPitchRatio, 1e-12)
        assertEquals(0.30, config.annulusInnerPitchRatio, 1e-12)
        assertEquals(0.44, config.annulusOuterPitchRatio, 1e-12)
        assertEquals(3.0, config.snrMinimum, 1e-12)
        assertEquals(0.35, config.contaminationRatioLimit, 1e-12)
        assertEquals(0.25, config.severeSaturationRatioLimit, 1e-12)
        assertEquals(0.20, config.severeBorderClipRatioLimit, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `背景环内径不得小于信号ROI半径`() {
        PgQuantConfig(
            roiRadiusPitchRatio = 0.35,
            annulusInnerPitchRatio = 0.30
        )
    }

    @Test
    fun `中心区域中位数共享同心几何并将比例写入处理器版本`() {
        val bounds = ArrayUnitBounds(0, 0, 41, 41)
        val center = GridPoint(20.0, 20.0)
        val circleMedian = WellReadoutGeometry(
            PgQuantConfig(readoutMode = WellReadoutMode.CENTER_MEDIAN, centerScale = 0.80),
            center,
            ArrayUnitBitmapCropper.detectedGeometryRegion(0, 0, 0, ArrayUnitShape.CIRCLE, bounds),
            20.0
        )
        val squareMedian = WellReadoutGeometry(
            PgQuantConfig(readoutMode = WellReadoutMode.CENTER_MEDIAN, centerScale = 0.80),
            center,
            ArrayUnitBitmapCropper.detectedGeometryRegion(0, 0, 0, ArrayUnitShape.SQUARE, bounds),
            20.0
        )
        assertEquals(0, circleMedian.region(20, 20))
        assertEquals(-1, circleMedian.region(35, 35))
        assertEquals(0, squareMedian.region(35, 35))

        val prefs = ArrayAnalysisPreferences(
            readout = WellReadoutMode.CENTER_MEDIAN,
            centerScale = 0.75
        ).normalized(ArrayAnalysisScope.CHIP)
        assertEquals("v1-readout1-CENTER_MEDIAN-75", prefs.readoutVersion("v1"))
    }

    @Test
    fun `隔离谷区同时排除圆孔边缘与方孔腔壁外扩带`() {
        val squareRegion = ArrayUnitBitmapCropper.detectedGeometryRegion(
            0, 0, 0, ArrayUnitShape.SQUARE, ArrayUnitBounds(10, 10, 20, 20)
        )
        assertTrue(squareRegion.isWithinExclusionZone(22, 15, valleyPx = 3))
        assertFalse(squareRegion.isWithinExclusionZone(23, 15, valleyPx = 3))

        val circleRegion = ArrayUnitBitmapCropper.detectedGeometryRegion(
            0, 0, 0, ArrayUnitShape.CIRCLE, ArrayUnitBounds(10, 10, 31, 31)
        )
        assertTrue(circleRegion.isWithinExclusionZone(32, 20, valleyPx = 3))
        assertFalse(circleRegion.isWithinExclusionZone(35, 20, valleyPx = 3))
    }

    @Test
    fun `人工标记异常孔可切换且取消后恢复原始自动可靠性`() {
        val cleanSite = BaseSitePhotometry(
            siteIndex = 0,
            rowIndex = 0,
            columnIndex = 0,
            rectifiedCenter = GridPoint(20.0, 20.0),
            originalCenter = GridPoint(20.0, 20.0),
            roiMedianRgb = RgbPhotometry(120.0, 120.0, 120.0),
            roiMedianGray = 120.0,
            backgroundMedianRgb = RgbPhotometry(20.0, 20.0, 20.0),
            backgroundMedianGray = 20.0,
            backgroundSigmaRgb = RgbPhotometry(2.0, 2.0, 2.0),
            backgroundSigmaGray = 2.0,
            correctedMedianRgb = RgbPhotometry(120.0, 120.0, 120.0),
            correctedMedianGray = 120.0,
            signalGray = 100.0,
            signalRatio = 5.0,
            correctedSignalGray = 100.0,
            integratedSignalRgb = RgbPhotometry(1000.0, 1000.0, 1000.0),
            integratedSignalGray = 1000.0,
            signalToNoiseRatio = 50.0,
            saturationRatio = 0.0,
            roiContaminationRatio = 0.0,
            hotPixelRatio = 0.0,
            roiClipRatio = 0.0,
            annulusClipRatio = 0.0,
            qc = SitePhotometryQc.from(emptySet(), 50.0, 3.0, hardFailure = false)
        )
        val hardFailSite = cleanSite.copy(
            siteIndex = 1,
            columnIndex = 1,
            qc = SitePhotometryQc.from(setOf(PhotometryFlag.SATURATED), 50.0, 3.0, hardFailure = true),
            autoQualityReliable = false
        )
        val quant = PgQuantResult(
            rows = 1,
            columns = 2,
            pitchPx = 50.0,
            roiRadiusPx = 9.0,
            annulusInnerPx = 15.0,
            annulusOuterPx = 22.0,
            illuminationModel = "dark_field_unity",
            illuminationUniformity = 1.0,
            config = PgQuantConfig(),
            sites = listOf(cleanSite, hardFailSite)
        )

        val flagged = quant.withManualBadSites(setOf(0))
        assertFalse(flagged.sites[0].qc.qualityReliable)
        assertTrue(flagged.sites[0].manuallyFlaggedBad)
        assertFalse(flagged.sites[1].qc.qualityReliable)

        val restored = flagged.withManualBadSites(emptySet())
        assertTrue(restored.sites[0].qc.qualityReliable)
        assertFalse(restored.sites[0].manuallyFlaggedBad)
        // 原本因严重饱和不可靠的位点在取消人工标记后仍必须保持不可靠
        assertFalse(restored.sites[1].qc.qualityReliable)
    }
}

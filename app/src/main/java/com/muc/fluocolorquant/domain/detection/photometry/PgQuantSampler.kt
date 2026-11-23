package com.muc.fluocolorquant.domain.detection.photometry

import android.graphics.Bitmap
import android.graphics.Color
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.grid.RegularGridGeometry
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitFallbackReason
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitRegion
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitRegionSource
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitSegmentationResult
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.apache.commons.math3.linear.MatrixUtils
import org.apache.commons.math3.linear.SingularValueDecomposition

/**
 * PG-Quant Android 原图采样器。
 *
 * ROI 的几何形状在矫正坐标中定义，使不同透视和阵列规格使用同一 pitch 比例；真正
 * 读取像素时逐点通过逆单应映射回原始 Bitmap。这样既保留规则晶格几何，又避免把
 * 透视插值、CLAHE、锐化或伪彩显示图当作科学定量输入。
 */
object PgQuantSampler {

    fun sample(
        bitmap: Bitmap,
        grid: PgGridResult,
        config: PgQuantConfig = PgQuantConfig(),
        unitSegmentation: ArrayUnitSegmentationResult? = null
    ): PgQuantResult {
        grid.requireValid()
        require(bitmap.width > 0 && bitmap.height > 0) { "原始定量图尺寸无效" }
        unitSegmentation?.let { segmentation ->
            segmentation.requireValid()
            require(segmentation.rows == grid.rows && segmentation.columns == grid.columns) {
                "单元分割规格必须与定位结果一致"
            }
            require(
                segmentation.imageWidth == grid.rectifiedWidth &&
                    segmentation.imageHeight == grid.rectifiedHeight
            ) { "单元分割坐标系必须与定位矫正图一致" }
        }

        val rectifiedPoints = grid.sites.map { it.rectified }
        val pitch = RegularGridGeometry.estimatePitch(
            points = rectifiedPoints,
            rows = grid.rows,
            columns = grid.columns
        )
        val representativePitch = listOf(pitch.horizontalPx, pitch.verticalPx)
            .filter { it > 2.0 }
            .minOrNull()
            ?: minOf(
                grid.rectifiedWidth.toDouble() / maxOf(grid.columns + 1, 2),
                grid.rectifiedHeight.toDouble() / maxOf(grid.rows + 1, 2)
            )
        require(representativePitch > 2.0) { "无法从定位结果估算有效 pitch" }

        val roiRadius = maxOf(2.0, representativePitch * config.roiRadiusPitchRatio)
        val annulusInner = maxOf(roiRadius + 1.0, representativePitch * config.annulusInnerPitchRatio)
        val annulusOuter = maxOf(annulusInner + 1.5, representativePitch * config.annulusOuterPitchRatio)
        val image = OriginalBitmapPixels(bitmap)
        val inverse = grid.homography.inverse.toDoubleArray()

        // 第一遍只提取原始 ROI 和背景环统计；全阵列背景准备好后才能拟合平场。
        val rawUnits = grid.sites.mapIndexed { index, site ->
            val neighborRegions = if (unitSegmentation == null) {
                emptyList()
            } else {
                collectNeighborRegions(
                    segmentation = unitSegmentation,
                    rowIndex = site.key.rowIndex,
                    columnIndex = site.key.columnIndex
                )
            }
            extractRawUnit(
                image = image,
                rectifiedCenter = site.rectified,
                inverseHomography = inverse,
                rectifiedWidth = grid.rectifiedWidth,
                rectifiedHeight = grid.rectifiedHeight,
                representativePitch = representativePitch,
                roiRadius = roiRadius,
                annulusInner = annulusInner,
                annulusOuter = annulusOuter,
                config = config,
                unitRegion = unitSegmentation?.regions?.get(index),
                neighborRegions = neighborRegions
            )
        }
        val valid = rawUnits.map { !it.outOfBounds }
        val fieldValues = rawUnits.map { unit ->
            doubleArrayOf(
                unit.backgroundMedianRgb.red,
                unit.backgroundMedianRgb.green,
                unit.backgroundMedianRgb.blue,
                unit.backgroundMedianGray
            )
        }
        val illumination = fitIlluminationField(
            centers = rectifiedPoints,
            values = fieldValues,
            valid = valid,
            width = grid.rectifiedWidth.toDouble(),
            height = grid.rectifiedHeight.toDouble(),
            requireBrightfieldBaseline = unitSegmentation != null
        )
        val fieldMean = channelMeans(illumination.predictions, valid)
        val grayPredictions = illumination.predictions.map { it[GRAY_CHANNEL_INDEX] }
        val illuminationUniformity = calculateUniformity(grayPredictions, valid)

        val backgroundResiduals = rawUnits.indices.map { index ->
            rawUnits[index].backgroundMedianGray - grayPredictions[index]
        }
        val validResiduals = backgroundResiduals.filterIndexed { index, _ -> valid[index] }
        val residualMedian = if (validResiduals.isEmpty()) 0.0 else median(validResiduals)
        val residualSigma = if (validResiduals.isEmpty()) {
            1.0
        } else {
            maxOf(1.0, ROBUST_SCALE * median(validResiduals.map { abs(it - residualMedian) }))
        }

        // 第二遍应用乘性平场并生成基础 QC。低 SNR 与硬质量失败在此明确分离。
        val sites = grid.sites.mapIndexed { index, localizedSite ->
            val raw = rawUnits[index]
            val region = unitSegmentation?.regions?.get(index)
            val prediction = illumination.predictions[index]
            // 暗场荧光下孔间近零像素是传感器黑电平/漏光（加性偏置），不能作为入射光增益做除法放大。
            fun channelScale(channel: Int): Double {
                if (illumination.model == DARK_FIELD_UNITY_MODEL ||
                    fieldMean[channel] <= EPSILON ||
                    prediction[channel] <= EPSILON
                ) {
                    return 1.0
                }
                return fieldMean[channel] / prediction[channel]
            }
            val redScale = channelScale(RED_CHANNEL_INDEX)
            val greenScale = channelScale(GREEN_CHANNEL_INDEX)
            val blueScale = channelScale(BLUE_CHANNEL_INDEX)
            val grayScale = channelScale(GRAY_CHANNEL_INDEX)
            val correctedRgb = RgbPhotometry(
                red = raw.roiMedianRgb.red * redScale,
                green = raw.roiMedianRgb.green * greenScale,
                blue = raw.roiMedianRgb.blue * blueScale
            )
            val signalGray = raw.roiMedianGray - raw.backgroundMedianGray
            val readout = raw.readout?.let { selection ->
                WellReadoutEvidence(
                    mode = config.readoutMode,
                    raw = selection.value,
                    correctedRgb = RgbPhotometry(
                        selection.value.rgb.red * redScale,
                        selection.value.rgb.green * greenScale,
                        selection.value.rgb.blue * blueScale
                    ),
                    correctedGray = selection.value.gray * grayScale,
                    subregions = selection.subregions
                )
            }
            val missingReadout = config.readoutMode != WellReadoutMode.FULL_MEDIAN && readout == null
            val readoutSignalGray = (readout?.raw?.gray ?: raw.roiMedianGray) - raw.backgroundMedianGray
            val snr = (abs(readoutSignalGray) / (raw.backgroundSigma + EPSILON)).coerceAtMost(MAXIMUM_SNR)
            val fallbackReason = region?.fallbackReason
            val segmentationAnomaly = fallbackReason in setOf(
                ArrayUnitFallbackReason.WINDOW_BOUNDARY_TOUCH,
                ArrayUnitFallbackReason.ABNORMAL_SIZE,
                ArrayUnitFallbackReason.EXCESSIVE_CENTER_SHIFT,
                ArrayUnitFallbackReason.IRREGULAR_SHAPE
            )
            val localBgSkewAnomaly = unitSegmentation != null &&
                raw.backgroundMeanMedianSkew > LOCAL_BG_SKEW_SIGMA_LIMIT &&
                abs(raw.backgroundMeanGray - raw.backgroundMedianGray) > MINIMUM_LOCAL_BG_SKEW_DELTA
            val flags = buildSet {
                if (raw.saturationRatio > config.saturationRatioLimit) add(PhotometryFlag.SATURATED)
                if ((readout?.raw?.gray ?: raw.roiMedianGray) < config.underExposedMedianLevel) add(PhotometryFlag.UNDER_EXPOSED)
                if (snr < config.snrMinimum) add(PhotometryFlag.LOW_SNR)
                if (raw.outOfBounds) add(PhotometryFlag.ROI_OUT_OF_BOUNDS)
                if (missingReadout) add(PhotometryFlag.READOUT_UNAVAILABLE)
                if (raw.contaminationRatio > config.contaminationRatioLimit) add(PhotometryFlag.NON_UNIFORM)
                if (segmentationAnomaly) add(PhotometryFlag.SEGMENTATION_ANOMALY)
                if (unitSegmentation != null && raw.annulusPixelCount < MINIMUM_RELIABLE_ANNULUS_PIXELS) {
                    add(PhotometryFlag.BACKGROUND_INSUFFICIENT)
                }
                if (
                    localBgSkewAnomaly ||
                    abs(backgroundResiduals[index] - residualMedian) >
                    config.backgroundAnomalyRobustZ * residualSigma
                ) {
                    add(PhotometryFlag.BACKGROUND_ANOMALY)
                }
            }
            // 分割回退与局部背景像素偏少记入 flags 供界面检视和人工标 Bad 排除，不将中位框兜底一票否决为无浓度。
            val qc = SitePhotometryQc.from(
                flags = flags,
                snr = snr,
                snrMinimum = config.snrMinimum,
                hardFailure = missingReadout ||
                    raw.saturationRatio >= config.severeSaturationRatioLimit ||
                    raw.roiClipRatio >= config.severeBorderClipRatioLimit ||
                    raw.annulusClipRatio >= config.severeBorderClipRatioLimit ||
                    raw.roiPixelCount == 0 ||
                    raw.annulusPixelCount == 0
            )
            BaseSitePhotometry(
                siteIndex = localizedSite.siteIndex,
                rowIndex = localizedSite.key.rowIndex,
                columnIndex = localizedSite.key.columnIndex,
                rectifiedCenter = localizedSite.rectified,
                originalCenter = localizedSite.original,
                roiMedianRgb = raw.roiMedianRgb,
                roiMedianGray = raw.roiMedianGray,
                backgroundMedianRgb = raw.backgroundMedianRgb,
                backgroundMedianGray = raw.backgroundMedianGray,
                backgroundSigmaRgb = raw.backgroundSigmaRgb,
                backgroundSigmaGray = raw.backgroundSigma,
                correctedMedianRgb = correctedRgb,
                correctedMedianGray = raw.roiMedianGray * grayScale,
                signalGray = signalGray,
                signalRatio = signalGray / maxOf(raw.backgroundMedianGray, EPSILON),
                correctedSignalGray = signalGray * grayScale,
                integratedSignalRgb = raw.integratedSignalRgb,
                integratedSignalGray = raw.integratedSignalGray,
                signalToNoiseRatio = snr,
                saturationRatio = raw.saturationRatio,
                roiContaminationRatio = raw.contaminationRatio,
                hotPixelRatio = raw.hotPixelRatio,
                roiClipRatio = raw.roiClipRatio,
                annulusClipRatio = raw.annulusClipRatio,
                qc = qc,
                readout = readout,
                roiMeanGray = raw.roiMeanGray,
                roiSigmaGray = raw.roiSigmaGray,
                backgroundMeanGray = raw.backgroundMeanGray,
                pctAboveBgPlus2Sd = raw.pctAboveBgPlus2Sd,
                backgroundMeanMedianSkew = raw.backgroundMeanMedianSkew,
                foregroundMeanMedianSkew = raw.foregroundMeanMedianSkew,
                roiPixelCount = raw.roiPixelCount,
                annulusPixelCount = raw.annulusPixelCount,
                segmentationSource = region?.source?.name,
                segmentationFallbackReason = region?.fallbackReason?.name,
                autoQualityReliable = qc.qualityReliable
            )
        }

        return PgQuantResult(
            processorVersion = config.versionFor(if (unitSegmentation == null) {
                PG_QUANT_LEGACY_PROCESSOR_VERSION
            } else {
                PG_QUANT_PROCESSOR_VERSION
            }),
            rows = grid.rows,
            columns = grid.columns,
            pitchPx = representativePitch,
            roiRadiusPx = roiRadius,
            annulusInnerPx = annulusInner,
            annulusOuterPx = annulusOuter,
            illuminationModel = illumination.model,
            illuminationUniformity = illuminationUniformity,
            config = config,
            sites = sites,
            unitSegmentation = unitSegmentation
        ).requireValid()
    }

    /** 收集八邻域位点的真实分割区域，防止局部背景环采入相邻强发光/强显色位点及其边缘晕圈。 */
    private fun collectNeighborRegions(
        segmentation: ArrayUnitSegmentationResult,
        rowIndex: Int,
        columnIndex: Int
    ): List<ArrayUnitRegion> {
        val neighbors = ArrayList<ArrayUnitRegion>(8)
        for (r in (rowIndex - 1)..(rowIndex + 1)) {
            if (r !in 0 until segmentation.rows) continue
            for (c in (columnIndex - 1)..(columnIndex + 1)) {
                if (c !in 0 until segmentation.columns) continue
                if (r == rowIndex && c == columnIndex) continue
                neighbors += segmentation.regions[r * segmentation.columns + c]
            }
        }
        return neighbors
    }

    /**
     * 在矫正坐标中枚举前景 ROI/互斥背景环，并回投影到原图读取最近邻像素。
     * 最近邻不会制造原图中不存在的新强度；位点详情仍可根据原图坐标回看实际像素。
     */
    private fun extractRawUnit(
        image: OriginalBitmapPixels,
        rectifiedCenter: GridPoint,
        inverseHomography: DoubleArray,
        rectifiedWidth: Int,
        rectifiedHeight: Int,
        representativePitch: Double,
        roiRadius: Double,
        annulusInner: Double,
        annulusOuter: Double,
        config: PgQuantConfig,
        unitRegion: ArrayUnitRegion?,
        neighborRegions: List<ArrayUnitRegion>
    ): RawUnit {
        // 当斑点真实分割成功且中心在 0.18 pitch 公差内时，局部采样与背景环跟随斑点真实中心，避免偏心压边。
        val samplingCenter = if (unitRegion?.source == ArrayUnitRegionSource.SEGMENTED) {
            val segCenter = GridPoint(unitRegion.bounds.centerX, unitRegion.bounds.centerY)
            if (hypot(segCenter.x - rectifiedCenter.x, segCenter.y - rectifiedCenter.y) <=
                representativePitch * MAXIMUM_SAMPLING_CENTER_SHIFT_RATIO
            ) {
                segCenter
            } else {
                rectifiedCenter
            }
        } else {
            rectifiedCenter
        }
        val valleyPx = maxOf(2, (representativePitch * config.valleyPitchRatio).roundToInt())
        val effectiveAnnulusOuter = if (unitRegion == null) {
            annulusOuter
        } else {
            val unitHalfSpan = max(unitRegion.bounds.width, unitRegion.bounds.height) / 2.0
            maxOf(
                annulusOuter,
                min(
                    0.56 * representativePitch,
                    unitHalfSpan + valleyPx + maxOf(3.0, 0.12 * representativePitch)
                )
            )
        }
        val regionReach = unitRegion?.bounds?.let { bounds ->
            max(
                max(abs(bounds.left - samplingCenter.x), abs(bounds.right - samplingCenter.x)),
                max(abs(bounds.top - samplingCenter.y), abs(bounds.bottom - samplingCenter.y))
            )
        } ?: 0.0
        val reach = ceil(max(effectiveAnnulusOuter, regionReach)).toInt() + 1
        val centerFloorX = floorToInt(samplingCenter.x)
        val centerFloorY = floorToInt(samplingCenter.y)
        val roiPixels = mutableListOf<PixelSample>()
        val annulusPixels = mutableListOf<PixelSample>()
        val readoutGeometry = WellReadoutGeometry(config, samplingCenter, unitRegion, roiRadius)
        val selectedPixels = if (config.readoutMode == WellReadoutMode.FULL_MEDIAN) emptyList()
            else List(if (config.readoutMode == WellReadoutMode.FIVE_REGION_MEAN) 5 else 1) { mutableListOf<PixelSample>() }
        val expectedWindowCounts = IntArray(selectedPixels.size)
        var roiIdealCount = 0
        var annulusIdealCount = 0

        for (y in centerFloorY - reach..centerFloorY + reach) {
            for (x in centerFloorX - reach..centerFloorX + reach) {
                val distance = hypot(x - samplingCenter.x, y - samplingCenter.y)
                // 有单元分割结果时，信号只读取真实单元本体；旧调用仍保持 v1 圆形 ROI。
                val inRoi = unitRegion?.contains(x, y) ?: (distance <= roiRadius)
                // 生产路径背景环严格遵守 GenePix 三剔除规则：排除本孔前景 + 本孔外扩隔离谷区 + 八邻域前景与隔离谷区。
                val inAnnulus = if (unitRegion == null) {
                    !inRoi && distance > annulusInner && distance <= annulusOuter
                } else {
                    !inRoi &&
                        distance > annulusInner &&
                        distance <= effectiveAnnulusOuter &&
                        !unitRegion.isWithinExclusionZone(x, y, valleyPx) &&
                        neighborRegions.none { neighbor -> neighbor.isWithinExclusionZone(x, y, valleyPx) }
                }
                if (!inRoi && !inAnnulus) continue
                if (inRoi) roiIdealCount++ else annulusIdealCount++
                val readoutRegion = if (inRoi && selectedPixels.isNotEmpty()) readoutGeometry.region(x, y) else -1
                if (readoutRegion >= 0) expectedWindowCounts[readoutRegion]++

                // Python 参考实现在矫正图上量化，超出矫正图边界的样本会被裁掉。Android
                // 虽然回到原图读取像素，也必须先执行同样的矫正域边界判断；否则靠近芯片
                // 边缘的背景环会错误采到芯片外部画面，并且不会产生 ROI_OUT_OF_BOUNDS。
                if (x !in 0 until rectifiedWidth || y !in 0 until rectifiedHeight) continue

                val original = try {
                    RegularGridGeometry.project(
                        inverseHomography,
                        GridPoint(x.toDouble(), y.toDouble())
                    )
                } catch (_: IllegalArgumentException) {
                    continue
                }
                val pixel = image.sampleNearest(original.x, original.y) ?: continue
                if (inRoi) roiPixels += pixel else annulusPixels += pixel
                if (readoutRegion >= 0) selectedPixels[readoutRegion].add(pixel)
            }
        }

        val roiClip = 1.0 - roiPixels.size.toDouble() / maxOf(roiIdealCount, 1)
        val annulusClip = 1.0 - annulusPixels.size.toDouble() / maxOf(annulusIdealCount, 1)
        val outOfBounds = roiClip > config.borderClipRatioLimit ||
            annulusClip > config.borderClipRatioLimit ||
            roiPixels.isEmpty() || annulusPixels.isEmpty()
        if (roiPixels.isEmpty() || annulusPixels.isEmpty()) {
            return RawUnit.empty(roiClip, annulusClip)
        }

        val roiGray = roiPixels.map(PixelSample::gray)
        val annulusGray = annulusPixels.map(PixelSample::gray)
        val roiGrayMedian = median(roiGray)
        val roiGrayMean = roiGray.average()
        val backgroundGrayMedian = median(annulusGray)
        val backgroundGrayMean = annulusGray.average()
        val madSigma = ROBUST_SCALE * median(annulusGray.map { abs(it - backgroundGrayMedian) })
        // 生产路径引入截尾标准差与 8-bit 量化噪声下界（0.5 LSB），防止暗场图 MAD=0 产生 9999.0 伪高信噪比。
        val useSafeNoiseFloor = unitRegion != null
        val backgroundSigma = if (useSafeNoiseFloor) {
            effectiveNoiseSigma(annulusGray, backgroundGrayMedian, madSigma)
        } else {
            madSigma
        }
        val backgroundRgb = medianRgb(annulusPixels)
        val backgroundSigmaRgb = RgbPhotometry(
            red = channelNoiseSigma(annulusPixels.map { it.red.toDouble() }, backgroundRgb.red, useSafeNoiseFloor),
            green = channelNoiseSigma(annulusPixels.map { it.green.toDouble() }, backgroundRgb.green, useSafeNoiseFloor),
            blue = channelNoiseSigma(annulusPixels.map { it.blue.toDouble() }, backgroundRgb.blue, useSafeNoiseFloor)
        )
        val contaminationTolerance = maxOf(4.0 * backgroundSigma, MINIMUM_CONTAMINATION_DELTA)
        val contamination = roiGray.count { abs(it - roiGrayMedian) > contaminationTolerance }
            .toDouble() / roiGray.size
        val roiMadSigma = ROBUST_SCALE * median(roiGray.map { abs(it - roiGrayMedian) })
        val roiSigma = if (useSafeNoiseFloor) {
            effectiveNoiseSigma(roiGray, roiGrayMedian, roiMadSigma)
        } else {
            roiMadSigma
        }
        val hotPixelThreshold = roiGrayMedian + maxOf(HOT_PIXEL_SIGMA_MULTIPLIER * roiSigma, MINIMUM_HOT_PIXEL_DELTA)
        val hotPixelRatio = roiGray.count { it > hotPixelThreshold }.toDouble() / roiGray.size
        val twoSigmaThreshold = 2.0 * maxOf(backgroundSigma, QUANTIZATION_NOISE_FLOOR_8BIT)
        val pctAboveBgPlus2Sd = roiGray.count { abs(it - backgroundGrayMedian) > twoSigmaThreshold }
            .toDouble() / roiGray.size
        val backgroundSkew = abs(backgroundGrayMean - backgroundGrayMedian) / maxOf(backgroundSigma, 1.0)
        val foregroundSkew = abs(roiGrayMean - roiGrayMedian) / maxOf(roiSigma, 1.0)
        val roiRgb = medianRgb(roiPixels)
        val minimumCount = if (config.readoutMode == WellReadoutMode.CENTER_PIXEL) 1 else 4
        val selection = selectedPixels.takeIf { groups -> groups.isNotEmpty() && groups.indices.all { index ->
            val count = groups[index].size
            count >= minimumCount && count >= expectedWindowCounts[index] * (1.0 - config.severeBorderClipRatioLimit)
        } }?.let { groups ->
            val windows = groups.map { pixels ->
                if (config.readoutMode == WellReadoutMode.CENTER_MEDIAN) {
                    WellRegionReadout(
                        pixelCount = pixels.size,
                        rgb = medianRgb(pixels),
                        gray = median(pixels.map(PixelSample::gray))
                    )
                } else {
                    WellRegionReadout(
                        pixelCount = pixels.size,
                        rgb = RgbPhotometry(
                            pixels.map { it.red }.average(),
                            pixels.map { it.green }.average(),
                            pixels.map { it.blue }.average()
                        ),
                        gray = pixels.map(PixelSample::gray).average()
                    )
                }
            }
            // 每个窗口先聚合再等权；圆形掩膜或透视裁切导致的少量像素数差异不会给某一区域加权。
            ReadoutSelection(
                value = WellRegionReadout(
                    windows.sumOf(WellRegionReadout::pixelCount),
                    RgbPhotometry(
                        windows.map { it.rgb.red }.average(),
                        windows.map { it.rgb.green }.average(),
                        windows.map { it.rgb.blue }.average()
                    ),
                    windows.map(WellRegionReadout::gray).average()
                ),
                subregions = windows.takeIf { it.size == 5 }.orEmpty()
            )
        }
        // 积分仍是所用 ROI 的像素扣背景之和，不能用“五区均值×孔面积”伪造未采区域。
        val integratedPixels = if (selection == null) roiPixels else selectedPixels.flatten()
        // 中心窗口饱和可能只占全孔很小比例。按实际用于计算的区域判定，不能被未采边缘稀释。
        val saturation = integratedPixels.count { pixel ->
            pixel.red >= config.saturationLevel || pixel.green >= config.saturationLevel || pixel.blue >= config.saturationLevel
        }.toDouble() / integratedPixels.size

        return RawUnit(
            roiMedianRgb = roiRgb,
            roiMedianGray = roiGrayMedian,
            roiMeanGray = roiGrayMean,
            roiSigmaGray = roiSigma,
            backgroundMedianRgb = backgroundRgb,
            backgroundMedianGray = backgroundGrayMedian,
            backgroundMeanGray = backgroundGrayMean,
            backgroundSigmaRgb = backgroundSigmaRgb,
            backgroundSigma = backgroundSigma,
            contaminationRatio = contamination,
            saturationRatio = saturation,
            pctAboveBgPlus2Sd = pctAboveBgPlus2Sd,
            backgroundMeanMedianSkew = backgroundSkew,
            foregroundMeanMedianSkew = foregroundSkew,
            integratedSignalRgb = RgbPhotometry(
                red = integratedPixels.sumOf { it.red - backgroundRgb.red },
                green = integratedPixels.sumOf { it.green - backgroundRgb.green },
                blue = integratedPixels.sumOf { it.blue - backgroundRgb.blue }
            ),
            integratedSignalGray = integratedPixels.sumOf { it.gray - backgroundGrayMedian },
            hotPixelRatio = hotPixelRatio,
            roiClipRatio = roiClip,
            annulusClipRatio = annulusClip,
            outOfBounds = outOfBounds,
            roiPixelCount = roiPixels.size,
            annulusPixelCount = annulusPixels.size,
            readout = selection
        )
    }

    /**
     * 用全阵列背景环中位数拟合二阶乘性照明场。
     * 暗场图像（背景中位灰度 < 45）禁止拟合除法平场；有效位点少于 12、矩阵退化或非正外推时回退常量场。
     */
    private fun fitIlluminationField(
        centers: List<GridPoint>,
        values: List<DoubleArray>,
        valid: List<Boolean>,
        width: Double,
        height: Double,
        requireBrightfieldBaseline: Boolean = false
    ): IlluminationField {
        val constant = constantField(values, valid)
        val validIndices = valid.indices.filter(valid::get)
        if (requireBrightfieldBaseline) {
            val medianBgGray = if (validIndices.isEmpty()) {
                0.0
            } else {
                median(validIndices.map { values[it][GRAY_CHANNEL_INDEX] })
            }
            if (medianBgGray < MINIMUM_BRIGHTFIELD_BACKGROUND_GRAY) {
                return IlluminationField(constant, DARK_FIELD_UNITY_MODEL)
            }
        }
        if (validIndices.size < MINIMUM_POLYNOMIAL_UNIT_COUNT) {
            return IlluminationField(constant, "constant_fallback")
        }

        val designAll = centers.map { center -> designRow(center, width, height) }
        val predictions = Array(centers.size) { DoubleArray(CHANNEL_COUNT) }
        for (channel in 0 until CHANNEL_COUNT) {
            val firstCoefficients = solveLeastSquares(
                designRows = validIndices.map(designAll::get),
                targets = validIndices.map { values[it][channel] }
            ) ?: return IlluminationField(constant, "constant_fallback")

            val firstResiduals = validIndices.map { index ->
                values[index][channel] - dot(designAll[index], firstCoefficients)
            }
            val residualMedian = median(firstResiduals)
            val residualSigma = ROBUST_SCALE * median(firstResiduals.map { abs(it - residualMedian) }) + EPSILON
            val keptIndices = validIndices.filterIndexed { localIndex, _ ->
                abs(firstResiduals[localIndex]) <= 3.0 * residualSigma
            }
            val minimumKept = maxOf(
                MINIMUM_POLYNOMIAL_UNIT_COUNT,
                (validIndices.size * MINIMUM_RETAINED_RATIO).toInt()
            )
            val coefficients = if (keptIndices.size >= minimumKept) {
                solveLeastSquares(
                    designRows = keptIndices.map(designAll::get),
                    targets = keptIndices.map { values[it][channel] }
                ) ?: firstCoefficients
            } else {
                firstCoefficients
            }
            predictions.indices.forEach { index ->
                predictions[index][channel] = dot(designAll[index], coefficients)
            }
        }

        val validPredictions = validIndices.flatMap { predictions[it].toList() }
        if (validPredictions.any { !it.isFinite() || it <= 0.0 }) {
            return IlluminationField(constant, "constant_fallback")
        }
        for (channel in 0 until CHANNEL_COUNT) {
            val channelMedian = median(validIndices.map { predictions[it][channel] })
            if (validIndices.any { predictions[it][channel] <= 0.3 * maxOf(channelMedian, EPSILON) }) {
                return IlluminationField(constant, "constant_fallback")
            }
        }
        return IlluminationField(predictions, "poly2")
    }

    private fun solveLeastSquares(
        designRows: List<DoubleArray>,
        targets: List<Double>
    ): DoubleArray? {
        if (designRows.size < POLYNOMIAL_COEFFICIENT_COUNT || designRows.size != targets.size) return null
        return try {
            val matrix = MatrixUtils.createRealMatrix(designRows.toTypedArray())
            val decomposition = SingularValueDecomposition(matrix)
            if (decomposition.rank < POLYNOMIAL_COEFFICIENT_COUNT) return null
            decomposition.solver
                .solve(MatrixUtils.createRealVector(targets.toDoubleArray()))
                .toArray()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun designRow(point: GridPoint, width: Double, height: Double): DoubleArray {
        val u = point.x / width * 2.0 - 1.0
        val v = point.y / height * 2.0 - 1.0
        return doubleArrayOf(1.0, u, v, u * u, u * v, v * v)
    }

    private fun constantField(values: List<DoubleArray>, valid: List<Boolean>): Array<DoubleArray> {
        val validValues = values.filterIndexed { index, _ -> valid[index] }.ifEmpty { values }
        val medians = DoubleArray(CHANNEL_COUNT) { channel ->
            median(validValues.map { it[channel] })
        }
        return Array(values.size) { medians.copyOf() }
    }

    private fun channelMeans(predictions: Array<DoubleArray>, valid: List<Boolean>): DoubleArray {
        val validPredictions = predictions.filterIndexed { index, _ -> valid[index] }.ifEmpty {
            predictions.toList()
        }
        return DoubleArray(CHANNEL_COUNT) { channel ->
            validPredictions.map { it[channel] }.average()
        }
    }

    private fun calculateUniformity(predictions: List<Double>, valid: List<Boolean>): Double {
        val values = predictions.filterIndexed { index, _ -> valid[index] }
        if (values.isEmpty()) return 1.0
        val mean = values.average()
        return (1.0 - (values.max() - values.min()) / maxOf(mean, EPSILON)).coerceIn(0.0, 1.0)
    }

    private fun medianRgb(pixels: List<PixelSample>): RgbPhotometry {
        return RgbPhotometry(
            red = median(pixels.map { it.red.toDouble() }),
            green = median(pixels.map { it.green.toDouble() }),
            blue = median(pixels.map { it.blue.toDouble() })
        )
    }

    /** 以给定中位数计算 MAD 稳健噪声，避免重复排序中位数。 */
    private fun robustSigma(values: List<Double>, medianValue: Double): Double {
        return ROBUST_SCALE * median(values.map { abs(it - medianValue) })
    }

    private fun channelNoiseSigma(values: List<Double>, medianValue: Double, safeFloor: Boolean): Double {
        val mad = robustSigma(values, medianValue)
        return if (safeFloor) effectiveNoiseSigma(values, medianValue, mad) else mad
    }

    /**
     * 结合稳健 MAD、抗离群截尾样本标准差与 8-bit 量化噪声下界（0.5 LSB）。
     *
     * 手机拍摄的 8-bit 暗场图像常有超过半数背景像素落在同一整数灰阶（MAD = 0），
     * 此时保留截尾样本 SD 与 0.5 LSB 量化底噪，既能抵抗偶发亮污点，又避免 SNR 除零失真。
     */
    private fun effectiveNoiseSigma(
        values: List<Double>,
        medianValue: Double,
        madSigma: Double
    ): Double {
        if (values.size < 2) return maxOf(madSigma, QUANTIZATION_NOISE_FLOOR_8BIT)
        val trimGate = maxOf(3.5 * maxOf(madSigma, 1.0), 6.0)
        val inliers = values.filter { abs(it - medianValue) <= trimGate }
            .ifEmpty { values }
        val trimmedSd = if (inliers.size < 2) {
            0.0
        } else {
            val mean = inliers.average()
            val variance = inliers.sumOf { (it - mean) * (it - mean) } / (inliers.size - 1)
            sqrt(variance.coerceAtLeast(0.0))
        }
        return maxOf(madSigma, trimmedSd, QUANTIZATION_NOISE_FLOOR_8BIT)
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "中位数输入不能为空" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun dot(first: DoubleArray, second: DoubleArray): Double {
        require(first.size == second.size) { "向量长度不一致" }
        return first.indices.sumOf { index -> first[index] * second[index] }
    }

    private fun floorToInt(value: Double): Int = kotlin.math.floor(value).toInt()

    /** 一次性读取 Bitmap，避免每个 ROI 重复 JNI/边界调用 getPixel。 */
    private class OriginalBitmapPixels(bitmap: Bitmap) {
        private val width: Int = bitmap.width
        private val height: Int = bitmap.height
        private val pixels: IntArray = IntArray(width * height).also { buffer ->
            bitmap.getPixels(buffer, 0, width, 0, 0, width, height)
        }

        fun sampleNearest(x: Double, y: Double): PixelSample? {
            val ix = x.roundToInt()
            val iy = y.roundToInt()
            if (ix !in 0 until width || iy !in 0 until height) return null
            val color = pixels[iy * width + ix]
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            return PixelSample(
                red = red,
                green = green,
                blue = blue,
                gray = 0.299 * red + 0.587 * green + 0.114 * blue
            )
        }
    }

    private data class PixelSample(
        val red: Int,
        val green: Int,
        val blue: Int,
        val gray: Double
    )

    private data class ReadoutSelection(val value: WellRegionReadout, val subregions: List<WellRegionReadout>)

    private data class RawUnit(
        val roiMedianRgb: RgbPhotometry,
        val roiMedianGray: Double,
        val roiMeanGray: Double,
        val roiSigmaGray: Double,
        val backgroundMedianRgb: RgbPhotometry,
        val backgroundMedianGray: Double,
        val backgroundMeanGray: Double,
        val backgroundSigmaRgb: RgbPhotometry,
        val backgroundSigma: Double,
        val contaminationRatio: Double,
        val saturationRatio: Double,
        val pctAboveBgPlus2Sd: Double,
        val backgroundMeanMedianSkew: Double,
        val foregroundMeanMedianSkew: Double,
        val integratedSignalRgb: RgbPhotometry,
        val integratedSignalGray: Double,
        val hotPixelRatio: Double,
        val roiClipRatio: Double,
        val annulusClipRatio: Double,
        val outOfBounds: Boolean,
        val roiPixelCount: Int,
        val annulusPixelCount: Int,
        val readout: ReadoutSelection? = null
    ) {
        companion object {
            fun empty(roiClipRatio: Double, annulusClipRatio: Double): RawUnit {
                val zeroRgb = RgbPhotometry(0.0, 0.0, 0.0)
                return RawUnit(
                    roiMedianRgb = zeroRgb,
                    roiMedianGray = 0.0,
                    roiMeanGray = 0.0,
                    roiSigmaGray = 0.0,
                    backgroundMedianRgb = zeroRgb,
                    backgroundMedianGray = 0.0,
                    backgroundMeanGray = 0.0,
                    backgroundSigmaRgb = zeroRgb,
                    backgroundSigma = 0.0,
                    contaminationRatio = 0.0,
                    saturationRatio = 0.0,
                    pctAboveBgPlus2Sd = 0.0,
                    backgroundMeanMedianSkew = 0.0,
                    foregroundMeanMedianSkew = 0.0,
                    integratedSignalRgb = zeroRgb,
                    integratedSignalGray = 0.0,
                    hotPixelRatio = 0.0,
                    roiClipRatio = roiClipRatio,
                    annulusClipRatio = annulusClipRatio,
                    outOfBounds = true,
                    roiPixelCount = 0,
                    annulusPixelCount = 0
                )
            }
        }
    }

    private data class IlluminationField(
        val predictions: Array<DoubleArray>,
        val model: String
    )

    private const val DARK_FIELD_UNITY_MODEL: String = "dark_field_unity"
    private const val MINIMUM_BRIGHTFIELD_BACKGROUND_GRAY: Double = 45.0
    private const val QUANTIZATION_NOISE_FLOOR_8BIT: Double = 0.5
    private const val MAXIMUM_SAMPLING_CENTER_SHIFT_RATIO: Double = 0.18
    private const val MINIMUM_RELIABLE_ANNULUS_PIXELS: Int = 16
    private const val LOCAL_BG_SKEW_SIGMA_LIMIT: Double = 2.5
    private const val MINIMUM_LOCAL_BG_SKEW_DELTA: Double = 4.0
    private const val CHANNEL_COUNT: Int = 4
    private const val RED_CHANNEL_INDEX: Int = 0
    private const val GREEN_CHANNEL_INDEX: Int = 1
    private const val BLUE_CHANNEL_INDEX: Int = 2
    private const val GRAY_CHANNEL_INDEX: Int = 3
    private const val POLYNOMIAL_COEFFICIENT_COUNT: Int = 6
    private const val MINIMUM_POLYNOMIAL_UNIT_COUNT: Int = 12
    private const val MINIMUM_RETAINED_RATIO: Double = 0.6
    private const val ROBUST_SCALE: Double = 1.4826
    private const val MINIMUM_CONTAMINATION_DELTA: Double = 8.0
    private const val HOT_PIXEL_SIGMA_MULTIPLIER: Double = 8.0
    private const val MINIMUM_HOT_PIXEL_DELTA: Double = 24.0
    private const val MAXIMUM_SNR: Double = 9999.0
    private const val EPSILON: Double = 1e-6
}

/** 孔内局部坐标与原图分辨率无关；调用方先检查孔的真实前景，再让这里划分采样区。 */
internal class WellReadoutGeometry(config: PgQuantConfig, center: GridPoint, unit: ArrayUnitRegion?, radius: Double) {
    private val mode = config.readoutMode
    private val scale = config.centerScale
    private val centerX = unit?.bounds?.let { (it.left + it.right - 1.0) / 2 } ?: center.x
    private val centerY = unit?.bounds?.let { (it.top + it.bottom - 1.0) / 2 } ?: center.y
    private val halfWidth = unit?.bounds?.let { it.width / 2.0 } ?: radius
    private val halfHeight = unit?.bounds?.let { it.height / 2.0 } ?: radius
    private val round = unit == null || unit.shape == ArrayUnitShape.CIRCLE

    /** 返回 0 或五区编号；-1 表示不纳入所选口径，不会将这些像素挪进背景环。 */
    fun region(pixelX: Int, pixelY: Int): Int {
        val offsetX = (pixelX - centerX) / halfWidth
        val offsetY = (pixelY - centerY) / halfHeight
        return when (mode) {
            WellReadoutMode.FULL_MEAN, WellReadoutMode.FULL_MEDIAN -> 0
            WellReadoutMode.CENTER_PIXEL -> if (pixelX == centerX.roundToInt() && pixelY == centerY.roundToInt()) 0 else -1
            WellReadoutMode.CENTER_MEDIAN,
            WellReadoutMode.CENTER_MEAN -> if (if (round) offsetX * offsetX + offsetY * offsetY <= scale * scale
                else maxOf(abs(offsetX), abs(offsetY)) <= scale) 0 else -1
            WellReadoutMode.FIVE_REGION_MEAN -> when {
                abs(offsetX) <= WINDOW && abs(offsetY) <= WINDOW -> 0
                abs(offsetX) <= WINDOW && abs(offsetY + ARM) <= WINDOW -> 1
                abs(offsetX) <= WINDOW && abs(offsetY - ARM) <= WINDOW -> 2
                abs(offsetX + ARM) <= WINDOW && abs(offsetY) <= WINDOW -> 3
                abs(offsetX - ARM) <= WINDOW && abs(offsetY) <= WINDOW -> 4
                else -> -1
            }
        }
    }

    private companion object {
        // 以半径/半边长为单位；同面积的五个小方窗在圆和方孔中心区均互不重叠。
        const val ARM = 0.45
        const val WINDOW = 0.14
    }
}

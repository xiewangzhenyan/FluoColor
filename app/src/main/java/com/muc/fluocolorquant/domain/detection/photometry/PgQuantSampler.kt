package com.muc.fluocolorquant.domain.detection.photometry

import android.graphics.Bitmap
import android.graphics.Color
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.grid.RegularGridGeometry
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitRegion
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitSegmentationResult
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
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
            extractRawUnit(
                image = image,
                rectifiedCenter = site.rectified,
                inverseHomography = inverse,
                rectifiedWidth = grid.rectifiedWidth,
                rectifiedHeight = grid.rectifiedHeight,
                roiRadius = roiRadius,
                annulusInner = annulusInner,
                annulusOuter = annulusOuter,
                config = config,
                unitRegion = unitSegmentation?.regions?.get(index)
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
            height = grid.rectifiedHeight.toDouble()
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
            val prediction = illumination.predictions[index]
            val redScale = fieldMean[RED_CHANNEL_INDEX] / maxOf(prediction[RED_CHANNEL_INDEX], EPSILON)
            val greenScale = fieldMean[GREEN_CHANNEL_INDEX] / maxOf(prediction[GREEN_CHANNEL_INDEX], EPSILON)
            val blueScale = fieldMean[BLUE_CHANNEL_INDEX] / maxOf(prediction[BLUE_CHANNEL_INDEX], EPSILON)
            val grayScale = fieldMean[GRAY_CHANNEL_INDEX] / maxOf(prediction[GRAY_CHANNEL_INDEX], EPSILON)
            val correctedRgb = RgbPhotometry(
                red = raw.roiMedianRgb.red * redScale,
                green = raw.roiMedianRgb.green * greenScale,
                blue = raw.roiMedianRgb.blue * blueScale
            )
            val signalGray = raw.roiMedianGray - raw.backgroundMedianGray
            val snr = (abs(signalGray) / (raw.backgroundSigma + EPSILON)).coerceAtMost(MAXIMUM_SNR)
            val flags = buildSet {
                if (raw.saturationRatio > config.saturationRatioLimit) add(PhotometryFlag.SATURATED)
                if (raw.roiMedianGray < config.underExposedMedianLevel) add(PhotometryFlag.UNDER_EXPOSED)
                if (snr < config.snrMinimum) add(PhotometryFlag.LOW_SNR)
                if (raw.outOfBounds) add(PhotometryFlag.ROI_OUT_OF_BOUNDS)
                if (raw.contaminationRatio > config.contaminationRatioLimit) add(PhotometryFlag.NON_UNIFORM)
                if (
                    abs(backgroundResiduals[index] - residualMedian) >
                    config.backgroundAnomalyRobustZ * residualSigma
                ) {
                    add(PhotometryFlag.BACKGROUND_ANOMALY)
                }
            }
            val qc = SitePhotometryQc.from(flags, snr, config.snrMinimum)
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
                qc = qc
            )
        }

        return PgQuantResult(
            processorVersion = if (unitSegmentation == null) {
                PG_QUANT_LEGACY_PROCESSOR_VERSION
            } else {
                PG_QUANT_PROCESSOR_VERSION
            },
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

    /**
     * 在矫正坐标中枚举圆形 ROI/背景环，并回投影到原图读取最近邻像素。
     * 最近邻不会制造原图中不存在的新强度；位点详情仍可根据原图坐标回看实际像素。
     */
    private fun extractRawUnit(
        image: OriginalBitmapPixels,
        rectifiedCenter: GridPoint,
        inverseHomography: DoubleArray,
        rectifiedWidth: Int,
        rectifiedHeight: Int,
        roiRadius: Double,
        annulusInner: Double,
        annulusOuter: Double,
        config: PgQuantConfig,
        unitRegion: ArrayUnitRegion?
    ): RawUnit {
        val regionReach = unitRegion?.bounds?.let { bounds ->
            max(
                max(abs(bounds.left - rectifiedCenter.x), abs(bounds.right - rectifiedCenter.x)),
                max(abs(bounds.top - rectifiedCenter.y), abs(bounds.bottom - rectifiedCenter.y))
            )
        } ?: 0.0
        val reach = ceil(max(annulusOuter, regionReach)).toInt() + 1
        val centerFloorX = floorToInt(rectifiedCenter.x)
        val centerFloorY = floorToInt(rectifiedCenter.y)
        val roiPixels = mutableListOf<PixelSample>()
        val annulusPixels = mutableListOf<PixelSample>()
        var roiIdealCount = 0
        var annulusIdealCount = 0

        for (y in centerFloorY - reach..centerFloorY + reach) {
            for (x in centerFloorX - reach..centerFloorX + reach) {
                val distance = hypot(x - rectifiedCenter.x, y - rectifiedCenter.y)
                // 有单元分割结果时，信号只读取真实单元本体；旧调用仍保持 v1 圆形 ROI。
                val inRoi = unitRegion?.contains(x, y) ?: (distance <= roiRadius)
                // 背景环永远排除前景。圆孔半径较大时也不会把孔内边缘误算成局部背景。
                val inAnnulus = !inRoi && distance > annulusInner && distance <= annulusOuter
                if (!inRoi && !inAnnulus) continue
                if (inRoi) roiIdealCount++ else annulusIdealCount++

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
        val backgroundGrayMedian = median(annulusGray)
        val backgroundSigma = ROBUST_SCALE * median(annulusGray.map { abs(it - backgroundGrayMedian) })
        val backgroundRgb = medianRgb(annulusPixels)
        val backgroundSigmaRgb = RgbPhotometry(
            red = robustSigma(annulusPixels.map { it.red.toDouble() }, backgroundRgb.red),
            green = robustSigma(annulusPixels.map { it.green.toDouble() }, backgroundRgb.green),
            blue = robustSigma(annulusPixels.map { it.blue.toDouble() }, backgroundRgb.blue)
        )
        val contaminationTolerance = maxOf(4.0 * backgroundSigma, MINIMUM_CONTAMINATION_DELTA)
        val contamination = roiGray.count { abs(it - roiGrayMedian) > contaminationTolerance }
            .toDouble() / roiGray.size
        val saturation = roiPixels.count { pixel ->
            pixel.red >= config.saturationLevel ||
                pixel.green >= config.saturationLevel ||
                pixel.blue >= config.saturationLevel
        }.toDouble() / roiPixels.size
        val roiSigma = ROBUST_SCALE * median(roiGray.map { abs(it - roiGrayMedian) })
        val hotPixelThreshold = roiGrayMedian + maxOf(HOT_PIXEL_SIGMA_MULTIPLIER * roiSigma, MINIMUM_HOT_PIXEL_DELTA)
        val hotPixelRatio = roiGray.count { it > hotPixelThreshold }.toDouble() / roiGray.size
        val roiRgb = medianRgb(roiPixels)

        return RawUnit(
            roiMedianRgb = roiRgb,
            roiMedianGray = roiGrayMedian,
            backgroundMedianRgb = backgroundRgb,
            backgroundMedianGray = backgroundGrayMedian,
            backgroundSigmaRgb = backgroundSigmaRgb,
            backgroundSigma = backgroundSigma,
            contaminationRatio = contamination,
            saturationRatio = saturation,
            integratedSignalRgb = RgbPhotometry(
                red = roiPixels.sumOf { it.red - backgroundRgb.red },
                green = roiPixels.sumOf { it.green - backgroundRgb.green },
                blue = roiPixels.sumOf { it.blue - backgroundRgb.blue }
            ),
            integratedSignalGray = roiGray.sumOf { it - backgroundGrayMedian },
            hotPixelRatio = hotPixelRatio,
            roiClipRatio = roiClip,
            annulusClipRatio = annulusClip,
            outOfBounds = outOfBounds
        )
    }

    /**
     * 用全阵列背景环中位数拟合二阶乘性照明场。
     * 有效位点少于 12、矩阵退化或预测出现明显非正外推时回退常量场。
     */
    private fun fitIlluminationField(
        centers: List<GridPoint>,
        values: List<DoubleArray>,
        valid: List<Boolean>,
        width: Double,
        height: Double
    ): IlluminationField {
        val constant = constantField(values, valid)
        val validIndices = valid.indices.filter(valid::get)
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

    private data class RawUnit(
        val roiMedianRgb: RgbPhotometry,
        val roiMedianGray: Double,
        val backgroundMedianRgb: RgbPhotometry,
        val backgroundMedianGray: Double,
        val backgroundSigmaRgb: RgbPhotometry,
        val backgroundSigma: Double,
        val contaminationRatio: Double,
        val saturationRatio: Double,
        val integratedSignalRgb: RgbPhotometry,
        val integratedSignalGray: Double,
        val hotPixelRatio: Double,
        val roiClipRatio: Double,
        val annulusClipRatio: Double,
        val outOfBounds: Boolean
    ) {
        companion object {
            fun empty(roiClipRatio: Double, annulusClipRatio: Double): RawUnit {
                val zeroRgb = RgbPhotometry(0.0, 0.0, 0.0)
                return RawUnit(
                    roiMedianRgb = zeroRgb,
                    roiMedianGray = 0.0,
                    backgroundMedianRgb = zeroRgb,
                    backgroundMedianGray = 0.0,
                    backgroundSigmaRgb = zeroRgb,
                    backgroundSigma = 0.0,
                    contaminationRatio = 0.0,
                    saturationRatio = 0.0,
                    integratedSignalRgb = zeroRgb,
                    integratedSignalGray = 0.0,
                    hotPixelRatio = 0.0,
                    roiClipRatio = roiClipRatio,
                    annulusClipRatio = annulusClipRatio,
                    outOfBounds = true
                )
            }
        }
    }

    private data class IlluminationField(
        val predictions: Array<DoubleArray>,
        val model: String
    )

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

package com.muc.fluocolorquant.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 像素提取工具类
 * 用于从孔位图像中提取各种像素特征值
 */
object PixelExtractionUtils {

    private const val TAG = "PixelExtractionUtils"
    
    /**
     * 提取所有像素特征值
     * @param bitmap 孔位图像
     * @return 像素特征值的JSON字符串
     */
    fun extractAllPixelValues(
        bitmap: Bitmap,
        shape: ArrayUnitShape = ArrayUnitShape.CIRCLE,
        foregroundMask: BooleanArray? = null
    ): String {
        val pixelValues = mutableMapOf<String, Double>()
        
        try {
            val mask = createUnitMask(bitmap.width, bitmap.height, shape, foregroundMask)
            // 计算平均RGB值
            val avgRgb = calculateAverageRgb(bitmap, mask)
            val avgR = avgRgb[0]
            val avgG = avgRgb[1]
            val avgB = avgRgb[2]
            
            // 存储基本RGB值
            pixelValues[PixelType.RED.identifier] = avgR
            pixelValues[PixelType.GREEN.identifier] = avgG
            pixelValues[PixelType.BLUE.identifier] = avgB
            
            // 计算灰度值（亮度）
            pixelValues[PixelType.GRAY_LUMINOSITY.identifier] = 0.299 * avgR + 0.587 * avgG + 0.114 * avgB
            
            // 计算欧几里得范数
            pixelValues[PixelType.EUCLIDEAN_NORM.identifier] = sqrt(avgR.pow(2) + avgG.pow(2) + avgB.pow(2))
            
            // 计算RGB平均值
            pixelValues[PixelType.AVERAGE_RGB.identifier] = (avgR + avgG + avgB) / 3
            
            // 计算反转RB平均值
            pixelValues[PixelType.INVERSE_RB_AVG.identifier] = ((255 - avgR) + (255 - avgB)) / 2
            
            // 计算RB差值
            pixelValues[PixelType.RB_DIFF.identifier] = avgR - avgB + 255
            
            // 计算比率
            if (avgG > 0) pixelValues[PixelType.RATIO_RG.identifier] = avgR / avgG
            if (avgB > 0) pixelValues[PixelType.RATIO_RB.identifier] = avgR / avgB
            if (avgB > 0) pixelValues[PixelType.RATIO_GB.identifier] = avgG / avgB
            
            // 计算HSV/HSL值
            val hsvValues = calculateAverageHsv(bitmap, mask)
            pixelValues[PixelType.HUE.identifier] = hsvValues[0]
            pixelValues[PixelType.SATURATION_HSV.identifier] = hsvValues[1]
            pixelValues[PixelType.VALUE_HSV.identifier] = hsvValues[2]
            
            val hslValues = calculateAverageHsl(bitmap, mask)
            pixelValues[PixelType.SATURATION_HSL.identifier] = hslValues[1]
            pixelValues[PixelType.LIGHTNESS_HSL.identifier] = hslValues[2]
            
            // 计算CIE XYZ值
            val xyzValues = calculateAverageCieXyz(bitmap, mask)
            pixelValues[PixelType.CIE_X.identifier] = xyzValues[0]
            pixelValues[PixelType.CIE_Y.identifier] = xyzValues[1]
            pixelValues[PixelType.CIE_Z.identifier] = xyzValues[2]
            
            // 计算CIE xy值
            val xySum = xyzValues[0] + xyzValues[1] + xyzValues[2]
            if (xySum > 0) {
                pixelValues[PixelType.CIE_x.identifier] = xyzValues[0] / xySum
                pixelValues[PixelType.CIE_y.identifier] = xyzValues[1] / xySum
            }
            
            // 计算CIE L*a*b*值
            val labValues = calculateAverageCieLab(bitmap, mask)
            pixelValues[PixelType.CIE_L.identifier] = labValues[0]
            pixelValues[PixelType.CIE_a.identifier] = labValues[1]
            pixelValues[PixelType.CIE_b.identifier] = labValues[2]
            
            // 计算YCbCr值
            val ycbcrValues = calculateAverageYCbCr(bitmap, mask)
            pixelValues[PixelType.YCBCR_Y.identifier] = ycbcrValues[0]
            pixelValues[PixelType.YCBCR_CB.identifier] = ycbcrValues[1]
            pixelValues[PixelType.YCBCR_CR.identifier] = ycbcrValues[2]
            
            // 计算CMYK值
            val cmykValues = calculateAverageCmyk(avgR, avgG, avgB)
            pixelValues[PixelType.CYAN.identifier] = cmykValues[0]
            pixelValues[PixelType.MAGENTA.identifier] = cmykValues[1]
            pixelValues[PixelType.YELLOW.identifier] = cmykValues[2]
            pixelValues[PixelType.BLACK.identifier] = cmykValues[3]
            
        } catch (e: Exception) {
            Log.e(TAG, "提取像素值失败", e)
        }
        
        // 转换为JSON字符串
        return Gson().toJson(pixelValues)
    }
    
    /**
     * 计算平均RGB值
     * @param bitmap 孔位图像
     * @return RGB平均值数组 [R, G, B]
     */
    private fun calculateAverageRgb(bitmap: Bitmap, mask: BooleanArray): DoubleArray {
        var totalR = 0.0
        var totalG = 0.0
        var totalB = 0.0
        val width = bitmap.width
        val height = bitmap.height
        var validPixels = 0
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x]) {
                    val pixel = bitmap.getPixel(x, y)
                    totalR += Color.red(pixel)
                    totalG += Color.green(pixel)
                    totalB += Color.blue(pixel)
                    validPixels++
                }
            }
        }
        
        return if (validPixels > 0) {
            doubleArrayOf(
                totalR / validPixels,
                totalG / validPixels,
                totalB / validPixels
            )
        } else {
            doubleArrayOf(0.0, 0.0, 0.0)
        }
    }

    /**
     * 从多个JSON字符串中计算平均像素值
     * @param jsonList 像素值JSON字符串列表
     * @return 平均像素值的映射
     */
    fun calculateAveragePixelValues(jsonList: List<String>): Map<String, Double> {
        if (jsonList.isEmpty()) {
            return emptyMap()
        }
        
        val pixelMaps = jsonList.mapNotNull { jsonToMap(it) }
        if (pixelMaps.isEmpty()) {
            return emptyMap()
        }
        
        val result = mutableMapOf<String, Double>()
        val allKeys = pixelMaps.flatMap { it.keys }.distinct()
        
        for (key in allKeys) {
            val values = pixelMaps.mapNotNull { it[key] }
            if (values.isNotEmpty()) {
                result[key] = values.average()
            }
        }
        
        return result
    }

    /**
     * 将JSON字符串转换为像素值映射
     * @param json 像素值JSON字符串
     * @return 像素值映射
     */
    fun jsonToMap(json: String): Map<String, Double> {
        try {
            val type = object : TypeToken<Map<String, Double>>() {}.type
            return Gson().fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "JSON转换为Map失败", e)
            return emptyMap()
        }
    }
    
    /**
     * 创建与载体单元形状一致的像素掩码。
     *
     * 旧 96 孔板默认仍使用内缩到 90% 半径的圆形区域，保持历史像素值连续；方形芯片
     * 使用完整紧致框。自定义形状应由调用方传入 [foregroundMask]，未提供时保守使用整框。
     * @param width 图像宽度
     * @param height 图像高度
     * @return 布尔数组，表示每个像素是否在圆形区域内
     */
    private fun createUnitMask(
        width: Int,
        height: Int,
        shape: ArrayUnitShape,
        foregroundMask: BooleanArray?
    ): BooleanArray {
        foregroundMask?.let { supplied ->
            require(supplied.size == width * height) { "前景掩膜尺寸必须等于 Bitmap 像素数" }
            return supplied.copyOf()
        }
        if (shape == ArrayUnitShape.SQUARE || shape == ArrayUnitShape.CUSTOM) {
            return BooleanArray(width * height) { true }
        }

        val mask = BooleanArray(width * height)
        val centerX = width / 2.0
        val centerY = height / 2.0
        val radius = minOf(width, height) / 2.0 * 0.9 // 使用90%的半径，避免边缘效应
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val distance = sqrt((x - centerX).pow(2) + (y - centerY).pow(2))
                mask[y * width + x] = distance <= radius
            }
        }
        
        return mask
    }
    
    /**
     * 计算平均HSV值
     * @param bitmap 孔位图像
     * @return HSV平均值数组 [H, S, V]
     */
    private fun calculateAverageHsv(bitmap: Bitmap, mask: BooleanArray): DoubleArray {
        var totalH = 0.0
        var totalS = 0.0
        var totalV = 0.0
        val width = bitmap.width
        val height = bitmap.height
        var validPixels = 0
        
        val hsv = FloatArray(3)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x]) {
                    val pixel = bitmap.getPixel(x, y)
                    Color.colorToHSV(pixel, hsv)
                    totalH += hsv[0]
                    totalS += hsv[1]
                    totalV += hsv[2]
                    validPixels++
                }
            }
        }
        
        return if (validPixels > 0) {
            doubleArrayOf(
                totalH / validPixels,
                totalS / validPixels,
                totalV / validPixels
            )
        } else {
            doubleArrayOf(0.0, 0.0, 0.0)
        }
    }
    
    /**
     * 计算平均HSL值
     * @param bitmap 孔位图像
     * @return HSL平均值数组 [H, S, L]
     */
    private fun calculateAverageHsl(bitmap: Bitmap, mask: BooleanArray): DoubleArray {
        var totalH = 0.0
        var totalS = 0.0
        var totalL = 0.0
        val width = bitmap.width
        val height = bitmap.height
        var validPixels = 0
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x]) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel) / 255.0
                    val g = Color.green(pixel) / 255.0
                    val b = Color.blue(pixel) / 255.0
                    
                    val max = maxOf(r, g, b) // Double类型明确指定
                    val min = minOf(r, g, b) // Double类型明确指定
                    val l = (max + min) / 2.0
                    
                    val s = if (max == min) {
                        0.0
                    } else {
                        if (l <= 0.5) {
                            (max - min) / (max + min)
                        } else {
                            (max - min) / (2.0 - max - min)
                        }
                    }
                    
                    val h = when {
                        max == min -> 0.0
                        max == r -> (60 * ((g - b) / (max - min)) + 360) % 360
                        max == g -> 60 * ((b - r) / (max - min)) + 120
                        else -> 60 * ((r - g) / (max - min)) + 240
                    }
                    
                    totalH += h
                    totalS += s
                    totalL += l
                    validPixels++
                }
            }
        }
        
        return if (validPixels > 0) {
            doubleArrayOf(
                totalH / validPixels,
                totalS / validPixels,
                totalL / validPixels
            )
        } else {
            doubleArrayOf(0.0, 0.0, 0.0)
        }
    }
    
    /**
     * 计算平均CIE XYZ值
     * @param bitmap 孔位图像
     * @return CIE XYZ平均值数组 [X, Y, Z]
     */
    private fun calculateAverageCieXyz(bitmap: Bitmap, pixelMask: BooleanArray): DoubleArray {
        // 将Bitmap转换为OpenCV Mat
        val rgbMat = Mat()
        Utils.bitmapToMat(bitmap, rgbMat)
        
        // 创建XYZ Mat
        val xyzMat = Mat()
        Imgproc.cvtColor(rgbMat, xyzMat, Imgproc.COLOR_RGB2XYZ)
        
        val mask = createOpenCvMask(rgbMat.width(), rgbMat.height(), pixelMask)
        
        // 计算平均值
        val meanValues = Core.mean(xyzMat, mask)
        
        // 释放Mat
        rgbMat.release()
        xyzMat.release()
        mask.release()
        
        return doubleArrayOf(
            meanValues.`val`[0],
            meanValues.`val`[1],
            meanValues.`val`[2]
        )
    }
    
    /**
     * 计算平均CIE L*a*b*值
     * @param bitmap 孔位图像
     * @return CIE L*a*b*平均值数组 [L*, a*, b*]
     */
    private fun calculateAverageCieLab(bitmap: Bitmap, pixelMask: BooleanArray): DoubleArray {
        // 将Bitmap转换为OpenCV Mat
        val rgbMat = Mat()
        Utils.bitmapToMat(bitmap, rgbMat)
        
        // 创建Lab Mat
        val labMat = Mat()
        Imgproc.cvtColor(rgbMat, labMat, Imgproc.COLOR_RGB2Lab)
        
        val mask = createOpenCvMask(rgbMat.width(), rgbMat.height(), pixelMask)
        
        // 计算平均值
        val meanValues = Core.mean(labMat, mask)
        
        // 释放Mat
        rgbMat.release()
        labMat.release()
        mask.release()
        
        return doubleArrayOf(
            meanValues.`val`[0],
            meanValues.`val`[1],
            meanValues.`val`[2]
        )
    }
    
    /**
     * 计算平均YCbCr值
     * @param bitmap 孔位图像
     * @return YCbCr平均值数组 [Y, Cb, Cr]
     */
    private fun calculateAverageYCbCr(bitmap: Bitmap, pixelMask: BooleanArray): DoubleArray {
        // 将Bitmap转换为OpenCV Mat
        val rgbMat = Mat()
        Utils.bitmapToMat(bitmap, rgbMat)
        
        // 创建YCrCb Mat
        val ycrcbMat = Mat()
        Imgproc.cvtColor(rgbMat, ycrcbMat, Imgproc.COLOR_RGB2YCrCb)
        
        val mask = createOpenCvMask(rgbMat.width(), rgbMat.height(), pixelMask)
        
        // 计算平均值
        val meanValues = Core.mean(ycrcbMat, mask)
        
        // 释放Mat
        rgbMat.release()
        ycrcbMat.release()
        mask.release()
        
        return doubleArrayOf(
            meanValues.`val`[0],
            meanValues.`val`[1],
            meanValues.`val`[2]
        )
    }

    /** 把 Kotlin 布尔掩膜转换为 OpenCV 8 位单通道掩膜。 */
    private fun createOpenCvMask(width: Int, height: Int, pixelMask: BooleanArray): Mat {
        require(pixelMask.size == width * height) { "OpenCV 掩膜尺寸与图像不一致" }
        val mask = Mat(height, width, CvType.CV_8UC1)
        val bytes = ByteArray(pixelMask.size) { index ->
            if (pixelMask[index]) 0xFF.toByte() else 0
        }
        mask.put(0, 0, bytes)
        return mask
    }
    
    /**
     * 计算平均CMYK值
     * @param r 平均R值 (0-255)
     * @param g 平均G值 (0-255)
     * @param b 平均B值 (0-255)
     * @return CMYK平均值数组 [C, M, Y, K]
     */
    private fun calculateAverageCmyk(r: Double, g: Double, b: Double): DoubleArray {
        val rNorm = r / 255.0
        val gNorm = g / 255.0
        val bNorm = b / 255.0
        
        val k = 1.0 - maxOf(rNorm, gNorm, bNorm)
        val c = if (k == 1.0) 0.0 else (1.0 - rNorm - k) / (1.0 - k)
        val m = if (k == 1.0) 0.0 else (1.0 - gNorm - k) / (1.0 - k)
        val y = if (k == 1.0) 0.0 else (1.0 - bNorm - k) / (1.0 - k)
        
        return doubleArrayOf(c, m, y, k)
    }
    
    /**
     * 从像素值JSON中获取指定类型的像素值
     * @param pixelValueJson 像素值JSON字符串
     * @param pixelType 像素类型
     * @return 指定类型的像素值，如果不存在则返回null
     */
    fun getPixelValue(pixelValueJson: String, pixelType: PixelType): Double? {
        return try {
            val type = object : TypeToken<Map<String, Double>>() {}.type
            val pixelMap: Map<String, Double> = Gson().fromJson(pixelValueJson, type)
            pixelMap[pixelType.identifier]
        } catch (e: Exception) {
            Log.e("PixelExtractionUtils", "解析像素值JSON失败: ${e.message}")
            null
        }
    }
}

package com.muc.fluocolorquant.utils

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import com.muc.fluocolorquant.domain.signal.SignalFeatureCatalog
import kotlin.math.hypot
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/** 旧孔板圆孔与方形芯片共用像素提取器时的形状分流回归。 */
@RunWith(AndroidJUnit4::class)
class PixelExtractionShapeTest {

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
    }

    @Test
    fun `圆形孔位排除外接框四角而方形单元保留整框`() {
        val size = 40
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (hypot(x - size / 2.0, y - size / 2.0) <= size / 2.0 * 0.9) {
                Color.rgb(100, 100, 100)
            } else {
                Color.rgb(250, 250, 250)
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        try {
            val circle = PixelExtractionUtils.jsonToMap(
                PixelExtractionUtils.extractAllPixelValues(bitmap, ArrayUnitShape.CIRCLE)
            ).getValue(PixelType.RED.identifier)
            val square = PixelExtractionUtils.jsonToMap(
                PixelExtractionUtils.extractAllPixelValues(bitmap, ArrayUnitShape.SQUARE)
            ).getValue(PixelType.RED.identifier)
            val squareV2 = PixelExtractionUtils.jsonToMap(
                PixelExtractionUtils.extractAllPixelValues(bitmap, ArrayUnitShape.SQUARE)
            ).getValue(SignalFeatureCatalog.v2Code(PixelType.RED))

            assertTrue("圆形掩膜应只保留孔内像素，actual=$circle", circle < 105.0)
            assertTrue("方形整框应包含四角，circle=$circle square=$square", square > circle + 25.0)
            assertTrue("同一孔位 JSON 应同时保存 Legacy 与 V2 键", kotlin.math.abs(square - squareV2) < 0.001)
        } finally {
            bitmap.recycle()
        }
    }
}

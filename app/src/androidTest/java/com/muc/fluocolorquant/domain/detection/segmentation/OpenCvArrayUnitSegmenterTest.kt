package com.muc.fluocolorquant.domain.detection.segmentation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import kotlin.math.hypot

/** Python `pg_unit_export.py` 关键行为的 Android/OpenCV 合成回归。 */
@RunWith(AndroidJUnit4::class)
class OpenCvArrayUnitSegmenterTest {

    private lateinit var segmenter: OpenCvArrayUnitSegmenter

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
        segmenter = OpenCvArrayUnitSegmenter()
    }

    @Test
    fun `暗方块逐个输出零背景紧致框`() {
        val fixture = squareFixture(blankSiteIndex = null)

        val result = segmenter.segmentRectified(
            rectifiedBitmap = fixture.bitmap,
            points = fixture.points,
            rows = 3,
            columns = 3,
            polarity = GridTargetPolarity.DARK,
            shape = ArrayUnitShape.SQUARE
        )

        assertEquals(9, result.regions.size)
        assertEquals(9, result.segmentedCount)
        assertEquals(0, result.fallbackCount)
        result.regions.forEach { region ->
            assertEquals(20, region.bounds.width)
            assertEquals(22, region.bounds.height)
            assertTrue(region.foregroundMask.all { it.toInt() != 0 })
            // 合成图中方块内部灰度恒为 30；若边界带入一颗背景像素，断言会立即失败。
            for (y in region.bounds.top until region.bounds.bottom) {
                for (x in region.bounds.left until region.bounds.right) {
                    assertEquals(30, Color.red(fixture.bitmap.getPixel(x, y)))
                }
            }
        }
        fixture.bitmap.recycle()
    }

    @Test
    fun `弱对比单元使用中位尺寸居中兜底且不漏裁`() {
        val fixture = squareFixture(blankSiteIndex = 4)

        val result = segmenter.segmentRectified(
            rectifiedBitmap = fixture.bitmap,
            points = fixture.points,
            rows = 3,
            columns = 3,
            polarity = GridTargetPolarity.DARK,
            shape = ArrayUnitShape.SQUARE
        )

        assertEquals(9, result.regions.size)
        assertEquals(8, result.segmentedCount)
        assertEquals(1, result.fallbackCount)
        val fallback = result.regions[4]
        assertEquals(ArrayUnitRegionSource.FALLBACK_MEDIAN_GEOMETRY, fallback.source)
        assertEquals(20, fallback.bounds.width)
        assertEquals(22, fallback.bounds.height)
        assertTrue(hypot(
            (fallback.bounds.left + fallback.bounds.right) / 2.0 - fixture.points[4].x,
            (fallback.bounds.top + fallback.bounds.bottom) / 2.0 - fixture.points[4].y
        ) <= 1.0)
        fixture.bitmap.recycle()
    }

    @Test
    fun `亮圆孔保留圆形前景掩膜并排除外接框四角`() {
        val fixture = circleFixture()

        val result = segmenter.segmentRectified(
            rectifiedBitmap = fixture.bitmap,
            points = fixture.points,
            rows = 3,
            columns = 3,
            polarity = GridTargetPolarity.BRIGHT,
            shape = ArrayUnitShape.CIRCLE
        )

        assertEquals(9, result.segmentedCount)
        result.regions.forEach { region ->
            assertTrue(region.bounds.width in 19..21)
            assertTrue(region.bounds.height in 19..21)
            assertEquals(0, region.foregroundMask.first().toInt())
            assertTrue(region.foregroundPixelCount > 250)
        }
        fixture.bitmap.recycle()
    }

    private fun squareFixture(blankSiteIndex: Int?): Fixture {
        val width = 160
        val height = 160
        val pixels = IntArray(width * height) { Color.rgb(230, 230, 230) }
        val points = gridPoints()
        points.forEachIndexed { index, center ->
            if (index == blankSiteIndex) return@forEachIndexed
            val left = center.x.toInt() - 10
            val top = center.y.toInt() - 11
            for (y in top until top + 22) {
                for (x in left until left + 20) pixels[y * width + x] = Color.rgb(30, 30, 30)
            }
        }
        return Fixture(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), points)
    }

    private fun circleFixture(): Fixture {
        val width = 160
        val height = 160
        val pixels = IntArray(width * height) { Color.rgb(20, 20, 20) }
        val points = gridPoints()
        points.forEach { center ->
            for (y in center.y.toInt() - 11..center.y.toInt() + 11) {
                for (x in center.x.toInt() - 11..center.x.toInt() + 11) {
                    if (hypot(x - center.x, y - center.y) <= 10.0) {
                        pixels[y * width + x] = Color.rgb(235, 235, 235)
                    }
                }
            }
        }
        return Fixture(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), points)
    }

    private fun gridPoints(): List<GridPoint> = listOf(30.0, 80.0, 130.0).flatMap { y ->
        listOf(30.0, 80.0, 130.0).map { x -> GridPoint(x, y) }
    }

    private data class Fixture(
        val bitmap: Bitmap,
        val points: List<GridPoint>
    )
}

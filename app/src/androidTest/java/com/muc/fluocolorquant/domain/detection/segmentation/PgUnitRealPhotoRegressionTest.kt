package com.muc.fluocolorquant.domain.detection.segmentation

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocator
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocatorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/** 用户实拍芯片的定位后紧致裁切常驻回归，防止后续优化重新退回固定半径窗口。 */
@RunWith(AndroidJUnit4::class)
class PgUnitRealPhotoRegressionTest {

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
    }

    @Test
    fun `实拍十乘十与十五乘十五始终完整输出且大多数来自真实分割`() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val locator = OpenCvPgGridLocator()
        val segmenter = OpenCvArrayUnitSegmenter()
        cases.forEach { case ->
            val bitmap = context.assets.open("pg_grid/real_v1/${case.assetPath}").use { input ->
                requireNotNull(BitmapFactory.decodeStream(input))
            }
            try {
                val grid = locator.locate(
                    bitmap,
                    PgGridLocatorConfig(
                        rows = case.gridSize,
                        columns = case.gridSize,
                        targetPolarity = GridTargetPolarity.DARK
                    )
                )
                val result = segmenter.segment(bitmap, grid, ArrayUnitShape.SQUARE)
                Log.i(
                    LOG_TAG,
                    "case=${case.id} units=${result.regions.size} segmented=${result.segmentedCount} " +
                        "fallback=${result.fallbackCount} median=${result.medianWidthPx}x${result.medianHeightPx}"
                )

                assertEquals(case.gridSize * case.gridSize, result.regions.size)
                assertTrue(
                    "${case.id} 真实分割数量过低：${result.segmentedCount}",
                    result.segmentedCount >= case.minimumSegmentedCount
                )
                assertTrue(result.regions.all { it.bounds.width > 0 && it.bounds.height > 0 })
            } finally {
                bitmap.recycle()
            }
        }
    }

    private data class RealCase(
        val id: String,
        val assetPath: String,
        val gridSize: Int,
        val minimumSegmentedCount: Int
    )

    private companion object {
        const val LOG_TAG = "PgUnitRealPhoto"

        val cases = listOf(
            RealCase("real_10x10_01", "images/real_10x10_01.jpg", 10, 90),
            RealCase("real_10x10_02", "images/real_10x10_02.jpg", 10, 85),
            RealCase("real_15x15_01", "images/real_15x15_01.jpg", 15, 225),
            RealCase("real_15x15_02", "images/real_15x15_02.jpg", 15, 220),
            RealCase("real_15x15_03", "images/real_15x15_03.jpg", 15, 220),
            RealCase("real_15x15_04", "images/real_15x15_04.jpg", 15, 190)
        )
    }
}

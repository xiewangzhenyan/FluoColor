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

        // 门槛统一以 Python 参考工程在同一张图上的实测值为基线，留出跨 OpenCV 版本的
        // 少量余量；Android 的职责是复现参考实现，而不是维持一条脱离参考的历史数字。
        val cases = listOf(
            // Python 参考实测 94/100。
            RealCase("real_10x10_01", "images/real_10x10_01.jpg", 10, 90),
            // 2026-07-26 重新定基线：由 85 下调到 74。
            // 原因不是移植退化——升级到 V2.1 区域仲裁后，主区域改为跨两档阈值统一评分
            // （与 Python 一致，选中 opencv_bright_region_wide），该图的紧致分割成功数
            // 从旧行为的 85+ 变为 76。同版本 Python 参考在同一张图上同样输出 76/100、
            // 24 个中位盒兜底，两端逐值一致，因此这是上游行为属性而非 Android 缺陷。
            // 兜底单元仍使用稳定晶格中心与中位尺寸，并在清单中如实标记。
            RealCase("real_10x10_02", "images/real_10x10_02.jpg", 10, 74),
            RealCase("real_15x15_01", "images/real_15x15_01.jpg", 15, 225),
            RealCase("real_15x15_02", "images/real_15x15_02.jpg", 15, 220),
            RealCase("real_15x15_03", "images/real_15x15_03.jpg", 15, 220),
            RealCase("real_15x15_04", "images/real_15x15_04.jpg", 15, 190),

            // 紧裁语料：覆盖用户在 uCrop 中贴着芯片裁切的场景（芯片占比 60%~83%）。
            // 门槛按本机实测值留约 5% 余量设定，既能挡住真实退化，也不至于因跨 OpenCV
            // 版本的少量像素差异而抖动。实测（API 35 模拟器 / OpenCV 4.5.3）：
            // 93 / 85 / 225 / 225 / 225 / 225。
            //
            // 值得记录的是：紧裁不但没有让分割变差，反而普遍更好——real_10x10_02 由原图的
            // 76 提升到 85，背景杂物被裁掉后误检候选减少。因此这里的门槛高于同一张原图。
            RealCase("real_10x10_01_cropped", "images/real_10x10_01_cropped.jpg", 10, 88),
            RealCase("real_10x10_02_cropped", "images/real_10x10_02_cropped.jpg", 10, 80),
            RealCase("real_15x15_01_cropped", "images/real_15x15_01_cropped.jpg", 15, 215),
            RealCase("real_15x15_02_cropped", "images/real_15x15_02_cropped.jpg", 15, 215),
            RealCase("real_15x15_03_cropped", "images/real_15x15_03_cropped.jpg", 15, 215),
            RealCase("real_15x15_04_cropped", "images/real_15x15_04_cropped.jpg", 15, 215)
        )
    }
}

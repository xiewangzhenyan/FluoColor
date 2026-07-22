package com.muc.fluocolorquant.domain.detection.grid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * OpenCV PG-Grid 端侧定位验收测试。
 *
 * 输入图片直接复制自用户的 Python 参考工程，确保 Android 首轮移植至少在相同合成
 * 语料上输出固定点数、稳定顺序和足够的局部候选支撑，而不是只生成均分网格。
 */
@RunWith(AndroidJUnit4::class)
class OpenCvPgGridLocatorTest {

    private lateinit var locator: PgGridLocator

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
        locator = OpenCvPgGridLocator()
    }

    @Test
    fun `暗方块十乘十输出一百个可信有序位点`() {
        val result = locator.locate(
            bitmap = loadAssetBitmap("pg_grid/synthetic_10x10_dark_squares.png"),
            config = PgGridLocatorConfig(
                rows = 10,
                columns = 10,
                targetPolarity = GridTargetPolarity.DARK
            )
        )

        assertEquals(100, result.sites.size)
        assertEquals(GridSiteKey(0, 0), result.sites.first().key)
        assertEquals(GridSiteKey(9, 9), result.sites.last().key)
        assertTrue(
            "10×10 候选支撑率应不低于 0.80，实际为 ${result.geometry.candidateSupportRatio}",
            requireNotNull(result.geometry.candidateSupportRatio) >= 0.80
        )
        assertTrue(result.geometry.trusted)
    }

    @Test
    fun `亮点十五乘十五输出二百二十五个可信有序位点`() {
        val result = locator.locate(
            bitmap = loadAssetBitmap("pg_grid/synthetic_15x15_bright_points.png"),
            config = PgGridLocatorConfig(
                rows = 15,
                columns = 15,
                targetPolarity = GridTargetPolarity.BRIGHT
            )
        )

        assertEquals(225, result.sites.size)
        assertEquals(GridSiteKey(0, 0), result.sites.first().key)
        assertEquals(GridSiteKey(14, 14), result.sites.last().key)
        assertTrue(
            "15×15 候选支撑率应不低于 0.80，实际为 ${result.geometry.candidateSupportRatio}",
            requireNotNull(result.geometry.candidateSupportRatio) >= 0.80
        )
        assertTrue(result.geometry.trusted)
    }

    @Test
    fun `后台兼容四乘四始终输出十六个行优先位点`() {
        val result = locator.locate(
            bitmap = loadAssetBitmap("pg_grid/synthetic_4x4_dark_squares.png"),
            config = PgGridLocatorConfig(
                rows = 4,
                columns = 4,
                targetPolarity = GridTargetPolarity.DARK
            )
        )

        // 4×4 不再作为实验室主规格，因此这里只承诺数据层和定位器可继续读取、输出固定
        // 点数与稳定顺序；是否恢复为主规格前，仍需另建真实芯片数据集重新发布 QC 门槛。
        assertEquals(16, result.sites.size)
        assertEquals(GridSiteKey(0, 0), result.sites.first().key)
        assertEquals(GridSiteKey(3, 3), result.sites.last().key)
        assertEquals((0 until 16).toList(), result.sites.map(GridLocalizedSite::siteIndex))
        assertTrue(result.sites.all { it.rectified.x.isFinite() && it.rectified.y.isFinite() })
    }

    /** 从 instrumentation test APK 的 assets 读取图片，避免依赖设备图库或外部存储。 */
    private fun loadAssetBitmap(path: String): Bitmap {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open(path).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "无法解码测试图片：$path" }
        }
    }
}

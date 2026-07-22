package com.muc.fluocolorquant.domain.detection.grid

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * 用户实拍 10×10/15×15 芯片的设备回归测试。
 *
 * 这组图片与合成扰动语料承担不同职责：合成图用于精确坐标真值和退化曲线，实拍图用于
 * 防止算法把“规格”错误等同于“目标极性”，以及防止只在理想化纹理上通过。15×15 的
 * 历史配置曾写成 BRIGHT，但用户当前 EL 背光实物是亮面板上的暗单元，因此测试故意传入
 * 旧的 BRIGHT 偏好，要求定位器依据图像证据自动裁决为 DARK。
 */
@RunWith(AndroidJUnit4::class)
class PgGridRealPhotoRegressionTest {

    private lateinit var locator: PgGridLocator

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
        locator = OpenCvPgGridLocator()
    }

    @Test
    fun `六张实拍图均自动识别暗单元并形成完整可信晶格`() {
        realCases.forEach { case ->
            val bitmap = InstrumentationRegistry.getInstrumentation().context.assets
                .open("pg_grid/real_v1/${case.assetPath}")
                .use { input ->
                    requireNotNull(BitmapFactory.decodeStream(input)) {
                        "无法解码实拍测试图片：${case.assetPath}"
                    }
                }
            try {
                val result = locator.locate(
                    bitmap = bitmap,
                    config = PgGridLocatorConfig(
                        rows = case.gridSize,
                        columns = case.gridSize,
                        // 15×15 故意沿用旧错误偏好，验证图像证据可以覆盖载体默认值。
                        targetPolarity = if (case.gridSize == 15) {
                            GridTargetPolarity.BRIGHT
                        } else {
                            GridTargetPolarity.DARK
                        }
                    )
                )

                Log.i(
                    LOG_TAG,
                    "case=${case.id} polarity=${result.targetPolarity} " +
                        "support=${result.geometry.candidateSupportRatio} " +
                        "observed=${result.geometry.observedRatio} trusted=${result.geometry.trusted} " +
                        "chipMethod=${result.chipRegionMethod} frameQc=${result.frameQc}"
                )

                assertEquals(case.gridSize * case.gridSize, result.sites.size)
                assertEquals("${case.id} 应自动识别为暗单元", GridTargetPolarity.DARK, result.targetPolarity)
                assertNotEquals("${case.id} 不应使用中心兜底芯片区域", "fallback_center", result.chipRegionMethod)
                assertTrue(
                    "${case.id} 候选支撑率不足：${result.geometry.candidateSupportRatio}",
                    requireNotNull(result.geometry.candidateSupportRatio) >= case.minimumSupport
                )
                assertTrue("${case.id} 晶格应可信", result.geometry.trusted)
            } finally {
                // 六张高分辨率 JPEG 连续执行时及时回收像素内存，避免低内存设备测试被 OOM 干扰。
                bitmap.recycle()
            }
        }
    }

    private data class RealCase(
        val id: String,
        val assetPath: String,
        val gridSize: Int,
        val minimumSupport: Double
    )

    private companion object {
        const val LOG_TAG: String = "PgGridRealPhoto"

        val realCases: List<RealCase> = listOf(
            RealCase("real_10x10_01", "images/real_10x10_01.jpg", 10, 0.70),
            RealCase("real_10x10_02", "images/real_10x10_02.jpg", 10, 0.70),
            RealCase("real_15x15_01", "images/real_15x15_01.jpg", 15, 0.60),
            RealCase("real_15x15_02", "images/real_15x15_02.jpg", 15, 0.60),
            RealCase("real_15x15_03", "images/real_15x15_03.jpg", 15, 0.60),
            RealCase("real_15x15_04", "images/real_15x15_04.jpg", 15, 0.60)
        )
    }
}

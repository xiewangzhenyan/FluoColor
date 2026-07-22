package com.muc.fluocolorquant.domain.detection.grid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 验证曝光、清晰度、照明和透视诊断真正进入 PG-Grid 帧级 QC。 */
@RunWith(AndroidJUnit4::class)
class PgGridFrameQcTest {

    private lateinit var locator: PgGridLocator

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
        locator = OpenCvPgGridLocator()
    }

    @Test
    fun `干净主规格不产生重拍级成像质量误报`() {
        listOf(
            Triple("pg_grid/synthetic_10x10_dark_squares.png", 10, GridTargetPolarity.DARK),
            Triple("pg_grid/synthetic_15x15_bright_points.png", 15, GridTargetPolarity.BRIGHT)
        ).forEach { (asset, size, polarity) ->
            val result = locate(assetBitmap(asset), size, polarity)
            assertFalse(
                "$asset 不应产生帧级失败：${result.frameQc}",
                result.frameQc.any { it.severity == GridQcSeverity.FAILURE }
            )
        }
    }

    @Test
    fun `中等高斯模糊在两种主规格上都要求重拍`() {
        listOf(
            Triple(
                "pg_grid/perturbation_v1/images/g10_blur_3p0_s20260722.png",
                10,
                GridTargetPolarity.DARK
            ),
            Triple(
                "pg_grid/perturbation_v1/images/g15_blur_3p0_s20260722.png",
                15,
                GridTargetPolarity.BRIGHT
            )
        ).forEach { (asset, size, polarity) ->
            assertIssue(
                result = locate(assetBitmap(asset), size, polarity),
                code = GridFrameQcCode.BLURRED,
                minimumSeverity = GridQcSeverity.FAILURE
            )
        }
    }

    @Test
    fun `全局光照梯度给出照明不均警告而局部遮挡不会冒充全局梯度`() {
        listOf(
            Triple(
                "pg_grid/perturbation_v1/images/g10_illumination_0p4_s20260722.png",
                10,
                GridTargetPolarity.DARK
            ),
            Triple(
                "pg_grid/perturbation_v1/images/g15_illumination_0p4_s20260722.png",
                15,
                GridTargetPolarity.BRIGHT
            )
        ).forEach { (asset, size, polarity) ->
            assertIssue(
                result = locate(assetBitmap(asset), size, polarity),
                code = GridFrameQcCode.ILLUMINATION_NON_UNIFORM,
                minimumSeverity = GridQcSeverity.WARNING
            )
        }

        val occluded = locate(
            assetBitmap("pg_grid/perturbation_v1/images/g10_occlusion_8p0_s20260722.png"),
            10,
            GridTargetPolarity.DARK
        )
        assertFalse(
            "局部遮挡应交给位点 QC，不能冒充全局光照梯度：${occluded.frameQc}",
            occluded.frameQc.any { it.code == GridFrameQcCode.ILLUMINATION_NON_UNIFORM }
        )
    }

    @Test
    fun `高光和整帧曝光异常生成稳定原因码`() {
        val glare = locate(
            assetBitmap("pg_grid/perturbation_v1/images/g10_glare_2p0_s20260722.png"),
            10,
            GridTargetPolarity.DARK
        )
        assertIssue(glare, GridFrameQcCode.OVER_EXPOSED, GridQcSeverity.WARNING)

        val clean = assetBitmap("pg_grid/synthetic_10x10_dark_squares.png")
        val underExposed = locate(scaleBrightness(clean, 0.04), 10, GridTargetPolarity.DARK)
        assertIssue(underExposed, GridFrameQcCode.UNDER_EXPOSED, GridQcSeverity.FAILURE)

        val white = Bitmap.createBitmap(clean.width, clean.height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val overExposed = locate(white, 10, GridTargetPolarity.DARK)
        assertIssue(overExposed, GridFrameQcCode.OVER_EXPOSED, GridQcSeverity.FAILURE)
    }

    @Test
    fun `强透视变形生成过度透视原因码`() {
        val source = assetBitmap("pg_grid/synthetic_15x15_bright_points.png")
        val warped = perspectiveWarp(source, shiftRatio = 0.18)
        val result = locate(warped, 15, GridTargetPolarity.BRIGHT)
        assertIssue(
            result = result,
            code = GridFrameQcCode.PERSPECTIVE_EXCESSIVE,
            minimumSeverity = GridQcSeverity.WARNING
        )
    }

    private fun locate(bitmap: Bitmap, size: Int, polarity: GridTargetPolarity): PgGridResult {
        return locator.locate(
            bitmap,
            PgGridLocatorConfig(rows = size, columns = size, targetPolarity = polarity)
        )
    }

    /** 严重程度按 INFO < WARNING < FAILURE 比较。 */
    private fun assertIssue(
        result: PgGridResult,
        code: GridFrameQcCode,
        minimumSeverity: GridQcSeverity
    ) {
        val issue = result.frameQc.firstOrNull { it.code == code }
        assertTrue("缺少 $code，实际为 ${result.frameQc}", issue != null)
        val severity = requireNotNull(issue).severity
        assertTrue(
            "$code 严重度不足：$severity < $minimumSeverity",
            severityRank(severity) >= severityRank(minimumSeverity)
        )
    }

    private fun severityRank(severity: GridQcSeverity): Int = when (severity) {
        GridQcSeverity.INFO -> 0
        GridQcSeverity.WARNING -> 1
        GridQcSeverity.FAILURE -> 2
    }

    /** 在原图 RGB 上执行确定性线性缩放，模拟整帧严重欠曝。 */
    private fun scaleBrightness(source: Bitmap, scale: Double): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        pixels.indices.forEach { index ->
            val color = pixels[index]
            pixels[index] = Color.rgb(
                (Color.red(color) * scale).toInt().coerceIn(0, 255),
                (Color.green(color) * scale).toInt().coerceIn(0, 255),
                (Color.blue(color) * scale).toInt().coerceIn(0, 255)
            )
        }
        return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
    }

    /** 使用同一组四角单应生成强梯形透视，验证最终晶格的 pitch 变化诊断。 */
    private fun perspectiveWarp(source: Bitmap, shiftRatio: Double): Bitmap {
        val input = Mat()
        val output = Mat()
        val sourceCorners = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(source.width - 1.0, 0.0),
            Point(source.width - 1.0, source.height - 1.0),
            Point(0.0, source.height - 1.0)
        )
        val shift = minOf(source.width, source.height) * shiftRatio
        val destinationCorners = MatOfPoint2f(
            Point(shift, shift * 0.20),
            Point(source.width - 1.0 - shift * 0.15, shift),
            Point(source.width - 1.0 - shift, source.height - 1.0 - shift * 0.10),
            Point(shift * 0.10, source.height - 1.0 - shift)
        )
        val matrix = Imgproc.getPerspectiveTransform(sourceCorners, destinationCorners)
        return try {
            Utils.bitmapToMat(source, input)
            Imgproc.warpPerspective(
                input,
                output,
                matrix,
                Size(source.width.toDouble(), source.height.toDouble()),
                Imgproc.INTER_CUBIC,
                0,
                Scalar(8.0, 8.0, 8.0, 255.0)
            )
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(output, it)
            }
        } finally {
            matrix.release()
            destinationCorners.release()
            sourceCorners.release()
            output.release()
            input.release()
        }
    }

    private fun assetBitmap(path: String): Bitmap {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "无法解码测试图片：$path" }
        }
    }
}

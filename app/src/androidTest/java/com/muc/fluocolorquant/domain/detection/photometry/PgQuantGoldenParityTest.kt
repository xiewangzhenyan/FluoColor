package com.muc.fluocolorquant.domain.detection.photometry

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.muc.fluocolorquant.domain.detection.grid.PgGridJsonCodec
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 使用同一原始 PNG 和冻结的 Python PG-Quant 输出验证 Android 光度数值。
 *
 * 定位一致性由 `PgGridGoldenParityTest` 单独负责；这里直接读取 Python 金标准网格，避免
 * 把微小坐标偏差重复计入光度误差，从而只比较 ROI、背景环、平场校正与 SNR 实现。
 */
@RunWith(AndroidJUnit4::class)
class PgQuantGoldenParityTest {

    companion object {
        /** 固定日志标签，便于把 Android/Python 光度误差写入工作包验收记录。 */
        private const val PARITY_LOG_TAG = "PgQuantParity"
    }

    @Test
    fun `十乘十与十五乘十五光度字段和Python在容差内一致`() {
        assertPhotometryParity(
            imageName = "synthetic_10x10_dark_squares.png",
            gridGoldenName = "10x10-dark.json",
            quantGoldenName = "10x10-dark-quant.json"
        )
        assertPhotometryParity(
            imageName = "synthetic_15x15_bright_points.png",
            gridGoldenName = "15x15-bright.json",
            quantGoldenName = "15x15-bright-quant.json"
        )
        assertPhotometryParity(
            imageName = "synthetic_4x4_dark_squares.png",
            gridGoldenName = "4x4-legacy.json",
            quantGoldenName = "4x4-legacy-quant.json"
        )
    }

    /** 对一个规格逐位点比较基础灰度统计、校正信号、SNR 和质量结论。 */
    private fun assertPhotometryParity(
        imageName: String,
        gridGoldenName: String,
        quantGoldenName: String
    ) {
        val grid = PgGridJsonCodec.decode(assetText("pg_grid/v2_1/$gridGoldenName"))
        @Suppress("DEPRECATION")
        val expectedRoot = JsonParser().parse(
            assetText("pg_grid/v2_1/$quantGoldenName")
        ).asJsonObject
        val expectedSites = expectedRoot.getAsJsonArray("units")
        val actual = PgQuantSampler.sample(
            bitmap = assetBitmap("pg_grid/$imageName"),
            grid = grid
        )

        val roiDifferences = mutableListOf<Double>()
        val backgroundDifferences = mutableListOf<Double>()
        val correctedDifferences = mutableListOf<Double>()
        val snrRelativeDifferences = mutableListOf<Double>()

        assertEquals(grid.rows * grid.columns, expectedSites.size())
        assertEquals(expectedSites.size(), actual.sites.size)
        actual.sites.forEachIndexed { index, actualSite ->
            val expectedSite = expectedSites[index].asJsonObject
            assertEquals(index, actualSite.siteIndex)
            assertEquals(expectedSite.int("row"), actualSite.rowIndex)
            assertEquals(expectedSite.int("col"), actualSite.columnIndex)

            val expectedRoi = expectedSite.double("roi_gray_median")
            val expectedBackground = expectedSite.double("bg_gray_median")
            val expectedCorrected = expectedSite.double("corr_signal_gray")
            val expectedSnr = expectedSite.double("snr")
            roiDifferences += abs(actualSite.roiMedianGray - expectedRoi)
            backgroundDifferences += abs(actualSite.backgroundMedianGray - expectedBackground)
            correctedDifferences += abs(actualSite.correctedSignalGray - expectedCorrected)
            snrRelativeDifferences += abs(actualSite.signalToNoiseRatio - expectedSnr) /
                abs(expectedSnr).coerceAtLeast(1e-9)

            assertGrayClose(index, "ROI 中位数", expectedRoi, actualSite.roiMedianGray)
            assertGrayClose(index, "背景中位数", expectedBackground, actualSite.backgroundMedianGray)
            assertGrayClose(
                index,
                "校正信号",
                expectedCorrected,
                actualSite.correctedSignalGray
            )
            assertRelativeClose(
                index,
                "SNR",
                expectedSnr,
                actualSite.signalToNoiseRatio,
                relativeTolerance = 0.05,
                absoluteFloor = 0.10
            )
            assertEquals(expectedSite.boolean("signal_detectable"), actualSite.qc.signalDetectable)
            assertEquals(expectedSite.boolean("quant_reliable"), actualSite.qc.qualityReliable)
            assertEquals(expectedSite.stringSet("flags"), actualSite.qc.flags.map(::wireName).toSet())
        }


        Log.i(
            PARITY_LOG_TAG,
            "PG_QUANT_PARITY image=$imageName siteCount=${actual.sites.size} " +
                "maxRoiAbs=${roiDifferences.maxOrNull() ?: 0.0} " +
                "maxBackgroundAbs=${backgroundDifferences.maxOrNull() ?: 0.0} " +
                "maxCorrectedAbs=${correctedDifferences.maxOrNull() ?: 0.0} " +
                "maxSnrRelative=${snrRelativeDifferences.maxOrNull() ?: 0.0}"
        )
    }

    /** 灰度字段采用计划规定的“绝对 1.0 或相对 2%，取较大者”容差。 */
    private fun assertGrayClose(index: Int, field: String, expected: Double, actual: Double) {
        assertRelativeClose(
            index = index,
            field = field,
            expected = expected,
            actual = actual,
            relativeTolerance = 0.02,
            absoluteFloor = 1.0
        )
    }

    /** 统一输出带位点和字段名的误差信息，失败后可直接定位 Python/Android 差异来源。 */
    private fun assertRelativeClose(
        index: Int,
        field: String,
        expected: Double,
        actual: Double,
        relativeTolerance: Double,
        absoluteFloor: Double
    ) {
        val tolerance = maxOf(absoluteFloor, abs(expected) * relativeTolerance)
        val difference = abs(actual - expected)
        assertTrue(
            "位点 $index 的$field 超出容差：expected=$expected, actual=$actual, " +
                "difference=$difference, tolerance=$tolerance",
            difference <= tolerance
        )
    }

    /** 将 Kotlin 枚举稳定映射回 Python `pg-quant-v1` 使用的机器码。 */
    private fun wireName(flag: PhotometryFlag): String = when (flag) {
        PhotometryFlag.SATURATED -> "saturated"
        PhotometryFlag.UNDER_EXPOSED -> "under_exposed"
        PhotometryFlag.LOW_SNR -> "low_snr"
        PhotometryFlag.ROI_OUT_OF_BOUNDS -> "roi_out_of_bounds"
        PhotometryFlag.NON_UNIFORM -> "non_uniform"
        PhotometryFlag.BACKGROUND_ANOMALY -> "background_anomaly"
        PhotometryFlag.HOT_PIXEL -> "hot_pixel"
        PhotometryFlag.SPECULAR_HIGHLIGHT -> "specular_highlight"
    }

    private fun JsonObject.double(name: String): Double = get(name).asDouble

    private fun JsonObject.int(name: String): Int = get(name).asInt

    private fun JsonObject.boolean(name: String): Boolean = get(name).asBoolean

    private fun JsonObject.stringSet(name: String): Set<String> =
        getAsJsonArray(name).map { it.asString }.toSet()

    /** 从仪器测试 assets 读取原始 PNG，保持与定位金标准使用完全相同的输入。 */
    private fun assetBitmap(path: String): Bitmap {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input))
        }
    }

    /** 以 UTF-8 读取冻结 JSON，避免中文 Windows 环境默认编码干扰。 */
    private fun assetText(path: String): String {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }
    }
}

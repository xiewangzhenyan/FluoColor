package com.muc.fluocolorquant.domain.detection.grid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.ceil
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/** 对同一 PNG 逐点比较 Android 与冻结 Python V2.1 金标准。 */
@RunWith(AndroidJUnit4::class)
class PgGridGoldenParityTest {

    companion object {
        /** 固定日志标签，用于把归一化坐标误差归档到实施记录。 */
        private const val PARITY_LOG_TAG = "PgGridParity"
    }

    private lateinit var locator: PgGridLocator

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
        locator = OpenCvPgGridLocator()
    }

    @Test
    fun `十乘十暗方块坐标来源和QC与Python在容差内一致`() {
        assertParity(
            imageName = "synthetic_10x10_dark_squares.png",
            goldenName = "10x10-dark.json",
            rows = 10,
            columns = 10,
            polarity = GridTargetPolarity.DARK
        )
    }

    @Test
    fun `十五乘十五亮点坐标来源和QC与Python在容差内一致`() {
        assertParity(
            imageName = "synthetic_15x15_bright_points.png",
            goldenName = "15x15-bright.json",
            rows = 15,
            columns = 15,
            polarity = GridTargetPolarity.BRIGHT
        )
    }

    private fun assertParity(
        imageName: String,
        goldenName: String,
        rows: Int,
        columns: Int,
        polarity: GridTargetPolarity
    ) {
        val golden = PgGridJsonCodec.decode(assetText("pg_grid/v2_1/$goldenName"))
        val android = locator.locate(
            assetBitmap("pg_grid/$imageName"),
            PgGridLocatorConfig(rows = rows, columns = columns, targetPolarity = polarity)
        )
        val pitch = RegularGridGeometry.estimatePitch(
            golden.sites.map { it.rectified },
            rows,
            columns
        ).representativePx
        val indexedErrors = android.sites.indices.map { index ->
            val error = hypot(
                android.sites[index].rectified.x - golden.sites[index].rectified.x,
                android.sites[index].rectified.y - golden.sites[index].rectified.y
            ) / pitch
            index to error
        }
        val normalizedErrors = indexedErrors.map(Pair<Int, Double>::second).sorted()
        val meanError = normalizedErrors.average()
        val p95Error = percentile(normalizedErrors, 0.95)
        val (worstIndex, worstError) = indexedErrors.maxBy(Pair<Int, Double>::second)

        Log.i(
            PARITY_LOG_TAG,
            "PG_GRID_PARITY rows=$rows columns=$columns meanPitchError=$meanError " +
                "p95PitchError=$p95Error maxPitchError=$worstError worstIndex=$worstIndex " +
                "expected=${golden.sites[worstIndex].rectified} " +
                "actual=${android.sites[worstIndex].rectified} siteCount=${android.sites.size}"
        )

        assertEquals(golden.sites.size, android.sites.size)
        assertTrue("平均坐标误差应 ≤0.05 pitch，实际为 $meanError", meanError <= 0.05)
        assertTrue("P95 坐标误差应 ≤0.10 pitch，实际为 $p95Error", p95Error <= 0.10)
        assertEquals(golden.sites.map { it.source }, android.sites.map { it.source })
        assertEquals(golden.frameQc.map { it.code }.toSet(), android.frameQc.map { it.code }.toSet())
    }

    private fun percentile(sorted: List<Double>, ratio: Double): Double {
        val index = (ceil(sorted.size * ratio).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun assetBitmap(path: String): Bitmap {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input))
        }
    }

    private fun assetText(path: String): String {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }
    }
}

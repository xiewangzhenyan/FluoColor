package com.muc.fluocolorquant.domain.detection.grid

import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/** API 35 模拟器端侧定位性能门槛；首轮作为 OpenCV/JIT 热身，不计入统计。 */
@RunWith(AndroidJUnit4::class)
class PgGridPerformanceTest {

    companion object {
        /** 固定日志标签，便于从仪器测试日志中提取并归档真实性能数据。 */
        private const val PERFORMANCE_LOG_TAG = "PgGridPerformance"

        /** 端侧交互可接受的单张阵列定位 P95 上限。 */
        private const val MAX_P95_MS = 1_500.0

        /** 定位过程允许的 Java 与 Native 堆合计增量上限。 */
        private const val MAX_HEAP_INCREASE_MB = 160.0
    }

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
    }

    @Test
    fun `十乘十和十五乘十五定位性能满足端侧门槛`() {
        assertPerformance(
            imageName = "synthetic_10x10_dark_squares.png",
            rows = 10,
            columns = 10,
            polarity = GridTargetPolarity.DARK
        )
        assertPerformance(
            imageName = "synthetic_15x15_bright_points.png",
            rows = 15,
            columns = 15,
            polarity = GridTargetPolarity.BRIGHT
        )
    }

    /**
     * 对单一规格执行一次热身和五次正式测量。
     *
     * 热身轮用于消除 OpenCV 初始化与 ART JIT 的一次性开销；正式轮同时记录耗时和每轮结束时的
     * Java/Native 堆占用，取相对基线的最大值作为本次验收可重复获取的峰值堆增量。
     */
    private fun assertPerformance(
        imageName: String,
        rows: Int,
        columns: Int,
        polarity: GridTargetPolarity
    ) {
        val bitmap = InstrumentationRegistry.getInstrumentation().context.assets
            .open("pg_grid/$imageName")
            .use { requireNotNull(BitmapFactory.decodeStream(it)) }
        val locator = OpenCvPgGridLocator()
        val config = PgGridLocatorConfig(rows, columns, polarity)
        val expectedSiteCount = rows * columns

        locator.locate(bitmap, config) // JIT 与 OpenCV 首轮热身
        Runtime.getRuntime().gc()
        val heapBaselineBytes = currentHeapBytes()
        var peakHeapBytes = heapBaselineBytes
        val durationsMs = buildList {
            repeat(5) {
                val startedAtNs = System.nanoTime()
                val siteCount = locator.locate(bitmap, config).sites.size
                val durationMs = (System.nanoTime() - startedAtNs) / 1_000_000.0

                assertEquals(expectedSiteCount, siteCount)
                add(durationMs)
                peakHeapBytes = maxOf(peakHeapBytes, currentHeapBytes())
            }
        }.sorted()
        Runtime.getRuntime().gc()
        val p50Ms = percentile(durationsMs, 0.50)
        val p95Ms = percentile(durationsMs, 0.95)
        val peakHeapIncreaseMb = (peakHeapBytes - heapBaselineBytes).coerceAtLeast(0L) /
            (1024.0 * 1024.0)

        Log.i(
            PERFORMANCE_LOG_TAG,
            "PG_GRID_PERF rows=$rows columns=$columns runs=5 " +
                "p50Ms=$p50Ms p95Ms=$p95Ms peakHeapIncreaseMb=$peakHeapIncreaseMb " +
                "siteCount=$expectedSiteCount"
        )

        assertTrue(
            "$rows×$columns 定位 P95 应 ≤$MAX_P95_MS ms，实际为 $p95Ms ms",
            p95Ms <= MAX_P95_MS
        )
        assertTrue(
            "$rows×$columns 堆内存峰值增量应 ≤$MAX_HEAP_INCREASE_MB MB，" +
                "实际为 $peakHeapIncreaseMb MB",
            peakHeapIncreaseMb <= MAX_HEAP_INCREASE_MB
        )
        bitmap.recycle()
    }

    /** 使用 nearest-rank 定义计算小样本分位数，和同图坐标验收保持一致。 */
    private fun percentile(sortedValues: List<Double>, ratio: Double): Double {
        val index = (ceil(sortedValues.size * ratio).toInt() - 1)
            .coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }

    /** 返回当前 Java 堆与 Native 堆的合计占用，用于比较相对增量。 */
    private fun currentHeapBytes(): Long {
        return Debug.getNativeHeapAllocatedSize() + usedJavaHeap()
    }

    /** 获取当前 Java 堆实际已用字节数。 */
    private fun usedJavaHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}

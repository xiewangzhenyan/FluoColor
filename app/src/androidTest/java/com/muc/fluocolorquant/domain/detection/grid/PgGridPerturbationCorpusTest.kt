package com.muc.fluocolorquant.domain.detection.grid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantSampler
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * PG-Grid 七扰动族 Android/Python 对照测试。
 *
 * 每个 case 使用同一张冻结 PNG，同时保存参考工程结果和扰动后的原图真值。测试重点不是
 * 让 Android 逐字节复制 Python，而是验证用户真正关心的科学性质：固定点数与顺序、
 * 原图定位精度、真实候选支撑、模型补位比例、光度梯度保序性和可靠位点比例。
 */
@RunWith(AndroidJUnit4::class)
class PgGridPerturbationCorpusTest {

    companion object {
        private const val LOG_TAG = "PgGridPerturbation"
        private const val MANIFEST_ASSET = "pg_grid/perturbation_v1/manifest.json"
        private const val EXPECTED_SCHEMA = "pg-grid-perturbation-corpus-v1"

        /** 中等扰动下平均误差应不超过 12% pitch，避免整体晶格明显错位。 */
        private const val MAXIMUM_MEAN_ERROR_PITCH = 0.12

        /** P95 允许少量遮挡/高光点退化，但不能超过 30% pitch。 */
        private const val MAXIMUM_P95_ERROR_PITCH = 0.30

        /** Android 相对 Python 最多额外损失 25% 的候选支撑率。 */
        private const val MAXIMUM_SUPPORT_LOSS = 0.25

        /** Android 相对 Python 最多额外增加 15% 的模型补位点。 */
        private const val MAXIMUM_IMPUTED_RATIO_INCREASE = 0.15

        /** 光度秩相关相对参考最多下降 0.20，且绝对值不能低于 0.70。 */
        private const val MAXIMUM_SPEARMAN_LOSS = 0.20
        private const val MINIMUM_SPEARMAN = 0.70

        /** 可靠位点比例相对 Python 最多下降 30%，防止 QC 把整板误判为不可用。 */
        private const val MAXIMUM_RELIABLE_RATIO_LOSS = 0.30
    }

    private lateinit var locator: PgGridLocator
    private val gson = Gson()

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
        locator = OpenCvPgGridLocator()
    }

    @Test
    fun `十乘十与十五乘十五在七类中等扰动下保持定位和光度基线`() {
        val manifest = gson.fromJson(assetText(MANIFEST_ASSET), CorpusManifest::class.java)
        assertEquals(EXPECTED_SCHEMA, manifest.schemaVersion)
        assertEquals(14, manifest.caseCount)
        assertEquals(setOf(10, 15), manifest.gridSizes.toSet())
        assertEquals(
            setOf("rotation", "perspective", "blur", "illumination", "occlusion", "glare", "noise"),
            manifest.families.toSet()
        )

        // 单个 case 失败后继续执行剩余 case，最终一次性报告完整退化分布，避免每次只看到
        // 第一张失败图片而反复安装 APK。异常仍会在测试末尾转为明确失败，不会被吞掉。
        val failures = mutableListOf<String>()
        manifest.cases.forEach { case ->
            runCatching { assertCase(case) }
                .onFailure { error -> failures += "${case.id}: ${error.message ?: error::class.java.simpleName}" }
        }
        assertTrue(
            "PG-Grid 扰动语料存在 ${failures.size} 个失败 case：\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }

    /** 对一个冻结 case 执行定位、光度和 Python 基线差值检查。 */
    private fun assertCase(case: CorpusCase) {
        assertEquals(case.imageSha256, assetSha256(case.imageAsset))
        assertTrue("Python 光度金标准不能为空：${case.pythonQuantAsset}", assetText(case.pythonQuantAsset).isNotBlank())
        val bitmap = assetBitmap(case.imageAsset)
        val expectedGrid = PgGridJsonCodec.decode(assetText(case.pythonGridAsset))
        val polarity = when (case.targetPolarity) {
            "dark" -> GridTargetPolarity.DARK
            "bright" -> GridTargetPolarity.BRIGHT
            else -> error("未知目标极性：${case.targetPolarity}")
        }
        assertEquals(case.rows, expectedGrid.rows)
        assertEquals(case.columns, expectedGrid.columns)
        assertEquals(case.rows * case.columns, case.truth.size)
        case.truth.forEachIndexed { index, truth ->
            assertEquals(index / case.columns, truth.row)
            assertEquals(index % case.columns, truth.column)
        }

        val actual = locator.locate(
            bitmap = bitmap,
            config = PgGridLocatorConfig(
                rows = case.rows,
                columns = case.columns,
                targetPolarity = polarity
            )
        )
        val expectedCount = case.rows * case.columns
        assertEquals(expectedCount, actual.sites.size)
        actual.sites.forEachIndexed { index, site ->
            assertEquals(index, site.siteIndex)
            assertEquals(GridSiteKey(index / case.columns, index % case.columns), site.key)
        }

        val pitch = case.pythonMetrics.truthPitchOriginalPx
        val errors = actual.sites.mapIndexed { index, site ->
            val truth = case.truth[index]
            hypot(site.original.x - truth.x, site.original.y - truth.y) / pitch
        }.sorted()
        val meanError = errors.average()
        val p95Error = percentile(errors, 0.95)
        val maximumError = errors.last()
        val support = actual.geometry.candidateSupportRatio ?: 0.0
        val pythonSupport = case.pythonMetrics.candidateSupportRatio ?: 0.0
        val minimumSupport = maxOf(0.35, pythonSupport - MAXIMUM_SUPPORT_LOSS)
        val imputedCount = actual.sites.count { it.source == GridPointSource.MODEL_IMPUTED }
        val pythonImputedCount = case.pythonMetrics.sourceCounts["model_imputed"] ?: 0
        val imputedRatio = imputedCount.toDouble() / expectedCount
        val pythonImputedRatio = pythonImputedCount.toDouble() / expectedCount
        // 光度必须使用 Android 实际定位结果，才能同时暴露定位漂移对 ROI/背景环的真实
        // 影响。暗方块信号取反后与合成真值同向，亮点保持原符号。
        val quant = PgQuantSampler.sample(bitmap, actual)
        val direction = if (polarity == GridTargetPolarity.DARK) -1.0 else 1.0
        val truthSignals = case.truth.map(TruthSite::signalTruth)
        val measuredSignals = quant.sites.map { direction * it.correctedSignalGray }
        val signalSpearman = spearman(truthSignals, measuredSignals)
        val minimumSpearman = maxOf(
            MINIMUM_SPEARMAN,
            case.pythonMetrics.quantSpearmanAll - MAXIMUM_SPEARMAN_LOSS
        )
        val reliableRatio = quant.sites.count { it.qc.qualityReliable }.toDouble() / expectedCount
        val minimumReliableRatio = maxOf(
            0.0,
            case.pythonMetrics.quantReliableRatio - MAXIMUM_RELIABLE_RATIO_LOSS
        )
        val sourceSummary = actual.sites.groupingBy(GridLocalizedSite::source).eachCount()
        val frameQcSummary = actual.frameQc.joinToString(",") { "${it.code}:${it.severity}" }
        Log.i(
            LOG_TAG,
            "PG_GRID_PERTURBATION id=${case.id} family=${case.family} level=${case.level} " +
                "grid=${case.gridSize} meanPitchError=$meanError p95PitchError=$p95Error " +
                "maxPitchError=$maximumError pythonMean=${case.pythonMetrics.meanErrorPitch} " +
                "support=$support pythonSupport=$pythonSupport trusted=${actual.geometry.trusted} " +
                "sources=$sourceSummary spearman=$signalSpearman " +
                "pythonSpearman=${case.pythonMetrics.quantSpearmanAll} reliable=$reliableRatio " +
                "pythonReliable=${case.pythonMetrics.quantReliableRatio} frameQc=[$frameQcSummary] " +
                "firstTruth=(${case.truth.first().x},${case.truth.first().y}) " +
                "firstPython=${expectedGrid.sites.first().original} " +
                "firstAndroid=${actual.sites.first().original} " +
                "lastTruth=(${case.truth.last().x},${case.truth.last().y}) " +
                "lastPython=${expectedGrid.sites.last().original} " +
                "lastAndroid=${actual.sites.last().original}"
        )

        // 先输出完整诊断再断言几何误差，失败 case 也能在一次设备运行中留下芯片四角、
        // 首末点和全部科学指标，避免只能看到第一条 AssertionError。
        assertTrue(
            "平均原图误差超过阈值：$meanError > $MAXIMUM_MEAN_ERROR_PITCH",
            meanError <= MAXIMUM_MEAN_ERROR_PITCH
        )
        assertTrue(
            "P95 原图误差超过阈值：$p95Error > $MAXIMUM_P95_ERROR_PITCH",
            p95Error <= MAXIMUM_P95_ERROR_PITCH
        )
        assertTrue(
            "候选支撑率损失过大：Android=$support, Python=$pythonSupport, 最低=$minimumSupport",
            support >= minimumSupport
        )
        if (case.pythonMetrics.trusted) {
            assertTrue("Python 晶格可信时 Android 不应降级为不可信", actual.geometry.trusted)
        }
        assertTrue(
            "模型补位比例增加过多：Android=$imputedRatio, Python=$pythonImputedRatio",
            imputedRatio <= pythonImputedRatio + MAXIMUM_IMPUTED_RATIO_INCREASE
        )
        assertTrue(
            "校正信号秩相关过低：Android=$signalSpearman, Python=${case.pythonMetrics.quantSpearmanAll}",
            signalSpearman >= minimumSpearman
        )
        assertTrue(
            "可靠位点比例损失过大：Android=$reliableRatio, Python=${case.pythonMetrics.quantReliableRatio}",
            reliableRatio >= minimumReliableRatio
        )
    }

    /** 与现有定位金标准测试保持一致，采用 nearest-rank P95，避免端侧浮点插值差异。 */
    private fun percentile(sorted: List<Double>, ratio: Double): Double {
        val index = (ceil(sorted.size * ratio).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    /**
     * 无额外统计库的 Spearman 实现。
     *
     * 合成信号真值没有并列值；测量值若并列则用原始索引稳定打破并列，口径与参考
     * benchmark 的两次 argsort 一致，足以用于跨实现退化检测。
     */
    private fun spearman(first: List<Double>, second: List<Double>): Double {
        require(first.size == second.size && first.size >= 3) { "Spearman 输入长度无效" }
        val firstRanks = ranks(first)
        val secondRanks = ranks(second)
        val firstMean = firstRanks.average()
        val secondMean = secondRanks.average()
        val covariance = firstRanks.indices.sumOf { index ->
            (firstRanks[index] - firstMean) * (secondRanks[index] - secondMean)
        }
        val firstSquare = firstRanks.sumOf { value -> (value - firstMean) * (value - firstMean) }
        val secondSquare = secondRanks.sumOf { value -> (value - secondMean) * (value - secondMean) }
        val denominator = kotlin.math.sqrt(firstSquare * secondSquare)
        return if (denominator <= 1e-12) 0.0 else covariance / denominator
    }

    private fun ranks(values: List<Double>): DoubleArray {
        val order = values.indices.sortedWith(compareBy<Int> { values[it] }.thenBy { it })
        return DoubleArray(values.size).also { ranks ->
            order.forEachIndexed { rank, originalIndex -> ranks[originalIndex] = rank.toDouble() }
        }
    }

    private fun assetBitmap(path: String): Bitmap {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "无法解码测试图片：$path" }
        }
    }

    private fun assetText(path: String): String {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    /** 设备端重新计算输入图哈希，防止 APK 合并 assets 时读到旧文件或同名错误资产。 */
    private fun assetSha256(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** manifest 仅承载测试资产索引，不进入应用运行时领域模型。 */
    private data class CorpusManifest(
        val schemaVersion: String,
        val caseCount: Int,
        val gridSizes: List<Int>,
        val families: List<String>,
        val cases: List<CorpusCase>
    )

    private data class CorpusCase(
        val id: String,
        val gridSize: Int,
        val rows: Int,
        val columns: Int,
        val targetPolarity: String,
        val family: String,
        val level: Double,
        val seed: Int,
        val imageAsset: String,
        val imageSha256: String,
        val pythonGridAsset: String,
        val pythonQuantAsset: String,
        val truth: List<TruthSite>,
        val pythonMetrics: PythonMetrics
    )

    private data class TruthSite(
        val row: Int,
        val column: Int,
        val x: Double,
        val y: Double,
        val signalTruth: Double
    )

    private data class PythonMetrics(
        val truthPitchOriginalPx: Double,
        val meanErrorPitch: Double,
        val p95ErrorPitch: Double,
        val maxErrorPitch: Double,
        val candidateSupportRatio: Double?,
        val trusted: Boolean,
        val sourceCounts: Map<String, Int>,
        val frameQcCodes: List<String>,
        val hasFailureQc: Boolean,
        val qualityStatus: String,
        val quantReliableRatio: Double,
        val quantSpearmanAll: Double,
        val quantSpearmanReliable: Double
    )
}

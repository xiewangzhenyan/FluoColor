package com.muc.fluocolorquant.domain.detection.grid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.domain.detection.photometry.FluorescencePhotometryProcessor
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceProcessorConfig
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantConfig
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantSampler
import com.muc.fluocolorquant.domain.detection.photometry.WellReadoutMode
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import kotlinx.coroutines.runBlocking
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random
import java.io.File

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

    @Test
    fun locatorReportOnLocalChipImage() {
        val device = InstrumentationRegistry.getInstrumentation()
        val name = InstrumentationRegistry.getArguments().getString("locatorChipImage")
        assumeTrue("未提供额外待查原图", !name.isNullOrBlank())
        val input = File(device.targetContext.cacheDir, requireNotNull(name))
        val image = requireNotNull(BitmapFactory.decodeFile(input.path))
        try {
            val methods = listOf(ArrayLocatorMode.AUTO, ArrayLocatorMode.PROJECTION, ArrayLocatorMode.KMEANS,
                ArrayLocatorMode.SPOT_TOPOLOGY, ArrayLocatorMode.CIRCLES_GRID, ArrayLocatorMode.DBSCAN, ArrayLocatorMode.LOG_SPOTS)
            val config = PgGridLocatorConfig(15, 15, GridTargetPolarity.BRIGHT)
            for (mode in methods) {
                val result = try {
                    locator.locate(image, config.copy(mode = mode))
                } catch (_: TraditionalGridNotFound) { null }
                Log.i("ArrayLocatorReport", "$mode region=${result?.chipRegionMethod} observed=${result?.geometry?.inlierCount} version=${result?.locatorVersion}")
                // 仅写测试进程的缓存以供检查同编号坐标；不把 PG 结果当实验真值，也不保存正式运行。
                File(device.targetContext.cacheDir, "locator-probe-${mode.name}.csv").writeText(
                    result?.sites?.joinToString("\n") { site -> "${site.original.x},${site.original.y},${site.source}" }.orEmpty()
                )
            }
        } finally { image.recycle() }
    }

    @Test
    fun weakLeftSideUsesActualSquareCentersWithoutChangingWellNumbers() {
        // 复现半边弱光但不使用用户图作真值：左端只比局部背景高几级灰度，右端高一个数量级。
        // 全画幅留有背景，孔位有轻微倾斜；误差按固定孔号计算，不能用最近邻掩盖整列错位。
        val size = 15
        val pitch = 38.0
        val truth = List(size * size) { index ->
            val row = index / size
            val column = index % size
            GridPoint(266 + column * pitch + row * .52, 264 + row * pitch - column * .49)
        }
        val bitmap = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun drawArray(omitFirstColumn: Boolean) {
            canvas.drawColor(Color.rgb(5, 8, 4))
            paint.color = Color.rgb(11, 22, 7)
            canvas.drawRect(215f, 207f, 848f, 856f, paint)
            truth.forEachIndexed { index, center ->
                val column = index % size
                if (omitFirstColumn && column == 0) return@forEachIndexed
                val strength = 7 + (195 * (column / 14.0) * (column / 14.0)).toInt()
                paint.color = Color.rgb(11 + strength / 3, 22 + strength, 7 + strength / 6)
                val left = center.x.toFloat()
                val top = center.y.toFloat()
                canvas.drawRect(left - 8.5f, top - 8.5f, left + 8.5f, top + 8.5f, paint)
            }
        }
        val modes = listOf(ArrayLocatorMode.PROJECTION, ArrayLocatorMode.KMEANS, ArrayLocatorMode.SPOT_TOPOLOGY,
            ArrayLocatorMode.DBSCAN, ArrayLocatorMode.LOG_SPOTS)
        val config = PgGridLocatorConfig(size, size, GridTargetPolarity.BRIGHT)
        try {
            drawArray(omitFirstColumn = false)
            modes.forEach { mode ->
                val result = locator.locate(bitmap, config.copy(mode = mode))
                val errors = result.sites.zip(truth).map { (site, expected) ->
                    hypot(site.original.x - expected.x, site.original.y - expected.y) / pitch
                }.sorted()
                val p95 = errors[ceil(.95 * errors.size).toInt() - 1]
                Log.i("ArrayLocatorReport", "weak-squares $mode p95=$p95 observed=${result.geometry.inlierCount}")
                assertEquals(mode.name, result.locatorName)
                assertEquals(size * size, result.sites.size)
                assertTrue("$mode 同编号 P95=$p95", p95 <= .20)
                assertTrue("$mode 左边须有实际孔观测", result.sites.count {
                    it.key.columnIndex == 0 && it.source != GridPointSource.MODEL_IMPUTED
                } >= 2)
            }
            drawArray(omitFirstColumn = true)
            for (mode in listOf(ArrayLocatorMode.DBSCAN, ArrayLocatorMode.LOG_SPOTS)) {
                assertTrue("$mode 不能把一整列未观测孔当作已找到", runCatching {
                    locator.locate(bitmap, config.copy(mode = mode))
                }.exceptionOrNull() is TraditionalGridNotFound)
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun traditionalLocatorsUseTheirOwnEvidenceAndPreserveRectangularLabels() {
        // 真值来自画图函数，不来自另一个定位器。两种极性和非方阵分别检验，拒绝“只凑满点数”的假通过。
        val rows = 6
        val columns = 8
        val pitch = 74.0
        val centers = List(rows * columns) { index -> GridPoint(74 + index % columns * pitch, 74 + index / columns * pitch) }
        val image = Bitmap.createBitmap(666, 518, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        try {
            for (mode in listOf(ArrayLocatorMode.KMEANS, ArrayLocatorMode.PROJECTION, ArrayLocatorMode.SPOT_TOPOLOGY,
                ArrayLocatorMode.CIRCLES_GRID, ArrayLocatorMode.HOUGH_GRID, ArrayLocatorMode.ELLIPSE_GRID)) {
                val dark = mode != ArrayLocatorMode.SPOT_TOPOLOGY
                canvas.drawColor(if (dark) Color.rgb(210,210,210) else Color.rgb(10,10,10))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(28,28,28) else Color.rgb(38,116,36) }
                centers.forEach { center -> canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), 16f, paint) }
                val config = PgGridLocatorConfig(rows, columns, if (dark) GridTargetPolarity.DARK else GridTargetPolarity.BRIGHT, mode = mode)
                val result = locator.locate(image, config)
                val errors = result.sites.zip(centers).map { (site, truth) -> kotlin.math.hypot(
                    site.original.x - truth.x, site.original.y - truth.y) / pitch }.sorted()
                assertEquals(mode.name, result.locatorName)
                assertTrue("$mode P95=${errors[45]}", errors[45] <= .15)
                assertTrue("$mode 支撑率 ${result.geometry.observedRatio}", result.geometry.observedRatio >= .90)
                canvas.drawColor(Color.BLACK)
                assertTrue("$mode 不应在纯色图输出网格", runCatching { locator.locate(image, config) }.exceptionOrNull() is IllegalArgumentException)
            }
        } finally { image.recycle() }
    }

    @Test
    fun comparisonLocatorsUseRealEvidenceAcrossChipShapesAndRectangularRounds() = runBlocking {
        // 确定性的像素图和逐孔真值同时覆盖亮、暗、方形、圆形与非方阵；按原行列评分，不允许最近邻重编号。
        val pitch = 40.0
        for ((rows, columns) in listOf(10 to 10, 15 to 15, 6 to 8)) {
            val image = Bitmap.createBitmap(40 * (columns + 1), 40 * (rows + 1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            val bright = rows == 15
            canvas.drawColor(if (bright) Color.rgb(22, 22, 22) else Color.rgb(216, 216, 216))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (bright) Color.rgb(25, 155, 43) else Color.rgb(38, 38, 38) }
            val truth = List(rows * columns) { index -> GridPoint((index % columns + 1) * pitch, (index / columns + 1) * pitch) }
            truth.forEach { center ->
                val x = center.x.toFloat()
                val y = center.y.toFloat()
                if (rows == 10) canvas.drawRect(x - 9, y - 9, x + 9, y + 9, paint)
                else canvas.drawCircle(x, y, 9f, paint)
            }
            try {
                for (mode in listOf(ArrayLocatorMode.DBSCAN, ArrayLocatorMode.LOG_SPOTS)) {
                    val grid = requireNotNull(locator.compareOnImage(image, rows, columns, mode)) { "$mode ${rows}×$columns 无对照网格" }
                    val errors = grid.sites.zip(truth).map { (found, expected) ->
                        hypot(found.original.x - expected.x, found.original.y - expected.y) / pitch
                    }.sorted()
                    val p95 = errors[ceil(.95 * errors.size).toInt() - 1]
                    assertEquals(mode.name, grid.locatorName)
                    assertEquals(rows * columns, grid.sites.size)
                    assertFalse("几何预览通过也不能标为已验证", grid.geometry.trusted)
                    assertTrue("$mode ${rows}×$columns 同编号 P95=$p95", p95 <= .15)
                    assertTrue("$mode 应以图像观测为主", grid.geometry.observedRatio >= .90)
                }
            } finally { image.recycle() }
        }
    }

    @Test
    fun comparisonLocatorsRejectFlatAndSeededNoiseWithoutReturningUniformGrid() = runBlocking {
        val image = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        try {
            for (value in listOf(Color.BLACK, Color.WHITE, null)) {
                if (value != null) image.eraseColor(value) else {
                    val generator = Random(29173)
                    val pixels = IntArray(image.width * image.height) {
                        val gray = generator.nextInt(256)
                        Color.rgb(gray, gray, gray)
                    }
                    image.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                }
                for (mode in listOf(ArrayLocatorMode.DBSCAN, ArrayLocatorMode.LOG_SPOTS)) {
                    assertNull("$mode 未拒绝无孔对照 $value", locator.compareOnImage(image, 10, 10, mode))
                }
            }
        } finally { image.recycle() }
    }

    @Test
    fun sameCornerGridProducesFiveDifferentScientificReadouts() {
        // 在黑背景上放置明确的亮度阶梯。纯黑背景不能把新口径的平场校正乘成零。
        val image = Bitmap.createBitmap(260, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.BLACK)
        val centers = List(12) { index -> GridPoint(40.0 + index%4*60, 40.0 + index/4*60) }
        centers.forEach { point ->
            val left = point.x.toFloat()
            val top = point.y.toFloat()
            val paint = Paint().apply { color = Color.rgb(65,65,65) }
            canvas.drawRect(left-13, top-13, left+14, top+14, paint)
            paint.color = Color.rgb(160,160,160)
            canvas.drawRect(left-5, top-5, left+6, top+6, paint)
            image.setPixel(point.x.toInt(), point.y.toInt(), Color.rgb(225,225,225))
        }
        try {
            val grid = ManualGridGeometry.build(image.width,image.height,
                PgGridLocatorConfig(3,4,GridTargetPolarity.BRIGHT), listOf(centers[0],centers[3],centers[11],centers[8]))
            val features = WellReadoutMode.entries.associateWith { mode ->
                val quant = PgQuantSampler.sample(image, grid, PgQuantConfig(readoutMode = mode))
                val site = FluorescencePhotometryProcessor.process(quant,
                    FluorescenceProcessorConfig(FluorescenceChannel.GREEN, AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY)).sites[5]
                if (mode == WellReadoutMode.FIVE_REGION_MEAN) assertEquals(5, site.base.readout?.subregions?.size)
                if (mode == WellReadoutMode.CENTER_PIXEL) assertEquals(1, site.base.readout?.raw?.pixelCount)
                requireNotNull(site.primaryFeatureValue)
            }
            assertEquals(225.0, features.getValue(WellReadoutMode.CENTER_PIXEL), .01)
            assertTrue(features.getValue(WellReadoutMode.CENTER_MEAN) > features.getValue(WellReadoutMode.FULL_MEAN))
            assertTrue(features.getValue(WellReadoutMode.FIVE_REGION_MEAN) > 0.0)
        } finally { image.recycle() }
    }

    /** 从 instrumentation test APK 的 assets 读取图片，避免依赖设备图库或外部存储。 */
    private fun loadAssetBitmap(path: String): Bitmap {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open(path).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "无法解码测试图片：$path" }
        }
    }
}

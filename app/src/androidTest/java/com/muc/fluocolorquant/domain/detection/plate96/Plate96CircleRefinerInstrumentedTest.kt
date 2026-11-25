package com.muc.fluocolorquant.domain.detection.plate96

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationSource
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocator
import com.muc.fluocolorquant.domain.detection.grid.compareOnImage
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationUiState
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationViewModel
import com.muc.fluocolorquant.utils.image.ScientificBitmapLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import kotlin.math.abs

/** 在真实Android OpenCV运行时验证圆形精定位，不依赖YOLO模型输出。 */
@RunWith(AndroidJUnit4::class)
class Plate96CircleRefinerInstrumentedTest {

    @Test
    fun comparisonUsesTheCommonLocatorInPortraitAndCannotReplaceConfirmedA1() = runBlocking {
        val fixture = createFixture(8, 12)
        val portrait = Bitmap.createBitmap(fixture.bitmap, 0, 0, fixture.bitmap.width, fixture.bitmap.height,
            Matrix().apply { postRotate(90f) }, false)
        val device = InstrumentationRegistry.getInstrumentation()
        val context = device.targetContext
        val photo = java.io.File.createTempFile("plate-comparison", ".png", context.cacheDir)
        try {
            val shared = OpenCvPgGridLocator()
            val expected = fixture.centers.map { (x, y) -> GridPoint(fixture.bitmap.height - 1 - y, x) }
                .sortedWith(compareBy(GridPoint::y, GridPoint::x))
            for (mode in listOf(ArrayLocatorMode.DBSCAN, ArrayLocatorMode.LOG_SPOTS)) {
                val comparison = requireNotNull(shared.compareOnImage(portrait, 8, 12, mode, allowTransposed = true))
                assertEquals(12, comparison.rows)
                assertEquals(8, comparison.columns)
                assertFalse(comparison.geometry.trusted)
                val farthest = comparison.sites.zip(expected).maxOf { (site, truth) ->
                    kotlin.math.hypot(site.original.x - truth.x, site.original.y - truth.y)
                }
                assertTrue("$mode 不能用横拍行列冒充竖拍，最大误差 $farthest", farthest < 7)
            }
            val forbiddenYolo = object : Plate96ObjectDetector {
                override suspend fun detect(sourceBitmap: Bitmap, confidenceThreshold: Float, iouThreshold: Float): List<Plate96ObjectCandidate> =
                    error("孔缘定位和对照都不执行 YOLO")
            }
            fixture.bitmap.let { image -> photo.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            val formal = Plate96Locator(forbiddenYolo, Plate96CircleRefiner(), Plate96OrientationResolver(), Plate96GridAssembler())
            val viewModel = Plate96LocalizationViewModel(ScientificBitmapLoader(context), formal, shared)
            withTimeout(30_000) {
                withContext(Dispatchers.Main) {
                    viewModel.start(photo.absolutePath, com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisPreferences(locator = ArrayLocatorMode.HOUGH_GRID))
                }
                viewModel.uiState.filterIsInstance<Plate96LocalizationUiState.Ready>().first()
                val confirmed = withContext(Dispatchers.Main) {
                    viewModel.confirmCurrentOrientation()
                    requireNotNull(viewModel.currentSelection()).also { viewModel.setLocatorMode(ArrayLocatorMode.LOG_SPOTS) }
                }
                val shown = viewModel.uiState.filterIsInstance<Plate96LocalizationUiState.LocatingArray>().first {
                    it.attempt.status is com.muc.fluocolorquant.ui.viewmodels.ArrayLocatorAttemptStatus.Preview
                }
                assertSame(confirmed.sourceBitmap, shown.attempt.bitmap)
                val preview = shown.attempt.status as com.muc.fluocolorquant.ui.viewmodels.ArrayLocatorAttemptStatus.Preview
                assertEquals(ArrayLocatorMode.LOG_SPOTS.name, preview.grid.locatorName)
                withContext(Dispatchers.Main) {
                    viewModel.confirmCurrentOrientation()
                    assertNull(viewModel.currentSelection())
                    viewModel.restorePreviousLocalization()
                    assertSame(confirmed.session, viewModel.currentSelection()?.session)
                }
            }
        } finally {
            fixture.bitmap.recycle()
            portrait.recycle()
            photo.delete()
        }
    }

    @Test
    fun wholePlateRimAlgorithmsAndFourCornersNeverCallYolo() = runBlocking {
        val fixture = createFixture(8,12)
        val detector = object : Plate96ObjectDetector {
            override suspend fun detect(sourceBitmap: Bitmap, confidenceThreshold: Float, iouThreshold: Float): List<Plate96ObjectCandidate> =
                error("圆缘和人工四角不得调用 YOLO")
        }
        val locator = Plate96Locator(detector, Plate96CircleRefiner(), Plate96OrientationResolver(), Plate96GridAssembler())
        try {
            for (mode in listOf(ArrayLocatorMode.HOUGH_GRID, ArrayLocatorMode.ELLIPSE_GRID, ArrayLocatorMode.CIRCLES_GRID)) {
                val located = locator.localizeSession(fixture.bitmap, Plate96Locator.DEFAULT_CONFIG.copy(mode = mode))
                assertEquals(mode.name, located.result.locatorName)
                assertEquals(96, located.result.diagnostics.observedSiteCount)
                val worst = located.result.sites.zip(fixture.centers).maxOf { (site, truth) ->
                    kotlin.math.hypot(site.sourceCenter.x-truth.first,site.sourceCenter.y-truth.second)
                }
                assertTrue("$mode 同孔号最大误差=$worst", worst <= 7.0)
                assertTrue(located.orientationResolution.requiresOriginConfirmation)
            }
            val manual = locator.localizeManual(fixture.bitmap, listOf(0,11,95,84).map { fixture.centers[it].let { center -> GridPoint(center.first,center.second) } },.56)
            val persisted = Plate96RunGeometryCodec.decode(Plate96RunGeometryCodec.encode(Plate96RunGeometrySnapshot.from(manual.result)))
            assertEquals(4, persisted.sites.count { it.source == ArraySiteLocalizationSource.MANUAL_ANCHOR })
            assertEquals(92, persisted.sites.count { it.source == ArraySiteLocalizationSource.MANUAL_INTERPOLATED })
            assertEquals("H12", persisted.sites.last().displayLabel)
            assertEquals(0, persisted.diagnostics.observedSiteCount)
            assertEquals(4, Plate96ScientificSamplingAdapter.create(manual.result).grid.manualAnchorCenters?.size)
        } finally { fixture.bitmap.recycle() }
    }

    @Test
    fun syntheticPlate96_refinesAllCirclesAndBuildsCanonicalGrid() {
        val fixture = createFixture(rows = 8, columns = 12)
        val refined = Plate96CircleRefiner().refine(fixture.bitmap, fixture.candidates)

        assertEquals(96, refined.size)
        assertTrue(refined.count { it.source != Plate96CircleSource.BOX_FALLBACK } >= 90)
        refined.zip(fixture.centers).forEach { (circle, expected) ->
            assertTrue(abs(circle.centerX - expected.first) <= 4.0)
            assertTrue(abs(circle.centerY - expected.second) <= 4.0)
        }

        val observations = refined.mapIndexed { index, circle ->
            Plate96CircleObservation(
                x = circle.centerX,
                y = circle.centerY,
                radius = circle.radius,
                confidence = circle.confidence,
                observationIndex = index
            )
        }
        val orientation = Plate96OrientationResolver().resolve(observations)
        val result = Plate96GridAssembler().assemble(
            sourceWidth = fixture.bitmap.width,
            sourceHeight = fixture.bitmap.height,
            circles = refined,
            resolution = orientation
        )
        assertEquals(96, result.sites.size)
        assertEquals("A1", result.sites.first().displayLabel)
        assertEquals("H12", result.sites.last().displayLabel)
        fixture.bitmap.recycle()
    }

    private fun createFixture(rows: Int, columns: Int): Fixture {
        val width = 1200
        val height = 800
        val radius = 24f
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(238, 238, 238))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 35)
            style = Paint.Style.FILL
        }
        val centers = mutableListOf<Pair<Double, Double>>()
        val candidates = mutableListOf<Plate96ObjectCandidate>()
        var index = 0
        repeat(rows) { row ->
            repeat(columns) { column ->
                val centerX = 110f + column * 88f
                val centerY = 105f + row * 82f
                canvas.drawCircle(centerX, centerY, radius, paint)
                centers += centerX.toDouble() to centerY.toDouble()
                candidates += Plate96ObjectCandidate(
                    candidateIndex = index++,
                    bounds = ArrayImageBounds(
                        left = centerX - 32.0,
                        top = centerY - 32.0,
                        right = centerX + 32.0,
                        bottom = centerY + 32.0
                    ),
                    confidence = 0.96
                )
            }
        }
        return Fixture(bitmap, candidates, centers)
    }

    private data class Fixture(
        val bitmap: Bitmap,
        val candidates: List<Plate96ObjectCandidate>,
        val centers: List<Pair<Double, Double>>
    )

    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeOpenCv() {
            check(OpenCVLoader.initDebug()) { "OpenCV初始化失败" }
        }
    }
}

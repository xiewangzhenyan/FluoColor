package com.muc.fluocolorquant.domain.detection.plate96

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import org.junit.Assert.assertEquals
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

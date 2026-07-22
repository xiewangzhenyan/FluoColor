package com.muc.fluocolorquant.domain.detection.photometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridHomography
import com.muc.fluocolorquant.domain.detection.grid.GridLocalizedSite
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridSiteKey
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实 Bitmap 验证原图采样、背景扣除和平场校正。 */
@RunWith(AndroidJUnit4::class)
class PgQuantSamplerImageTest {

    @Test
    fun `背景环扣除恢复四个位点的已知灰度信号`() {
        val centers = listOf(
            GridPoint(80.0, 80.0),
            GridPoint(220.0, 80.0),
            GridPoint(80.0, 220.0),
            GridPoint(220.0, 220.0)
        )
        val absoluteLevels = listOf(70, 90, 110, 130)
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also { image ->
            val canvas = Canvas(image)
            canvas.drawColor(Color.rgb(40, 40, 40))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            centers.zip(absoluteLevels).forEach { (center, level) ->
                paint.color = Color.rgb(level, level, level)
                canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), 22f, paint)
            }
        }

        val result = PgQuantSampler.sample(bitmap, identityGrid(centers, rows = 2, columns = 2))

        assertEquals(4, result.sites.size)
        result.sites.zip(absoluteLevels).forEach { (site, level) ->
            assertEquals(level - 40.0, site.signalGray, 1.5)
            assertFalse(PhotometryFlag.ROI_OUT_OF_BOUNDS in site.qc.flags)
            assertTrue(site.qc.qualityReliable)
        }
    }

    @Test
    fun `二阶平场校正降低横向乘性光照梯度造成的信号离散`() {
        val rows = 4
        val columns = 4
        val centers = buildList {
            repeat(rows) { row ->
                repeat(columns) { column ->
                    add(GridPoint(60.0 + column * 70.0, 60.0 + row * 70.0))
                }
            }
        }
        val bitmap = Bitmap.createBitmap(330, 330, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height) { index ->
            val x = index % bitmap.width
            val background = 45.0 + 45.0 * x / (bitmap.width - 1).toDouble()
            val level = background.toInt().coerceIn(0, 255)
            Color.rgb(level, level, level)
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        centers.forEach { center ->
            val localBackground = 45.0 + 45.0 * center.x / (bitmap.width - 1).toDouble()
            // 真实信号随照明强度等比例变化，正确平场后应恢复到接近同一水平。
            val rawSignal = 28.0 * localBackground / 67.5
            val level = (localBackground + rawSignal).toInt().coerceIn(0, 255)
            paint.color = Color.rgb(level, level, level)
            canvas.drawCircle(center.x.toFloat(), center.y.toFloat(), 10f, paint)
        }

        val result = PgQuantSampler.sample(bitmap, identityGrid(centers, rows, columns))
        val rawSignals = result.sites.map(BaseSitePhotometry::signalGray)
        val correctedSignals = result.sites.map(BaseSitePhotometry::correctedSignalGray)

        assertEquals("poly2", result.illuminationModel)
        assertTrue(standardDeviation(correctedSignals) < standardDeviation(rawSignals) * 0.55)
    }

    /** 构造原图与矫正图坐标完全一致的定位结果，使测试只关注光度算法。 */
    private fun identityGrid(
        centers: List<GridPoint>,
        rows: Int,
        columns: Int
    ): PgGridResult {
        val identity = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val sites = centers.mapIndexed { index, center ->
            GridLocalizedSite(
                key = GridSiteKey(index / columns, index % columns),
                siteIndex = index,
                rectified = center,
                original = center,
                confidence = 1.0,
                source = GridPointSource.CANDIDATE_REFINED,
                flags = emptySet()
            )
        }
        return PgGridResult(
            rows = rows,
            columns = columns,
            rectifiedWidth = 330,
            rectifiedHeight = 330,
            targetPolarity = GridTargetPolarity.BRIGHT,
            chipRegionMethod = "test_identity",
            chipCorners = listOf(
                GridPoint(0.0, 0.0),
                GridPoint(329.0, 0.0),
                GridPoint(329.0, 329.0),
                GridPoint(0.0, 329.0)
            ),
            homography = GridHomography(identity, identity),
            sites = sites,
            geometry = GridGeometryDiagnostics(
                candidateSupportRatio = 1.0,
                trusted = true,
                observedRatio = 1.0,
                geometryRmsePx = 0.0,
                inlierCount = sites.size,
                outlierCount = 0,
                meanConfidence = 1.0
            ),
            frameQc = emptyList(),
            locatorName = "test",
            locatorVersion = "1"
        ).requireValid()
    }

    private fun standardDeviation(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.map { (it - mean) * (it - mean) }.average())
    }
}

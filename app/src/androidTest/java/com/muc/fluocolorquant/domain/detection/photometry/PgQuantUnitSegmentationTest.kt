package com.muc.fluocolorquant.domain.detection.photometry

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridHomography
import com.muc.fluocolorquant.domain.detection.grid.GridLocalizedSite
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridSiteKey
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import com.muc.fluocolorquant.domain.detection.segmentation.OpenCvArrayUnitSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/** 验证紧致裁切不只用于预览，而是真正改变比色/荧光共享的基础前景采样。 */
@RunWith(AndroidJUnit4::class)
class PgQuantUnitSegmentationTest {

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
    }

    @Test
    fun `小方块信号使用紧致区域而不是被固定圆形ROI背景淹没`() {
        val bitmap = fixtureBitmap()
        val grid = identityGrid()
        val segmentation = OpenCvArrayUnitSegmenter().segmentRectified(
            rectifiedBitmap = bitmap,
            points = grid.sites.map { it.rectified },
            rows = grid.rows,
            columns = grid.columns,
            polarity = GridTargetPolarity.DARK,
            shape = ArrayUnitShape.SQUARE
        )

        val legacy = PgQuantSampler.sample(bitmap, grid)
        val segmented = PgQuantSampler.sample(bitmap, grid, unitSegmentation = segmentation)

        assertEquals(PG_QUANT_LEGACY_PROCESSOR_VERSION, legacy.processorVersion)
        assertEquals(PG_QUANT_PROCESSOR_VERSION, segmented.processorVersion)
        assertTrue(legacy.sites.all { it.roiMedianGray > 200.0 })
        assertTrue(segmented.sites.all { kotlin.math.abs(it.roiMedianGray - 30.0) < 0.5 })
        assertEquals(4, segmented.unitSegmentation?.segmentedCount)
        bitmap.recycle()
    }

    private fun fixtureBitmap(): Bitmap {
        val width = 160
        val height = 160
        val pixels = IntArray(width * height) { Color.rgb(220, 220, 220) }
        centers.forEach { center ->
            for (y in center.y.toInt() - 6 until center.y.toInt() + 6) {
                for (x in center.x.toInt() - 6 until center.x.toInt() + 6) {
                    pixels[y * width + x] = Color.rgb(30, 30, 30)
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun identityGrid(): PgGridResult {
        val sites = centers.mapIndexed { index, point ->
            GridLocalizedSite(
                key = GridSiteKey(index / 2, index % 2),
                siteIndex = index,
                rectified = point,
                original = point,
                confidence = 1.0,
                source = GridPointSource.CANDIDATE_REFINED,
                flags = emptySet()
            )
        }
        return PgGridResult(
            rows = 2,
            columns = 2,
            rectifiedWidth = 160,
            rectifiedHeight = 160,
            targetPolarity = GridTargetPolarity.DARK,
            chipRegionMethod = "test_identity",
            chipCorners = listOf(
                GridPoint(0.0, 0.0),
                GridPoint(159.0, 0.0),
                GridPoint(159.0, 159.0),
                GridPoint(0.0, 159.0)
            ),
            homography = GridHomography(
                forward = IDENTITY_HOMOGRAPHY,
                inverse = IDENTITY_HOMOGRAPHY
            ),
            sites = sites,
            geometry = GridGeometryDiagnostics(
                candidateSupportRatio = 1.0,
                trusted = true,
                observedRatio = 1.0,
                geometryRmsePx = 0.0,
                inlierCount = 4,
                outlierCount = 0,
                meanConfidence = 1.0
            ),
            frameQc = emptyList(),
            locatorName = "test",
            locatorVersion = "1"
        ).requireValid()
    }

    private companion object {
        val centers = listOf(
            GridPoint(40.0, 40.0),
            GridPoint(120.0, 40.0),
            GridPoint(40.0, 120.0),
            GridPoint(120.0, 120.0)
        )
        val IDENTITY_HOMOGRAPHY = listOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )
    }
}

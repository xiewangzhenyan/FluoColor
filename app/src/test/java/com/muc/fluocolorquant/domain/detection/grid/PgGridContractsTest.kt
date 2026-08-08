package com.muc.fluocolorquant.domain.detection.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PG-Grid V2.1 领域契约测试。
 *
 * 这些测试首先冻结与具体 OpenCV 实现无关的科学语义，避免后续移植算法时重新引入
 * Python 旧版单一 grid_size 的方阵假设，或者丢失模型补位点的置信度和来源信息。
 */
class PgGridContractsTest {

    @Test
    fun `十乘十五契约必须包含一百五十个行优先位点`() {
        val result = createResult(rows = 10, columns = 15)

        assertEquals(150, result.sites.size)
        assertEquals(GridSiteKey(rowIndex = 0, columnIndex = 0), result.sites.first().key)
        assertEquals(GridSiteKey(rowIndex = 9, columnIndex = 14), result.sites.last().key)
        assertEquals(149, result.sites.last().siteIndex)
    }

    @Test
    fun `模型补位点固定使用低置信度与补位标志`() {
        val site = GridLocalizedSite.modelImputed(
            key = GridSiteKey(rowIndex = 2, columnIndex = 3),
            siteIndex = 23,
            rectified = GridPoint(x = 40.0, y = 50.0),
            original = GridPoint(x = 140.0, y = 150.0)
        )

        assertEquals(0.3, site.confidence, 1e-9)
        assertEquals(GridPointSource.MODEL_IMPUTED, site.source)
        assertTrue(GridSiteFlag.IMPUTED_POSITION in site.flags)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `点数与行列不一致时拒绝构造科学结果`() {
        createResult(rows = 2, columns = 3).copy(
            sites = createResult(rows = 2, columns = 3).sites.dropLast(1)
        ).requireValid()
    }

    /** 构造不依赖图像算法的规则契约样本，供本文件多个测试复用。 */
    private fun createResult(rows: Int, columns: Int): PgGridResult {
        val sites = List(rows * columns) { index ->
            val row = index / columns
            val column = index % columns
            GridLocalizedSite(
                key = GridSiteKey(rowIndex = row, columnIndex = column),
                siteIndex = index,
                rectified = GridPoint(x = column * 20.0, y = row * 20.0),
                original = GridPoint(x = column * 20.0 + 5.0, y = row * 20.0 + 8.0),
                confidence = 0.95,
                source = GridPointSource.CANDIDATE_REFINED,
                flags = emptySet()
            )
        }
        return PgGridResult(
            rows = rows,
            columns = columns,
            rectifiedWidth = 640,
            rectifiedHeight = 480,
            targetPolarity = GridTargetPolarity.DARK,
            chipRegionMethod = "opencv_bright_region",
            chipCorners = listOf(
                GridPoint(0.0, 0.0),
                GridPoint(639.0, 0.0),
                GridPoint(639.0, 479.0),
                GridPoint(0.0, 479.0)
            ),
            homography = GridHomography(
                forward = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                inverse = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            ),
            sites = sites,
            geometry = GridGeometryDiagnostics(
                candidateSupportRatio = 0.95,
                trusted = true,
                observedRatio = 0.95,
                geometryRmsePx = 0.8,
                inlierCount = sites.size,
                outlierCount = 0,
                meanConfidence = 0.95
            ),
            frameQc = emptyList(),
            locatorName = "PG-Grid",
            locatorVersion = "2.1.0"
        ).requireValid()
    }
}

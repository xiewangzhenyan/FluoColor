package com.muc.fluocolorquant.domain.detection.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PG-Grid JSON 边界测试。
 *
 * 数据会进入运行快照、历史结果和 Android/Python 对照文件，因此解码必须严格失败，
 * 不能在字段损坏时自动补一个默认网格继续输出看似合理的科学结果。
 */
class PgGridJsonCodecTest {

    @Test
    fun `编解码保留非方阵位点来源和标志`() {
        val original = smallResult()

        val restored = PgGridJsonCodec.decode(PgGridJsonCodec.encode(original))

        assertEquals(2, restored.rows)
        assertEquals(3, restored.columns)
        assertEquals(6, restored.sites.size)
        assertEquals(GridPointSource.MODEL_IMPUTED, restored.sites.last().source)
        assertTrue(GridSiteFlag.IMPUTED_POSITION in restored.sites.last().flags)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `未知schema必须拒绝解码`() {
        val invalidJson = PgGridJsonCodec.encode(smallResult())
            .replace(PG_GRID_SCHEMA_V2_1, "pg-grid-v9")

        PgGridJsonCodec.decode(invalidJson)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空JSON必须拒绝解码`() {
        PgGridJsonCodec.decode("   ")
    }

    /** 构造包含一个模型补位点的 2×3 契约样本。 */
    private fun smallResult(): PgGridResult {
        val sites = List(6) { index ->
            val key = GridSiteKey(rowIndex = index / 3, columnIndex = index % 3)
            if (index == 5) {
                GridLocalizedSite.modelImputed(
                    key = key,
                    siteIndex = index,
                    rectified = GridPoint(50.0, 30.0),
                    original = GridPoint(55.0, 38.0)
                )
            } else {
                GridLocalizedSite(
                    key = key,
                    siteIndex = index,
                    rectified = GridPoint((index % 3) * 25.0, (index / 3) * 25.0),
                    original = GridPoint((index % 3) * 25.0 + 5.0, (index / 3) * 25.0 + 8.0),
                    confidence = 0.9,
                    source = GridPointSource.CANDIDATE_REFINED,
                    flags = emptySet()
                )
            }
        }
        return PgGridResult(
            rows = 2,
            columns = 3,
            rectifiedWidth = 100,
            rectifiedHeight = 80,
            targetPolarity = GridTargetPolarity.BRIGHT,
            chipRegionMethod = "opencv_bright_region_wide",
            chipCorners = listOf(
                GridPoint(1.0, 2.0),
                GridPoint(90.0, 2.0),
                GridPoint(90.0, 70.0),
                GridPoint(1.0, 70.0)
            ),
            homography = GridHomography(
                forward = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                inverse = listOf(1.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            ),
            sites = sites,
            geometry = GridGeometryDiagnostics(
                candidateSupportRatio = 5.0 / 6.0,
                trusted = true,
                observedRatio = 5.0 / 6.0,
                geometryRmsePx = 1.2,
                inlierCount = 5,
                outlierCount = 1,
                meanConfidence = sites.map { it.confidence }.average()
            ),
            frameQc = listOf(
                GridFrameQcIssue(
                    code = GridFrameQcCode.HIGH_IMPUTED_RATIO,
                    severity = GridQcSeverity.WARNING
                )
            ),
            locatorName = "PG-Grid",
            locatorVersion = "2.1.0"
        ).requireValid()
    }
}

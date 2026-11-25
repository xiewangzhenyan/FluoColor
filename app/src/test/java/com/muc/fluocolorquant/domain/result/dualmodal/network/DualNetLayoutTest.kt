package com.muc.fluocolorquant.domain.result.dualmodal.network

import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetTestFixtures.ANALYTE
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetTestFixtures.LEVELS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统版面到 DualNet 训练版面的映射：同行参照孔、样本分组与重标定水平。 */
class DualNetLayoutTest {

    @Test
    fun `W2 版面的每个样本取同一行的空白与两只阳控作参照`() {
        val groups = DualNetLayout.sampleGroups(DualNetTestFixtures.run("col", "COLORIMETRIC"), ANALYTE)

        assertEquals((1..12).map { "S%02d".format(it) }, groups.map { it.key })
        assertTrue(groups.all { it.sites.size == 15 })
        val s05 = groups.first { it.key == "S05" }
        s05.sites.forEach { refs ->
            val row = refs.site / 15
            assertEquals(row * 15 + 5, refs.site)
            assertEquals(row * 15 + 0, refs.negative)
            assertEquals(row * 15 + 13, refs.qcMid)
            assertEquals(row * 15 + 14, refs.qcHigh)
        }
    }

    @Test
    fun `缺少高水平阳控的行不送入网络`() {
        val groups = DualNetLayout.sampleGroups(DualNetTestFixtures.run("col", "COLORIMETRIC", dropQcHighInRow = 3), ANALYTE)

        val s01 = groups.first { it.key == "S01" }
        assertEquals(14, s01.sites.size)
        assertTrue(s01.sites.none { it.site / 15 == 3 })
    }

    @Test
    fun `重标定只取 1点4 到 219 ng每mL 之间的标准水平`() {
        val levels = DualNetLayout.standardLevels(DualNetTestFixtures.run("cal", "COLORIMETRIC", standards = true), ANALYTE)

        assertEquals(LEVELS.subList(4, 11), levels.map { it.level })
        assertTrue(levels.all { it.sites.size == 15 })
    }

    @Test
    fun `不是 15×15 的阵列不适用`() {
        val small = DualNetTestFixtures.run("col", "COLORIMETRIC", size = 10)

        assertTrue(DualNetLayout.sampleGroups(small, ANALYTE).isEmpty())
        assertTrue(DualNetLayout.standardLevels(small, ANALYTE).isEmpty())
    }

    @Test
    fun `从冻结快照追溯曲线的标定运行，排除的标定点不计`() {
        val snapshot = DualNetTestFixtures.run("col", "COLORIMETRIC", calibrationRunIds = listOf("cal-a", "cal-a"))

        assertEquals(setOf("cal-a"), DualNetCalibrationRuns.runIds(snapshot, ANALYTE))
        assertTrue(DualNetCalibrationRuns.runIds(snapshot, "other").isEmpty())
        val excluded = snapshot.copy(
            effectiveConfigSnapshotJson = """{"analytes":[{"analyte":{"id":"cea"},"analysisModel":{"calibrationPoints":[""" +
                """{"runId":"cal-b","excluded":true},{"runId":null}]}}]}"""
        )
        assertTrue(DualNetCalibrationRuns.runIds(excluded, ANALYTE).isEmpty())
        assertTrue(DualNetCalibrationRuns.runIds(snapshot.copy(effectiveConfigSnapshotJson = "not json"), ANALYTE).isEmpty())
    }
}

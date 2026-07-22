package com.muc.fluocolorquant.utils.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/** 工作包 6 的通用网格与旧 96 孔兼容回归测试。 */
class GridLayoutCompatibilityTest {

    @Test
    fun `十乘十十五乘十五和非方阵索引均可往返`() {
        listOf(
            GridDimensions(10, 10),
            GridDimensions(15, 15),
            GridDimensions(4, 6),
            GridDimensions(8, 12)
        ).forEach { dimensions ->
            val lastIndex = dimensions.siteCount - 1
            val coordinates = WellMappingUtils.mapRealToVirtualCoordinates(
                realIndex = lastIndex,
                columns = dimensions.columns
            )

            assertEquals(dimensions.rows - 1, coordinates.first)
            assertEquals(dimensions.columns - 1, coordinates.second)
            assertEquals(
                lastIndex,
                WellMappingUtils.mapVirtualToRealIndex(
                    virtualRow = coordinates.first,
                    virtualCol = coordinates.second,
                    columns = dimensions.columns
                )
            )
        }
    }

    @Test
    fun `旧无模板十二乘八项目显式恢复为八乘十二`() {
        assertEquals(
            GridDimensions(8, 12),
            GridLayoutPolicy.resolve(rows = 12, columns = 8, isTemplateBacked = false)
        )

        // 新模板载体的方向是科学配置的一部分，不能套用旧项目兼容规则。
        assertEquals(
            GridDimensions(12, 8),
            GridLayoutPolicy.resolve(rows = 12, columns = 8, isTemplateBacked = true)
        )
    }

    @Test
    fun `尺寸校验允许十五乘十五并拒绝零和超过九十九的边长`() {
        assertTrue(GridLayoutPolicy.isValid(rows = 15, columns = 15))
        assertTrue(GridLayoutPolicy.isValid(rows = 99, columns = 99))
        assertFalse(GridLayoutPolicy.isValid(rows = 0, columns = 10))
        assertFalse(GridLayoutPolicy.isValid(rows = 10, columns = 100))
    }

    @Test
    fun `孔板标签支持超过二十六行并可逆解析`() {
        assertEquals("A1", WellMappingUtils.getWellLabel(row = 0, col = 0))
        assertEquals("Z12", WellMappingUtils.getWellLabel(row = 25, col = 11))
        assertEquals("AA1", WellMappingUtils.getWellLabel(row = 26, col = 0))
        assertEquals(26 to 0, WellMappingUtils.parseWellLabel("aa1"))
        assertNull(WellMappingUtils.parseWellLabel("R01C01"))
    }

    @Test
    fun `非法坐标不会被静默映射到其他位点`() {
        assertThrows(IllegalArgumentException::class.java) {
            WellMappingUtils.mapVirtualToRealIndex(
                virtualRow = 0,
                virtualCol = 10,
                columns = 10
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WellMappingUtils.mapRealToVirtualCoordinates(realIndex = -1, columns = 10)
        }
    }
}

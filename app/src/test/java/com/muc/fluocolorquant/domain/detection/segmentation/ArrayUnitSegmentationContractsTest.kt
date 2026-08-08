package com.muc.fluocolorquant.domain.detection.segmentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通用阵列单元边界、形状掩膜和数量强契约的纯 JVM 测试。 */
class ArrayUnitSegmentationContractsTest {

    @Test
    fun `方形几何掩膜覆盖整个紧致框`() {
        val mask = ArrayUnitBitmapCropper.geometryMask(5, 4, ArrayUnitShape.SQUARE)

        assertEquals(20, mask.size)
        assertTrue(mask.all { it.toInt() != 0 })
    }

    @Test
    fun `圆形几何掩膜排除外接框四角但保留中心`() {
        val mask = ArrayUnitBitmapCropper.geometryMask(9, 9, ArrayUnitShape.CIRCLE)

        assertEquals(0, mask.first().toInt())
        assertEquals(0, mask.last().toInt())
        assertTrue(mask[4 * 9 + 4].toInt() != 0)
    }

    @Test
    fun `区域包含判断同时遵守边界和前景掩膜`() {
        val region = ArrayUnitBitmapCropper.detectedGeometryRegion(
            siteIndex = 0,
            rowIndex = 0,
            columnIndex = 0,
            shape = ArrayUnitShape.CIRCLE,
            bounds = ArrayUnitBounds(10, 20, 19, 29)
        )

        assertTrue(region.contains(14, 24))
        assertFalse(region.contains(10, 20))
        assertFalse(region.contains(9, 24))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `阵列区域数量不等于行列乘积时拒绝结果`() {
        ArrayUnitSegmentationResult(
            rows = 2,
            columns = 2,
            imageWidth = 100,
            imageHeight = 100,
            pitchPx = 30.0,
            medianWidthPx = 12,
            medianHeightPx = 12,
            regions = listOf(
                ArrayUnitBitmapCropper.detectedGeometryRegion(
                    siteIndex = 0,
                    rowIndex = 0,
                    columnIndex = 0,
                    shape = ArrayUnitShape.SQUARE,
                    bounds = ArrayUnitBounds(10, 10, 22, 22)
                )
            )
        ).requireValid()
    }
}

package com.muc.fluocolorquant.utils.math

import org.junit.Assert.assertEquals
import org.junit.Test

/** 旧 YOLO/霍夫圆兼容链的通用行优先排序回归测试。 */
class GridPointOrderingTest {

    private data class Point(val id: Int, val x: Float, val y: Float)

    @Test
    fun `排序不依赖检测器输出顺序也不包含八乘十二先验`() {
        val rows = 12
        val columns = 8
        val shuffled = buildList {
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    add(Point(id = row * columns + column, x = column * 40f, y = row * 40f))
                }
            }
        }.shuffled(kotlin.random.Random(20260720))

        val result = GridPointOrdering.sortRowMajor(
            items = shuffled,
            xSelector = Point::x,
            ySelector = Point::y,
            rowTolerance = 20f
        )

        assertEquals(rows, result.observedRows)
        assertEquals((0 until rows * columns).toList(), result.items.map(Point::id))
    }

    @Test
    fun `十五乘十五完整网格保持二百二十五个位点行优先顺序`() {
        val points = List(225) { index ->
            Point(id = index, x = (index % 15) * 30f, y = (index / 15) * 30f)
        }.reversed()

        val result = GridPointOrdering.sortRowMajor(
            items = points,
            xSelector = Point::x,
            ySelector = Point::y,
            rowTolerance = 15f
        )

        assertEquals(15, result.observedRows)
        assertEquals((0 until 225).toList(), result.items.map(Point::id))
    }
}

package com.muc.fluocolorquant.domain.detection.grid

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 鲁棒单应晶格平差测试。
 *
 * 测试同时包含真实透视和 20 个大偏移离群点，确保实现不是普通最小二乘，也不会用
 * “平均看起来还行”的矩阵掩盖被遮挡/误检的位点。
 */
class RobustHomographyFitterTest {

    @Test
    fun `Tukey迭代平差恢复透视晶格并压低离群点权重`() {
        val source = RegularGridGeometry.generate(
            rows = 10,
            columns = 15,
            width = 700.0,
            height = 500.0,
            marginRatio = 0.08
        )
        val trueMatrix = doubleArrayOf(
            1.04, 0.035, 18.0,
            -0.025, 1.08, 14.0,
            0.00018, -0.00012, 1.0
        )
        val trueDestination = source.map { RegularGridGeometry.project(trueMatrix, it) }
        val outlierIndices = (0 until 20).map { (it * 7 + 3) % source.size }.toSet()
        val observed = trueDestination.mapIndexed { index, point ->
            if (index in outlierIndices) {
                GridPoint(point.x + 42.0, point.y - 36.0)
            } else {
                // 小幅确定性扰动模拟亚像素候选中心误差，避免测试只覆盖完美数据。
                GridPoint(
                    point.x + ((index % 5) - 2) * 0.08,
                    point.y + ((index % 7) - 3) * 0.06
                )
            }
        }

        val result = RobustHomographyFitter.fit(
            source = source,
            destination = observed,
            expectedSiteCount = source.size
        )

        assertTrue("鲁棒单应拟合应成功，实际结果为 $result", result is HomographyFitResult.Success)
        result as HomographyFitResult.Success
        val meanError = source.indices.map { index ->
            val predicted = RegularGridGeometry.project(result.matrix, source[index])
            hypot(
                predicted.x - trueDestination[index].x,
                predicted.y - trueDestination[index].y
            )
        }.average()
        assertTrue("平差后平均误差应小于 1.5 px，实际为 $meanError", meanError < 1.5)
        outlierIndices.forEach { index ->
            assertTrue("离群点 $index 的 Tukey 权重应接近 0", result.weights[index] < 0.1)
        }
        assertTrue(result.inlierIndices.size >= source.size - outlierIndices.size - 3)
    }

    @Test
    fun `有效观测不足总位点四成时明确拒绝拟合`() {
        val allSource = RegularGridGeometry.generate(
            rows = 10,
            columns = 15,
            width = 700.0,
            height = 500.0,
            marginRatio = 0.08
        )
        val source = allSource.take(59)
        val destination = source.map { GridPoint(it.x + 10.0, it.y + 20.0) }

        val result = RobustHomographyFitter.fit(
            source = source,
            destination = destination,
            expectedSiteCount = allSource.size
        )

        assertTrue(result is HomographyFitResult.InsufficientObservations)
        result as HomographyFitResult.InsufficientObservations
        assertEquals(60, result.minimumRequired)
        assertEquals(59, result.actualCount)
    }
}

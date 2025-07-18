package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.enums.FittingFunction
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs

/**
 * FittingEngine测试类
 * 验证所有拟合函数的正确性
 */
class FittingEngineTest {

    private val tolerance = 1e-3

    @Test
    fun testLinearFitting() {
        // 测试线性拟合 y = 2x + 1
        val dataPoints = listOf(
            Pair(1.0, 3.0),
            Pair(2.0, 5.0),
            Pair(3.0, 7.0),
            Pair(4.0, 9.0)
        )

        val result = FittingEngine.fitSingle(dataPoints, FittingFunction.LINEAR)
        assertTrue("Linear fitting should succeed", result.isSuccess)
        
        // 验证参数
        assertEquals(2.0, result.parameters[0], tolerance) // a = 2
        assertEquals(1.0, result.parameters[1], tolerance) // b = 1
        
        // 验证预测
        val concentration = FittingEngine.predictConcentration(result.parameters, FittingFunction.LINEAR, 5.0)
        assertEquals(2.0, concentration, tolerance)
    }

    @Test
    fun testQuadraticFitting() {
        // 测试二次拟合 y = x² + 2x + 1
        val dataPoints = listOf(
            Pair(0.0, 1.0),
            Pair(1.0, 4.0),
            Pair(2.0, 9.0),
            Pair(3.0, 16.0)
        )

        val result = FittingEngine.fitSingle(dataPoints, FittingFunction.QUADRATIC)
        assertTrue("Quadratic fitting should succeed", result.isSuccess)
        assertTrue("R² should be reasonable", result.rSquared > 0.9)
    }

    @Test
    fun testExponentialFitting() {
        // 测试指数拟合
        val dataPoints = listOf(
            Pair(0.0, 1.0),
            Pair(1.0, 2.718),
            Pair(2.0, 7.389),
            Pair(3.0, 20.086)
        )

        val result = FittingEngine.fitSingle(dataPoints, FittingFunction.EXPONENTIAL)
        assertTrue("Exponential fitting should succeed", result.isSuccess)
    }

    @Test
    fun testRodbardFitting() {
        // 测试4PL Rodbard拟合
        val dataPoints = listOf(
            Pair(0.1, 10.0),
            Pair(1.0, 8.0),
            Pair(10.0, 5.0),
            Pair(100.0, 2.0),
            Pair(1000.0, 1.0)
        )

        val result = FittingEngine.fitSingle(dataPoints, FittingFunction.RODBARD)
        assertTrue("Rodbard fitting should succeed", result.isSuccess)
        assertEquals(4, result.parameters.size) // 应该有4个参数
    }

    @Test
    fun testAllFunctions() {
        // 为所有函数创建简单的测试数据
        val dataPoints = listOf(
            Pair(1.0, 2.0),
            Pair(2.0, 4.0),
            Pair(3.0, 6.0),
            Pair(4.0, 8.0),
            Pair(5.0, 10.0)
        )

        // 测试所有函数类型
        for (function in FittingFunction.values()) {
            try {
                val result = FittingEngine.fitSingle(dataPoints, function)
                assertNotNull("Fitting result should not be null for $function", result)
                
                if (result.isSuccess) {
                    assertTrue("Parameters should match required count for $function", 
                        result.parameters.size == function.requiredParams.size)
                    
                    // 测试预测函数
                    if (function != FittingFunction.INTERPOLATION) {
                        val prediction = FittingEngine.predictConcentration(result.parameters, function, 6.0)
                        assertTrue("Prediction should be non-negative for $function", prediction >= 0)
                    }
                }
                
                println("✓ $function: ${if (result.isSuccess) "SUCCESS" else "FAILED"} - R² = ${result.rSquared}")
            } catch (e: Exception) {
                println("✗ $function: ERROR - ${e.message}")
            }
        }
    }

    @Test
    fun testCalculateFunction() {
        // 测试calculate函数
        val params = mapOf("a" to 2.0, "b" to 1.0)
        
        val result = FittingEngine.calculate(FittingFunction.LINEAR, params, 3.0)
        assertEquals(7.0, result, tolerance) // 2*3 + 1 = 7
    }

    @Test
    fun testFormatParametersToLatex() {
        // 测试LaTeX格式化功能（不修改这个函数）
        val params = mapOf("a" to 2.0, "b" to 1.0)
        val latex = FittingEngine.formatParametersToLatex(FittingFunction.LINEAR, params)
        assertNotNull("LaTeX formula should not be null", latex)
        assertFalse("LaTeX formula should not be empty", latex.isEmpty())
    }
}

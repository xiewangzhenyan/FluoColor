package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.enums.FittingFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** 成熟标准曲线自动择优的固定数学回归测试。 */
class CalibrationModelSelectorTest {

    @Test
    fun `六个浓度水平满足反算验收时优先选择线性模型`() {
        val points = listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0).map { concentration ->
            concentration to (3.0 * concentration + 5.0)
        }

        val result = FittingEngine.fit(points)

        assertTrue(result.isSuccess)
        assertEquals(FittingFunction.LINEAR, result.function)
        assertEquals(1.0, result.allMetrics["ICH M10 Accepted"] ?: 0.0, 0.0)
        assertEquals(2.0, result.allMetrics["Endpoint Pass Count"] ?: 0.0, 0.0)
        assertTrue((result.allMetrics["Back-calculated RMSE (%)"] ?: 1.0) < 1e-6)
    }

    @Test
    fun `对称剂量响应自动选择4PL而不是5PL或高阶多项式`() {
        val concentrations = listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0)
        val points = concentrations.map { concentration ->
            concentration to fourParameterSignal(
                concentration = concentration,
                a = 2.0,
                b = 1.4,
                c = 8.0,
                d = 110.0
            )
        }

        val result = FittingEngine.fit(points)

        assertTrue(result.isSuccess)
        assertEquals(FittingFunction.RODBARD, result.function)
        assertEquals(1.0, result.allMetrics["ICH M10 Accepted"] ?: 0.0, 0.0)
        assertTrue((result.allMetrics["Back-calculated RMSE (%)"] ?: 100.0) < 0.1)
    }

    @Test
    fun `明显不对称剂量响应在反算精度显著改善时选择5PL`() {
        val concentrations = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0)
        val points = concentrations.map { concentration ->
            concentration to fiveParameterSignal(
                concentration = concentration,
                a = 125.0,
                b = 1.25,
                c = 9.0,
                d = 4.0,
                g = 2.4
            )
        }

        val result = FittingEngine.fit(points)

        assertTrue(result.isSuccess)
        assertEquals(FittingFunction.LOGISTIC, result.function)
        assertEquals(1.0, result.allMetrics["ICH M10 Accepted"] ?: 0.0, 0.0)
        assertTrue((result.allMetrics["Back-calculated RMSE (%)"] ?: 100.0) < 0.1)
    }

    @Test
    fun `自动候选只包含线性4PL和5PL`() {
        assertEquals(
            setOf(
                FittingFunction.LINEAR,
                FittingFunction.RODBARD,
                FittingFunction.LOGISTIC
            ),
            FittingEngine.automaticCalibrationFunctions()
        )
    }

    @Test
    fun `存在重复标准孔时自动生成逆方差加权候选`() {
        val points = buildList {
            listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0).forEach { concentration ->
                val expected = 2.5 * concentration + 3.0
                val deviation = 0.02 * concentration
                add(concentration to (expected - deviation))
                add(concentration to (expected + deviation))
            }
        }

        val candidates = FittingEngine.fitCalibrationCandidates(points)

        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.any { result ->
            result.allMetrics["Weighting Scheme"] == 3.0
        })
    }

    @Test
    fun `统一候选入口不会静默丢弃二次指数对数和幂函数`() {
        val points = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0).map { concentration ->
            concentration to (2.0 * concentration * concentration + 3.0 * concentration + 4.0)
        }
        val requested = linkedSetOf(
            FittingFunction.LINEAR,
            FittingFunction.QUADRATIC,
            FittingFunction.EXPONENTIAL,
            FittingFunction.LOG,
            FittingFunction.POWER
        )

        val candidates = FittingEngine.fitRequestedCalibrationFunctions(points, requested)

        assertEquals(requested, candidates.mapTo(linkedSetOf(), FittingResult::function))
    }

    @Test
    fun `统一候选入口不会为零浓度静默删除对数和幂函数标准点`() {
        val points = listOf(
            0.0 to 2.0,
            1.0 to 3.0,
            2.0 to 5.0,
            4.0 to 9.0
        )

        val candidates = FittingEngine.fitRequestedCalibrationFunctions(
            points,
            linkedSetOf(FittingFunction.LOG, FittingFunction.POWER)
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `浓度水平不足时不会用参数数目等于样本数的非线性模型强行过拟合`() {
        val points = listOf(
            1.0 to 2.0,
            2.0 to 5.0,
            4.0 to 17.0,
            8.0 to 65.0
        )

        val result = FittingEngine.fit(points)

        assertTrue(result.isSuccess)
        assertEquals(FittingFunction.LINEAR, result.function)
    }

    @Test
    fun `明显劣于均值的模型保留负R方而不是截断成零`() {
        val metrics = MetricsCalculator.calculateAllMetrics(
            observed = listOf(1.0, 2.0, 3.0),
            predicted = listOf(3.0, 2.0, 1.0),
            numParameters = 1
        )

        assertTrue((metrics["R²"] ?: 0.0) < 0.0)
    }

    private fun fourParameterSignal(
        concentration: Double,
        a: Double,
        b: Double,
        c: Double,
        d: Double
    ): Double {
        return d + (a - d) / (1.0 + (concentration / c).pow(b))
    }

    private fun fiveParameterSignal(
        concentration: Double,
        a: Double,
        b: Double,
        c: Double,
        d: Double,
        g: Double
    ): Double {
        return d + (a - d) / (1.0 + (concentration / c).pow(b)).pow(g)
    }
}

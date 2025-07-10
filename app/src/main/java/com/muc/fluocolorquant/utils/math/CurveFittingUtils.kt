package com.muc.fluocolorquant.utils.math

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.enums.FittingFunction
import org.apache.commons.math3.analysis.ParametricUnivariateFunction
import org.apache.commons.math3.fitting.AbstractCurveFitter
import org.apache.commons.math3.fitting.PolynomialCurveFitter
import org.apache.commons.math3.fitting.SimpleCurveFitter
import org.apache.commons.math3.fitting.WeightedObservedPoint
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem
import org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction
import org.apache.commons.math3.linear.DiagonalMatrix
import org.apache.commons.math3.linear.MatrixUtils
import org.apache.commons.math3.linear.RealMatrix
import org.apache.commons.math3.linear.RealVector
import org.apache.commons.math3.stat.regression.SimpleRegression
import org.apache.commons.math3.util.FastMath
import org.apache.commons.math3.util.Pair as CommonsPair
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * 曲线拟合工具类
 * 提供多种拟合方法：线性、多项式、四参数Logistic、五参数Logistic等
 */
object CurveFittingUtils {
    private val gson = Gson()
    
    /**
     * 拟合结果数据类
     * 重命名为CurveFittingResult以避免与FittingResult类冲突
     */
    data class CurveFittingResult(
        val method: String,
        val parameters: List<Double>,
        val equation: String,
        val r2: Double,
        val predictedValues: Map<Double, Double>
    )
    
    /**
     * 拟合方法枚举
     */
    enum class FittingMethod(val displayName: String) {
        LINEAR("线性拟合"),
        POLYNOMIAL("多项式拟合"),
        LOG_LINEAR("对数线性拟合"),
        FOUR_PL("四参数Logistic拟合"),
        FIVE_PL("五参数Logistic拟合")
    }
    
    /**
     * 执行曲线拟合
     * @param xValues X轴值（浓度）
     * @param yValues Y轴值（像素特征值）
     * @param method 拟合方法
     * @return 拟合结果
     */
    fun fitCurve(
        xValues: List<Double>,
        yValues: List<Double>,
        method: FittingMethod
    ): CurveFittingResult {
        // 确保数据点数量相同
        require(xValues.size == yValues.size) { "X和Y值的数量必须相同" }
        require(xValues.isNotEmpty()) { "必须提供至少一个数据点" }
        
        return when (method) {
            FittingMethod.LINEAR -> fitLinear(xValues, yValues)
            FittingMethod.POLYNOMIAL -> fitPolynomial(xValues, yValues, 2) // 二次多项式
            FittingMethod.LOG_LINEAR -> fitLogLinear(xValues, yValues)
            FittingMethod.FOUR_PL -> fitFourPL(xValues, yValues)
            FittingMethod.FIVE_PL -> fitFivePL(xValues, yValues)
        }
    }
    
    /**
     * 线性拟合 (y = ax + b)
     */
    private fun fitLinear(
        xValues: List<Double>,
        yValues: List<Double>
    ): CurveFittingResult {
        val regression = SimpleRegression(true)
        
        // 添加数据点
        for (i in xValues.indices) {
            regression.addData(xValues[i], yValues[i])
        }
        
        // 获取拟合参数
        val slope = regression.slope
        val intercept = regression.intercept
        val r2 = regression.rSquare
        
        // 计算预测值
        val predictedValues = xValues.associateWith { x -> slope * x + intercept }
        
        return CurveFittingResult(
            method = FittingMethod.LINEAR.name,
            parameters = listOf(slope, intercept),
            equation = "y = ${formatDouble(slope)}x + ${formatDouble(intercept)}",
            r2 = r2,
            predictedValues = predictedValues
        )
    }
    
    /**
     * 多项式拟合 (y = a0 + a1*x + a2*x^2 + ... + an*x^n)
     */
    private fun fitPolynomial(
        xValues: List<Double>,
        yValues: List<Double>,
        degree: Int
    ): CurveFittingResult {
        val fitter = PolynomialCurveFitter.create(degree)
        val observations = xValues.zip(yValues).map { (x, y) ->
            WeightedObservedPoint(1.0, x, y)
        }
        
        // 拟合并获取参数
        val params = fitter.fit(observations)
        
        // 计算预测值和R²
        val predictedValues = mutableMapOf<Double, Double>()
        var sumSquaredErrors = 0.0
        var sumSquaredTotal = 0.0
        val yMean = yValues.average()
        
        for (i in xValues.indices) {
            val x = xValues[i]
            val y = yValues[i]
            
            // 计算多项式值
            var predicted = 0.0
            for (j in params.indices) {
                predicted += params[j] * x.pow(j)
            }
            
            predictedValues[x] = predicted
            
            // 计算误差
            val error = y - predicted
            sumSquaredErrors += error * error
            
            // 计算总方差
            val deviation = y - yMean
            sumSquaredTotal += deviation * deviation
        }
        
        // 计算R²
        val r2 = 1.0 - (sumSquaredErrors / sumSquaredTotal)
        
        // 构建方程字符串
        val equation = buildString {
            append("y = ")
            for (i in params.indices.reversed()) {
                if (i == 0) {
                    append(formatDouble(params[i]))
                } else if (i == 1) {
                    append("${formatDouble(params[i])}x + ")
                } else {
                    append("${formatDouble(params[i])}x^$i + ")
                }
            }
        }
        
        return CurveFittingResult(
            method = FittingMethod.POLYNOMIAL.name,
            parameters = params.toList(),
            equation = equation,
            r2 = r2,
            predictedValues = predictedValues
        )
    }
    
    /**
     * 对数线性拟合 (y = a*ln(x) + b)
     */
    private fun fitLogLinear(
        xValues: List<Double>,
        yValues: List<Double>
    ): CurveFittingResult {
        val regression = SimpleRegression(true)
        
        // 转换X值为ln(x)并添加数据点
        for (i in xValues.indices) {
            val x = xValues[i]
            if (x <= 0) continue // 跳过非正值
            regression.addData(ln(x), yValues[i])
        }
        
        // 获取拟合参数
        val slope = regression.slope
        val intercept = regression.intercept
        val r2 = regression.rSquare
        
        // 计算预测值
        val predictedValues = xValues.filter { it > 0 }.associateWith { x ->
            slope * ln(x) + intercept
        }
        
        return CurveFittingResult(
            method = FittingMethod.LOG_LINEAR.name,
            parameters = listOf(slope, intercept),
            equation = "y = ${formatDouble(slope)}*ln(x) + ${formatDouble(intercept)}",
            r2 = r2,
            predictedValues = predictedValues
        )
    }
    
    /**
     * 四参数Logistic拟合 (y = d + (a-d)/(1+(x/c)^b))
     */
    private fun fitFourPL(
        xValues: List<Double>,
        yValues: List<Double>
    ): CurveFittingResult {
        // 初始参数估计
        val initialGuess = estimateFourPLParams(xValues, yValues)
        
        // 创建拟合器
        val fitter = object : AbstractCurveFitter() {
            override fun getProblem(points: MutableCollection<WeightedObservedPoint>): LeastSquaresProblem {
                val len = points.size
                val weights = DoubleArray(len) { 1.0 }
                
                return LeastSquaresBuilder()
                    .start(initialGuess)
                    .model(MultivariateJacobianFunction { params ->
                        val values = DoubleArray(points.size) { i ->
                            val point = points.elementAt(i)
                            val a = params.getEntry(0)
                            val b = params.getEntry(1)
                            val c = params.getEntry(2)
                            val d = params.getEntry(3)
                            val x = point.x
                            
                            // 四参数Logistic方程
                            d + (a - d) / (1 + (x / c).pow(b))
                        }
                        
                        // 创建雅可比矩阵
                        val jacobian = Array(points.size) { i ->
                            val point = points.elementAt(i)
                            val a = params.getEntry(0)
                            val b = params.getEntry(1)
                            val c = params.getEntry(2)
                            val d = params.getEntry(3)
                            val x = point.x
                            
                            val denominator = 1 + (x / c).pow(b)
                            val denom2 = denominator * denominator
                            
                            // 对a的偏导数
                            val dda = 1.0 / denominator
                            
                            // 对b的偏导数
                            val ddb = -(a - d) * (x / c).pow(b) * ln(x / c) / denom2
                            
                            // 对c的偏导数
                            val ddc = (a - d) * b * (x / c).pow(b) / (c * denom2)
                            
                            // 对d的偏导数
                            val ddd = 1.0 - 1.0 / denominator
                            
                            doubleArrayOf(dda, ddb, ddc, ddd)
                        }
                        
                        CommonsPair(MatrixUtils.createRealVector(values), MatrixUtils.createRealMatrix(jacobian))
                    })
                    .target(points.map { it.y }.toDoubleArray())
                    .weight(DiagonalMatrix(weights))
                    .lazyEvaluation(false)
                    .maxEvaluations(1000)
                    .maxIterations(1000)
                    .build()
            }
        }
        
        // 准备观测点
        val observations = xValues.zip(yValues).map { (x, y) ->
            WeightedObservedPoint(1.0, x, y)
        }
        
        // 执行拟合
        val params: DoubleArray
        try {
            params = fitter.fit(observations)
        } catch (e: Exception) {
            // 如果拟合失败，返回线性拟合结果
            return fitLinear(xValues, yValues)
        }
        
        // 计算预测值和R²
        val predictedValues = mutableMapOf<Double, Double>()
        var sumSquaredErrors = 0.0
        var sumSquaredTotal = 0.0
        val yMean = yValues.average()
        
        for (i in xValues.indices) {
            val x = xValues[i]
            val y = yValues[i]
            
            // 计算四参数Logistic值
            val a = params[0]
            val b = params[1]
            val c = params[2]
            val d = params[3]
            val predicted = d + (a - d) / (1 + (x / c).pow(b))
            
            predictedValues[x] = predicted
            
            // 计算误差
            val error = y - predicted
            sumSquaredErrors += error * error
            
            // 计算总方差
            val deviation = y - yMean
            sumSquaredTotal += deviation * deviation
        }
        
        // 计算R²
        val r2 = 1.0 - (sumSquaredErrors / sumSquaredTotal)
        
        return CurveFittingResult(
            method = FittingMethod.FOUR_PL.name,
            parameters = params.toList(),
            equation = "y = ${formatDouble(params[3])} + (${formatDouble(params[0])}-${formatDouble(params[3])})/(1+(x/${formatDouble(params[2])})^${formatDouble(params[1])})",
            r2 = r2,
            predictedValues = predictedValues
        )
    }
    
    /**
     * 五参数Logistic拟合 (y = d + (a-d)/(1+(x/c)^b)^g)
     */
    private fun fitFivePL(
        xValues: List<Double>,
        yValues: List<Double>
    ): CurveFittingResult {
        // 初始参数估计（基于四参数Logistic）
        val initialGuess = estimateFivePLParams(xValues, yValues)
        
        // 创建拟合器
        val fitter = object : AbstractCurveFitter() {
            override fun getProblem(points: MutableCollection<WeightedObservedPoint>): LeastSquaresProblem {
                val len = points.size
                val weights = DoubleArray(len) { 1.0 }
                
                return LeastSquaresBuilder()
                    .start(initialGuess)
                    .model(MultivariateJacobianFunction { params ->
                        val values = DoubleArray(points.size) { i ->
                            val point = points.elementAt(i)
                            val a = params.getEntry(0)
                            val b = params.getEntry(1)
                            val c = params.getEntry(2)
                            val d = params.getEntry(3)
                            val g = params.getEntry(4)
                            val x = point.x
                            
                            // 五参数Logistic方程
                            d + (a - d) / (1 + (x / c).pow(b)).pow(g)
                        }
                        
                        // 创建雅可比矩阵
                        val jacobian = Array(points.size) { i ->
                            val point = points.elementAt(i)
                            val a = params.getEntry(0)
                            val b = params.getEntry(1)
                            val c = params.getEntry(2)
                            val d = params.getEntry(3)
                            val g = params.getEntry(4)
                            val x = point.x
                            
                            val base = 1 + (x / c).pow(b)
                            val term = base.pow(-g)
                            
                            // 对a的偏导数
                            val dda = term
                            
                            // 对b的偏导数
                            val ddb = -(a - d) * g * term * ln(x / c) * (x / c).pow(b) / base
                            
                            // 对c的偏导数
                            val ddc = (a - d) * g * b * term * (x / c).pow(b) / (c * base)
                            
                            // 对d的偏导数
                            val ddd = 1.0 - term
                            
                            // 对g的偏导数
                            val ddg = -(a - d) * term * ln(base)
                            
                            doubleArrayOf(dda, ddb, ddc, ddd, ddg)
                        }
                        
                        CommonsPair(MatrixUtils.createRealVector(values), MatrixUtils.createRealMatrix(jacobian))
                    })
                    .target(points.map { it.y }.toDoubleArray())
                    .weight(DiagonalMatrix(weights))
                    .lazyEvaluation(false)
                    .maxEvaluations(1000)
                    .maxIterations(1000)
                    .build()
            }
        }
        
        // 准备观测点
        val observations = xValues.zip(yValues).map { (x, y) ->
            WeightedObservedPoint(1.0, x, y)
        }
        
        // 执行拟合
        val params: DoubleArray
        try {
            params = fitter.fit(observations)
        } catch (e: Exception) {
            // 如果拟合失败，尝试四参数Logistic拟合
            return fitFourPL(xValues, yValues)
        }
        
        // 计算预测值和R²
        val predictedValues = mutableMapOf<Double, Double>()
        var sumSquaredErrors = 0.0
        var sumSquaredTotal = 0.0
        val yMean = yValues.average()
        
        for (i in xValues.indices) {
            val x = xValues[i]
            val y = yValues[i]
            
            // 计算五参数Logistic值
            val a = params[0]
            val b = params[1]
            val c = params[2]
            val d = params[3]
            val g = params[4]
            val predicted = d + (a - d) / (1 + (x / c).pow(b)).pow(g)
            
            predictedValues[x] = predicted
            
            // 计算误差
            val error = y - predicted
            sumSquaredErrors += error * error
            
            // 计算总方差
            val deviation = y - yMean
            sumSquaredTotal += deviation * deviation
        }
        
        // 计算R²
        val r2 = 1.0 - (sumSquaredErrors / sumSquaredTotal)
        
        return CurveFittingResult(
            method = FittingMethod.FIVE_PL.name,
            parameters = params.toList(),
            equation = "y = ${formatDouble(params[3])} + (${formatDouble(params[0])}-${formatDouble(params[3])})/(1+(x/${formatDouble(params[2])})^${formatDouble(params[1])})^${formatDouble(params[4])}",
            r2 = r2,
            predictedValues = predictedValues
        )
    }
    
    /**
     * 估计四参数Logistic初始参数
     */
    private fun estimateFourPLParams(
        xValues: List<Double>,
        yValues: List<Double>
    ): DoubleArray {
        // 估计参数a（最小响应）和d（最大响应）
        val a = yValues.minOrNull() ?: 0.0
        val d = yValues.maxOrNull() ?: 1.0
        
        // 估计参数c（中点）
        val midResponse = (a + d) / 2
        val closestToMid = yValues.withIndex()
            .minByOrNull { abs(it.value - midResponse) }
        val c = if (closestToMid != null) xValues[closestToMid.index] else xValues.average()
        
        // 估计参数b（斜率）
        val b = 1.0
        
        return doubleArrayOf(a, b, c, d)
    }
    
    /**
     * 估计五参数Logistic初始参数
     */
    private fun estimateFivePLParams(
        xValues: List<Double>,
        yValues: List<Double>
    ): DoubleArray {
        // 基于四参数Logistic估计
        val fourPLParams = estimateFourPLParams(xValues, yValues)
        
        // 添加对称因子g（初始值为1，表示对称）
        return doubleArrayOf(
            fourPLParams[0],
            fourPLParams[1],
            fourPLParams[2],
            fourPLParams[3],
            1.0
        )
    }
    
    /**
     * 根据拟合结果预测未知浓度
     * @param fittingResult 拟合结果
     * @param yValue 要预测的Y值（像素特征值）
     * @return 预测的X值（浓度）
     */
    fun predictConcentration(
        fittingResult: CurveFittingResult,
        yValue: Double
    ): Double {
        return when (fittingResult.method) {
            FittingMethod.LINEAR.name -> {
                val slope = fittingResult.parameters[0]
                val intercept = fittingResult.parameters[1]
                (yValue - intercept) / slope
            }
            FittingMethod.POLYNOMIAL.name -> {
                // 对于多项式，需要求解方程，这里使用二分法近似求解
                val params = fittingResult.parameters
                
                // 找出x的合理范围
                val xMin = fittingResult.predictedValues.keys.minOrNull() ?: 0.0
                val xMax = fittingResult.predictedValues.keys.maxOrNull() ?: 100.0
                
                // 二分法求解
                solveBisection(xMin, xMax, yValue) { x ->
                    var result = 0.0
                    for (i in params.indices) {
                        result += params[i] * x.pow(i)
                    }
                    result
                }
            }
            FittingMethod.LOG_LINEAR.name -> {
                val slope = fittingResult.parameters[0]
                val intercept = fittingResult.parameters[1]
                FastMath.exp((yValue - intercept) / slope)
            }
            FittingMethod.FOUR_PL.name -> {
                val a = fittingResult.parameters[0]
                val b = fittingResult.parameters[1]
                val c = fittingResult.parameters[2]
                val d = fittingResult.parameters[3]
                
                // 求解四参数Logistic方程
                val term = (a - d) / (yValue - d) - 1
                if (term <= 0) return 0.0
                c * term.pow(1 / b)
            }
            FittingMethod.FIVE_PL.name -> {
                val a = fittingResult.parameters[0]
                val b = fittingResult.parameters[1]
                val c = fittingResult.parameters[2]
                val d = fittingResult.parameters[3]
                val g = fittingResult.parameters[4]
                
                // 对于五参数Logistic，使用二分法求解
                val xMin = fittingResult.predictedValues.keys.minOrNull() ?: 0.0
                val xMax = fittingResult.predictedValues.keys.maxOrNull() ?: 100.0
                
                solveBisection(xMin, xMax, yValue) { x ->
                    d + (a - d) / (1 + (x / c).pow(b)).pow(g)
                }
            }
            else -> Double.NaN
        }
    }
    
    /**
     * 二分法求解方程
     * @param xMin x的最小值
     * @param xMax x的最大值
     * @param targetY 目标y值
     * @param function 方程函数
     * @return 求解的x值
     */
    private fun solveBisection(
        xMin: Double,
        xMax: Double,
        targetY: Double,
        function: (Double) -> Double
    ): Double {
        var left = xMin
        var right = xMax
        val epsilon = 1e-6
        val maxIterations = 100
        var iterations = 0
        
        while (right - left > epsilon && iterations < maxIterations) {
            val mid = (left + right) / 2
            val yMid = function(mid)
            
            if (abs(yMid - targetY) < epsilon) {
                return mid
            }
            
            if ((function(left) - targetY) * (yMid - targetY) < 0) {
                right = mid
            } else {
                left = mid
            }
            
            iterations++
        }
        
        return (left + right) / 2
    }
    
    /**
     * 格式化小数，保留4位小数
     */
    private fun formatDouble(value: Double): String {
        return String.format("%.4f", value)
    }
    
    /**
     * 从像素值JSON中提取特定特征值
     * @param pixelValuesJson 像素值JSON字符串
     * @param featureKey 特征键名
     * @return 特征值
     */
    fun extractFeatureFromPixelValues(pixelValuesJson: String?, featureKey: String): Double {
        if (pixelValuesJson.isNullOrEmpty()) return 0.0
        
        try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val pixelValues = gson.fromJson<Map<String, Any>>(pixelValuesJson, type)
            
            // 解析嵌套特征（如 "rgb.r"）
            val keys = featureKey.split(".")
            var value: Any? = pixelValues
            
            for (key in keys) {
                if (value is Map<*, *>) {
                    value = (value as Map<String, Any>)[key]
                } else {
                    return 0.0
                }
            }
            
            // 转换为Double
            return when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        } catch (e: Exception) {
            return 0.0
        }
    }
} 
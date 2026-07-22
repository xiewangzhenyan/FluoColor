package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.enums.FittingFunction
import org.apache.commons.math3.analysis.ParametricUnivariateFunction
import org.apache.commons.math3.fitting.AbstractCurveFitter
import org.apache.commons.math3.fitting.WeightedObservedPoint
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer
import org.apache.commons.math3.fitting.PolynomialCurveFitter
import org.apache.commons.math3.fitting.SimpleCurveFitter
import kotlin.math.E
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.abs
import java.text.DecimalFormat
import com.muc.fluocolorquant.data.enums.PixelType

/**
 * 曲线拟合引擎
 * 处理各种数学拟合模型计算和数据拟合
 */
object FittingEngine {

    /**
     * 将函数参数格式化为LaTeX表达式
     *
     * @param function 函数类型
     * @param params 参数值映射表
     * @return 格式化后的LaTeX表达式字符串
     */
    fun formatParametersToLatex(function: FittingFunction?, params: Map<String, Double>): String {
        if (function == null) return ""
        var latexString = function.latexFormula

        fun formatValue(value: Double): String {
            if (abs(value) < 1e-9) return "0"
            return if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                DecimalFormat("0.###").format(value)
            }
        }

        // 按照参数名称长度降序排序，确保先替换较长的参数名（例如，先替换"a1"再替换"a"）
        val sortedParams = function.requiredParams.sortedByDescending { it.length }

        // 针对不同的函数类型，采用不同的替换策略
        when (function) {
            // 多项式函数使用简单的直接替换
            FittingFunction.LINEAR,
            FittingFunction.QUADRATIC,
            FittingFunction.CUBIC,
            FittingFunction.QUARTIC -> {
                // 对于多项式函数，使用简单的直接替换
                sortedParams.forEach { paramName ->
                    val paramValue = params[paramName]
                    if (paramValue != null) {
                        latexString = latexString.replace(paramName, formatValue(paramValue))
                    }
                }
            }

            // 指数函数需要特殊处理
            FittingFunction.EXPONENTIAL -> {
                // 处理a参数（简单替换）
                params["a"]?.let { a ->
                    latexString = latexString.replace(
                        "a \\cdot e",
                        "${formatValue(a)} \\cdot e"
                    )
                }

                // 处理b参数（位于指数上标位置）
                params["b"]?.let { b ->
                    latexString = latexString.replace(
                        "e^{bx}",
                        "e^{${formatValue(b)}x}"
                    )
                }
            }

            // 带偏移量的指数函数
            FittingFunction.EXPONENTIAL_WITH_OFFSET -> {
                // 处理a参数（简单替换）
                params["a"]?.let { a ->
                    latexString = latexString.replace(
                        "a \\cdot e",
                        "${formatValue(a)} \\cdot e"
                    )
                }

                // 处理b参数（位于指数上标位置）
                params["b"]?.let { b ->
                    latexString = latexString.replace(
                        "e^{-bx}",
                        "e^{-${formatValue(b)}x}"
                    )
                }

                // 处理c参数（简单替换）
                params["c"]?.let { c ->
                    latexString = latexString.replace(
                        " + c",
                        " + ${formatValue(c)}"
                    )
                }
            }

            // 高斯函数
            FittingFunction.GAUSSIAN -> {
                // 处理a参数（简单替换）
                params["a"]?.let { a ->
                    latexString = latexString.replace(
                        "a + ",
                        "${formatValue(a)} + "
                    )
                }

                // 处理b参数（简单替换）
                params["b"]?.let { b ->
                    latexString = latexString.replace(
                        "(b-a)",
                        "(${formatValue(b)}-" + (params["a"]?.let { formatValue(it) } ?: "a") + ")"
                    )
                }

                // 处理c参数（位于指数内）
                params["c"]?.let { c ->
                    latexString = latexString.replace(
                        "(x-c)",
                        "(x-${formatValue(c)})"
                    )
                }

                // 处理d参数（位于指数内分母）
                params["d"]?.let { d ->
                    latexString = latexString.replace(
                        "2d^2",
                        "2${formatValue(d)}^2"
                    )
                }
            }

            // 指数恢复函数
            FittingFunction.EXPONENTIAL_RECOVERY -> {
                // 处理a参数（简单替换）
                params["a"]?.let { a ->
                    latexString = latexString.replace(
                        "a \\cdot",
                        "${formatValue(a)} \\cdot"
                    )
                }

                // 处理b参数（位于指数上标位置）
                params["b"]?.let { b ->
                    latexString = latexString.replace(
                        "e^{-bx}",
                        "e^{-${formatValue(b)}x}"
                    )
                }
            }

            // 一般Gompertz函数
            FittingFunction.GENERAL_GOMPERTZ -> {
                // 处理a参数（简单替换）
                params["a"]?.let { a ->
                    latexString = latexString.replace(
                        "a \\cdot",
                        "${formatValue(a)} \\cdot"
                    )
                }

                // 处理b参数（指数内）
                params["b"]?.let { b ->
                    latexString = latexString.replace(
                        "-b \\cdot",
                        "-${formatValue(b)} \\cdot"
                    )
                }

                // 处理c参数（指数内的指数项）
                params["c"]?.let { c ->
                    latexString = latexString.replace(
                        "e^{-cx",
                        "e^{-${formatValue(c)}x"
                    )
                }

                // 处理d参数（x的幂）
                params["d"]?.let { d ->
                    latexString = latexString.replace(
                        "x^d}",
                        "x^${formatValue(d)}}"
                    )
                }
            }

            // Gompertz函数
            FittingFunction.GOMPERTZ -> {
                // 处理a参数（简单替换）
                params["a"]?.let { a ->
                    latexString = latexString.replace(
                        "a \\cdot",
                        "${formatValue(a)} \\cdot"
                    )
                }

                // 处理b参数（指数内）
                params["b"]?.let { b ->
                    latexString = latexString.replace(
                        "-b \\cdot",
                        "-${formatValue(b)} \\cdot"
                    )
                }

                // 处理c参数（指数内的指数项）
                params["c"]?.let { c ->
                    latexString = latexString.replace(
                        "e^{-cx}",
                        "e^{-${formatValue(c)}x}"
                    )
                }
            }

            // Richards函数
            FittingFunction.RICHARDS -> {
                // 处理a参数（简单替换）
                params["a"]?.let { a ->
                    latexString = latexString.replace(
                        "\\frac{a}",
                        "\\frac{${formatValue(a)}}"
                    )
                }

                // 修复：特别处理Richards函数的b参数，直接针对整个表达式部分替换
                params["b"]?.let { b ->
                    // 修改替换策略，针对整个表达式进行处理
                    latexString = latexString.replace(
                        "(1+b \\cdot e",
                        "(1+${formatValue(b)} \\cdot e"
                    )
                }

                // 处理c参数（指数内）
                params["c"]?.let { c ->
                    latexString = latexString.replace(
                        "e^{-cx}",
                        "e^{-${formatValue(c)}x}"
                    )
                }

                // 处理d参数（分数上标）
                params["d"]?.let { d ->
                    latexString = latexString.replace(
                        "\\frac{1}{d}}",
                        "\\frac{1}{${formatValue(d)}}}"
                    )
                }
            }

            // 其他函数使用正则表达式替换
            else -> {
                sortedParams.forEach { paramName ->
                    val replacement = params[paramName]?.let {
                        // 将数字用花括号包裹，让LaTeX库把它当作一个文本块
                        "{${formatValue(it)}}"
                    } ?: paramName // 如果参数未输入，则显示其字母

                    // 使用更严格的正则表达式确保正确替换
                    latexString = latexString.replace(Regex("(^|[^a-zA-Z0-9])$paramName($|[^a-zA-Z0-9])")) { matchResult ->
                        val prefix = matchResult.groupValues[1]
                        val suffix = matchResult.groupValues[2]
                        "$prefix$replacement$suffix"
                    }
                }
            }
        }

        return latexString
    }

    /**
     * 计算给定拟合函数在特定x值的输出
     * @param function 拟合函数类型
     * @param params 函数参数映射表
     * @param x 自变量值
     * @return 函数输出值
     */
    fun calculate(function: FittingFunction, params: Map<String, Double>, x: Double): Double {
        return when (function) {
            FittingFunction.LINEAR -> linearFunction(params, x)
            FittingFunction.QUADRATIC -> quadraticFunction(params, x)
            FittingFunction.CUBIC -> cubicFunction(params, x)
            FittingFunction.QUARTIC -> quarticFunction(params, x)
            FittingFunction.EXPONENTIAL -> exponentialFunction(params, x)
            FittingFunction.POWER -> powerFunction(params, x)
            FittingFunction.LOG -> logFunction(params, x)
            FittingFunction.RODBARD -> rodbardFunction(params, x)
            FittingFunction.GAMMA_VARIATE -> gammaVariateFunction(params, x)
            FittingFunction.CUSTOM_LOG -> customLogFunction(params, x)
            FittingFunction.RODBARD_NIH -> rodbardNihFunction(params, x)
            FittingFunction.EXPONENTIAL_WITH_OFFSET -> exponentialWithOffsetFunction(params, x)
            FittingFunction.GAUSSIAN -> gaussianFunction(params, x)
            FittingFunction.EXPONENTIAL_RECOVERY -> exponentialRecoveryFunction(params, x)
            FittingFunction.LOGISTIC -> logisticFunction(params, x)
            FittingFunction.GOMPERTZ -> gompertzFunction(params, x)
            FittingFunction.HILL -> hillFunction(params, x)
            FittingFunction.GENERAL_GOMPERTZ -> generalGompertzFunction(params, x)
            FittingFunction.RICHARDS -> richardsFunction(params, x)
            FittingFunction.INTERPOLATION -> interpolationFunction(params, x)
        }
    }

    /**
     * 拟合数据点到最佳函数模型
     * @param dataPoints 数据点列表，每个点为 (x, y) 对
     * @return 拟合结果
     */
    fun fit(dataPoints: List<Pair<Double, Double>>): FittingResult {
        if (dataPoints.size < 2) {
            return FittingResult(
                function = FittingFunction.LINEAR,
                parameters = doubleArrayOf(),
                formula = "",
                rSquared = 0.0,
                standardPoints = dataPoints,
                curvePoints = emptyList(),
                isSuccess = false,
                errorMessage = "数据点数量不足，至少需要2个点",
                allMetrics = mapOf("R²" to 0.0)
            )
        }

        /*
         * 普通用户的自动推荐不再遍历全部函数并选择训练集 R² 最大值。成熟的标准曲线流程
         * 只比较加权线性、4PL 和 5PL，并优先检查标准点反算浓度、端点接受情况、单调性
         * 以及第五个参数是否确有必要。专家仍可通过 fitSingle 手动指定其他函数。
         */
        return fitCalibrationCandidates(dataPoints).firstOrNull()
            ?: FittingResult(
                function = FittingFunction.LINEAR,
                parameters = doubleArrayOf(),
                formula = "",
                rSquared = 0.0,
                standardPoints = dataPoints,
                curvePoints = emptyList(),
                isSuccess = false,
                errorMessage = "所有函数拟合均失败",
                allMetrics = mapOf("R²" to 0.0)
            )
    }

    /**
     * 返回按科学验收质量排序的自动标准曲线候选。
     *
     * @param dataPoints 标准点，第一项是浓度，第二项是响应信号
     * @param allowedFunctions 本次允许参加比较的函数；自动模式默认只含线性、4PL、5PL
     */
    fun fitCalibrationCandidates(
        dataPoints: List<Pair<Double, Double>>,
        allowedFunctions: Set<FittingFunction> = CalibrationModelSelector.automaticFunctions
    ): List<FittingResult> {
        val normalizedPoints = dataPoints.filter { (concentration, signal) ->
            concentration.isFinite() && concentration >= 0.0 && signal.isFinite()
        }.sortedBy { it.first }
        return CalibrationModelSelector.rank(normalizedPoints, allowedFunctions).map { candidate ->
            buildCalibrationResult(normalizedPoints, candidate)
        }
    }

    /** 普通自动模式支持的成熟曲线集合，供旧96孔板选择界面复用。 */
    fun automaticCalibrationFunctions(): Set<FittingFunction> =
        CalibrationModelSelector.automaticFunctions

    /** 将新择优器内部候选转换为项目既有 FittingResult 契约。 */
    private fun buildCalibrationResult(
        dataPoints: List<Pair<Double, Double>>,
        candidate: CalibrationFitCandidate
    ): FittingResult {
        val function = candidate.function
        val parameters = function.requiredParams.map { key ->
            candidate.parameters[key] ?: 0.0
        }.toDoubleArray()
        val observed = dataPoints.map { it.second }
        val predicted = dataPoints.map { (concentration, _) ->
            calculate(function, candidate.parameters, concentration)
        }
        val metrics = MetricsCalculator.calculateAllMetrics(
            observed = observed,
            predicted = predicted,
            numParameters = parameters.size
        ) + candidate.diagnostics.asMetrics(candidate.weighting)
        return FittingResult(
            function = function,
            parameters = parameters,
            formula = generateFormula(function, candidate.parameters),
            rSquared = metrics["R²"] ?: 0.0,
            standardPoints = dataPoints,
            curvePoints = generateCurvePoints(function, candidate.parameters, dataPoints),
            allMetrics = metrics,
            isSuccess = true
        )
    }

    /**
     * 拟合数据点到指定函数模型
     * @param dataPoints 数据点列表
     * @param function 拟合函数类型
     * @return 拟合结果
     */
    fun fitSingle(dataPoints: List<Pair<Double, Double>>, function: FittingFunction): FittingResult {
        if (dataPoints.size < 2) {
            return FittingResult(
                function = function,
                parameters = doubleArrayOf(),
                formula = "",
                rSquared = 0.0,
                standardPoints = dataPoints,
                curvePoints = emptyList(),
                isSuccess = false,
                errorMessage = "数据点数量不足，至少需要2个点",
                allMetrics = mapOf("R²" to 0.0)
            )
        }

        try {
            val paramsMap = when (function) {
                FittingFunction.LINEAR -> fitLinear(dataPoints)
                FittingFunction.QUADRATIC -> fitPolynomial(dataPoints, 2)
                FittingFunction.CUBIC -> fitPolynomial(dataPoints, 3)
                FittingFunction.QUARTIC -> fitPolynomial(dataPoints, 4)
                FittingFunction.EXPONENTIAL -> fitExponential(dataPoints)
                FittingFunction.POWER -> fitPower(dataPoints)
                FittingFunction.LOG -> fitLog(dataPoints)
                FittingFunction.RODBARD -> fitRodbard(dataPoints)
                FittingFunction.GAMMA_VARIATE -> fitGammaVariate(dataPoints)
                FittingFunction.CUSTOM_LOG -> fitCustomLog(dataPoints)
                FittingFunction.RODBARD_NIH -> fitRodbardNih(dataPoints)
                FittingFunction.EXPONENTIAL_WITH_OFFSET -> fitExponentialWithOffset(dataPoints)
                FittingFunction.GAUSSIAN -> fitGaussian(dataPoints)
                FittingFunction.EXPONENTIAL_RECOVERY -> fitExponentialRecovery(dataPoints)
                FittingFunction.LOGISTIC -> fitLogistic(dataPoints)
                FittingFunction.GOMPERTZ -> fitGompertz(dataPoints)
                FittingFunction.HILL -> fitHill(dataPoints)
                FittingFunction.GENERAL_GOMPERTZ -> fitGeneralGompertz(dataPoints)
                FittingFunction.RICHARDS -> fitRichards(dataPoints)
                FittingFunction.INTERPOLATION -> fitInterpolation(dataPoints)
            }

            // 获取函数对应的参数列表
            val paramKeys = function.requiredParams
            val parameters = paramKeys.map { paramsMap[it] ?: 0.0 }.toDoubleArray()

            // 计算拟合指标
            val observed = dataPoints.map { it.second }
            val predicted = dataPoints.map { (x, _) -> calculate(function, paramsMap, x) }
            val metrics = MetricsCalculator.calculateAllMetrics(
                observed, predicted, parameters.size
            )

            // 生成公式
            val formula = generateFormula(function, paramsMap)

            // 生成曲线点
            val curvePoints = generateCurvePoints(function, paramsMap, dataPoints)

            return FittingResult(
                function = function,
                parameters = parameters,
                formula = formula,
                rSquared = metrics["R²"] ?: 0.0,
                standardPoints = dataPoints,
                curvePoints = curvePoints,
                allMetrics = metrics,
                isSuccess = true
            )
        } catch (e: Exception) {
            return FittingResult(
                function = function,
                parameters = doubleArrayOf(),
                formula = "",
                rSquared = 0.0,
                standardPoints = dataPoints,
                curvePoints = emptyList(),
                isSuccess = false,
                errorMessage = e.message ?: "拟合失败",
                allMetrics = mapOf("R²" to 0.0)
            )
        }
    }

    /**
     * 为特定像素类型和函数类型执行拟合
     * @param dataPoints 数据点列表，每个点为(浓度, 像素值)的对
     * @param function 拟合函数类型
     * @param pixelType 像素类型
     * @return 拟合结果对象
     */
    fun fitCurve(
        dataPoints: List<Pair<Double, Double>>,
        function: FittingFunction,
        pixelType: PixelType? = null
    ): FittingResult? {
        if (dataPoints.size < 4) {
            return null // 不足4个点无法进行有效拟合
        }

        try {
            // 调用现有的fitSingle函数执行拟合
            val result = fitSingle(dataPoints, function)

            // 如果拟合成功，返回带有PixelType的结果
            if (result.isSuccess) {
                return result.copy(pixelType = pixelType)
            }

            return null
        } catch (e: Exception) {
            // 拟合失败
            return null
        }
    }

    /**
     * 拟合线性函数: y = a·x + b
     */
    private fun fitLinear(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        val fitter = PolynomialCurveFitter.create(1)
        val points = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
        val coefficients = fitter.fit(points)
        return mapOf("a" to coefficients[1], "b" to coefficients[0])
    }

    /**
     * 拟合多项式函数
     * @param degree 多项式次数
     */
    private fun fitPolynomial(dataPoints: List<Pair<Double, Double>>, degree: Int): Map<String, Double> {
        val fitter = PolynomialCurveFitter.create(degree)
        val points = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
        val coefficients = fitter.fit(points)

        return when (degree) {
            2 -> mapOf(
                "a" to coefficients[2],
                "b" to coefficients[1],
                "c" to coefficients[0]
            )
            3 -> mapOf(
                "a" to coefficients[3],
                "b" to coefficients[2],
                "c" to coefficients[1],
                "d" to coefficients[0]
            )
            4 -> mapOf(
                "a" to coefficients[4],
                "b" to coefficients[3],
                "c" to coefficients[2],
                "d" to coefficients[1],
                "e" to coefficients[0]
            )
            else -> mapOf("a" to coefficients[1], "b" to coefficients[0])
        }
    }

    // 以下是各个函数的实现，仅展示部分常用函数
    // 实际项目中需要根据需要实现所有函数

    /**
     * 拟合指数函数: y = a·e^(b·x)
     */
    private fun fitExponential(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        // 对数转换后线性拟合
        val transformedPoints = dataPoints.filter { it.second > 0 }
            .map { (x, y) -> Pair(x, ln(y)) }

        val linearParams = fitLinear(transformedPoints)
        return mapOf(
            "a" to exp(linearParams["b"] ?: 0.0),
            "b" to (linearParams["a"] ?: 0.0)
        )
    }

    /**
     * 拟合幂函数: y = a·x^b
     */
    private fun fitPower(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        // 对数转换后线性拟合
        val transformedPoints = dataPoints.filter { it.first > 0 && it.second > 0 }
            .map { (x, y) -> Pair(ln(x), ln(y)) }

        val linearParams = fitLinear(transformedPoints)
        return mapOf(
            "a" to exp(linearParams["b"] ?: 0.0),
            "b" to (linearParams["a"] ?: 0.0)
        )
    }

    /**
     * 拟合对数函数: y = a + b·ln(x)
     */
    private fun fitLog(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        // 变换x后线性拟合
        val transformedPoints = dataPoints.filter { it.first > 0 }
            .map { (x, y) -> Pair(ln(x), y) }

        val linearParams = fitLinear(transformedPoints)
        return mapOf(
            "a" to (linearParams["b"] ?: 0.0),
            "b" to (linearParams["a"] ?: 0.0)
        )
    }

    /**
     * 拟合Rodbard函数 (4PL): y = d + (a-d)/(1+(x/c)^b)
     * 使用Levenberg-Marquardt优化算法
     */
    private fun fitRodbard(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            // 初始参数估计
            val yValues = dataPoints.map { it.second }
            val xValues = dataPoints.map { it.first }
            val yMin = yValues.minOrNull() ?: 0.0
            val yMax = yValues.maxOrNull() ?: 1.0
            val xMid = xValues.average()

            // 定义4PL函数
            val function = object : ParametricUnivariateFunction {
                override fun value(x: Double, parameters: DoubleArray): Double {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]
                    return d + (a - d) / (1 + (x / c).pow(b))
                }

                override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]

                    val ratio = x / c
                    val powered = ratio.pow(b)
                    val denominator = 1 + powered
                    val denominatorSq = denominator * denominator

                    return doubleArrayOf(
                        1.0 / denominator,
                        -(a - d) * powered * ln(ratio) / denominatorSq,
                        (a - d) * b * powered / (c * denominatorSq),
                        1.0 - 1.0 / denominator
                    )
                }
            }

            // 使用SimpleCurveFitter进行拟合
            val fitter = SimpleCurveFitter.create(function, doubleArrayOf(yMin, 1.0, xMid, yMax))
            val observations = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
            val params = fitter.fit(observations)

            mapOf(
                "a" to params[0],
                "b" to params[1],
                "c" to params[2],
                "d" to params[3]
            )
        } catch (e: Exception) {
            // 备用方案：返回简单估计值
            val yMin = dataPoints.minByOrNull { it.second }?.second ?: 0.0
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0
            val xMid = dataPoints.map { it.first }.average()

            mapOf(
                "a" to yMin,
                "b" to 1.0,
                "c" to xMid,
                "d" to yMax
            )
        }
    }

    /**
     * 拟合伽马变量函数: y = a·(x-b)^c·e^(-(x-b)/d)
     */
    private fun fitGammaVariate(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            // 初始参数估计
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0
            val xAtYMax = dataPoints.maxByOrNull { it.second }?.first ?: 1.0
            val xMin = dataPoints.minByOrNull { it.first }?.first ?: 0.0

            val function = object : ParametricUnivariateFunction {
                override fun value(x: Double, parameters: DoubleArray): Double {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]

                    if (x <= b || a <= 0 || c <= 0 || d <= 0) return 0.0

                    val term1 = (x - b).pow(c)
                    val term2 = exp(-(x - b) / d)
                    return a * term1 * term2
                }

                override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]

                    if (x <= b || a <= 0 || c <= 0 || d <= 0) {
                        return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
                    }

                    val diff = x - b
                    val powered = diff.pow(c)
                    val exponential = exp(-diff / d)
                    val baseValue = powered * exponential

                    return doubleArrayOf(
                        baseValue,
                        -a * (c * diff.pow(c - 1) * exponential - powered * exponential / d),
                        a * powered * ln(diff) * exponential,
                        a * powered * exponential * diff / (d * d)
                    )
                }
            }

            val fitter = SimpleCurveFitter.create(function, doubleArrayOf(yMax, xMin, 1.0, 1.0))
            val observations = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
            val params = fitter.fit(observations)

            mapOf(
                "a" to params[0],
                "b" to params[1],
                "c" to params[2],
                "d" to params[3]
            )
        } catch (e: Exception) {
            // 备用方案
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0
            val xMin = dataPoints.minByOrNull { it.first }?.first ?: 0.0

            mapOf(
                "a" to yMax,
                "b" to xMin,
                "c" to 1.0,
                "d" to 1.0
            )
        }
    }

    /**
     * 拟合自定义对数函数: y = a + b·ln(x-c)
     */
    private fun fitCustomLog(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            val xMin = dataPoints.minByOrNull { it.first }?.first ?: 0.0
            val offset = if (xMin > 0) xMin * 0.1 else 0.01

            // 变换数据后进行线性拟合
            val transformedPoints = dataPoints
                .filter { it.first > offset }
                .map { (x, y) -> Pair(ln(x - offset), y) }

            if (transformedPoints.isEmpty()) {
                throw Exception("No valid data points for custom log fitting")
            }

            val linearParams = fitLinear(transformedPoints)

            mapOf(
                "a" to (linearParams["b"] ?: 0.0),
                "b" to (linearParams["a"] ?: 0.0),
                "c" to offset
            )
        } catch (e: Exception) {
            // 备用方案：普通对数拟合
            val logParams = fitLog(dataPoints)
            mapOf(
                "a" to (logParams["a"] ?: 0.0),
                "b" to (logParams["b"] ?: 0.0),
                "c" to 0.0
            )
        }
    }

    /**
     * 拟合Rodbard NIH函数: y = a·(1/(1+(x/c)^b))
     */
    private fun fitRodbardNih(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0
            val xMid = dataPoints.map { it.first }.average()

            val function = object : ParametricUnivariateFunction {
                override fun value(x: Double, parameters: DoubleArray): Double {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]

                    return if (c > 0) {
                        a * (1 / (1 + (x / c).pow(b)))
                    } else {
                        a * (1 / (1 + (x * 0.001).pow(b)))
                    }
                }

                override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]

                    if (c <= 0) {
                        return doubleArrayOf(0.0, 0.0, 0.0)
                    }

                    val ratio = x / c
                    val powered = ratio.pow(b)
                    val denominator = 1 + powered
                    val denominatorSq = denominator * denominator

                    return doubleArrayOf(
                        1.0 / denominator,
                        -a * powered * ln(ratio) / denominatorSq,
                        a * b * powered / (c * denominatorSq)
                    )
                }
            }

            val fitter = SimpleCurveFitter.create(function, doubleArrayOf(yMax, 1.0, xMid))
            val observations = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
            val params = fitter.fit(observations)

            mapOf(
                "a" to params[0],
                "b" to params[1],
                "c" to params[2]
            )
        } catch (e: Exception) {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0
            val xMid = dataPoints.map { it.first }.average()

            mapOf(
                "a" to yMax,
                "b" to 1.0,
                "c" to xMid
            )
        }
    }

    /**
     * 拟合带偏移的指数函数: y = a·e^(-b·x) + c
     */
    private fun fitExponentialWithOffset(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            val yMin = dataPoints.minByOrNull { it.second }?.second ?: 0.0
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0

            val function = object : ParametricUnivariateFunction {
                override fun value(x: Double, parameters: DoubleArray): Double {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    return a * exp(-b * x) + c
                }

                override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                    val a = parameters[0]
                    val b = parameters[1]
                    val expTerm = exp(-b * x)

                    return doubleArrayOf(
                        expTerm,
                        -a * x * expTerm,
                        1.0
                    )
                }
            }

            val fitter = SimpleCurveFitter.create(function, doubleArrayOf(yMax - yMin, 0.1, yMin))
            val observations = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
            val params = fitter.fit(observations)

            mapOf(
                "a" to params[0],
                "b" to params[1],
                "c" to params[2]
            )
        } catch (e: Exception) {
            val yMin = dataPoints.minByOrNull { it.second }?.second ?: 0.0
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0

            mapOf(
                "a" to (yMax - yMin),
                "b" to 0.1,
                "c" to yMin
            )
        }
    }

    /**
     * 拟合高斯函数: y = a + (b-a) * e^(-(x-c)²/(2·d²))
     */
    private fun fitGaussian(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            val yValues = dataPoints.map { it.second }
            val xValues = dataPoints.map { it.first }
            val yMin = yValues.minOrNull() ?: 0.0
            val yMax = yValues.maxOrNull() ?: 1.0
            val xAtYMax = dataPoints.maxByOrNull { it.second }?.first ?: 0.0
            val xRange = (xValues.maxOrNull() ?: 1.0) - (xValues.minOrNull() ?: 0.0)

            val function = object : ParametricUnivariateFunction {
                override fun value(x: Double, parameters: DoubleArray): Double {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]
                    return a + (b - a) * exp(-(x - c).pow(2) / (2 * d.pow(2)))
                }

                override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]

                    val diff = x - c
                    val diffSq = diff * diff
                    val dSq = d * d
                    val expTerm = exp(-diffSq / (2 * dSq))

                    return doubleArrayOf(
                        1.0 - expTerm,
                        expTerm,
                        (b - a) * expTerm * diff / dSq,
                        (b - a) * expTerm * diffSq / (d * dSq)
                    )
                }
            }

            val fitter = SimpleCurveFitter.create(function, doubleArrayOf(yMin, yMax, xAtYMax, xRange / 4))
            val observations = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
            val params = fitter.fit(observations)

            mapOf(
                "a" to params[0],
                "b" to params[1],
                "c" to params[2],
                "d" to params[3]
            )
        } catch (e: Exception) {
            val yMin = dataPoints.minByOrNull { it.second }?.second ?: 0.0
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0
            val xAtYMax = dataPoints.maxByOrNull { it.second }?.first ?: 0.0
            val xRange = (dataPoints.maxByOrNull { it.first }?.first ?: 1.0) -
                    (dataPoints.minByOrNull { it.first }?.first ?: 0.0)

            mapOf(
                "a" to yMin,
                "b" to yMax,
                "c" to xAtYMax,
                "d" to xRange / 4
            )
        }
    }

    /**
     * 拟合指数恢复函数: y = a·(1-e^(-b·x))
     */
    private fun fitExponentialRecovery(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0

            val function = object : ParametricUnivariateFunction {
                override fun value(x: Double, parameters: DoubleArray): Double {
                    val a = parameters[0]
                    val b = parameters[1]
                    return a * (1 - exp(-b * x))
                }

                override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                    val a = parameters[0]
                    val b = parameters[1]
                    val expTerm = exp(-b * x)

                    return doubleArrayOf(
                        1 - expTerm,
                        a * x * expTerm
                    )
                }
            }

            val fitter = SimpleCurveFitter.create(function, doubleArrayOf(yMax, 0.1))
            val observations = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
            val params = fitter.fit(observations)

            mapOf(
                "a" to params[0],
                "b" to params[1]
            )
        } catch (e: Exception) {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0

            mapOf(
                "a" to yMax,
                "b" to 0.1
            )
        }
    }

    /**
     * 拟合Logistic函数 (5PL): y = d + (a-d)/(1+(x/c)^b)^g
     */
    private fun fitLogistic(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            val yValues = dataPoints.map { it.second }
            val xValues = dataPoints.map { it.first }
            val yMin = yValues.minOrNull() ?: 0.0
            val yMax = yValues.maxOrNull() ?: 1.0
            val xMid = xValues.average()

            val function = object : ParametricUnivariateFunction {
                override fun value(x: Double, parameters: DoubleArray): Double {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]
                    val g = parameters[4]

                    return if (c > 0) {
                        d + (a - d) / (1 + (x / c).pow(b)).pow(g)
                    } else {
                        d + (a - d) / (1 + (x * 0.001).pow(b)).pow(g)
                    }
                }

                override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]
                    val g = parameters[4]

                    if (c <= 0) {
                        return doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
                    }

                    val ratio = x / c
                    val powered = ratio.pow(b)
                    val denominator = 1 + powered
                    val denominatorPowered = denominator.pow(g)

                    return doubleArrayOf(
                        1.0 / denominatorPowered,
                        -(a - d) * g * powered * ln(ratio) / (denominator * denominatorPowered),
                        (a - d) * g * b * powered / (c * denominator * denominatorPowered),
                        1.0 - 1.0 / denominatorPowered,
                        -(a - d) * ln(denominator) / denominatorPowered
                    )
                }
            }

            val fitter = SimpleCurveFitter.create(function, doubleArrayOf(yMin, 1.0, xMid, yMax, 1.0))
            val observations = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
            val params = fitter.fit(observations)

            mapOf(
                "a" to params[0],
                "b" to params[1],
                "c" to params[2],
                "d" to params[3],
                "g" to params[4]
            )
        } catch (e: Exception) {
            val yMin = dataPoints.minByOrNull { it.second }?.second ?: 0.0
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0
            val xMid = dataPoints.map { it.first }.average()

            mapOf(
                "a" to yMin,
                "b" to 1.0,
                "c" to xMid,
                "d" to yMax,
                "g" to 1.0
            )
        }
    }

    /**
     * 拟合Gompertz函数: y = a·e^(-b·e^(-c·x))
     */
    private fun fitGompertz(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0

            val function = object : ParametricUnivariateFunction {
                override fun value(x: Double, parameters: DoubleArray): Double {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    return a * exp(-b * exp(-c * x))
                }

                override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]

                    val innerExp = exp(-c * x)
                    val outerExp = exp(-b * innerExp)

                    return doubleArrayOf(
                        outerExp,
                        -a * innerExp * outerExp,
                        a * b * x * innerExp * outerExp
                    )
                }
            }

            val fitter = SimpleCurveFitter.create(function, doubleArrayOf(yMax, 1.0, 0.1))
            val observations = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
            val params = fitter.fit(observations)

            mapOf(
                "a" to params[0],
                "b" to params[1],
                "c" to params[2]
            )
        } catch (e: Exception) {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0

            mapOf(
                "a" to yMax,
                "b" to 1.0,
                "c" to 0.1
            )
        }
    }

    /**
     * 拟合Hill函数: y = a·x^b/(c^b+x^b)
     */
    private fun fitHill(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0
            val xMid = dataPoints.map { it.first }.average()

            val function = object : ParametricUnivariateFunction {
                override fun value(x: Double, parameters: DoubleArray): Double {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]

                    return if (x >= 0) {
                        a * x.pow(b) / (c.pow(b) + x.pow(b))
                    } else {
                        0.0
                    }
                }

                override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]

                    if (x <= 0) {
                        return doubleArrayOf(0.0, 0.0, 0.0)
                    }

                    val xPowB = x.pow(b)
                    val cPowB = c.pow(b)
                    val denominator = cPowB + xPowB
                    val denominatorSq = denominator * denominator

                    return doubleArrayOf(
                        xPowB / denominator,
                        a * xPowB * cPowB * (ln(x) - ln(c)) / denominatorSq,
                        -a * b * xPowB * cPowB / (c * denominatorSq)
                    )
                }
            }

            val fitter = SimpleCurveFitter.create(function, doubleArrayOf(yMax, 1.0, xMid))
            val observations = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
            val params = fitter.fit(observations)

            mapOf(
                "a" to params[0],
                "b" to params[1],
                "c" to params[2]
            )
        } catch (e: Exception) {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0
            val xMid = dataPoints.map { it.first }.average()

            mapOf(
                "a" to yMax,
                "b" to 1.0,
                "c" to xMid
            )
        }
    }

    /**
     * 拟合广义Gompertz函数: y = a·e^(-b·e^(-c·x^d))
     */
    private fun fitGeneralGompertz(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0

            val function = object : ParametricUnivariateFunction {
                override fun value(x: Double, parameters: DoubleArray): Double {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]

                    return if (x >= 0) {
                        a * exp(-b * exp(-c * x.pow(d)))
                    } else {
                        0.0
                    }
                }

                override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]

                    if (x <= 0) {
                        return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
                    }

                    val xPowD = x.pow(d)
                    val innerExp = exp(-c * xPowD)
                    val outerExp = exp(-b * innerExp)

                    return doubleArrayOf(
                        outerExp,
                        -a * innerExp * outerExp,
                        a * b * xPowD * innerExp * outerExp,
                        a * b * c * xPowD * ln(x) * innerExp * outerExp
                    )
                }
            }

            val fitter = SimpleCurveFitter.create(function, doubleArrayOf(yMax, 1.0, 0.1, 1.0))
            val observations = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
            val params = fitter.fit(observations)

            mapOf(
                "a" to params[0],
                "b" to params[1],
                "c" to params[2],
                "d" to params[3]
            )
        } catch (e: Exception) {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0

            mapOf(
                "a" to yMax,
                "b" to 1.0,
                "c" to 0.1,
                "d" to 1.0
            )
        }
    }

    /**
     * 拟合Richards函数: y = a/(1+b·e^(-c·x))^(1/d)
     */
    private fun fitRichards(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        return try {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0

            val function = object : ParametricUnivariateFunction {
                override fun value(x: Double, parameters: DoubleArray): Double {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]

                    return if (d != 0.0) {
                        a / (1 + b * exp(-c * x)).pow(1 / d)
                    } else {
                        0.0
                    }
                }

                override fun gradient(x: Double, parameters: DoubleArray): DoubleArray {
                    val a = parameters[0]
                    val b = parameters[1]
                    val c = parameters[2]
                    val d = parameters[3]

                    if (d == 0.0) {
                        return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
                    }

                    val expTerm = exp(-c * x)
                    val denominator = 1 + b * expTerm
                    val denominatorPowered = denominator.pow(1 / d)

                    return doubleArrayOf(
                        1.0 / denominatorPowered,
                        -a * expTerm / (d * denominator * denominatorPowered),
                        a * b * x * expTerm / (d * denominator * denominatorPowered),
                        a * ln(denominator) / (d * d * denominatorPowered)
                    )
                }
            }

            val fitter = SimpleCurveFitter.create(function, doubleArrayOf(yMax, 1.0, 0.1, 1.0))
            val observations = dataPoints.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
            val params = fitter.fit(observations)

            mapOf(
                "a" to params[0],
                "b" to params[1],
                "c" to params[2],
                "d" to params[3]
            )
        } catch (e: Exception) {
            val yMax = dataPoints.maxByOrNull { it.second }?.second ?: 1.0

            mapOf(
                "a" to yMax,
                "b" to 1.0,
                "c" to 0.1,
                "d" to 1.0
            )
        }
    }

    /**
     * 拟合插值函数
     */
    private fun fitInterpolation(dataPoints: List<Pair<Double, Double>>): Map<String, Double> {
        // 对于插值，我们只需要存储数据点
        val result = mutableMapOf<String, Double>()

        // 按x值排序
        val sortedPoints = dataPoints.sortedBy { it.first }

        // 存储点对（最多存储前10个点以避免参数过多）
        val maxPoints = minOf(10, sortedPoints.size)
        for (i in 0 until maxPoints) {
            result["x$i"] = sortedPoints[i].first
            result["y$i"] = sortedPoints[i].second
        }

        return result
    }

    // 各种函数的计算部分

    /**
     * 线性函数: y = a·x + b
     */
    private fun linearFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        return a * x + b
    }

    /**
     * 二次函数: y = a·x² + b·x + c
     */
    private fun quadraticFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        val c = params["c"] ?: 0.0
        return a * x * x + b * x + c
    }

    /**
     * 三次函数: y = a·x³ + b·x² + c·x + d
     */
    private fun cubicFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        val c = params["c"] ?: 0.0
        val d = params["d"] ?: 0.0
        return a * x * x * x + b * x * x + c * x + d
    }

    /**
     * 四次函数: y = a·x⁴ + b·x³ + c·x² + d·x + e
     */
    private fun quarticFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        val c = params["c"] ?: 0.0
        val d = params["d"] ?: 0.0
        val e = params["e"] ?: 0.0
        return a * x.pow(4) + b * x.pow(3) + c * x * x + d * x + e
    }

    /**
     * 指数函数: y = a·e^(b·x)
     */
    private fun exponentialFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        return a * exp(b * x)
    }

    /**
     * 幂函数: y = a·x^b
     */
    private fun powerFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        return if (x > 0) a * x.pow(b) else 0.0
    }

    /**
     * 对数函数: y = a + b·ln(x)
     */
    private fun logFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        return if (x > 0) a + b * ln(x) else Double.NaN
    }

    /**
     * Rodbard函数 (4PL): y = d + (a-d)/(1+(x/c)^b)
     */
    private fun rodbardFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 1.0
        val c = params["c"] ?: 1.0
        val d = params["d"] ?: 0.0

        return if (c > 0) {
            d + (a - d) / (1 + (x / c).pow(b))
        } else {
            d + (a - d) / (1 + (x * 0.001).pow(b)) // 避免除以零
        }
    }

    /**
     * 伽马变量函数: y = a·(x-b)^c·e^(-(x-b)/d)
     * 【重要改进】增加了对定义域和参数的检查
     */
    private fun gammaVariateFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0 // a: amplitude
        val b = params["b"] ?: 0.0 // b: offset (start time)
        val c = params["c"] ?: 0.0 // c: shape parameter
        val d = params["d"] ?: 1.0 // d: scale parameter

        // 增加定义域和参数检查
        if (x <= b || a <= 0 || c <= 0 || d <= 0) {
            return 0.0
        }

        val term1 = (x - b).pow(c)
        val term2 = exp(-(x - b) / d)

        return a * term1 * term2
    }

    /**
     * 自定义对数函数: y = a + b·ln(x-c)
     */
    private fun customLogFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        val c = params["c"] ?: 0.0
        return if (x > c) a + b * ln(x - c) else Double.NaN
    }

    /**
     * Rodbard NIH函数: y = a·(1/(1+(x/c)^b))
     */
    private fun rodbardNihFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 1.0
        val c = params["c"] ?: 1.0

        return if (c > 0) {
            a * (1 / (1 + (x / c).pow(b)))
        } else {
            a * (1 / (1 + (x * 0.001).pow(b))) // 避免除以零
        }
    }

    /**
     * 带偏移的指数函数: y = a·e^(-b·x) + c
     */
    private fun exponentialWithOffsetFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        val c = params["c"] ?: 0.0
        return a * exp(-b * x) + c
    }

    /**
     * 高斯函数: y = a + (b-a) * e^(-(x-c)²/(2·d²))
     */
    private fun gaussianFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0 // base
        val b = params["b"] ?: 0.0 // max y
        val c = params["c"] ?: 0.0 // center
        val d = params["d"] ?: 1.0 // width
        return a + (b - a) * exp(-(x - c).pow(2) / (2 * d.pow(2)))
    }

    /**
     * 指数恢复函数: y = a·(1-e^(-b·x))
     */
    private fun exponentialRecoveryFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        return a * (1 - exp(-b * x))
    }

    /**
     * Logistic函数 (5PL): y = d + (a-d)/(1+(x/c)^b)^g
     */
    private fun logisticFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 1.0
        val c = params["c"] ?: 1.0
        val d = params["d"] ?: 0.0
        val g = params["g"] ?: 1.0

        return if (c > 0) {
            d + (a - d) / (1 + (x / c).pow(b)).pow(g)
        } else {
            d + (a - d) / (1 + (x * 0.001).pow(b)).pow(g) // 避免除以零
        }
    }

    /**
     * Gompertz函数: y = a·e^(-b·e^(-c·x))
     */
    private fun gompertzFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        val c = params["c"] ?: 0.0
        return a * exp(-b * exp(-c * x))
    }

    /**
     * Hill函数: y = a·x^b/(c^b+x^b)
     */
    private fun hillFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 1.0
        val c = params["c"] ?: 1.0

        return if (x >= 0) {
            a * x.pow(b) / (c.pow(b) + x.pow(b))
        } else {
            0.0
        }
    }

    /**
     * 广义Gompertz函数: y = a·e^(-b·e^(-c·x^d))
     */
    private fun generalGompertzFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        val c = params["c"] ?: 0.0
        val d = params["d"] ?: 1.0

        return if (x >= 0) {
            a * exp(-b * exp(-c * x.pow(d)))
        } else {
            0.0
        }
    }

    /**
     * Richards函数: y = a/(1+b·e^(-c·x))^(1/d)
     */
    private fun richardsFunction(params: Map<String, Double>, x: Double): Double {
        val a = params["a"] ?: 0.0
        val b = params["b"] ?: 0.0
        val c = params["c"] ?: 0.0
        val d = params["d"] ?: 1.0

        return if (d != 0.0) {
            a / (1 + b * exp(-c * x)).pow(1 / d)
        } else {
            0.0 // 避免除以零
        }
    }

    /**
     * 插值函数
     */
    private fun interpolationFunction(params: Map<String, Double>, x: Double): Double {
        // 插值需要有序的点对，这里简化处理
        val points = params.entries
            .filter { it.key.startsWith("x") && it.key.length > 1 }
            .map {
                val index = it.key.substring(1).toIntOrNull() ?: 0
                Pair(it.value, params["y$index"] ?: 0.0)
            }
            .sortedBy { it.first }

        if (points.isEmpty()) return 0.0
        if (points.size == 1) return points[0].second

        // 找到x所在的区间
        for (i in 0 until points.size - 1) {
            val x1 = points[i].first
            val y1 = points[i].second
            val x2 = points[i + 1].first
            val y2 = points[i + 1].second

            if (x >= x1 && x <= x2) {
                // 线性插值
                return y1 + (y2 - y1) * (x - x1) / (x2 - x1)
            }
        }

        // 超出范围时的外推
        return if (x < points.first().first) {
            points.first().second
        } else {
            points.last().second
        }
    }

    /**
     * 根据函数类型和参数创建函数
     */
    fun createFunctionFromParameters(function: FittingFunction, params: Map<String, Double>): (Double) -> Double {
        return { x -> calculate(function, params, x) }
    }

    /**
     * 生成公式字符串
     */
    private fun generateFormula(function: FittingFunction, params: Map<String, Double>): String {
        return when (function) {
            FittingFunction.LINEAR -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                "y = ${formatDouble(a)}x + ${formatDouble(b)}"
            }
            FittingFunction.QUADRATIC -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                "y = ${formatDouble(a)}x² + ${formatDouble(b)}x + ${formatDouble(c)}"
            }
            FittingFunction.CUBIC -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                val d = params["d"] ?: 0.0
                "y = ${formatDouble(a)}x³ + ${formatDouble(b)}x² + ${formatDouble(c)}x + ${formatDouble(d)}"
            }
            FittingFunction.QUARTIC -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                val d = params["d"] ?: 0.0
                val e = params["e"] ?: 0.0
                "y = ${formatDouble(a)}x⁴ + ${formatDouble(b)}x³ + ${formatDouble(c)}x² + ${formatDouble(d)}x + ${formatDouble(e)}"
            }
            FittingFunction.EXPONENTIAL -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                "y = ${formatDouble(a)} · e^(${formatDouble(b)}x)"
            }
            FittingFunction.POWER -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                "y = ${formatDouble(a)} · x^${formatDouble(b)}"
            }
            FittingFunction.LOG -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                "y = ${formatDouble(a)} + ${formatDouble(b)} · ln(x)"
            }
            FittingFunction.RODBARD -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                val d = params["d"] ?: 0.0
                "y = ${formatDouble(d)} + (${formatDouble(a)}-${formatDouble(d)})/(1+(x/${formatDouble(c)})^${formatDouble(b)})"
            }
            FittingFunction.GAMMA_VARIATE -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                val d = params["d"] ?: 0.0
                "y = ${formatDouble(a)} · (x-${formatDouble(b)})^${formatDouble(c)} · e^(-(x-${formatDouble(b)})/${formatDouble(d)})"
            }
            FittingFunction.CUSTOM_LOG -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                "y = ${formatDouble(a)} + ${formatDouble(b)} · ln(x-${formatDouble(c)})"
            }
            FittingFunction.RODBARD_NIH -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                "y = ${formatDouble(a)} · (1/(1+(x/${formatDouble(c)})^${formatDouble(b)}))"
            }
            FittingFunction.EXPONENTIAL_WITH_OFFSET -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                "y = ${formatDouble(a)} · e^(-${formatDouble(b)}x) + ${formatDouble(c)}"
            }
            FittingFunction.GAUSSIAN -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                val d = params["d"] ?: 0.0
                "y = ${formatDouble(a)} + (${formatDouble(b)}-${formatDouble(a)}) · e^(-(x-${formatDouble(c)})²/(2·${formatDouble(d)}²))"
            }
            FittingFunction.EXPONENTIAL_RECOVERY -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                "y = ${formatDouble(a)} · (1-e^(-${formatDouble(b)}x))"
            }
            FittingFunction.LOGISTIC -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                val d = params["d"] ?: 0.0
                val g = params["g"] ?: 0.0
                "y = ${formatDouble(d)} + (${formatDouble(a)}-${formatDouble(d)})/(1+(x/${formatDouble(c)})^${formatDouble(b)})^${formatDouble(g)}"
            }
            FittingFunction.GOMPERTZ -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                "y = ${formatDouble(a)} · e^(-${formatDouble(b)} · e^(-${formatDouble(c)}x))"
            }
            FittingFunction.HILL -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                "y = ${formatDouble(a)} · x^${formatDouble(b)}/(${formatDouble(c)}^${formatDouble(b)}+x^${formatDouble(b)})"
            }
            FittingFunction.GENERAL_GOMPERTZ -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                val d = params["d"] ?: 0.0
                "y = ${formatDouble(a)} · e^(-${formatDouble(b)} · e^(-${formatDouble(c)}x^${formatDouble(d)}))"
            }
            FittingFunction.RICHARDS -> {
                val a = params["a"] ?: 0.0
                val b = params["b"] ?: 0.0
                val c = params["c"] ?: 0.0
                val d = params["d"] ?: 0.0
                "y = ${formatDouble(a)}/(1+${formatDouble(b)} · e^(-${formatDouble(c)}x))^(1/${formatDouble(d)})"
            }
            FittingFunction.INTERPOLATION -> {
                "y = Interpolation"
            }
        }
    }

    /**
     * 生成曲线点
     */
    private fun generateCurvePoints(
        function: FittingFunction,
        params: Map<String, Double>,
        dataPoints: List<Pair<Double, Double>>
    ): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()

        // 获取浓度范围
        val xValues = dataPoints.map { it.first }
        val minX = xValues.minOrNull() ?: 0.0
        val maxX = xValues.maxOrNull() ?: 0.0
        val range = maxX - minX
        val start = if (minX > 0) minX / 2 else 0.0
        val end = maxX + range / 2

        // 生成100个点
        val steps = 100
        val step = (end - start) / steps

        for (i in 0..steps) {
            val x = start + i * step
            val y = calculate(function, params, x)
            points.add(Pair(x, y))
        }

        return points
    }

    /**
     * 格式化双精度浮点数为LaTeX格式
     */
    private fun formatDouble(value: Double): String {
        if (abs(value) < 1e-9) return "0"
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            DecimalFormat("0.###").format(value)
        }
    }

    /**
     * 根据拟合参数和像素值预测浓度
     * @param parameters 拟合参数数组
     * @param function 拟合函数类型
     * @param pixelValue 像素值
     * @return 预测的浓度值
     */
    fun predictConcentration(
        parameters: DoubleArray,
        function: FittingFunction,
        pixelValue: Double
    ): Double {
        // 将DoubleArray转换为Map
        val paramMap = function.requiredParams.mapIndexed { index, paramName ->
            paramName to parameters.getOrElse(index) { 0.0 }
        }.toMap()

        return when (function) {
            FittingFunction.LINEAR -> {
                val a = paramMap["a"] ?: 0.0
                val b = paramMap["b"] ?: 0.0
                if (a == 0.0) return 0.0
                return (pixelValue - b) / a
            }

            FittingFunction.QUADRATIC -> {
                val a = paramMap["a"] ?: 0.0
                val b = paramMap["b"] ?: 0.0
                val c = paramMap["c"] ?: 0.0

                // 求解一元二次方程 ax^2 + bx + (c - pixelValue) = 0
                if (a == 0.0) {
                    // 退化为线性方程
                    if (b == 0.0) return 0.0
                    return (pixelValue - c) / b
                }

                val discriminant = b * b - 4 * a * (c - pixelValue)
                if (discriminant < 0) return 0.0

                val x1 = (-b + sqrt(discriminant)) / (2 * a)
                val x2 = (-b - sqrt(discriminant)) / (2 * a)

                // 返回正值解
                return if (x1 > 0) x1 else if (x2 > 0) x2 else 0.0
            }

            FittingFunction.EXPONENTIAL -> {
                val a = paramMap["a"] ?: 0.0
                val b = paramMap["b"] ?: 0.0
                if (a <= 0 || b == 0.0 || pixelValue <= 0) return 0.0
                return ln(pixelValue / a) / b
            }

            FittingFunction.POWER -> {
                val a = paramMap["a"] ?: 0.0
                val b = paramMap["b"] ?: 0.0
                if (a <= 0 || b == 0.0 || pixelValue <= 0) return 0.0
                return (pixelValue / a).pow(1.0 / b)
            }

            FittingFunction.LOG -> {
                val a = paramMap["a"] ?: 0.0
                val b = paramMap["b"] ?: 0.0
                if (b == 0.0) return 0.0
                val expArg = (pixelValue - a) / b
                return exp(expArg)
            }

            FittingFunction.RODBARD -> {
                // 4PL 有稳定解析反函数，避免旧固定二分范围把大于1000的真实浓度压错。
                invertLogisticConcentration(paramMap, pixelValue, fiveParameter = false)
            }

            FittingFunction.GAMMA_VARIATE -> {
                // 伽马变量函数需要数值求解
                solveBisection(paramMap, function, pixelValue, 0.001, 1000.0)
            }

            FittingFunction.CUSTOM_LOG -> {
                val a = paramMap["a"] ?: 0.0
                val b = paramMap["b"] ?: 0.0
                val c = paramMap["c"] ?: 0.0
                if (b == 0.0) return 0.0
                val expArg = (pixelValue - a) / b
                return exp(expArg) + c
            }

            FittingFunction.RODBARD_NIH -> {
                // 需要数值求解
                solveBisection(paramMap, function, pixelValue, 0.001, 1000.0)
            }

            FittingFunction.EXPONENTIAL_WITH_OFFSET -> {
                val a = paramMap["a"] ?: 0.0
                val b = paramMap["b"] ?: 0.0
                val c = paramMap["c"] ?: 0.0
                if (a == 0.0 || b == 0.0) return 0.0
                val adjusted = pixelValue - c
                if (adjusted <= 0 || adjusted >= a) return 0.0
                return -ln(adjusted / a) / b
            }

            FittingFunction.GAUSSIAN -> {
                // 高斯函数需要数值求解
                solveBisection(paramMap, function, pixelValue, 0.001, 1000.0)
            }

            FittingFunction.EXPONENTIAL_RECOVERY -> {
                val a = paramMap["a"] ?: 0.0
                val b = paramMap["b"] ?: 0.0
                if (a == 0.0 || b == 0.0) return 0.0
                val ratio = pixelValue / a
                if (ratio >= 1.0 || ratio <= 0.0) return 0.0
                return -ln(1 - ratio) / b
            }

            FittingFunction.LOGISTIC -> {
                // 5PL 同样使用解析反函数；非法或超出渐近范围时保持旧接口的0兜底语义。
                invertLogisticConcentration(paramMap, pixelValue, fiveParameter = true)
            }

            FittingFunction.GOMPERTZ -> {
                // Gompertz函数需要数值求解
                solveBisection(paramMap, function, pixelValue, 0.001, 1000.0)
            }

            FittingFunction.HILL -> {
                // Hill函数需要数值求解
                solveBisection(paramMap, function, pixelValue, 0.001, 1000.0)
            }

            FittingFunction.GENERAL_GOMPERTZ -> {
                // 广义Gompertz函数需要数值求解
                solveBisection(paramMap, function, pixelValue, 0.001, 1000.0)
            }

            FittingFunction.RICHARDS -> {
                // Richards函数需要数值求解
                solveBisection(paramMap, function, pixelValue, 0.001, 1000.0)
            }

            FittingFunction.CUBIC -> {
                // 三次方程需要数值求解
                solveBisection(paramMap, function, pixelValue, 0.001, 1000.0)
            }

            FittingFunction.QUARTIC -> {
                // 四次方程需要数值求解
                solveBisection(paramMap, function, pixelValue, 0.001, 1000.0)
            }

            FittingFunction.INTERPOLATION -> {
                // 插值函数的反向查找
                val points = mutableListOf<Pair<Double, Double>>()
                for (i in 0..9) {
                    val x = paramMap["x$i"]
                    val y = paramMap["y$i"]
                    if (x != null && y != null) {
                        points.add(Pair(y, x)) // 注意这里x和y交换了
                    }
                }

                if (points.isEmpty()) return 0.0
                if (points.size == 1) return points[0].second

                val sortedPoints = points.sortedBy { it.first }

                // 在排序后的点中查找插值
                for (i in 0 until sortedPoints.size - 1) {
                    val y1 = sortedPoints[i].first
                    val x1 = sortedPoints[i].second
                    val y2 = sortedPoints[i + 1].first
                    val x2 = sortedPoints[i + 1].second

                    if (pixelValue >= y1 && pixelValue <= y2) {
                        // 线性插值
                        return x1 + (x2 - x1) * (pixelValue - y1) / (y2 - y1)
                    }
                }

                // 外推
                return if (pixelValue < sortedPoints.first().first) {
                    sortedPoints.first().second
                } else {
                    sortedPoints.last().second
                }
            }
        }
    }

    /**
     * 解析反算4PL/5PL浓度。
     *
     * 4PL：x = c * (((a-d)/(y-d))-1)^(1/b)
     * 5PL：x = c * ((((a-d)/(y-d))^(1/g))-1)^(1/b)
     */
    private fun invertLogisticConcentration(
        parameters: Map<String, Double>,
        signal: Double,
        fiveParameter: Boolean
    ): Double {
        val a = parameters["a"] ?: return 0.0
        val slope = parameters["b"] ?: return 0.0
        val center = parameters["c"] ?: return 0.0
        val d = parameters["d"] ?: return 0.0
        val asymmetry = if (fiveParameter) parameters["g"] ?: return 0.0 else 1.0
        val signalOffset = signal - d
        if (abs(signalOffset) <= 1e-12 || slope <= 0.0 || center <= 0.0 || asymmetry <= 0.0) {
            return 0.0
        }
        val ratio = (a - d) / signalOffset
        if (!ratio.isFinite() || ratio <= 0.0) return 0.0
        val powered = ratio.pow(1.0 / asymmetry) - 1.0
        if (!powered.isFinite() || powered < -1e-12) return 0.0
        val concentration = if (powered <= 1e-12) 0.0 else center * powered.pow(1.0 / slope)
        return concentration.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    }

    /**
     * 使用二分法求解方程 f(x) = targetValue
     */
    private fun solveBisection(
        paramMap: Map<String, Double>,
        function: FittingFunction,
        targetValue: Double,
        xMin: Double = 0.001,
        xMax: Double = 1000.0,
        tolerance: Double = 1e-6,
        maxIterations: Int = 100
    ): Double {
        var low = xMin
        var high = xMax
        var iterations = 0

        // 检查边界条件
        val fLow = calculate(function, paramMap, low)
        val fHigh = calculate(function, paramMap, high)

        // 如果目标值在边界外，扩展搜索范围
        if (targetValue < fLow && targetValue < fHigh) {
            if (fLow < fHigh) {
                high = low
                low = low / 10
            } else {
                low = high
                high = high * 10
            }
        } else if (targetValue > fLow && targetValue > fHigh) {
            if (fLow > fHigh) {
                high = low
                low = low / 10
            } else {
                low = high
                high = high * 10
            }
        }

        while (high - low > tolerance && iterations < maxIterations) {
            val mid = (low + high) / 2
            val fMid = calculate(function, paramMap, mid)

            if (abs(fMid - targetValue) < tolerance) {
                return mid
            }

            val fLowCurrent = calculate(function, paramMap, low)
            if ((fLowCurrent - targetValue) * (fMid - targetValue) < 0) {
                high = mid
            } else {
                low = mid
            }

            iterations++
        }

        return (low + high) / 2
    }

    /**
     * 执行完整的拟合工作流：标准品拟合 + 样本浓度预测
     * 
     * @param standardPoints 标准品数据点（浓度，像素值）
     * @param samplePixelValues 样本的像素值列表
     * @param function 拟合函数类型
     * @param pixelType 像素类型（可选）
     * @return 包含拟合结果和样本浓度预测的 FittingResult
     */
    fun fitWithPredictions(
        standardPoints: List<Pair<Double, Double>>,
        samplePixelValues: List<Double>,
        function: FittingFunction,
        pixelType: PixelType? = null
    ): FittingResult {
        // 执行标准品拟合
        val fittingResult = fitSingle(standardPoints, function)
        
        if (!fittingResult.isSuccess) {
            return fittingResult
        }
        
        // 预测样本浓度
        val predictions = samplePixelValues.mapIndexed { index, pixelValue ->
            try {
                val concentration = predictConcentration(
                    fittingResult.parameters,
                    function,
                    pixelValue
                )
                ConcentrationPrediction(
                    sampleIndex = index,
                    pixelValue = pixelValue,
                    concentration = concentration,
                    pixelType = pixelType,
                    isValid = concentration >= 0.0 && concentration.isFinite()
                )
            } catch (e: Exception) {
                ConcentrationPrediction(
                    sampleIndex = index,
                    pixelValue = pixelValue,
                    concentration = 0.0,
                    pixelType = pixelType,
                    isValid = false,
                    errorMessage = e.message
                )
            }
        }
        
        // 返回包含预测结果的新 FittingResult
        return fittingResult.copy(
            pixelType = pixelType,
            predictions = predictions
        )
    }

    /**
     * 为样本数据批量预测浓度
     * 
     * @param fittingResult 拟合结果
     * @param samplePixelValues 样本像素值列表
     * @return 浓度预测结果列表
     */
    fun batchPredictConcentrations(
        fittingResult: FittingResult,
        samplePixelValues: List<Double>
    ): List<ConcentrationPrediction> {
        return samplePixelValues.mapIndexed { index, pixelValue ->
            try {
                val concentration = predictConcentration(
                    fittingResult.parameters,
                    fittingResult.function,
                    pixelValue
                )
                ConcentrationPrediction(
                    sampleIndex = index,
                    pixelValue = pixelValue,
                    concentration = concentration,
                    isValid = concentration >= 0.0 && concentration.isFinite()
                )
            } catch (e: Exception) {
                ConcentrationPrediction(
                    sampleIndex = index,
                    pixelValue = pixelValue,
                    concentration = 0.0,
                    isValid = false,
                    errorMessage = e.message
                )
            }
        }
    }
}

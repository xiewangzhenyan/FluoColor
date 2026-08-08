package com.muc.fluocolorquant.domain.detection.quantification

import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 端点标准曲线反算只在模型声明的可靠范围内输出浓度。 */
class StandardCurveQuantifierTest {

    @Test
    fun `项目量程宽于标定范围时保留有界外推浓度和范围状态`() {
        val bundle = linearBundle(
            parametersJson = """{"a":2.0,"b":1.0}""",
            reliableRangeMin = 28.0,
            reliableRangeMax = 34.0
        )
        val ready = StandardCurveQuantifier.prepare(
            bundle = bundle,
            projectRangeMin = 0.0,
            projectRangeMax = 100.0
        ) as PreparedStandardCurveQuantifier.Ready

        val belowCalibration = ready.quantify(41.0) as PreparedEndpointQuantificationResult.Quantified
        val withinCalibration = ready.quantify(61.0) as PreparedEndpointQuantificationResult.Quantified
        val aboveCalibration = ready.quantify(101.0) as PreparedEndpointQuantificationResult.Quantified
        val outsideProject = ready.quantify(251.0) as PreparedEndpointQuantificationResult.OutOfRange

        assertEquals(20.0, belowCalibration.concentration, 1e-6)
        assertEquals(ReliableRangeStatus.BELOW_RANGE, belowCalibration.rangeStatus)
        assertEquals(30.0, withinCalibration.concentration, 1e-6)
        assertEquals(ReliableRangeStatus.WITHIN_RANGE, withinCalibration.rangeStatus)
        assertEquals(50.0, aboveCalibration.concentration, 1e-6)
        assertEquals(ReliableRangeStatus.ABOVE_RANGE, aboveCalibration.rangeStatus)
        assertEquals(ReliableRangeStatus.ABOVE_PROJECT_RANGE, outsideProject.rangeStatus)
    }

    @Test
    fun `V2曲线只在冻结可信范围内输出估计并在范围外给出单侧界限`() {
        val bundle = linearBundle(
            parametersJson = """{"a":2.0,"b":1.0}""",
            reliableRangeMin = 0.0,
            reliableRangeMax = 10.0,
            validationMetricsJson = """
                {
                  "CALIBRATION_ALGORITHM_SCHEMA":"calibration-v2",
                  "TRUSTED_RANGE":{
                    "minimum":0.0,
                    "maximum":20.0,
                    "confidenceLevel":0.95,
                    "parameterSampleCount":256,
                    "validSampleRatio":1.0,
                    "methodVersion":"hessian-sampling-v1",
                    "transformedParameterCovariance":[[0.0,0.0],[0.0,0.0]],
                    "samplingSeed":42,
                    "residualSignalScale":0.01
                  }
                }
            """.trimIndent()
        )
        val ready = StandardCurveQuantifier.prepare(
            bundle = bundle,
            projectRangeMin = 0.0,
            projectRangeMax = 100.0
        ) as PreparedStandardCurveQuantifier.Ready

        val estimated = ready.quantify(31.0) as PreparedEndpointQuantificationResult.Quantified
        val outside = ready.quantify(51.0) as PreparedEndpointQuantificationResult.OutOfRange

        assertEquals(15.0, estimated.concentration, 1e-6)
        assertEquals(QuantificationState.ESTIMATED, estimated.quantificationState)
        assertEquals(15.0, estimated.concentrationLowerBound ?: Double.NaN, 1e-6)
        assertEquals(15.0, estimated.concentrationUpperBound ?: Double.NaN, 1e-6)
        assertEquals(ReliableRangeStatus.ABOVE_TRUSTED_RANGE, outside.rangeStatus)
        assertEquals(20.0, outside.concentrationBound ?: Double.NaN, 1e-6)
        assertEquals(QuantificationCensoringDirection.LOWER_BOUND, outside.censoringDirection)
    }

    @Test
    fun `严重饱和只保存浓度下界而质量失败单独标记不可用`() {
        val ready = StandardCurveQuantifier.prepare(
            linearBundle(parametersJson = """{"a":2.0,"b":1.0}""")
        ) as PreparedStandardCurveQuantifier.Ready

        val saturated = ready.quantify(
            QuantificationObservation(
                signalValue = 21.0,
                qualityReliable = false,
                saturationRatio = 0.30,
                photometryFlags = setOf("SATURATED")
            )
        ) as PreparedEndpointQuantificationResult.OutOfRange
        val unavailable = ready.quantify(
            QuantificationObservation(
                signalValue = 21.0,
                qualityReliable = false,
                saturationRatio = 0.0
            )
        ) as PreparedEndpointQuantificationResult.Unavailable

        assertEquals(10.0, saturated.concentrationBound ?: Double.NaN, 1e-6)
        assertEquals(QuantificationCensoringDirection.LOWER_BOUND, saturated.censoringDirection)
        assertEquals(QuantificationState.BOUND_ONLY, saturated.quantificationState)
        assertEquals(EndpointQuantificationReason.UNRELIABLE_OBSERVATION, unavailable.reason)
        assertEquals(QuantificationState.UNAVAILABLE, unavailable.quantificationState)
    }

    @Test
    fun `五参数逻辑曲线允许项目量程从零开始并反算真实孔位信号`() {
        val bundle = linearBundle(
            fittingFunction = "logistic_5pl",
            parametersJson = """{"a":361625.78152999684,"b":8.547083392401774,"c":34.921194891259134,"d":1113444.9059174429,"g":0.4861663820746118}""",
            reliableRangeMin = 26.0,
            reliableRangeMax = 85.0,
            monotonicDirection = "AUTO"
        )

        val ready = StandardCurveQuantifier.prepare(
            bundle = bundle,
            projectRangeMin = 0.0,
            projectRangeMax = 100.0
        ) as PreparedStandardCurveQuantifier.Ready
        val quantified = ready.quantify(756000.0) as PreparedEndpointQuantificationResult.Quantified

        // 该参数和信号来自用户本次96孔板现场标定运行。回归测试固定真实故障样例，
        // 防止以后再次把“量程从0开始”的有效5PL整批降级为仅信号。
        assertEquals(40.58720368279272, quantified.concentration, 1e-6)
        assertEquals("ng/mL", quantified.unit)
        assertEquals(ReliableRangeStatus.WITHIN_RANGE, quantified.rangeStatus)
    }

    @Test
    fun `四参数逻辑曲线允许项目量程从零开始`() {
        val bundle = linearBundle(
            fittingFunction = "rodbard_4pl",
            parametersJson = """{"a":100.0,"b":2.0,"c":10.0,"d":0.0}""",
            reliableRangeMin = 2.0,
            reliableRangeMax = 20.0,
            monotonicDirection = "AUTO"
        )

        val ready = StandardCurveQuantifier.prepare(
            bundle = bundle,
            projectRangeMin = 0.0,
            projectRangeMax = 100.0
        ) as PreparedStandardCurveQuantifier.Ready
        val quantified = ready.quantify(50.0) as PreparedEndpointQuantificationResult.Quantified

        assertEquals(10.0, quantified.concentration, 1e-6)
        assertEquals(ReliableRangeStatus.WITHIN_RANGE, quantified.rangeStatus)
    }

    @Test
    fun `逻辑曲线零浓度端点仍拒绝非正斜率幂`() {
        listOf("rodbard_4pl", "rodbard_nih", "logistic_5pl").forEach { function ->
            val parameters = when (function) {
                "rodbard_4pl" -> """{"a":100.0,"b":-1.0,"c":10.0,"d":0.0}"""
                "rodbard_nih" -> """{"a":100.0,"b":-1.0,"c":10.0}"""
                else -> """{"a":100.0,"b":-1.0,"c":10.0,"d":0.0,"g":1.0}"""
            }
            val preparation = StandardCurveQuantifier.prepare(
                bundle = linearBundle(
                    fittingFunction = function,
                    parametersJson = parameters,
                    reliableRangeMin = 1.0,
                    reliableRangeMax = 20.0,
                    monotonicDirection = "AUTO"
                ),
                projectRangeMin = 0.0,
                projectRangeMax = 100.0
            )

            assertEquals(
                function,
                EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
                (preparation as PreparedStandardCurveQuantifier.SignalOnly).reason
            )
        }
    }

    @Test
    fun `线性曲线y等于2x加1时信号21反算浓度10`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(parametersJson = """{"a":2.0,"b":1.0}"""),
            signalValue = 21.0
        )

        val quantified = result as EndpointQuantificationResult.Quantified
        assertEquals(10.0, quantified.concentration, 1e-6)
        assertEquals("ng/mL", quantified.unit)
        assertEquals(ReliableRangeStatus.WITHIN_RANGE, quantified.rangeStatus)
        assertTrue(quantified.modelSnapshotJson.contains("model-linear"))
    }

    @Test
    fun `递减线性曲线按声明方向正确反算`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(
                parametersJson = """{"a":-1.0,"b":101.0}""",
                monotonicDirection = "DECREASING"
            ),
            signalValue = 91.0
        )

        assertEquals(10.0, (result as EndpointQuantificationResult.Quantified).concentration, 1e-6)
    }

    @Test
    fun `信号超出可靠曲线端点时不外推浓度`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(parametersJson = """{"a":2.0,"b":1.0}"""),
            signalValue = 250.0
        )

        val outOfRange = result as EndpointQuantificationResult.OutOfRange
        assertEquals(ReliableRangeStatus.ABOVE_RANGE, outOfRange.rangeStatus)
    }

    @Test
    fun `插值曲线使用未排除标定点分段反算`() {
        val bundle = linearBundle(
            fittingFunction = "interpolation",
            parametersJson = "{}",
            calibrationPoints = listOf(
                point("p0", concentration = 0.0, signal = 5.0),
                point("p1", concentration = 10.0, signal = 25.0),
                point("excluded", concentration = 50.0, signal = 26.0, excluded = true),
                point("p2", concentration = 100.0, signal = 205.0)
            )
        )

        val result = StandardCurveQuantifier.quantify(bundle, signalValue = 15.0)

        assertEquals(5.0, (result as EndpointQuantificationResult.Quantified).concentration, 1e-6)
    }

    @Test
    fun `插值先对同一浓度的未排除重复点求信号均值`() {
        val bundle = linearBundle(
            fittingFunction = "interpolation",
            parametersJson = "{}",
            reliableRangeMin = 0.0,
            reliableRangeMax = 10.0,
            calibrationPoints = listOf(
                point("zero", concentration = 0.0, signal = 0.0),
                point("repeat-1", concentration = 10.0, signal = 10.0),
                point("repeat-2", concentration = 10.0, signal = 30.0)
            )
        )

        val result = StandardCurveQuantifier.quantify(bundle, signalValue = 10.0)

        // 10 浓度处的信号应先求 (10 + 30) / 2 = 20，因此信号 10 对应浓度 5。
        assertEquals(5.0, (result as EndpointQuantificationResult.Quantified).concentration, 1e-6)
    }

    @Test
    fun `递增插值只在可靠浓度范围内反算并以边界信号判定越界`() {
        val bundle = linearBundle(
            fittingFunction = "interpolation",
            parametersJson = "{}",
            reliableRangeMin = 10.0,
            reliableRangeMax = 20.0,
            calibrationPoints = listOf(
                point("wide-low", concentration = 0.0, signal = 0.0),
                point("wide-high", concentration = 30.0, signal = 30.0)
            )
        )

        val belowRange = StandardCurveQuantifier.quantify(bundle, signalValue = 5.0)
        val withinRange = StandardCurveQuantifier.quantify(bundle, signalValue = 15.0)
        val aboveRange = StandardCurveQuantifier.quantify(bundle, signalValue = 25.0)

        // 可靠边界应由标定段正向插值得到 10 和 20，而不能错误使用全量标定端点 0 和 30。
        assertEquals(
            ReliableRangeStatus.BELOW_RANGE,
            (belowRange as EndpointQuantificationResult.OutOfRange).rangeStatus
        )
        assertEquals(15.0, (withinRange as EndpointQuantificationResult.Quantified).concentration, 1e-6)
        assertEquals(
            ReliableRangeStatus.ABOVE_RANGE,
            (aboveRange as EndpointQuantificationResult.OutOfRange).rangeStatus
        )
    }

    @Test
    fun `递减插值按可靠范围边界返回正确越界方向`() {
        val bundle = linearBundle(
            fittingFunction = "interpolation",
            parametersJson = "{}",
            monotonicDirection = "DECREASING",
            reliableRangeMin = 10.0,
            reliableRangeMax = 20.0,
            calibrationPoints = listOf(
                point("wide-low", concentration = 0.0, signal = 100.0),
                point("wide-high", concentration = 30.0, signal = 40.0)
            )
        )

        val belowRange = StandardCurveQuantifier.quantify(bundle, signalValue = 90.0)
        val withinRange = StandardCurveQuantifier.quantify(bundle, signalValue = 70.0)
        val aboveRange = StandardCurveQuantifier.quantify(bundle, signalValue = 50.0)

        // 递减曲线中，高于低浓度边界信号表示浓度低于可靠范围，方向不能和递增曲线混用。
        assertEquals(
            ReliableRangeStatus.BELOW_RANGE,
            (belowRange as EndpointQuantificationResult.OutOfRange).rangeStatus
        )
        assertEquals(15.0, (withinRange as EndpointQuantificationResult.Quantified).concentration, 1e-6)
        assertEquals(
            ReliableRangeStatus.ABOVE_RANGE,
            (aboveRange as EndpointQuantificationResult.OutOfRange).rangeStatus
        )
    }

    @Test
    fun `缺少必需参数时明确返回仅信号原因`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(parametersJson = """{"a":2.0}"""),
            signalValue = 21.0
        )

        assertEquals(
            EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
            (result as EndpointQuantificationResult.SignalOnly).reason
        )
    }

    @Test
    fun `非有限信号不执行反算而返回仅信号原因`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(parametersJson = """{"a":2.0,"b":1.0}"""),
            signalValue = Double.NaN
        )

        // NaN 不能参与二分反算，必须保留稳定原因，避免写入看似有效的浓度。
        assertEquals(
            EndpointQuantificationReason.NON_FINITE_SIGNAL,
            (result as EndpointQuantificationResult.SignalOnly).reason
        )
    }

    @Test
    fun `可靠范围内发生回折的曲线不反算浓度`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(
                fittingFunction = "quadratic",
                parametersJson = """{"a":1.0,"b":-10.0,"c":0.0}"""
            ),
            signalValue = 12.0
        )

        // 二次函数在 0 到 100 的可靠范围内先减后增，反函数不唯一，必须安全降级为仅信号。
        assertEquals(
            EndpointQuantificationReason.NON_MONOTONIC_MODEL,
            (result as EndpointQuantificationResult.SignalOnly).reason
        )
    }

    @Test
    fun `窄二次回折不能被均匀采样误判为单调递增`() {
        val preparation = StandardCurveQuantifier.prepare(
            linearBundle(
                fittingFunction = "quadratic",
                parametersJson = """{"a":1.0,"b":-0.2,"c":0.01}""",
                reliableRangeMin = 0.0,
                reliableRangeMax = 256.0
            )
        )

        // y=(x-0.1)^2 只在 [0, 0.1] 内递减。257 点采样步长为 1，端点样本会
        // 呈现递增假象；必须通过导数根 x=0.1 对整个可靠区间作数学证明后拒绝。
        assertEquals(
            EndpointQuantificationReason.NON_MONOTONIC_MODEL,
            (preparation as PreparedStandardCurveQuantifier.SignalOnly).reason
        )
    }

    @Test
    fun `Gamma和Gaussian可靠区间跨峰值时拒绝反算`() {
        val crossingPeakBundles = listOf(
            "Gamma" to linearBundle(
                fittingFunction = "gamma_variate",
                parametersJson = """{"a":1.0,"b":0.0,"c":2.0,"d":1.0}""",
                monotonicDirection = "AUTO",
                reliableRangeMin = 0.5,
                reliableRangeMax = 3.0
            ),
            "Gaussian" to linearBundle(
                fittingFunction = "gaussian",
                parametersJson = """{"a":0.0,"b":10.0,"c":5.0,"d":1.0}""",
                monotonicDirection = "AUTO",
                reliableRangeMin = 0.0,
                reliableRangeMax = 10.0
            )
        )

        crossingPeakBundles.forEach { (caseName, bundle) ->
            val preparation = StandardCurveQuantifier.prepare(bundle)
            assertEquals(
                caseName,
                EndpointQuantificationReason.NON_MONOTONIC_MODEL,
                (preparation as PreparedStandardCurveQuantifier.SignalOnly).reason
            )
        }
    }

    @Test
    fun `解析导数允许有效单调多项式和孤立驻点`() {
        val monotonicPolynomialBundles = listOf(
            linearBundle(
                fittingFunction = "linear",
                parametersJson = """{"a":2.0,"b":1.0}""",
                reliableRangeMin = -10.0,
                reliableRangeMax = 10.0
            ),
            linearBundle(
                fittingFunction = "quadratic",
                parametersJson = """{"a":1.0,"b":2.0,"c":1.0}""",
                reliableRangeMin = 0.0,
                reliableRangeMax = 10.0
            ),
            // y=x^3 的导数 3x^2 在 x=0 有孤立零点但不会改变符号，应允许反算。
            linearBundle(
                fittingFunction = "cubic",
                parametersJson = """{"a":1.0,"b":0.0,"c":0.0,"d":0.0}""",
                reliableRangeMin = -1.0,
                reliableRangeMax = 1.0
            ),
            linearBundle(
                fittingFunction = "quartic",
                parametersJson = """{"a":1.0,"b":0.0,"c":0.0,"d":1.0,"e":0.0}""",
                reliableRangeMin = 0.0,
                reliableRangeMax = 2.0
            )
        )

        monotonicPolynomialBundles.forEach { bundle ->
            assertTrue(StandardCurveQuantifier.prepare(bundle) is PreparedStandardCurveQuantifier.Ready)
        }
    }

    @Test
    fun `非标准曲线模型不借用通用深度学习模型反算`() {
        val standardCurveBundle = linearBundle(parametersJson = """{"a":2.0,"b":1.0}""")
        val bundle = standardCurveBundle.copy(
            model = standardCurveBundle.model.copy(modelType = "DEEP_LEARNING")
        )

        val result = StandardCurveQuantifier.quantify(bundle, signalValue = 21.0)

        // 端点量化器只执行显式标准曲线，不能以旧通用深度学习模型猜测浓度。
        assertEquals(
            EndpointQuantificationReason.UNSUPPORTED_MODEL_TYPE,
            (result as EndpointQuantificationResult.SignalOnly).reason
        )
    }

    @Test
    fun `非有限标定点在序列化快照前安全降级为仅信号`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(
                parametersJson = """{"a":2.0,"b":1.0}""",
                calibrationPoints = listOf(point("damaged", concentration = 10.0, signal = Double.NaN))
            ),
            signalValue = 21.0
        )

        assertEquals(
            EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
            (result as EndpointQuantificationResult.SignalOnly).reason
        )
    }

    @Test
    fun `非有限LOD在序列化快照前安全降级为仅信号`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(
                parametersJson = """{"a":2.0,"b":1.0}""",
                lod = Double.POSITIVE_INFINITY
            ),
            signalValue = 21.0
        )

        assertEquals(
            EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
            (result as EndpointQuantificationResult.SignalOnly).reason
        )
    }

    @Test
    fun `非有限LOQ在序列化快照前安全降级为仅信号`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(
                parametersJson = """{"a":2.0,"b":1.0}""",
                loq = Double.NaN
            ),
            signalValue = 21.0
        )

        // LOD 与 LOQ 都属于模型快照的科学输入，任一损坏都不能让运行链路抛异常。
        assertEquals(
            EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
            (result as EndpointQuantificationResult.SignalOnly).reason
        )
    }

    @Test
    fun `非有限曲线参数安全降级为仅信号`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(parametersJson = """{"a":"NaN","b":1.0}"""),
            signalValue = 21.0
        )

        // 参数解析必须在执行正向公式前拒绝 NaN，不能把损坏参数传给旧拟合引擎。
        assertEquals(
            EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
            (result as EndpointQuantificationResult.SignalOnly).reason
        )
    }

    @Test
    fun `量化器正向值与旧拟合引擎保持一致`() {
        val cases = listOf(
            ForwardCase("power", """{"a":2.0,"b":2.0}""", 1.0, 10.0, 3.0),
            ForwardCase("rodbard_4pl", """{"a":100.0,"b":1.0,"c":10.0,"d":0.0}""", 1.0, 20.0, 10.0),
            ForwardCase("rodbard_nih", """{"a":100.0,"b":1.0,"c":10.0}""", 1.0, 20.0, 10.0),
            ForwardCase("logistic_5pl", """{"a":100.0,"b":1.0,"c":10.0,"d":0.0,"g":1.0}""", 1.0, 20.0, 10.0),
            ForwardCase("hill", """{"a":100.0,"b":1.0,"c":10.0}""", 1.0, 20.0, 10.0),
            ForwardCase("gamma_variate", """{"a":10.0,"b":0.0,"c":1.0,"d":10.0}""", 1.0, 2.0, 1.5),
            ForwardCase("gompertz_general", """{"a":100.0,"b":1.0,"c":1.0,"d":1.0}""", 1.0, 10.0, 5.0),
            ForwardCase("richards", """{"a":100.0,"b":1.0,"c":1.0,"d":1.0}""", 1.0, 10.0, 5.0)
        )

        cases.forEach { case ->
            val function = requireNotNull(com.muc.fluocolorquant.data.enums.FittingFunction.fromIdentifier(case.function))
            val signal = FittingEngine.calculate(function, case.parameters, case.concentration)
            val result = StandardCurveQuantifier.quantify(
                bundle = linearBundle(
                    fittingFunction = case.function,
                    parametersJson = case.parametersJson,
                    reliableRangeMin = case.minimum,
                    reliableRangeMax = case.maximum,
                    monotonicDirection = "AUTO"
                ),
                signalValue = signal
            )

            // 信号由旧页面实际使用的 FittingEngine 生成，反算必须回到相同浓度而非使用漂移公式。
            assertEquals(
                case.concentration,
                (result as EndpointQuantificationResult.Quantified).concentration,
                1e-5
            )
        }
    }

    @Test
    fun `Rodbard零尺度参数不接受旧引擎容错替代`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(
                fittingFunction = "rodbard_4pl",
                parametersJson = """{"a":100.0,"b":1.0,"c":0.0,"d":0.0}""",
                reliableRangeMin = 1.0,
                reliableRangeMax = 20.0,
                monotonicDirection = "DECREASING"
            ),
            signalValue = 50.0
        )

        assertEquals(
            EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
            (result as EndpointQuantificationResult.SignalOnly).reason
        )
    }

    @Test
    fun `伽马曲线可靠范围跨越定义域时拒绝反算`() {
        val result = StandardCurveQuantifier.quantify(
            bundle = linearBundle(
                fittingFunction = "gamma_variate",
                parametersJson = """{"a":10.0,"b":5.0,"c":1.0,"d":2.0}""",
                reliableRangeMin = 0.0,
                reliableRangeMax = 10.0,
                monotonicDirection = "INCREASING"
            ),
            signalValue = 3.0
        )

        assertEquals(
            EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
            (result as EndpointQuantificationResult.SignalOnly).reason
        )
    }

    @Test
    fun `对数幂函数和Richards在无效定义域稳定降级`() {
        val invalidBundles = listOf(
            linearBundle("""{"a":1.0,"b":1.0}""", fittingFunction = "power", reliableRangeMin = 0.0),
            linearBundle("""{"a":1.0,"b":1.0}""", fittingFunction = "log", reliableRangeMin = 0.0),
            linearBundle("""{"a":1.0,"b":1.0,"c":0.0,"d":0.0}""", fittingFunction = "richards")
        )

        invalidBundles.forEach { bundle ->
            val result = StandardCurveQuantifier.quantify(bundle, signalValue = 1.0)
            assertEquals(
                EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
                (result as EndpointQuantificationResult.SignalOnly).reason
            )
        }
    }

    @Test
    fun `prepare一次后同一Ready可稳定量化多个位点`() {
        val mutablePoints = mutableListOf(
            point("low", concentration = 0.0, signal = 0.0),
            point("high", concentration = 20.0, signal = 20.0)
        )
        val preparation = StandardCurveQuantifier.prepare(
            linearBundle(
                fittingFunction = "interpolation",
                parametersJson = "{}",
                reliableRangeMin = 0.0,
                reliableRangeMax = 20.0,
                calibrationPoints = mutablePoints
            )
        )
        val ready = preparation as PreparedStandardCurveQuantifier.Ready

        val first = ready.quantify(5.0) as PreparedEndpointQuantificationResult.Quantified
        // 准备完成后即冻结插值点；调用方持有的可变列表后续变化不能触发重复准备或改变已准备模型。
        mutablePoints.clear()
        val second = ready.quantify(15.0) as PreparedEndpointQuantificationResult.Quantified

        assertEquals(5.0, first.concentration, 1e-9)
        assertEquals(15.0, second.concentration, 1e-9)
        assertEquals(first.modelSnapshotJson, second.modelSnapshotJson)
    }

    @Test
    fun `prepare在模型级错误时返回稳定SignalOnly`() {
        val source = linearBundle(parametersJson = """{"a":2.0,"b":1.0}""")
        val preparation = StandardCurveQuantifier.prepare(
            source.copy(model = source.model.copy(modelType = AnalysisModelType.DEEP_LEARNING.code))
        )

        val signalOnly = preparation as PreparedStandardCurveQuantifier.SignalOnly
        assertEquals(EndpointQuantificationReason.UNSUPPORTED_MODEL_TYPE, signalOnly.reason)
    }

    @Test
    fun `Ready对非有限逐位点信号只降级当前位点`() {
        val ready = StandardCurveQuantifier.prepare(
            linearBundle(parametersJson = """{"a":2.0,"b":1.0}""")
        ) as PreparedStandardCurveQuantifier.Ready

        val damagedSite = ready.quantify(Double.NaN)
        val validSite = ready.quantify(21.0) as PreparedEndpointQuantificationResult.Quantified

        assertTrue(damagedSite === PreparedEndpointQuantificationResult.SiteSignalOnly)
        assertEquals(
            EndpointQuantificationReason.NON_FINITE_SIGNAL,
            PreparedEndpointQuantificationResult.SiteSignalOnly.reason
        )
        assertEquals(10.0, validSite.concentration, 1e-6)
    }

    @Test
    fun `快照记录量化器与公式引擎版本`() {
        val result = StandardCurveQuantifier.quantify(
            linearBundle(parametersJson = """{"a":2.0,"b":1.0}"""),
            signalValue = 21.0
        ) as EndpointQuantificationResult.Quantified
        val snapshot = JsonParser().parse(result.modelSnapshotJson).asJsonObject

        assertEquals(ENDPOINT_QUANTIFIER_VERSION, snapshot["quantifierVersion"].asString)
        assertEquals(FORMULA_ENGINE_VERSION, snapshot["formulaEngineVersion"].asString)
    }

    @Test
    fun `所有共享公式绘图兜底参数都在准备阶段被严格拒绝`() {
        val invalidBundles = listOf(
            "Rodbard c为零" to linearBundle(
                fittingFunction = "rodbard_4pl",
                parametersJson = """{"a":100.0,"b":1.0,"c":0.0,"d":0.0}""",
                reliableRangeMin = 1.0
            ),
            "Rodbard NIH c为负" to linearBundle(
                fittingFunction = "rodbard_nih",
                parametersJson = """{"a":100.0,"b":1.0,"c":-1.0}""",
                reliableRangeMin = 1.0
            ),
            "Logistic c为零" to linearBundle(
                fittingFunction = "logistic_5pl",
                parametersJson = """{"a":100.0,"b":1.0,"c":0.0,"d":0.0,"g":1.0}""",
                reliableRangeMin = 1.0
            ),
            "Gamma a为零" to linearBundle(
                fittingFunction = "gamma_variate",
                parametersJson = """{"a":0.0,"b":0.0,"c":1.0,"d":2.0}""",
                reliableRangeMin = 1.0
            ),
            "Gamma c为零" to linearBundle(
                fittingFunction = "gamma_variate",
                parametersJson = """{"a":1.0,"b":0.0,"c":0.0,"d":2.0}""",
                reliableRangeMin = 1.0
            ),
            "Gamma d为负" to linearBundle(
                fittingFunction = "gamma_variate",
                parametersJson = """{"a":1.0,"b":0.0,"c":1.0,"d":-2.0}""",
                reliableRangeMin = 1.0
            ),
            "CustomLog下限等于c" to linearBundle(
                fittingFunction = "custom_log",
                parametersJson = """{"a":1.0,"b":1.0,"c":1.0}""",
                reliableRangeMin = 1.0
            ),
            "Gaussian d为零" to linearBundle(
                fittingFunction = "gaussian",
                parametersJson = """{"a":0.0,"b":1.0,"c":10.0,"d":0.0}"""
            ),
            "Hill可靠下限为负" to linearBundle(
                fittingFunction = "hill",
                parametersJson = """{"a":100.0,"b":1.0,"c":10.0}""",
                reliableRangeMin = -1.0
            ),
            "Hill c为零" to linearBundle(
                fittingFunction = "hill",
                parametersJson = """{"a":100.0,"b":1.0,"c":0.0}"""
            ),
            "GeneralGompertz可靠下限为负" to linearBundle(
                fittingFunction = "gompertz_general",
                parametersJson = """{"a":100.0,"b":1.0,"c":1.0,"d":1.0}""",
                reliableRangeMin = -1.0
            ),
            "Richards d为零" to linearBundle(
                fittingFunction = "richards",
                parametersJson = """{"a":100.0,"b":1.0,"c":1.0,"d":0.0}"""
            )
        )

        invalidBundles.forEach { (caseName, bundle) ->
            val preparation = StandardCurveQuantifier.prepare(bundle)
            assertTrue(
                "$caseName 应在准备阶段返回模型级 SignalOnly，实际为 $preparation",
                preparation is PreparedStandardCurveQuantifier.SignalOnly
            )
            assertEquals(
                caseName,
                EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
                (preparation as PreparedStandardCurveQuantifier.SignalOnly).reason
            )
        }
    }

    @Test
    fun `可靠区间采样出现非有限值按无效定义处理`() {
        val preparation = StandardCurveQuantifier.prepare(
            linearBundle(
                fittingFunction = "richards",
                parametersJson = """{"a":1.0,"b":-2.0,"c":0.0,"d":2.0}""",
                reliableRangeMin = 0.0,
                reliableRangeMax = 1.0
            )
        )

        assertEquals(
            EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
            (preparation as PreparedStandardCurveQuantifier.SignalOnly).reason
        )
    }

    @Test
    fun `极小信号尺度不被固定绝对容差吞掉`() {
        val ready = StandardCurveQuantifier.prepare(
            linearBundle(
                fittingFunction = "interpolation",
                parametersJson = "{}",
                reliableRangeMin = 0.0,
                reliableRangeMax = 2.0,
                calibrationPoints = listOf(
                    point("zero", concentration = 0.0, signal = 0.0),
                    point("small", concentration = 1.0, signal = 1.1e-9),
                    point("high", concentration = 2.0, signal = 1.0e-6)
                )
            )
        ) as PreparedStandardCurveQuantifier.Ready

        val belowRange = ready.quantify(-0.9e-9)
        val firstSegment = ready.quantify(0.55e-9) as PreparedEndpointQuantificationResult.Quantified

        assertEquals(
            ReliableRangeStatus.BELOW_RANGE,
            (belowRange as PreparedEndpointQuantificationResult.OutOfRange).rangeStatus
        )
        assertEquals(0.5, firstSegment.concentration, 1e-9)
        assertTrue(firstSegment.concentration >= 0.0)
    }

    @Test
    fun `极小尺度拟合曲线仍能识别真实单调变化`() {
        val ready = StandardCurveQuantifier.prepare(
            linearBundle(
                parametersJson = """{"a":1.0e-12,"b":0.0}""",
                reliableRangeMin = 0.0,
                reliableRangeMax = 100.0
            )
        ) as PreparedStandardCurveQuantifier.Ready

        val result = ready.quantify(5.0e-11) as PreparedEndpointQuantificationResult.Quantified

        assertEquals(50.0, result.concentration, 1e-6)
    }

    @Test
    fun `极大信号尺度的相邻边界按ULP容差夹到可靠端点`() {
        val minimumSignal = 1.0e16
        val maximumSignal = minimumSignal + 1.0e8
        val ready = StandardCurveQuantifier.prepare(
            linearBundle(
                fittingFunction = "interpolation",
                parametersJson = "{}",
                reliableRangeMin = 0.0,
                reliableRangeMax = 100.0,
                calibrationPoints = listOf(
                    point("low", concentration = 0.0, signal = minimumSignal),
                    point("high", concentration = 100.0, signal = maximumSignal)
                )
            )
        ) as PreparedStandardCurveQuantifier.Ready

        val justBelow = ready.quantify(Math.nextDown(minimumSignal)) as PreparedEndpointQuantificationResult.Quantified
        val justAbove = ready.quantify(Math.nextUp(maximumSignal)) as PreparedEndpointQuantificationResult.Quantified
        val clearlyBelow = ready.quantify(minimumSignal - 1.0e6) as PreparedEndpointQuantificationResult.OutOfRange

        assertEquals(0.0, justBelow.concentration, 0.0)
        assertEquals(100.0, justAbove.concentration, 0.0)
        assertEquals(ReliableRangeStatus.BELOW_RANGE, clearlyBelow.rangeStatus)
    }

    @Test
    fun `零跨度与近零跨度都稳定判为非单调模型`() {
        val invalidPoints = listOf(
            listOf(
                point("zero-low", concentration = 0.0, signal = 5.0),
                point("zero-high", concentration = 1.0, signal = 5.0)
            ),
            listOf(
                point("tiny-low", concentration = 0.0, signal = 0.0),
                point("tiny-high", concentration = 1.0, signal = 1.0e-321)
            )
        )

        invalidPoints.forEach { points ->
            val preparation = StandardCurveQuantifier.prepare(
                linearBundle(
                    fittingFunction = "interpolation",
                    parametersJson = "{}",
                    reliableRangeMin = 0.0,
                    reliableRangeMax = 1.0,
                    calibrationPoints = points
                )
            )
            assertEquals(
                EndpointQuantificationReason.NON_MONOTONIC_MODEL,
                (preparation as PreparedStandardCurveQuantifier.SignalOnly).reason
            )
        }
    }

    @Test
    fun `标准曲线定义外键错配时prepare独立拒绝定量`() {
        val valid = linearBundle(parametersJson = """{"a":2.0,"b":1.0}""")
        val mismatched = valid.copy(
            standardCurve = requireNotNull(valid.standardCurve).copy(
                analysisModelId = "another-model"
            )
        )

        val preparation = StandardCurveQuantifier.prepare(mismatched)

        assertEquals(
            EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
            (preparation as PreparedStandardCurveQuantifier.SignalOnly).reason
        )
    }

    @Test
    fun `标定点外键错配时prepare独立拒绝定量`() {
        val mismatchedPoint = point(
            id = "mismatched",
            concentration = 10.0,
            signal = 21.0
        ).copy(analysisModelId = "another-model")

        val preparation = StandardCurveQuantifier.prepare(
            linearBundle(
                parametersJson = """{"a":2.0,"b":1.0}""",
                calibrationPoints = listOf(mismatchedPoint)
            )
        )

        assertEquals(
            EndpointQuantificationReason.INVALID_MODEL_DEFINITION,
            (preparation as PreparedStandardCurveQuantifier.SignalOnly).reason
        )
    }

    private fun linearBundle(
        parametersJson: String,
        monotonicDirection: String = "INCREASING",
        fittingFunction: String = "linear",
        calibrationPoints: List<CalibrationPoint> = emptyList(),
        reliableRangeMin: Double = 0.0,
        reliableRangeMax: Double = 100.0,
        lod: Double? = null,
        loq: Double? = null,
        validationMetricsJson: String = """{"r2":0.998}"""
    ): AnalysisModelBundle {
        val model = AnalysisModel(
            id = "model-linear",
            name = "CEA 线性标准曲线",
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = "cea",
            detectionMode = DetectionModality.FLUORESCENCE.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
            processorName = "fluorescence-photometry",
            processorVersion = "v1",
            concentrationUnit = "ng/mL",
            reliableRangeMin = reliableRangeMin,
            reliableRangeMax = reliableRangeMax,
            validationMetricsJson = validationMetricsJson,
            status = AnalysisModelLifecycleStatus.PUBLISHED.code,
            version = 3
        )
        return AnalysisModelBundle(
            model = model,
            standardCurve = StandardCurveDefinition(
                analysisModelId = model.id,
                fittingFunction = fittingFunction,
                parametersJson = parametersJson,
                monotonicDirection = monotonicDirection,
                lod = lod,
                loq = loq
            ),
            calibrationPoints = calibrationPoints
        )
    }

    private fun point(
        id: String,
        concentration: Double,
        signal: Double,
        excluded: Boolean = false
    ): CalibrationPoint = CalibrationPoint(
        id = id,
        analysisModelId = "model-linear",
        concentration = concentration,
        signalValue = signal,
        repeatIndex = 1,
        excluded = excluded
    )

    /** 统一记录旧拟合引擎正向公式与量化反算之间的代表性一致性样例。 */
    private data class ForwardCase(
        val function: String,
        val parametersJson: String,
        val minimum: Double,
        val maximum: Double,
        val concentration: Double
    ) {
        val parameters: Map<String, Double> = com.google.gson.Gson().fromJson(
            parametersJson,
            object : com.google.gson.reflect.TypeToken<Map<String, Double>>() {}.type
        )
    }
}

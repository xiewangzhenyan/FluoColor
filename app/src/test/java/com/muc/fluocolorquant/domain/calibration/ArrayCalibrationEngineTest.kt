package com.muc.fluocolorquant.domain.calibration

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.FittingFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通用阵列标定契约回归，重点防止拟合失败再次被压缩成 null。 */
class ArrayCalibrationEngineTest {

    private val engine = ArrayCalibrationEngine()

    @Test
    fun `无法形成有效曲线时仍返回三个函数及失败原因`() {
        val observations = (0 until 6).map { index ->
            CalibrationStandardObservation(
                siteIndex = index,
                concentration = index.toDouble(),
                signals = mapOf(AnalysisPrimaryFeature.FLUORESCENCE_SNR to 10.0)
            )
        }

        val result = engine.fit(draft(observations))

        assertEquals(3, result.functionResults.size)
        assertTrue(result.functionResults.all { functionResult ->
            functionResult.candidate == null && functionResult.failureReasons.isNotEmpty()
        })
        assertNull(result.recommendedCandidateId)
    }

    @Test
    fun `只有两个浓度水平时线性可用而4PL和5PL明确标记水平不足`() {
        val observations = listOf(
            CalibrationStandardObservation(
                siteIndex = 0,
                concentration = 0.0,
                signals = mapOf(AnalysisPrimaryFeature.FLUORESCENCE_SNR to 2.0)
            ),
            CalibrationStandardObservation(
                siteIndex = 1,
                concentration = 10.0,
                signals = mapOf(AnalysisPrimaryFeature.FLUORESCENCE_SNR to 22.0)
            )
        )

        val result = engine.fit(draft(observations))

        assertEquals(FittingFunction.LINEAR, result.candidates.single().function)
        assertTrue(
            result.functionResults.first { it.function == FittingFunction.RODBARD }
                .failureReasons.contains(CalibrationFailureReason.INSUFFICIENT_STANDARD_LEVELS)
        )
        assertTrue(
            result.functionResults.first { it.function == FittingFunction.LOGISTIC }
                .failureReasons.contains(CalibrationFailureReason.INSUFFICIENT_STANDARD_LEVELS)
        )
    }

    @Test
    fun `非单调实测标准点明确解释4PL和5PL不可用且低R方线性不算验收通过`() {
        val concentrations = listOf(28.0, 29.0, 30.0, 30.0, 31.0, 32.0, 33.0, 34.0)
        val signals = listOf(-86.636, -83.592, -86.326, -87.755, -86.006, -85.781, -84.340, -91.594)
        val observations = concentrations.indices.map { index ->
            CalibrationStandardObservation(
                siteIndex = index,
                concentration = concentrations[index],
                signals = mapOf(AnalysisPrimaryFeature.FLUORESCENCE_SNR to signals[index])
            )
        }

        val result = engine.fit(draft(observations))
        val linear = requireNotNull(
            result.functionResults.first { it.function == FittingFunction.LINEAR }.candidate
        )

        assertTrue(linear.rSquared < CalibrationPolicy.DEFAULT.lowQualityRSquaredThreshold)
        assertEquals(CalibrationCandidateStatus.LOW_QUALITY, linear.status)
        assertTrue(!linear.accepted)
        assertTrue(
            result.functionResults.first { it.function == FittingFunction.RODBARD }
                .failureReasons.contains(CalibrationFailureReason.CURVE_NOT_MONOTONIC)
        )
        assertTrue(
            result.functionResults.first { it.function == FittingFunction.LOGISTIC }
                .failureReasons.contains(CalibrationFailureReason.CURVE_NOT_MONOTONIC)
        )
    }

    @Test
    fun `用户明确选择专家函数时不再被默认三函数集合丢弃`() {
        val observations = (0..5).map { level ->
            CalibrationStandardObservation(
                siteIndex = level,
                concentration = level.toDouble(),
                signals = mapOf(
                    AnalysisPrimaryFeature.FLUORESCENCE_SNR to level.toDouble() * level.toDouble()
                )
            )
        }
        val expertDraft = draft(observations).copy(
            requestedFunctions = setOf(FittingFunction.QUADRATIC)
        )

        val result = engine.fit(expertDraft)

        assertEquals(listOf(FittingFunction.QUADRATIC), result.functionResults.map { it.function })
        assertEquals(FittingFunction.QUADRATIC, requireNotNull(result.candidates.single()).function)
    }

    @Test
    fun `稳健推荐在R方差值小于阈值时选择更简单模型`() {
        val linear = candidate(FittingFunction.LINEAR, rSquared = 0.9980)
        val fourParameter = candidate(FittingFunction.RODBARD, rSquared = 0.9995)
        val selected = CalibrationRecommendationEngine.recommend(
            candidates = listOf(linear, fourParameter),
            policy = CalibrationPolicy.DEFAULT.copy(rSquaredSimplicityTolerance = 0.002)
        )

        assertEquals(FittingFunction.LINEAR, requireNotNull(selected).function)
    }

    @Test
    fun `R方优先策略不会应用简单模型保护`() {
        val linear = candidate(FittingFunction.LINEAR, rSquared = 0.9980)
        val fourParameter = candidate(FittingFunction.RODBARD, rSquared = 0.9995)
        val selected = CalibrationRecommendationEngine.recommend(
            candidates = listOf(linear, fourParameter),
            policy = CalibrationPolicy.DEFAULT.copy(
                strategy = CalibrationStrategy.R_SQUARED_FIRST
            )
        )

        assertEquals(FittingFunction.RODBARD, requireNotNull(selected).function)
    }

    @Test
    fun `跨信号排序不会使用不可比的原始MAE`() {
        val first = candidate(FittingFunction.LINEAR, rSquared = 0.999).copy(
            id = "net-fluorescence",
            primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
            mae = 100.0
        )
        val second = candidate(FittingFunction.LINEAR, rSquared = 0.999).copy(
            id = "snr",
            primaryFeature = AnalysisPrimaryFeature.FLUORESCENCE_SNR,
            mae = 0.01
        )

        val ranked = CalibrationRecommendationEngine.rank(
            candidates = listOf(first, second),
            policy = CalibrationPolicy.DEFAULT.copy(
                strategy = CalibrationStrategy.R_SQUARED_FIRST
            )
        )

        // 两个候选的可比指标完全一致时保持确定性输入顺序；不能因为 SNR 的原始
        // 数值尺度更小，就把它误判为科学质量更高的信号特征。
        assertEquals(first.id, ranked.first().id)
    }

    @Test
    fun `相同科学内容生成稳定曲线资源指纹`() {
        val selected = candidate(FittingFunction.LINEAR, rSquared = 0.999)

        val first = CalibrationResourceFingerprint.create(
            analyteId = "cea",
            modalityCode = DetectionModality.FLUORESCENCE.code,
            concentrationUnit = "ng/mL",
            candidate = selected,
            processorVersion = "FluorescenceProcessor-v2",
            engineVersion = CALIBRATION_ENGINE_VERSION
        )
        val second = CalibrationResourceFingerprint.create(
            analyteId = "cea",
            modalityCode = DetectionModality.FLUORESCENCE.code,
            concentrationUnit = "ng/mL",
            candidate = selected.copy(id = "另一次页面会话生成的临时ID"),
            processorVersion = "FluorescenceProcessor-v2",
            engineVersion = CALIBRATION_ENGINE_VERSION
        )

        assertEquals(first, second)
    }

    @Test
    fun `参数或标准点变化会生成不同曲线资源指纹`() {
        val selected = candidate(FittingFunction.LINEAR, rSquared = 0.999)
        val baseline = CalibrationResourceFingerprint.create(
            analyteId = "cea",
            modalityCode = DetectionModality.FLUORESCENCE.code,
            concentrationUnit = "ng/mL",
            candidate = selected,
            processorVersion = "FluorescenceProcessor-v2",
            engineVersion = CALIBRATION_ENGINE_VERSION
        )
        val parameterChanged = CalibrationResourceFingerprint.create(
            analyteId = "cea",
            modalityCode = DetectionModality.FLUORESCENCE.code,
            concentrationUnit = "ng/mL",
            candidate = selected.copy(parameters = selected.parameters + ("a" to 2.0)),
            processorVersion = "FluorescenceProcessor-v2",
            engineVersion = CALIBRATION_ENGINE_VERSION
        )
        val pointChanged = CalibrationResourceFingerprint.create(
            analyteId = "cea",
            modalityCode = DetectionModality.FLUORESCENCE.code,
            concentrationUnit = "ng/mL",
            candidate = selected.copy(standardPoints = listOf(0.0 to 1.0, 2.0 to 3.0)),
            processorVersion = "FluorescenceProcessor-v2",
            engineVersion = CALIBRATION_ENGINE_VERSION
        )

        assertTrue(baseline != parameterChanged)
        assertTrue(baseline != pointChanged)
    }

    private fun draft(
        observations: List<CalibrationStandardObservation>
    ): CalibrationDraft = CalibrationDraft(
        analyteId = "cea",
        modality = DetectionModality.FLUORESCENCE,
        concentrationUnit = "ng/mL",
        observations = observations,
        requestedFeatures = setOf(AnalysisPrimaryFeature.FLUORESCENCE_SNR),
        requestedFunctions = CalibrationPolicy.DEFAULT_FUNCTIONS,
        processorVersion = "test",
        policy = CalibrationPolicy.DEFAULT,
        inputFingerprint = "fingerprint"
    )

    private fun candidate(
        function: FittingFunction,
        rSquared: Double
    ): CalibrationCandidate = CalibrationCandidate(
        id = function.identifier,
        analyteId = "cea",
        primaryFeature = AnalysisPrimaryFeature.FLUORESCENCE_SNR,
        function = function,
        parameters = function.requiredParams.associateWith { 1.0 },
        standardPoints = listOf(0.0 to 1.0, 1.0 to 2.0),
        curvePoints = listOf(0.0 to 1.0, 1.0 to 2.0),
        latexFormula = "y=x",
        rSquared = rSquared,
        rmse = 0.01,
        normalizedRmse = 0.01,
        mae = 0.01,
        backCalculatedRmsePercent = 1.0,
        acceptedStandardRatio = 1.0,
        weightingCode = 0,
        accepted = true,
        status = CalibrationCandidateStatus.AVAILABLE
    )
}

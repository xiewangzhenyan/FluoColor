package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
import com.muc.fluocolorquant.domain.calibration.CalibrationStrategy
import com.muc.fluocolorquant.domain.calibration.LowQualityCalibrationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 曲线拟合设置必须能够稳定往返，并在损坏数据时整体回退。 */
class CalibrationPolicyCodecTest {

    @Test
    fun `策略JSON往返保留全部用户选择`() {
        val policy = CalibrationPolicy(
            strategy = CalibrationStrategy.SIMPLE_MODEL_FIRST,
            allowedFunctions = linkedSetOf(FittingFunction.LINEAR, FittingFunction.RODBARD),
            rSquaredSimplicityTolerance = 0.004,
            lowQualityRSquaredThreshold = 0.975,
            minimumFourParameterLevels = 7,
            minimumFiveParameterLevels = 8,
            lowQualityAction = LowQualityCalibrationAction.REQUIRE_CONFIRMATION,
            colorimetricFeatures = linkedSetOf(AnalysisPrimaryFeature.OPTICAL_DENSITY),
            fluorescenceFeatures = linkedSetOf(
                AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
            ),
            enabledWeightingCodes = linkedSetOf(0, 3),
            saveToLibraryByDefault = true
        )

        assertEquals(policy, CalibrationPolicyCodec.decodeOrNull(CalibrationPolicyCodec.encode(policy)))
    }

    @Test
    fun `损坏或未来版本策略不会部分应用`() {
        assertNull(CalibrationPolicyCodec.decodeOrNull("not-json"))
        assertNull(
            CalibrationPolicyCodec.decodeOrNull(
                CalibrationPolicyCodec.encode(CalibrationPolicy.DEFAULT)
                    .replace(
                        "\"schemaVersion\":${CalibrationPolicy.CURRENT_SCHEMA_VERSION}",
                        "\"schemaVersion\":99"
                    )
            )
        )
    }

    @Test
    fun `旧版策略缺少R方质量门槛时按新默认值兼容读取`() {
        val legacyJson = CalibrationPolicyCodec.encode(CalibrationPolicy.DEFAULT)
            .replace(
                "\"schemaVersion\":${CalibrationPolicy.CURRENT_SCHEMA_VERSION}",
                "\"schemaVersion\":1"
            )
            .replace(Regex(",\"lowQualityRSquaredThreshold\":[^,}]+"), "")

        val restored = requireNotNull(CalibrationPolicyCodec.decodeOrNull(legacyJson))

        assertEquals(
            CalibrationPolicy.DEFAULT.lowQualityRSquaredThreshold,
            restored.lowQualityRSquaredThreshold,
            0.0
        )
    }

    @Test
    fun `旧版隐式稳健默认迁移为R方优先`() {
        val legacyJson = CalibrationPolicyCodec.encode(
            CalibrationPolicy.DEFAULT.copy(strategy = CalibrationStrategy.ROBUST)
        ).replace(
            "\"schemaVersion\":${CalibrationPolicy.CURRENT_SCHEMA_VERSION}",
            "\"schemaVersion\":2"
        )

        val restored = requireNotNull(CalibrationPolicyCodec.decodeOrNull(legacyJson))

        assertEquals(CalibrationStrategy.R_SQUARED_FIRST, restored.strategy)
    }

    @Test
    fun `V3荧光旧默认三信号迁移为当前完整推荐池`() {
        val legacyDefault = linkedSetOf(
            AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
            AnalysisPrimaryFeature.INTEGRATED_FLUORESCENCE_INTENSITY,
            AnalysisPrimaryFeature.FLUORESCENCE_SNR
        )
        val legacyJson = CalibrationPolicyCodec.encode(
            CalibrationPolicy.DEFAULT.copy(fluorescenceFeatures = legacyDefault)
        ).replace(
            "\"schemaVersion\":${CalibrationPolicy.CURRENT_SCHEMA_VERSION}",
            "\"schemaVersion\":3"
        )

        val restored = requireNotNull(CalibrationPolicyCodec.decodeOrNull(legacyJson))

        assertEquals(CalibrationPolicy.DEFAULT_FLUORESCENCE_FEATURES, restored.fluorescenceFeatures)
    }
}

package com.muc.fluocolorquant.ui.screens.settings.analysis

import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.AnalysisFeaturePolicy

/** 分析模型列表可使用的生命周期筛选条件。 */
enum class AnalysisModelStatusFilter {
    ALL,
    DRAFT,
    PUBLISHED,
    ARCHIVED,
    LEGACY
}

/** 自训练模型输出的明确语义，稳定编码必须与生产执行器一致。 */
enum class DeepLearningModelOutputMode {
    /** 模型直接输出最终浓度。 */
    RAW_CONCENTRATION,
    /** 模型输出 0～100，再线性映射到可靠浓度范围。 */
    PERCENT_OF_RELIABLE_MAX,
    /** 模型输出 0～1，再线性映射到可靠浓度范围。 */
    FRACTION_OF_RELIABLE_MAX
}

/**
 * 分析模型表单的稳定错误码。
 *
 * ViewModel 只发送错误码，Compose 页面负责映射中英文字符串，避免业务层持有用户可见
 * 文本，也避免在非 Composable 回调中错误调用 `stringResource()`。
 */
enum class AnalysisModelFormError {
    NAME_REQUIRED,
    ANALYTE_REQUIRED,
    PROCESSOR_NAME_REQUIRED,
    PROCESSOR_VERSION_REQUIRED,
    CONCENTRATION_UNIT_REQUIRED,
    RELIABLE_RANGE_INVALID,
    INPUT_PROTOCOL_INCOMPATIBLE,
    PRIMARY_FEATURE_INCOMPATIBLE,
    CARRIER_REQUIRED,
    ACQUISITION_PROFILE_REQUIRED,
    FITTING_FUNCTION_REQUIRED,
    PARAMETERS_REQUIRED,
    MODEL_FILE_REQUIRED,
    CHECKSUM_INVALID,
    INPUT_SIZE_INVALID,
    NORMALIZATION_REQUIRED,
    TRAINING_DATA_VERSION_REQUIRED,
    OUTPUT_CONTRACT_INVALID
}

/**
 * 统一分析模型编辑草稿。
 *
 * 数字字段以字符串保存，使用户可以在输入过程中暂时清空内容；只有保存或发布校验时
 * 才解析数值。标准曲线和智能模型共用科学元数据，专用字段则按 [modelType] 校验。
 */
data class AnalysisModelDraft(
    val name: String = "",
    val modelType: AnalysisModelType = AnalysisModelType.STANDARD_CURVE,
    val analyteId: String = "",
    val detectionMode: String = DetectionModality.COLORIMETRIC.code,
    val inputProtocol: String = InputProtocol.ENDPOINT_ONLY.code,
    val primaryFeature: String = AnalysisPrimaryFeature.DELTA_E_2000.code,
    // 处理器名称/版本必须与检测模态锁定的光度处理器完全一致，因此由模态自动派生，
    // 不再由用户手动输入；默认对应默认模态（比色）。
    val processorName: String = COLORIMETRIC_PROCESSOR_NAME,
    val processorVersion: String = COLORIMETRIC_PROCESSOR_VERSION,
    // 空集合表示运行时自动冻结当前手机、镜头与曝光元数据，并不等于已经完成设备级验证；
    // 如果模型只在固定设备/光学模块上验证过，用户应在高级兼容性中明确选择对应档案。
    val compatibleCarrierTypes: Set<String> = setOf(CarrierType.MICROFLUIDIC_CHIP.code),
    val compatibleAcquisitionProfileIds: Set<String> = emptySet(),
    val concentrationUnit: String = "",
    val reliableRangeMinInput: String = "",
    val reliableRangeMaxInput: String = "",
    val fittingFunction: String = "linear",
    val parametersJson: String = "{}",
    val monotonicDirection: String = "AUTO",
    val lodInput: String = "",
    val loqInput: String = "",
    val modelFileName: String = "",
    /** 导入前的原始名称只服务 UI 和追溯；执行器始终读取受控私有相对路径。 */
    val modelOriginalFileName: String = "",
    val checksumSha256: String = "",
    val inputWidthInput: String = "",
    val inputHeightInput: String = "",
    val normalizationJson: String = "{}",
    val trainingDataVersion: String = "",
    val outputMode: DeepLearningModelOutputMode =
        DeepLearningModelOutputMode.RAW_CONCENTRATION,
    val outputScaleInput: String = "1",
    val outputOffsetInput: String = "0"
) {
    /** 当前检测模态允许使用的输入协议。 */
    val allowedProtocols: Set<String>
        get() = when (DetectionModality.fromCode(detectionMode)) {
            DetectionModality.COLORIMETRIC,
            DetectionModality.FLUORESCENCE -> setOf(InputProtocol.ENDPOINT_ONLY.code)

            DetectionModality.SPECTRUM -> setOf(
                InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code,
                InputProtocol.LSPR_PAIRED_QUANTIFICATION.code
            )

            null -> emptySet()
        }

    /** 当前“检测模态 + 输入协议”允许作为主输入的科学特征。 */
    val allowedPrimaryFeatures: Set<String>
        get() = when (DetectionModality.fromCode(detectionMode)) {
            DetectionModality.COLORIMETRIC,
            DetectionModality.FLUORESCENCE -> AnalysisFeaturePolicy.allowedFeatures(
                requireNotNull(DetectionModality.fromCode(detectionMode))
            ).mapTo(linkedSetOf(), AnalysisPrimaryFeature::code)

            DetectionModality.SPECTRUM -> when (InputProtocol.fromCode(inputProtocol)) {
                InputProtocol.SINGLE_SPECTRUM_ANALYSIS -> setOf(
                    AnalysisPrimaryFeature.PEAK_WAVELENGTH_NM.code
                )

                InputProtocol.LSPR_PAIRED_QUANTIFICATION -> setOf(
                    AnalysisPrimaryFeature.DELTA_PEAK_WAVELENGTH_NM.code
                )

                InputProtocol.ENDPOINT_ONLY,
                null -> emptySet()
            }

            null -> emptySet()
        }

    /**
     * 草稿保存校验。
     *
     * 草稿可以暂时缺少设备兼容范围和类型专用发布资料，但必须具备可识别的科学身份、
     * 合法协议和可靠范围，避免数据库中出现无法解释的半结构化记录。
     */
    fun validateForDraft(): Set<AnalysisModelFormError> = buildSet {
        if (name.isBlank()) add(AnalysisModelFormError.NAME_REQUIRED)
        if (analyteId.isBlank()) add(AnalysisModelFormError.ANALYTE_REQUIRED)
        if (processorName.isBlank()) add(AnalysisModelFormError.PROCESSOR_NAME_REQUIRED)
        if (processorVersion.isBlank()) add(AnalysisModelFormError.PROCESSOR_VERSION_REQUIRED)
        if (concentrationUnit.isBlank()) add(AnalysisModelFormError.CONCENTRATION_UNIT_REQUIRED)

        val rangeMin = reliableRangeMinInput.toDoubleOrNull()
        val rangeMax = reliableRangeMaxInput.toDoubleOrNull()
        if (rangeMin == null || rangeMax == null ||
            !rangeMin.isFinite() || !rangeMax.isFinite() || rangeMax <= rangeMin
        ) {
            add(AnalysisModelFormError.RELIABLE_RANGE_INVALID)
        }

        if (inputProtocol !in allowedProtocols) {
            add(AnalysisModelFormError.INPUT_PROTOCOL_INCOMPATIBLE)
        }
        if (primaryFeature !in allowedPrimaryFeatures) {
            add(AnalysisModelFormError.PRIMARY_FEATURE_INCOMPATIBLE)
        }
    }

    /** 发布校验在草稿规则之外补齐兼容范围和模型类型专用资料。 */
    fun validateForPublication(): Set<AnalysisModelFormError> = buildSet {
        addAll(validateForDraft())
        if (compatibleCarrierTypes.isEmpty()) {
            add(AnalysisModelFormError.CARRIER_REQUIRED)
        }
        // 空设备范围表示由手机拍摄链自动记录真实设备与曝光元数据，不再强迫普通用户
        // 为每一台手机手工建立“采集设备档案”。载体范围仍必须明确，防止跨结构误用。

        when (modelType) {
            AnalysisModelType.STANDARD_CURVE -> {
                if (fittingFunction.isBlank()) {
                    add(AnalysisModelFormError.FITTING_FUNCTION_REQUIRED)
                }
                if (parametersJson.isBlank() || parametersJson.trim() == "{}") {
                    add(AnalysisModelFormError.PARAMETERS_REQUIRED)
                }
            }

            AnalysisModelType.DEEP_LEARNING -> {
                if (modelFileName.isBlank()) {
                    add(AnalysisModelFormError.MODEL_FILE_REQUIRED)
                }
                if (!SHA_256_REGEX.matches(checksumSha256.trim())) {
                    add(AnalysisModelFormError.CHECKSUM_INVALID)
                }
                val width = inputWidthInput.toIntOrNull()
                val height = inputHeightInput.toIntOrNull()
                if (width == null || height == null ||
                    width !in MINIMUM_MODEL_INPUT_SIZE..MAXIMUM_MODEL_INPUT_SIZE ||
                    height !in MINIMUM_MODEL_INPUT_SIZE..MAXIMUM_MODEL_INPUT_SIZE
                ) {
                    add(AnalysisModelFormError.INPUT_SIZE_INVALID)
                }
                if (normalizationJson.isBlank() || normalizationJson.trim() == "{}") {
                    add(AnalysisModelFormError.NORMALIZATION_REQUIRED)
                }
                if (trainingDataVersion.isBlank()) {
                    add(AnalysisModelFormError.TRAINING_DATA_VERSION_REQUIRED)
                }
                val outputScale = outputScaleInput.toDoubleOrNull()
                val outputOffset = outputOffsetInput.toDoubleOrNull()
                if (outputScale == null || outputOffset == null ||
                    !outputScale.isFinite() || !outputOffset.isFinite()
                ) {
                    add(AnalysisModelFormError.OUTPUT_CONTRACT_INVALID)
                }
            }
        }
    }

    /** 仅在可靠范围已通过校验后返回数值对，供 ViewModel 构建 Room 实体。 */
    fun reliableRangeOrNull(): Pair<Double, Double>? {
        val minimum = reliableRangeMinInput.toDoubleOrNull() ?: return null
        val maximum = reliableRangeMaxInput.toDoubleOrNull() ?: return null
        return if (minimum.isFinite() && maximum.isFinite() && maximum > minimum) {
            minimum to maximum
        } else {
            null
        }
    }

    companion object {
        private val SHA_256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private const val MINIMUM_MODEL_INPUT_SIZE = 8
        private const val MAXIMUM_MODEL_INPUT_SIZE = 4096
    }
}

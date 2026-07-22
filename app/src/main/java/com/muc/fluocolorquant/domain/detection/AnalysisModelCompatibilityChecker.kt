package com.muc.fluocolorquant.domain.detection

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.model.AnalysisModel

/** 当前运行与分析模型比较时使用的完整科学输入契约。 */
data class ModelCompatibilityRequest(
    val analyteId: String,
    val modality: DetectionModality,
    val inputProtocol: InputProtocol,
    val primaryFeature: AnalysisPrimaryFeature,
    val carrierType: CarrierType,
    val acquisitionProfileId: String,
    val processorName: String,
    val processorVersion: String
)

/** 模型不兼容的稳定机器码，可映射到中英文页面提示和导出原因。 */
enum class ModelCompatibilityReason {
    MODEL_NOT_PUBLISHED,
    ANALYTE_MISMATCH,
    MODALITY_MISMATCH,
    INPUT_PROTOCOL_MISMATCH,
    PRIMARY_FEATURE_MISMATCH,
    CARRIER_TYPE_MISMATCH,
    ACQUISITION_PROFILE_MISMATCH,
    PROCESSOR_NAME_MISMATCH,
    PROCESSOR_VERSION_MISMATCH,
    INVALID_COMPATIBILITY_METADATA
}

sealed interface ModelCompatibilityResult {
    data object Compatible : ModelCompatibilityResult

    data class Incompatible(
        val reasons: Set<ModelCompatibilityReason>
    ) : ModelCompatibilityResult
}

/**
 * 新检测链的分析模型兼容性检查器。
 *
 * 检查器一次返回全部不兼容原因，方便管理员修复模板；任何字段不匹配时上层只能保存
 * 原始/校正信号并标记 `SIGNAL_ONLY_COMPLETED`，不得寻找“相似”模型、首个模型或通用
 * 浓度模型继续输出数值。
 */
object AnalysisModelCompatibilityChecker {
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type

    fun check(
        model: AnalysisModel,
        request: ModelCompatibilityRequest
    ): ModelCompatibilityResult {
        val reasons = linkedSetOf<ModelCompatibilityReason>()
        if (model.status != AnalysisModelLifecycleStatus.PUBLISHED.code) {
            reasons += ModelCompatibilityReason.MODEL_NOT_PUBLISHED
        }
        if (model.analyteId != request.analyteId) {
            reasons += ModelCompatibilityReason.ANALYTE_MISMATCH
        }
        if (model.detectionMode != request.modality.code) {
            reasons += ModelCompatibilityReason.MODALITY_MISMATCH
        }
        if (model.inputProtocol != request.inputProtocol.code) {
            reasons += ModelCompatibilityReason.INPUT_PROTOCOL_MISMATCH
        }
        if (model.primaryFeature != request.primaryFeature.code) {
            reasons += ModelCompatibilityReason.PRIMARY_FEATURE_MISMATCH
        }
        if (model.processorName != request.processorName) {
            reasons += ModelCompatibilityReason.PROCESSOR_NAME_MISMATCH
        }
        if (model.processorVersion != request.processorVersion) {
            reasons += ModelCompatibilityReason.PROCESSOR_VERSION_MISMATCH
        }

        val carrierTypes = parseCompatibilitySet(
            json = model.compatibleCarrierTypesJson,
            allowEmpty = false
        )
        val acquisitionProfiles = parseCompatibilitySet(
            json = model.compatibleAcquisitionProfileIdsJson,
            // 空设备范围表示使用手机在拍摄时自动记录真实元数据，不要求用户预建设备档案。
            allowEmpty = true
        )
        if (carrierTypes == null || acquisitionProfiles == null) {
            reasons += ModelCompatibilityReason.INVALID_COMPATIBILITY_METADATA
        } else {
            if (request.carrierType.code !in carrierTypes) {
                reasons += ModelCompatibilityReason.CARRIER_TYPE_MISMATCH
            }
            if (acquisitionProfiles.isNotEmpty() &&
                request.acquisitionProfileId !in acquisitionProfiles
            ) {
                reasons += ModelCompatibilityReason.ACQUISITION_PROFILE_MISMATCH
            }
        }

        return if (reasons.isEmpty()) {
            ModelCompatibilityResult.Compatible
        } else {
            ModelCompatibilityResult.Incompatible(reasons)
        }
    }

    /**
     * 载体范围必须是非空 JSON 字符串数组；采集设备范围允许为空数组，表示依靠手机自动
     * 记录 ISO、曝光、焦距等实际元数据。null、非字符串或损坏 JSON 仍视为元数据无效。
     */
    private fun parseCompatibilitySet(json: String?, allowEmpty: Boolean): Set<String>? {
        if (json.isNullOrBlank()) return null
        return try {
            val values: List<String> = gson.fromJson(json, stringListType) ?: return null
            values.map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
                .takeIf { allowEmpty || it.isNotEmpty() }
        } catch (_: JsonParseException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }
}

package com.muc.fluocolorquant.domain.detection

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel

/**
 * 微流控定位和荧光读出的统一科学配置编解码器。
 *
 * UI 只操作稳定枚举，检测协调器只读取本对象生成的版本化 JSON。任何损坏、未知版本或
 * 未知枚举都返回 `null`，由模板发布/检测预检明确阻止，绝不静默替换为默认值。
 */
object ScientificDetectionConfigCodec {
    const val CARRIER_SCHEMA: String = "pg-grid-carrier-v1"
    const val FLUORESCENCE_SCHEMA: String = "fluorescence-display-v1"
    const val ANALYTE_DISPLAY_SCHEMA: String = "analyte-display-v1"

    private val gson = Gson()

    /** 将载体目标相对背景的亮暗关系写入稳定 PG-Grid 配置。 */
    fun encodeCarrierLocator(polarity: GridTargetPolarity): String {
        return gson.toJson(
            linkedMapOf(
                "schemaVersion" to CARRIER_SCHEMA,
                "targetPolarity" to polarity.name
            )
        )
    }

    /** 严格解析载体极性；旧 schema、空字段和损坏 JSON 均返回 `null`。 */
    fun decodeCarrierPolarity(json: String?): GridTargetPolarity? {
        val root = parseVersionedObject(json, CARRIER_SCHEMA) ?: return null
        val value = root.get("targetPolarity")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.uppercase()
            ?: return null
        return GridTargetPolarity.entries.firstOrNull { it.name == value }
    }

    /** 将单个分析物的荧光定量通道、可选阳性参考阈值及关联试剂信息写入模板显示/处理配置。 */
    fun encodeFluorescenceDisplay(
        channel: FluorescenceChannel,
        referenceCutoff: Double? = null,
        referenceCutoffUnit: String? = null,
        reagentSummary: String? = null
    ): String {
        val payload = linkedMapOf<String, Any>(
            "schemaVersion" to FLUORESCENCE_SCHEMA,
            "fluorescenceChannel" to channel.name
        )
        val validCutoff = referenceCutoff?.takeIf { it.isFinite() && it > 0.0 }
        if (validCutoff != null && !referenceCutoffUnit.isNullOrBlank()) {
            payload["referenceCutoff"] = validCutoff
            payload["referenceCutoffUnit"] = referenceCutoffUnit.trim()
        }
        reagentSummary?.trim()?.takeIf(String::isNotEmpty)?.let {
            payload["reagentSummary"] = it
        }
        return gson.toJson(payload)
    }

    /** 比色模式下仅在存在有效阳性参考阈值或关联试剂时写入冻结 JSON；均无时保持 null 以兼容旧运行。 */
    fun encodeColorimetricDisplay(
        referenceCutoff: Double?,
        referenceCutoffUnit: String?,
        reagentSummary: String? = null
    ): String? {
        val validCutoff = referenceCutoff?.takeIf { it.isFinite() && it > 0.0 }
        val unit = referenceCutoffUnit?.trim()?.takeIf(String::isNotEmpty)
        val validReagent = reagentSummary?.trim()?.takeIf(String::isNotEmpty)
        if ((validCutoff == null || unit == null) && validReagent == null) return null
        val payload = linkedMapOf<String, Any>(
            "schemaVersion" to ANALYTE_DISPLAY_SCHEMA
        )
        if (validCutoff != null && unit != null) {
            payload["referenceCutoff"] = validCutoff
            payload["referenceCutoffUnit"] = unit
        }
        if (validReagent != null) {
            payload["reagentSummary"] = validReagent
        }
        return gson.toJson(payload)
    }

    /** 严格解析荧光通道；不把缺失配置解释为绿色通道。 */
    fun decodeFluorescenceChannel(json: String?): FluorescenceChannel? {
        val root = parseVersionedObject(json, FLUORESCENCE_SCHEMA) ?: return null
        val value = root.get("fluorescenceChannel")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.uppercase()
            ?: return null
        return FluorescenceChannel.entries.firstOrNull { it.name == value }
    }

    /**
     * 从冻结的 `displayConfigJson` 中读取阳性参考截断值（Cut-off）。
     *
     * 当调用方提供 [expectedUnit] 时，要求冻结单位一致（忽略大小写）才返回阈值，防止单位不一致误判。
     */
    fun decodeReferenceCutoff(json: String?, expectedUnit: String? = null): Double? {
        val root = parseVersionedObject(json, FLUORESCENCE_SCHEMA)
            ?: parseVersionedObject(json, ANALYTE_DISPLAY_SCHEMA)
            ?: return null
        val cutoff = root.get("referenceCutoff")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asDouble
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return null
        if (!expectedUnit.isNullOrBlank()) {
            val savedUnit = root.get("referenceCutoffUnit")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.trim()
            if (!savedUnit.isNullOrEmpty() && !savedUnit.equals(expectedUnit.trim(), ignoreCase = true)) {
                return null
            }
        }
        return cutoff
    }

    /** 从冻结的 `displayConfigJson` 中读取项目创建时锁定的试剂批次摘要。 */
    fun decodeReagentSummary(json: String?): String? {
        val root = parseVersionedObject(json, FLUORESCENCE_SCHEMA)
            ?: parseVersionedObject(json, ANALYTE_DISPLAY_SCHEMA)
            ?: return null
        return root.get("reagentSummary")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    /** 统一执行 JSON 类型和 schema 检查，避免两个配置入口出现不一致的宽松解析。 */
    private fun parseVersionedObject(json: String?, expectedSchema: String): JsonObject? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = gson.fromJson(json, JsonObject::class.java) ?: return null
            val schema = root.get("schemaVersion")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
            root.takeIf { schema == expectedSchema }
        } catch (_: RuntimeException) {
            null
        }
    }
}

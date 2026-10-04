package com.muc.fluocolorquant.domain.spectrum

import com.google.gson.Gson

/**
 * 一次光谱结果生成时使用的处理参数快照。
 *
 * 该对象会随 [com.muc.fluocolorquant.data.model.SpectrumResult] 持久化，生命周期与历史结果一致。
 * 设置页只决定“下一次新建结果”的默认值，不能在读取历史结果时覆盖这里的值。
 */
data class SpectrumProcessingConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val minWavelength: Double,
    val maxWavelength: Double,
    val smoothingLevel: Int,
    val sensitivity: String
) {
    fun isValid(): Boolean {
        return schemaVersion == CURRENT_SCHEMA_VERSION &&
            minWavelength.isFinite() &&
            maxWavelength.isFinite() &&
            minWavelength >= 0.0 &&
            maxWavelength > minWavelength &&
            smoothingLevel in MIN_SMOOTHING_LEVEL..MAX_SMOOTHING_LEVEL &&
            sensitivity in SUPPORTED_SENSITIVITIES
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val MIN_SMOOTHING_LEVEL: Int = 0
        const val MAX_SMOOTHING_LEVEL: Int = 10
        const val PROCESSOR_VERSION: String = "spectrum-result-v1"

        const val SENSITIVITY_LOW: String = "Low"
        const val SENSITIVITY_MEDIUM: String = "Medium"
        const val SENSITIVITY_HIGH: String = "High"

        private val SUPPORTED_SENSITIVITIES = setOf(
            SENSITIVITY_LOW,
            SENSITIVITY_MEDIUM,
            SENSITIVITY_HIGH
        )

        /**
         * 旧记录没有保存处理参数。这里使用固定的历史兼容值，绝不能读取当前 DataStore；
         * 否则用户修改设置后，同一条旧历史会再次漂移。
         */
        val LEGACY_DEFAULT: SpectrumProcessingConfig = SpectrumProcessingConfig(
            minWavelength = 400.0,
            maxWavelength = 800.0,
            smoothingLevel = 3,
            sensitivity = SENSITIVITY_MEDIUM
        )

        fun normalizeSensitivity(value: String): String? {
            return SUPPORTED_SENSITIVITIES.firstOrNull { it.equals(value, ignoreCase = true) }
        }
    }
}

/** 历史结果所用配置的可信来源，供 UI 明确提示旧数据或损坏快照。 */
enum class SpectrumProcessingConfigOrigin {
    FROZEN,
    LEGACY_DEFAULT,
    INVALID_SNAPSHOT,
    INCONSISTENT_SNAPSHOTS
}

data class ResolvedSpectrumProcessingConfig(
    val config: SpectrumProcessingConfig,
    val origin: SpectrumProcessingConfigOrigin,
    val processorVersion: String
)

/**
 * 光谱处理配置的版本化 JSON 编解码与项目级一致性检查。
 *
 * 每个通道都保存同一份快照，属于有意的数据冗余：单条通道导出或恢复时仍可独立解释；
 * 同时解析阶段会校验各通道是否一致，防止部分写入造成无提示的数据语义分裂。
 */
object SpectrumProcessingConfigSnapshot {
    private val gson = Gson()

    fun encode(config: SpectrumProcessingConfig): String {
        require(config.isValid()) { "光谱处理配置不合法，不能写入历史快照" }
        return gson.toJson(config)
    }

    fun decode(json: String?): SpectrumProcessingConfig? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            gson.fromJson(json, SpectrumProcessingConfig::class.java)
        }.getOrNull()?.takeIf(SpectrumProcessingConfig::isValid)
    }

    fun resolve(snapshots: List<Pair<String?, String?>>): ResolvedSpectrumProcessingConfig {
        if (snapshots.isEmpty() || snapshots.all { (json, version) ->
                json.isNullOrBlank() && version.isNullOrBlank()
            }
        ) {
            return legacyResolved(SpectrumProcessingConfigOrigin.LEGACY_DEFAULT)
        }

        // Room 15 及更早记录的两个字段都为空；只出现版本或只出现 JSON 则是部分写入/损坏，
        // 不能伪装成合法旧历史，否则页面会把数据损坏解释为普通兼容回退。
        if (snapshots.any { (json, version) ->
                json.isNullOrBlank() != version.isNullOrBlank()
            }
        ) {
            return legacyResolved(SpectrumProcessingConfigOrigin.INVALID_SNAPSHOT)
        }

        val decoded = snapshots.map { (json, version) ->
            decode(json) to version?.takeIf(String::isNotBlank)
        }
        if (decoded.any { (config, _) -> config == null }) {
            return legacyResolved(SpectrumProcessingConfigOrigin.INVALID_SNAPSHOT)
        }

        val configs = decoded.mapNotNull { it.first }.distinct()
        val versions = decoded.mapNotNull { it.second }.distinct()
        if (configs.size != 1 || versions.size != 1) {
            return legacyResolved(SpectrumProcessingConfigOrigin.INCONSISTENT_SNAPSHOTS)
        }

        return ResolvedSpectrumProcessingConfig(
            config = configs.single(),
            origin = SpectrumProcessingConfigOrigin.FROZEN,
            processorVersion = versions.single()
        )
    }

    private fun legacyResolved(origin: SpectrumProcessingConfigOrigin): ResolvedSpectrumProcessingConfig {
        return ResolvedSpectrumProcessingConfig(
            config = SpectrumProcessingConfig.LEGACY_DEFAULT,
            origin = origin,
            processorVersion = "legacy-unversioned"
        )
    }
}

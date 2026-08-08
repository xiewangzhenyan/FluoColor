package com.muc.fluocolorquant.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
import com.muc.fluocolorquant.domain.calibration.CalibrationStrategy
import com.muc.fluocolorquant.domain.calibration.LowQualityCalibrationAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 曲线拟合策略使用独立 DataStore，避免继续扩张通用 [SettingsRepository]。 */
private val Context.calibrationSettingsDataStore by preferencesDataStore(
    name = "calibration_settings"
)

/**
 * 向检测、标准曲线和设置页面暴露的最小策略接口。
 *
 * 拟合入口只读取 [policyFlow] 的一次快照；设置页面负责更新未来拟合的默认值，不能回写
 * 已经生成的候选或历史运行。
 */
interface CalibrationPolicyPreferences {
    val policyFlow: Flow<CalibrationPolicy>

    suspend fun save(policy: CalibrationPolicy)

    suspend fun reset()
}

/**
 * 版本化曲线拟合策略仓库。
 *
 * DataStore 中只保存稳定字符串编码，领域枚举的显示名称和 Kotlin 类名都不进入持久化。
 * 任何缺失、损坏或越界值都会整体回退到 [CalibrationPolicy.DEFAULT]，避免半份策略让不同
 * 页面产生不一致推荐。
 */
@Singleton
class CalibrationSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : CalibrationPolicyPreferences {
    private val policyKey = stringPreferencesKey("calibration_policy_json")

    override val policyFlow: Flow<CalibrationPolicy> =
        context.calibrationSettingsDataStore.data.map { preferences ->
            preferences[policyKey]
                ?.let(CalibrationPolicyCodec::decodeOrNull)
                ?: CalibrationPolicy.DEFAULT
        }

    override suspend fun save(policy: CalibrationPolicy) {
        // 重新构造一次可以触发领域层范围校验，禁止页面绕过合法区间写入损坏数据。
        val validated = policy.copy()
        context.calibrationSettingsDataStore.edit { preferences ->
            preferences[policyKey] = CalibrationPolicyCodec.encode(validated)
        }
    }

    override suspend fun reset() {
        context.calibrationSettingsDataStore.edit { preferences ->
            preferences.remove(policyKey)
        }
    }
}

/**
 * 策略 JSON 的唯一编解码入口，拆成纯 Kotlin 对象后可以使用 JVM 测试验证兼容行为。
 */
object CalibrationPolicyCodec {
    private val gson = Gson()

    fun encode(policy: CalibrationPolicy): String = gson.toJson(
        StoredCalibrationPolicy(
            schemaVersion = policy.schemaVersion,
            strategy = policy.strategy.name,
            allowedFunctions = policy.allowedFunctions.map(FittingFunction::identifier).sorted(),
            rSquaredSimplicityTolerance = policy.rSquaredSimplicityTolerance,
            lowQualityRSquaredThreshold = policy.lowQualityRSquaredThreshold,
            minimumFourParameterLevels = policy.minimumFourParameterLevels,
            minimumFiveParameterLevels = policy.minimumFiveParameterLevels,
            lowQualityAction = policy.lowQualityAction.name,
            colorimetricFeatures = policy.colorimetricFeatures.map { it.code }.sorted(),
            fluorescenceFeatures = policy.fluorescenceFeatures.map { it.code }.sorted(),
            enabledWeightingCodes = policy.enabledWeightingCodes.sorted(),
            saveToLibraryByDefault = policy.saveToLibraryByDefault,
            engineVersion = policy.engineVersion
        )
    )

    fun decodeOrNull(json: String): CalibrationPolicy? = runCatching {
        val stored = gson.fromJson(json, StoredCalibrationPolicy::class.java)
            ?: return@runCatching null
        if (stored.schemaVersion !in 1..CalibrationPolicy.CURRENT_SCHEMA_VERSION) {
            return@runCatching null
        }
        val functions = stored.allowedFunctions
            .mapNotNull(FittingFunction::fromIdentifier)
            .toCollection(linkedSetOf())
        val colorFeatures = stored.colorimetricFeatures
            .mapNotNull(AnalysisPrimaryFeature::fromCode)
            .toCollection(linkedSetOf())
        val fluorescenceFeatures = stored.fluorescenceFeatures
            .mapNotNull(AnalysisPrimaryFeature::fromCode)
            .toCollection(linkedSetOf())
        CalibrationPolicy(
            schemaVersion = CalibrationPolicy.CURRENT_SCHEMA_VERSION,
            strategy = CalibrationStrategy.valueOf(stored.strategy),
            allowedFunctions = functions,
            rSquaredSimplicityTolerance = stored.rSquaredSimplicityTolerance,
            lowQualityRSquaredThreshold = stored.lowQualityRSquaredThreshold
                ?: CalibrationPolicy.DEFAULT.lowQualityRSquaredThreshold,
            minimumFourParameterLevels = stored.minimumFourParameterLevels,
            minimumFiveParameterLevels = stored.minimumFiveParameterLevels,
            lowQualityAction = LowQualityCalibrationAction.valueOf(stored.lowQualityAction),
            colorimetricFeatures = colorFeatures,
            fluorescenceFeatures = fluorescenceFeatures,
            enabledWeightingCodes = stored.enabledWeightingCodes.toCollection(linkedSetOf()),
            saveToLibraryByDefault = stored.saveToLibraryByDefault,
            engineVersion = stored.engineVersion
        )
    }.getOrNull()

    /**
     * Gson DTO 使用基础类型，避免未来重命名领域属性时静默改变已安装用户的 JSON 结构。
     */
    private data class StoredCalibrationPolicy(
        val schemaVersion: Int,
        val strategy: String,
        val allowedFunctions: List<String>,
        val rSquaredSimplicityTolerance: Double,
        val lowQualityRSquaredThreshold: Double? = null,
        val minimumFourParameterLevels: Int,
        val minimumFiveParameterLevels: Int,
        val lowQualityAction: String,
        val colorimetricFeatures: List<String>,
        val fluorescenceFeatures: List<String>,
        val enabledWeightingCodes: List<Int>,
        val saveToLibraryByDefault: Boolean,
        val engineVersion: String
    )
}

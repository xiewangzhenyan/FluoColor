package com.muc.fluocolorquant.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.muc.fluocolorquant.data.enums.SpectrumLightSource
import com.muc.fluocolorquant.utils.math.GridLayoutPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// 语言偏好的 DataStore 委托统一声明在 AppLanguageStore：应用启动阶段（Hilt 尚未就绪）
// 与本仓库都需要读取它，两处各自声明委托会让同一进程出现两个指向同一文件的实例。
private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

/**
 * 只暴露浓度单位相关设置的轻量接口。
 *
 * 模板和曲线编辑器只需要读取单位列表与默认单位，不应依赖整个应用设置仓库。拆出该接口
 * 后，页面业务既能与“检测设置”使用同一份 DataStore 数据，也能在 JVM 单元测试中使用
 * 简单的内存实现，避免为了读取一个下拉列表而引入 Android Context。
 */
interface ConcentrationUnitPreferences {
    val defaultConcentrationUnitFlow: Flow<String>
    val concentrationUnitsFlow: Flow<Set<String>>
}

/**
 * 直接新建项目真正消费的默认设置契约。
 *
 * 将接口限定为检测方式和浓度单位，能在编译期阻止新建流程误读“默认行列、像素提取”等
 * 没有运行快照承载的旧偏好，也让设置项是否存在生产消费者变得可测试。
 */
interface ProjectCreationPreferences : ConcentrationUnitPreferences {
    /**
     * 新建页只应读取一次完整默认值。持续收集这个 Flow 会让用户正在编辑的表单被设置页
     * 后续变化覆盖，因此消费方必须在页面初始化时使用 `first()`。
     */
    val projectCreationDefaultsFlow: Flow<ProjectCreationDefaults>
}

/** 自定义阵列默认规格；两个维度必须来自同一次读取和同一次写入。 */
data class CustomGridDefaults(
    val rows: Int,
    val columns: Int
)

/**
 * 直接新建项目的完整预填值。
 *
 * 这些字段只负责初始化新表单，不是全局运行参数。项目创建后，阵列规格进入
 * `CarrierProfile`，光源进入 `Project` 和光谱结果快照，后续设置变化不会修改已有项目。
 */
data class ProjectCreationDefaults(
    val detectionMode: String,
    val customGrid: CustomGridDefaults,
    val spectrumLightSource: SpectrumLightSource
)

/**
 * 一次光谱结果生成必须从同一个 DataStore 版本读取的默认参数。
 *
 * 把四个字段合并为一个不可变对象，避免结果保存期间分别调用多个 Flow.first()，从而冻结到
 * 来自不同设置版本的混合参数。该对象只决定“下一次结果”的默认值，历史仍读取结果快照。
 */
data class SpectrumProcessingPreferences(
    val minWavelength: Float,
    val maxWavelength: Float,
    val smoothingLevel: Int,
    val sensitivity: String
)

/** 设置持久化边界的纯值策略，既兼容旧脏值，也允许 JVM 契约测试直接覆盖。 */
internal object SettingsValuePolicy {
    private val supportedDetectionModes = setOf("FLUORESCENCE", "COLORIMETRIC")
    private val supportedSensitivities = listOf("Low", "Medium", "High")

    fun detectionModeOrDefault(value: String?): String {
        val normalized = value?.trim()?.uppercase(Locale.ROOT)
        return normalized?.takeIf(supportedDetectionModes::contains) ?: "FLUORESCENCE"
    }

    fun requireDetectionMode(value: String): String {
        val normalized = value.trim().uppercase(Locale.ROOT)
        require(normalized in supportedDetectionModes) { "不支持的默认检测方式" }
        return normalized
    }

    fun customGridOrDefault(rows: Int?, columns: Int?): CustomGridDefaults {
        // 行列是一个不可分割的载体规格：任一维度越界时整体回退，不能拼出半份旧配置。
        return if (
            rows != null && columns != null &&
            GridLayoutPolicy.isValid(rows, columns)
        ) {
            CustomGridDefaults(rows = rows, columns = columns)
        } else {
            CustomGridDefaults(
                rows = SettingsRepository.DEFAULT_CUSTOM_GRID_ROWS,
                columns = SettingsRepository.DEFAULT_CUSTOM_GRID_COLUMNS
            )
        }
    }

    fun spectrumLightSourceOrDefault(value: String?): SpectrumLightSource {
        return SpectrumLightSource.entries.firstOrNull {
            it.name.equals(value?.trim(), ignoreCase = true)
        } ?: SettingsRepository.DEFAULT_SPECTRUM_LIGHT_SOURCE
    }

    fun requireSpectrumLightSource(value: SpectrumLightSource): String = value.name

    fun concentrationUnitsOrDefault(values: Set<String>?): Set<String> {
        val normalized = values.orEmpty().map(String::trim).filter(String::isNotEmpty).toSet()
        return normalized.takeIf(Set<String>::isNotEmpty)
            ?: SettingsRepository.DEFAULT_CONCENTRATION_UNITS
    }

    fun defaultConcentrationUnit(value: String?, units: Set<String>): String {
        val normalized = value?.trim().orEmpty()
        return normalized.takeIf { it in units }
            ?: units.firstOrNull()
            ?: SettingsRepository.DEFAULT_CONCENTRATION_UNITS.first()
    }

    fun spectrumPreferences(
        minWavelength: Float?,
        maxWavelength: Float?,
        smoothingLevel: Int?,
        sensitivity: String?
    ): SpectrumProcessingPreferences {
        val min = minWavelength ?: SettingsRepository.DEFAULT_SPECTRUM_MIN_WAVELENGTH
        val max = maxWavelength ?: SettingsRepository.DEFAULT_SPECTRUM_MAX_WAVELENGTH
        val validRange = min.isFinite() && max.isFinite() && min >= 0f && max > min
        return SpectrumProcessingPreferences(
            minWavelength = if (validRange) min else SettingsRepository.DEFAULT_SPECTRUM_MIN_WAVELENGTH,
            maxWavelength = if (validRange) max else SettingsRepository.DEFAULT_SPECTRUM_MAX_WAVELENGTH,
            smoothingLevel = smoothingLevel
                ?.takeIf { it in SettingsRepository.MIN_SPECTRUM_SMOOTHING..SettingsRepository.MAX_SPECTRUM_SMOOTHING }
                ?: SettingsRepository.DEFAULT_SPECTRUM_SMOOTHING,
            sensitivity = supportedSensitivities.firstOrNull {
                it.equals(sensitivity, ignoreCase = true)
            } ?: SettingsRepository.DEFAULT_SPECTRUM_SENSITIVITY
        )
    }
}

/** 设置页可见项允许绑定的生产消费者；测试会拒绝没有消费者的可见设置。 */
enum class SettingProductionConsumer {
    APP_LOCALE,
    APP_THEME,
    DIRECT_PROJECT_CREATION,
    TEMPLATE_AND_CURVE_CREATION,
    SPECTRUM_RESULT_SNAPSHOT,
    SPECTRUM_CAPTURE_QUALITY_GATE
}

data class VisibleSettingProductionContract(
    val settingId: String,
    val consumers: Set<SettingProductionConsumer>
)

/**
 * 设置—生产消费者白名单。
 *
 * 只有这里列出的项目可以出现在普通设置页；新增设置必须先明确实际读取者和冻结边界，
 * 再补充契约测试。旧 DataStore 中已经写入但未列出的键保持原值、不主动删除，仅不再展示。
 */
object VisibleSettingProductionContracts {
    val all: List<VisibleSettingProductionContract> = listOf(
        VisibleSettingProductionContract("language", setOf(SettingProductionConsumer.APP_LOCALE)),
        VisibleSettingProductionContract("theme_mode", setOf(SettingProductionConsumer.APP_THEME)),
        VisibleSettingProductionContract(
            "default_detection_mode",
            setOf(SettingProductionConsumer.DIRECT_PROJECT_CREATION)
        ),
        VisibleSettingProductionContract(
            "default_rows",
            setOf(SettingProductionConsumer.DIRECT_PROJECT_CREATION)
        ),
        VisibleSettingProductionContract(
            "default_columns",
            setOf(SettingProductionConsumer.DIRECT_PROJECT_CREATION)
        ),
        VisibleSettingProductionContract(
            "default_concentration_unit",
            setOf(
                SettingProductionConsumer.DIRECT_PROJECT_CREATION,
                SettingProductionConsumer.TEMPLATE_AND_CURVE_CREATION
            )
        ),
        VisibleSettingProductionContract(
            "concentration_units",
            setOf(
                SettingProductionConsumer.DIRECT_PROJECT_CREATION,
                SettingProductionConsumer.TEMPLATE_AND_CURVE_CREATION
            )
        ),
        VisibleSettingProductionContract(
            "spectrum_min_wavelength",
            setOf(SettingProductionConsumer.SPECTRUM_RESULT_SNAPSHOT)
        ),
        VisibleSettingProductionContract(
            "spectrum_max_wavelength",
            setOf(SettingProductionConsumer.SPECTRUM_RESULT_SNAPSHOT)
        ),
        VisibleSettingProductionContract(
            "spectrum_smoothing",
            setOf(SettingProductionConsumer.SPECTRUM_RESULT_SNAPSHOT)
        ),
        VisibleSettingProductionContract(
            "spectrum_sensitivity",
            setOf(SettingProductionConsumer.SPECTRUM_RESULT_SNAPSHOT)
        ),
        VisibleSettingProductionContract(
            "spectrum_default_light_source",
            setOf(
                SettingProductionConsumer.DIRECT_PROJECT_CREATION,
                SettingProductionConsumer.SPECTRUM_RESULT_SNAPSHOT
            )
        ),
        VisibleSettingProductionContract(
            "spectrum_quality_check_enabled",
            setOf(SettingProductionConsumer.SPECTRUM_CAPTURE_QUALITY_GATE)
        )
    )
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : ProjectCreationPreferences {
    // 语言相关的DataStore实例；与 FluoColorApp 共用 AppLanguageStore 提供的同一个实例
    private val languageDataStore: DataStore<Preferences> = AppLanguageStore.dataStore(context)
    
    // 应用其他设置的DataStore实例
    private val appSettingsDataStore: DataStore<Preferences> = context.appSettingsDataStore

    // 偏好设置的键
    private val LANGUAGE_KEY = AppLanguageStore.LANGUAGE_KEY
    private val DEFAULT_DETECTION_MODE_KEY = stringPreferencesKey("default_detection_mode")
    private val DEFAULT_CONCENTRATION_UNIT_KEY = stringPreferencesKey("default_concentration_unit")
    private val CONCENTRATION_UNITS_KEY = stringSetPreferencesKey("concentration_units")
    // 复用旧键以保留用户曾经明确保存的规格；读取时按当前 1..99 规则整体归一化。
    private val DEFAULT_ROWS_KEY = intPreferencesKey("default_rows")
    private val DEFAULT_COLUMNS_KEY = intPreferencesKey("default_columns")
    private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    private val SPECTRUM_MIN_WAVELENGTH_KEY = floatPreferencesKey("spectrum_min_wavelength")
    private val SPECTRUM_MAX_WAVELENGTH_KEY = floatPreferencesKey("spectrum_max_wavelength")
    private val SPECTRUM_SMOOTHING_KEY = intPreferencesKey("spectrum_smoothing")
    private val SPECTRUM_SENSITIVITY_KEY = stringPreferencesKey("spectrum_sensitivity")
    private val SPECTRUM_DEFAULT_LIGHT_SOURCE_KEY =
        stringPreferencesKey("spectrum_default_light_source")
    private val SPECTRUM_LAST_REFERENCE_WAVELENGTHS_KEY = stringPreferencesKey("spectrum_last_reference_wavelengths")
    private val SPECTRUM_QUALITY_CHECK_ENABLED_KEY = booleanPreferencesKey("spectrum_quality_check_enabled")

    // 获取当前语言设置，默认为系统语言
    val languageFlow: Flow<String> = languageDataStore.data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: getSystemLanguage()
    }

    // 设置语言。必须经 AppLanguageStore 写入，它会同步刷新 attachBaseContext 使用的
    // 进程内缓存；直接写 DataStore 会让缓存滞留旧值，重建后的 Activity 仍用旧语言。
    suspend fun setLanguage(languageCode: String) {
        AppLanguageStore.setLanguage(context, languageCode)
    }

    // 获取系统语言代码
    private fun getSystemLanguage(): String {
        val locale = Locale.getDefault()
        return when (locale.language) {
            "zh" -> "zh"
            else -> "en"
        }
    }
    
    /** 新建页所需默认值来自同一次 DataStore 发射，避免字段跨设置版本混用。 */
    override val projectCreationDefaultsFlow: Flow<ProjectCreationDefaults> =
        appSettingsDataStore.data.map { preferences ->
            ProjectCreationDefaults(
                detectionMode = SettingsValuePolicy.detectionModeOrDefault(
                    preferences[DEFAULT_DETECTION_MODE_KEY]
                ),
                customGrid = SettingsValuePolicy.customGridOrDefault(
                    rows = preferences[DEFAULT_ROWS_KEY],
                    columns = preferences[DEFAULT_COLUMNS_KEY]
                ),
                spectrumLightSource = SettingsValuePolicy.spectrumLightSourceOrDefault(
                    preferences[SPECTRUM_DEFAULT_LIGHT_SOURCE_KEY]
                )
            )
        }

    // 独立观察字段仅供设置页和应用启动兼容；新建页必须读取上面的完整对象。
    val defaultDetectionModeFlow: Flow<String> = projectCreationDefaultsFlow.map {
        it.detectionMode
    }

    val spectrumDefaultLightSourceFlow: Flow<SpectrumLightSource> =
        projectCreationDefaultsFlow.map { it.spectrumLightSource }
    
    // 设置默认检测模式
    suspend fun setDefaultDetectionMode(mode: String) {
        val normalized = SettingsValuePolicy.requireDetectionMode(mode)
        appSettingsDataStore.edit { preferences ->
            preferences[DEFAULT_DETECTION_MODE_KEY] = normalized
        }
    }
    
    // 更新默认检测模式（不触发语言变化）
    suspend fun setDefaultDetectionModeWithoutLanguageChange(mode: String) {
        // 默认检测方式本来就位于独立 DataStore；保留旧 API 名称仅兼容调用方。
        setDefaultDetectionMode(mode)
    }

    /** 自定义阵列行列必须原子保存，防止设置页中途退出留下不完整规格。 */
    suspend fun setDefaultCustomGrid(rows: Int, columns: Int) {
        require(GridLayoutPolicy.isValid(rows, columns)) {
            "自定义阵列行列必须位于 ${GridLayoutPolicy.MIN_DIMENSION}..${GridLayoutPolicy.MAX_DIMENSION}"
        }
        appSettingsDataStore.edit { preferences ->
            preferences[DEFAULT_ROWS_KEY] = rows
            preferences[DEFAULT_COLUMNS_KEY] = columns
        }
    }

    suspend fun setSpectrumDefaultLightSource(lightSource: SpectrumLightSource) {
        appSettingsDataStore.edit { preferences ->
            preferences[SPECTRUM_DEFAULT_LIGHT_SOURCE_KEY] =
                SettingsValuePolicy.requireSpectrumLightSource(lightSource)
        }
    }
    
    // 获取默认浓度单位，默认为ng/ml
    override val defaultConcentrationUnitFlow: Flow<String> = appSettingsDataStore.data.map { preferences ->
        val units = SettingsValuePolicy.concentrationUnitsOrDefault(preferences[CONCENTRATION_UNITS_KEY])
        SettingsValuePolicy.defaultConcentrationUnit(preferences[DEFAULT_CONCENTRATION_UNIT_KEY], units)
    }
    
    // 设置默认浓度单位
    suspend fun setDefaultConcentrationUnit(unit: String) {
        appSettingsDataStore.edit { preferences ->
            val units = SettingsValuePolicy.concentrationUnitsOrDefault(preferences[CONCENTRATION_UNITS_KEY])
            val normalized = unit.trim()
            require(normalized in units) { "默认浓度单位必须来自当前单位列表" }
            preferences[DEFAULT_CONCENTRATION_UNIT_KEY] = normalized
        }
    }
    
    // 更新默认浓度单位（不触发语言变化）
    suspend fun setDefaultConcentrationUnitWithoutLanguageChange(unit: String) {
        setDefaultConcentrationUnit(unit)
    }
    
    // 获取所有可用浓度单位
    override val concentrationUnitsFlow: Flow<Set<String>> = appSettingsDataStore.data.map { preferences ->
        SettingsValuePolicy.concentrationUnitsOrDefault(preferences[CONCENTRATION_UNITS_KEY])
    }

    // 添加浓度单位
    suspend fun addConcentrationUnit(unit: String) {
        val normalized = unit.trim()
        require(normalized.isNotEmpty()) { "浓度单位不能为空" }
        appSettingsDataStore.edit { preferences ->
            val currentUnits = SettingsValuePolicy.concentrationUnitsOrDefault(
                preferences[CONCENTRATION_UNITS_KEY]
            )
            preferences[CONCENTRATION_UNITS_KEY] = currentUnits + normalized
        }
    }
    
    // 删除浓度单位
    suspend fun deleteConcentrationUnit(unit: String) {
        appSettingsDataStore.edit { preferences ->
            val currentUnits = SettingsValuePolicy.concentrationUnitsOrDefault(
                preferences[CONCENTRATION_UNITS_KEY]
            )
            val defaultUnit = SettingsValuePolicy.defaultConcentrationUnit(
                preferences[DEFAULT_CONCENTRATION_UNIT_KEY],
                currentUnits
            )
            require(unit != defaultUnit) { "不能删除当前默认浓度单位" }
            val updated = currentUnits - unit
            require(updated.isNotEmpty()) { "浓度单位列表不能为空" }
            preferences[CONCENTRATION_UNITS_KEY] = updated
        }
    }
    
    // 获取主题模式，默认跟随系统
    val themeModeFlow: Flow<String> = appSettingsDataStore.data.map { preferences ->
        preferences[THEME_MODE_KEY] ?: DEFAULT_THEME_MODE
    }

    // 设置主题模式
    suspend fun setThemeMode(mode: String) {
        appSettingsDataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode
        }
    }
    
    /** 单次 DataStore 发射中的完整光谱默认参数，结果生成入口必须只读取这个 Flow。 */
    val spectrumProcessingPreferencesFlow: Flow<SpectrumProcessingPreferences> =
        appSettingsDataStore.data.map { preferences ->
            SettingsValuePolicy.spectrumPreferences(
                minWavelength = preferences[SPECTRUM_MIN_WAVELENGTH_KEY],
                maxWavelength = preferences[SPECTRUM_MAX_WAVELENGTH_KEY],
                smoothingLevel = preferences[SPECTRUM_SMOOTHING_KEY],
                sensitivity = preferences[SPECTRUM_SENSITIVITY_KEY]
            )
        }

    // 兼容设置页面的字段级观察；生产结果生成应使用上面的完整快照 Flow。
    val spectrumMinWavelengthFlow: Flow<Float> = spectrumProcessingPreferencesFlow.map {
        it.minWavelength
    }

    val spectrumMaxWavelengthFlow: Flow<Float> = spectrumProcessingPreferencesFlow.map {
        it.maxWavelength
    }

    /** 最小/最大波长必须在同一个 DataStore 事务中写入，禁止产生半更新范围。 */
    suspend fun setSpectrumWavelengthRange(min: Float, max: Float) {
        require(min.isFinite() && max.isFinite() && min >= 0f && max > min) {
            "光谱波长范围不合法"
        }
        appSettingsDataStore.edit { preferences ->
            preferences[SPECTRUM_MIN_WAVELENGTH_KEY] = min
            preferences[SPECTRUM_MAX_WAVELENGTH_KEY] = max
        }
    }

    // 获取光谱平滑等级，默认 3
    val spectrumSmoothingFlow: Flow<Int> = spectrumProcessingPreferencesFlow.map {
        it.smoothingLevel
    }

    suspend fun setSpectrumSmoothing(level: Int) {
        require(level in MIN_SPECTRUM_SMOOTHING..MAX_SPECTRUM_SMOOTHING) {
            "光谱平滑等级超出范围"
        }
        appSettingsDataStore.edit { preferences ->
            preferences[SPECTRUM_SMOOTHING_KEY] = level
        }
    }

    // 获取光谱灵敏度，默认 Medium
    val spectrumSensitivityFlow: Flow<String> = spectrumProcessingPreferencesFlow.map {
        it.sensitivity
    }

    suspend fun setSpectrumSensitivity(value: String) {
        val normalized = SettingsValuePolicy.spectrumPreferences(
            minWavelength = DEFAULT_SPECTRUM_MIN_WAVELENGTH,
            maxWavelength = DEFAULT_SPECTRUM_MAX_WAVELENGTH,
            smoothingLevel = DEFAULT_SPECTRUM_SMOOTHING,
            sensitivity = value
        ).sensitivity
        require(normalized.equals(value.trim(), ignoreCase = true)) { "不支持的寻峰灵敏度" }
        appSettingsDataStore.edit { preferences ->
            preferences[SPECTRUM_SENSITIVITY_KEY] = normalized
        }
    }

    // 获取上次使用的参考波长列表（JSON 格式存储），默认为空
    val spectrumLastReferenceWavelengthsFlow: Flow<String> = appSettingsDataStore.data.map { preferences ->
        preferences[SPECTRUM_LAST_REFERENCE_WAVELENGTHS_KEY] ?: ""
    }

    // 保存参考波长列表（以逗号分隔的字符串）
    suspend fun setSpectrumLastReferenceWavelengths(wavelengths: String) {
        appSettingsDataStore.edit { preferences ->
            preferences[SPECTRUM_LAST_REFERENCE_WAVELENGTHS_KEY] = wavelengths
        }
    }

    val spectrumQualityCheckEnabledFlow: Flow<Boolean> = appSettingsDataStore.data.map { preferences ->
        preferences[SPECTRUM_QUALITY_CHECK_ENABLED_KEY] ?: DEFAULT_SPECTRUM_QUALITY_CHECK_ENABLED
    }

    suspend fun setSpectrumQualityCheckEnabled(enabled: Boolean) {
        appSettingsDataStore.edit { preferences ->
            preferences[SPECTRUM_QUALITY_CHECK_ENABLED_KEY] = enabled
        }
    }

    /** 恢复默认值也使用一次原子写入，页面不会观察到半恢复状态。 */
    suspend fun resetSpectrumDefaults() {
        appSettingsDataStore.edit { preferences ->
            preferences[SPECTRUM_MIN_WAVELENGTH_KEY] = DEFAULT_SPECTRUM_MIN_WAVELENGTH
            preferences[SPECTRUM_MAX_WAVELENGTH_KEY] = DEFAULT_SPECTRUM_MAX_WAVELENGTH
            preferences[SPECTRUM_SMOOTHING_KEY] = DEFAULT_SPECTRUM_SMOOTHING
            preferences[SPECTRUM_SENSITIVITY_KEY] = DEFAULT_SPECTRUM_SENSITIVITY
            preferences[SPECTRUM_DEFAULT_LIGHT_SOURCE_KEY] = DEFAULT_SPECTRUM_LIGHT_SOURCE.name
            preferences[SPECTRUM_QUALITY_CHECK_ENABLED_KEY] = DEFAULT_SPECTRUM_QUALITY_CHECK_ENABLED
        }
    }
    
    companion object {
        const val THEME_MODE_SYSTEM = "system"
        const val THEME_MODE_LIGHT = "light"
        const val THEME_MODE_DARK = "dark"
        const val DEFAULT_THEME_MODE = THEME_MODE_SYSTEM
        // 默认浓度单位集合
        val DEFAULT_CONCENTRATION_UNITS = setOf("ng/ml", "μg/ml", "mg/ml", "g/ml", "mol/L", "mmol/L", "μmol/L", "nmol/L")
        // 自定义阵列沿用新建页既有 10×10 初值；固定 96 孔板仍始终是 8×12，不读此设置。
        const val DEFAULT_CUSTOM_GRID_ROWS = 10
        const val DEFAULT_CUSTOM_GRID_COLUMNS = 10
        // 光谱默认配置
        const val DEFAULT_SPECTRUM_MIN_WAVELENGTH = 400.0f
        const val DEFAULT_SPECTRUM_MAX_WAVELENGTH = 800.0f
        const val DEFAULT_SPECTRUM_SMOOTHING = 3
        const val MIN_SPECTRUM_SMOOTHING = 0
        const val MAX_SPECTRUM_SMOOTHING = 10
        const val DEFAULT_SPECTRUM_SENSITIVITY = "Medium"
        val DEFAULT_SPECTRUM_LIGHT_SOURCE = SpectrumLightSource.LED_WHITE
        const val DEFAULT_SPECTRUM_QUALITY_CHECK_ENABLED = true
    }
}

package com.muc.fluocolorquant.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// 创建独立的DataStore实例，确保语言设置与其他设置分开存储
private val Context.languageDataStore by preferencesDataStore(name = "language_settings")
private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // 语言相关的DataStore实例
    private val languageDataStore: DataStore<Preferences> = context.languageDataStore
    
    // 应用其他设置的DataStore实例
    private val appSettingsDataStore: DataStore<Preferences> = context.appSettingsDataStore

    // 偏好设置的键
    private val LANGUAGE_KEY = stringPreferencesKey("language")
    private val DEFAULT_DETECTION_MODE_KEY = stringPreferencesKey("default_detection_mode")
    private val DEFAULT_CONCENTRATION_UNIT_KEY = stringPreferencesKey("default_concentration_unit")
    private val CONCENTRATION_UNITS_KEY = stringSetPreferencesKey("concentration_units")
    private val DEFAULT_ROWS_KEY = intPreferencesKey("default_rows")
    private val DEFAULT_COLUMNS_KEY = intPreferencesKey("default_columns")
    private val PIXEL_EXTRACTION_METHOD_KEY = stringPreferencesKey("pixel_extraction_method")
    private val IMAGE_PREPROCESSING_ENABLED_KEY = booleanPreferencesKey("image_preprocessing_enabled")

    // 获取当前语言设置，默认为系统语言
    val languageFlow: Flow<String> = languageDataStore.data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: getSystemLanguage()
    }

    // 设置语言
    suspend fun setLanguage(languageCode: String) {
        languageDataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = languageCode
        }
    }

    // 获取系统语言代码
    private fun getSystemLanguage(): String {
        val locale = Locale.getDefault()
        return when (locale.language) {
            "zh" -> "zh"
            else -> "en"
        }
    }
    
    // 获取默认检测模式，默认为荧光检测
    val defaultDetectionModeFlow: Flow<String> = appSettingsDataStore.data.map { preferences ->
        preferences[DEFAULT_DETECTION_MODE_KEY] ?: "FLUORESCENCE"
    }
    
    // 设置默认检测模式
    suspend fun setDefaultDetectionMode(mode: String) {
        appSettingsDataStore.edit { preferences ->
            preferences[DEFAULT_DETECTION_MODE_KEY] = mode
        }
    }
    
    // 更新默认检测模式（不触发语言变化）
    suspend fun setDefaultDetectionModeWithoutLanguageChange(mode: String) {
        // 已确保不会触发语言变化，因为使用了不同的DataStore实例
        appSettingsDataStore.edit { preferences ->
            preferences[DEFAULT_DETECTION_MODE_KEY] = mode
        }
    }
    
    // 获取默认浓度单位，默认为ng/ml
    val defaultConcentrationUnitFlow: Flow<String> = appSettingsDataStore.data.map { preferences ->
        preferences[DEFAULT_CONCENTRATION_UNIT_KEY] ?: "ng/ml"
    }
    
    // 设置默认浓度单位
    suspend fun setDefaultConcentrationUnit(unit: String) {
        appSettingsDataStore.edit { preferences ->
            preferences[DEFAULT_CONCENTRATION_UNIT_KEY] = unit
        }
    }
    
    // 更新默认浓度单位（不触发语言变化）
    suspend fun setDefaultConcentrationUnitWithoutLanguageChange(unit: String) {
        // 已确保不会触发语言变化，因为使用了不同的DataStore实例
        appSettingsDataStore.edit { preferences ->
            preferences[DEFAULT_CONCENTRATION_UNIT_KEY] = unit
        }
    }
    
    // 获取所有可用浓度单位
    val concentrationUnitsFlow: Flow<Set<String>> = appSettingsDataStore.data.map { preferences ->
        preferences[CONCENTRATION_UNITS_KEY] ?: DEFAULT_CONCENTRATION_UNITS
    }
    
    // 添加浓度单位
    suspend fun addConcentrationUnit(unit: String) {
        appSettingsDataStore.edit { preferences ->
            val currentUnits = preferences[CONCENTRATION_UNITS_KEY] ?: DEFAULT_CONCENTRATION_UNITS
            preferences[CONCENTRATION_UNITS_KEY] = currentUnits + unit
        }
    }
    
    // 删除浓度单位
    suspend fun deleteConcentrationUnit(unit: String) {
        appSettingsDataStore.edit { preferences ->
            val currentUnits = preferences[CONCENTRATION_UNITS_KEY] ?: DEFAULT_CONCENTRATION_UNITS
            preferences[CONCENTRATION_UNITS_KEY] = currentUnits - unit
        }
    }
    
    // 获取默认行数
    val defaultRowsFlow: Flow<Int> = appSettingsDataStore.data.map { preferences ->
        preferences[DEFAULT_ROWS_KEY] ?: DEFAULT_ROWS
    }
    
    // 设置默认行数
    suspend fun setDefaultRows(rows: Int) {
        appSettingsDataStore.edit { preferences ->
            preferences[DEFAULT_ROWS_KEY] = rows
        }
    }
    
    // 获取默认列数
    val defaultColumnsFlow: Flow<Int> = appSettingsDataStore.data.map { preferences ->
        preferences[DEFAULT_COLUMNS_KEY] ?: DEFAULT_COLUMNS
    }
    
    // 设置默认列数
    suspend fun setDefaultColumns(columns: Int) {
        appSettingsDataStore.edit { preferences ->
            preferences[DEFAULT_COLUMNS_KEY] = columns
        }
    }
    
    // 获取像素提取方式，默认为区域平均值
    val pixelExtractionMethodFlow: Flow<String> = appSettingsDataStore.data.map { preferences ->
        preferences[PIXEL_EXTRACTION_METHOD_KEY] ?: "roi_avg"
    }
    
    // 设置像素提取方式
    suspend fun setPixelExtractionMethod(method: String) {
        appSettingsDataStore.edit { preferences ->
            preferences[PIXEL_EXTRACTION_METHOD_KEY] = method
        }
    }
    
    // 获取图像预处理开关状态
    val imagePreprocessingEnabledFlow: Flow<Boolean> = appSettingsDataStore.data.map { preferences ->
        preferences[IMAGE_PREPROCESSING_ENABLED_KEY] ?: true
    }
    
    // 设置图像预处理开关
    suspend fun setImagePreprocessingEnabled(enabled: Boolean) {
        appSettingsDataStore.edit { preferences ->
            preferences[IMAGE_PREPROCESSING_ENABLED_KEY] = enabled
        }
    }
    
    companion object {
        // 默认浓度单位集合
        val DEFAULT_CONCENTRATION_UNITS = setOf("ng/ml", "μg/ml", "mg/ml", "g/ml", "mol/L", "mmol/L", "μmol/L", "nmol/L")
        // 默认行数
        val DEFAULT_ROWS = 12
        // 默认列数
        val DEFAULT_COLUMNS = 8
    }
} 
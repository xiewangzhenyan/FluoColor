package com.muc.fluocolorquant.ui.viewmodels

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.SpectrumLightSource
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.utils.math.GridLayoutPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // 收集光谱设置并更新UI状态
        viewModelScope.launch {
            combine(
                settingsRepository.spectrumProcessingPreferencesFlow,
                settingsRepository.spectrumQualityCheckEnabledFlow,
                settingsRepository.projectCreationDefaultsFlow
            ) { processing, qualityCheckEnabled, creationDefaults ->
                SettingsUiState(
                    spectrumMinWavelength = processing.minWavelength,
                    spectrumMaxWavelength = processing.maxWavelength,
                    spectrumSmoothing = processing.smoothingLevel,
                    spectrumSensitivity = processing.sensitivity,
                    spectrumQualityCheckEnabled = qualityCheckEnabled,
                    defaultCustomRows = creationDefaults.customGrid.rows,
                    defaultCustomColumns = creationDefaults.customGrid.columns,
                    spectrumDefaultLightSource = creationDefaults.spectrumLightSource
                )
            }.collect { state -> _uiState.value = state }
        }
    }

    // 当前语言设置
    val currentLanguage: StateFlow<String> = settingsRepository.languageFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "en" // 默认英语
        )

    // 当前主题模式
    val currentThemeMode: StateFlow<String> = settingsRepository.themeModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsRepository.DEFAULT_THEME_MODE
        )

    // 更新语言设置
    fun setLanguage(languageCode: String) {
        viewModelScope.launch {
            settingsRepository.setLanguage(languageCode)
        }
    }

    // 更新主题模式
    fun setThemeMode(themeMode: String) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(themeMode)
        }
    }

    // 语言选项
    val languageOptions = listOf(
        LanguageOption("en", "English"),
        LanguageOption("zh", "中文")
    )

    // 根据语言代码获取显示名称
    fun getLanguageNameByCode(code: String): String {
        return languageOptions.find { it.code == code }?.name ?: "English"
    }

    // 语言选项数据类
    data class LanguageOption(val code: String, val name: String)
    
    // 当前默认检测模式
    val defaultDetectionMode: StateFlow<String> = settingsRepository.defaultDetectionModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "FLUORESCENCE" // 默认荧光检测
        )
    
    // 更新默认检测模式
    fun setDefaultDetectionMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.setDefaultDetectionMode(mode)
        }
    }
    
    // 更新默认检测模式，不重启应用
    fun setDefaultDetectionModeWithoutRestart(mode: String) {
        viewModelScope.launch {
            settingsRepository.setDefaultDetectionModeWithoutLanguageChange(mode)
        }
    }

    /**
     * 原子保存自定义阵列默认规格。返回值只表示输入是否通过同步校验，实际写入仍在
     * ViewModel 协程完成；页面据此避免对非法行列显示“保存成功”。
     */
    fun updateDefaultCustomGrid(rows: Int?, columns: Int?): Boolean {
        if (rows == null || columns == null || !GridLayoutPolicy.isValid(rows, columns)) {
            return false
        }
        viewModelScope.launch {
            settingsRepository.setDefaultCustomGrid(rows, columns)
        }
        return true
    }
    
    // 检测模式选项
    val detectionModeOptions = listOf(
        DetectionModeOption("FLUORESCENCE", R.string.fluorescence_detection),
        DetectionModeOption("COLORIMETRIC", R.string.colorimetric_detection)
    )
    
    // 检测模式选项数据类
    data class DetectionModeOption(
        val code: String,
        @StringRes val nameRes: Int
    )
    
    // 当前默认浓度单位
    val defaultConcentrationUnit: StateFlow<String> = settingsRepository.defaultConcentrationUnitFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "ng/ml" // 默认ng/ml
        )
    
    // 更新默认浓度单位
    fun setDefaultConcentrationUnit(unit: String) {
        viewModelScope.launch {
            settingsRepository.setDefaultConcentrationUnit(unit)
        }
    }
    
    // 更新默认浓度单位，不重启应用
    fun setDefaultConcentrationUnitWithoutRestart(unit: String) {
        viewModelScope.launch {
            settingsRepository.setDefaultConcentrationUnitWithoutLanguageChange(unit)
        }
    }
    
    // 可用浓度单位列表
    val concentrationUnits: StateFlow<Set<String>> = settingsRepository.concentrationUnitsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsRepository.DEFAULT_CONCENTRATION_UNITS
        )
    
    // 添加浓度单位
    fun addConcentrationUnit(unit: String): Boolean {
        if (unit.isBlank() || concentrationUnits.value.contains(unit)) {
            return false
        }
        
        viewModelScope.launch {
            settingsRepository.addConcentrationUnit(unit)
        }
        return true
    }
    
    // 删除浓度单位
    fun deleteConcentrationUnit(unit: String): Boolean {
        if (unit == defaultConcentrationUnit.value) {
            return false
        }
        
        viewModelScope.launch {
            settingsRepository.deleteConcentrationUnit(unit)
        }
        return true
    }
    
    // 获取新单位输入的状态
    val newUnitInput = MutableStateFlow("")
    
    // 更新新单位输入
    fun updateNewUnitInput(input: String) {
        newUnitInput.value = input
    }
    
    // 获取添加单位的结果消息
    val addUnitResult = MutableStateFlow<String?>(null)
    
    // 清除添加单位的结果消息
    fun clearAddUnitResult() {
        addUnitResult.value = null
    }
    
    // 刷新所有设置，确保获取最新的设置值
    fun refreshSettings() {
        viewModelScope.launch {
            // 这里不需要直接更新StateFlow值，因为它们已经绑定到repository中的flow
            // 通过触发一次读取操作，我们确保DataStore的最新值被加载
            val currentDetectionMode = settingsRepository.defaultDetectionModeFlow.first()
            val currentUnit = settingsRepository.defaultConcentrationUnitFlow.first()
            val currentUnits = settingsRepository.concentrationUnitsFlow.first()
            
            android.util.Log.d("SettingsViewModel", "Settings refreshed: mode=$currentDetectionMode, unit=$currentUnit, units=${currentUnits.size}")
        }
    }

    /**
     * 更新波长范围，需保证 max > min，否则不保存。
     */
    fun updateWavelengthRange(min: Float, max: Float) {
        if (max <= min) {
            Log.w(TAG, "Invalid wavelength range: min=$min, max=$max")
            return
        }
        viewModelScope.launch {
            settingsRepository.setSpectrumWavelengthRange(min, max)
        }
    }

    /**
     * 更新曲线平滑等级
     */
    fun updateSmoothing(level: Int) {
        viewModelScope.launch {
            settingsRepository.setSpectrumSmoothing(level)
        }
    }

    /**
     * 更新自动寻峰灵敏度
     */
    fun updateSensitivity(level: String) {
        viewModelScope.launch {
            settingsRepository.setSpectrumSensitivity(level)
        }
    }

    /** 默认光源只影响下一次新建光谱项目，不会改变当前项目或任何历史结果。 */
    fun updateSpectrumDefaultLightSource(lightSource: SpectrumLightSource) {
        viewModelScope.launch {
            settingsRepository.setSpectrumDefaultLightSource(lightSource)
        }
    }

    fun setSpectrumQualityCheckEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSpectrumQualityCheckEnabled(enabled)
        }
    }

    /**
     * 恢复光谱默认配置
     */
    fun resetSpectrumDefaults() {
        viewModelScope.launch {
            settingsRepository.resetSpectrumDefaults()
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}

data class SettingsUiState(
    val spectrumMinWavelength: Float = SettingsRepository.DEFAULT_SPECTRUM_MIN_WAVELENGTH,
    val spectrumMaxWavelength: Float = SettingsRepository.DEFAULT_SPECTRUM_MAX_WAVELENGTH,
    val spectrumSmoothing: Int = SettingsRepository.DEFAULT_SPECTRUM_SMOOTHING,
    val spectrumSensitivity: String = SettingsRepository.DEFAULT_SPECTRUM_SENSITIVITY,
    val spectrumQualityCheckEnabled: Boolean = SettingsRepository.DEFAULT_SPECTRUM_QUALITY_CHECK_ENABLED,
    val defaultCustomRows: Int = SettingsRepository.DEFAULT_CUSTOM_GRID_ROWS,
    val defaultCustomColumns: Int = SettingsRepository.DEFAULT_CUSTOM_GRID_COLUMNS,
    val spectrumDefaultLightSource: SpectrumLightSource =
        SettingsRepository.DEFAULT_SPECTRUM_LIGHT_SOURCE
)

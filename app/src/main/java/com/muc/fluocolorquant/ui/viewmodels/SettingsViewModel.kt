package com.muc.fluocolorquant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
            val spectrumBase = combine(
                settingsRepository.spectrumMinWavelengthFlow,
                settingsRepository.spectrumMaxWavelengthFlow,
                settingsRepository.spectrumSmoothingFlow,
                settingsRepository.spectrumSensitivityFlow
            ) { min, max, smoothing, sensitivity ->
                SettingsUiState(
                    spectrumMinWavelength = min,
                    spectrumMaxWavelength = max,
                    spectrumSmoothing = smoothing,
                    spectrumSensitivity = sensitivity
                )
            }

            combine(
                spectrumBase,
                settingsRepository.spectrumDefaultTrackCountFlow,
                settingsRepository.spectrumMaxTrackCountFlow
            ) { base, defaultTracks, maxTracks ->
                base.copy(
                    spectrumDefaultTrackCount = defaultTracks,
                    spectrumMaxTrackCount = maxTracks
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

    // 更新语言设置
    fun setLanguage(languageCode: String) {
        viewModelScope.launch {
            settingsRepository.setLanguage(languageCode)
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
    
    // 检测模式选项
    val detectionModeOptions = listOf(
        DetectionModeOption("FLUORESCENCE", "Fluorescence Detection"),
        DetectionModeOption("COLORIMETRIC", "Colorimetric Detection")
    )
    
    // 检测模式选项数据类
    data class DetectionModeOption(val code: String, val name: String)
    
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

    // 当前默认行数
    val defaultRows: StateFlow<Int> = settingsRepository.defaultRowsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 12 // 默认12行
        )
    
    // 更新默认行数
    fun setDefaultRows(rows: Int) {
        viewModelScope.launch {
            settingsRepository.setDefaultRows(rows)
        }
    }
    
    // 当前默认列数
    val defaultColumns: StateFlow<Int> = settingsRepository.defaultColumnsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 8 // 默认8列
        )
    
    // 更新默认列数
    fun setDefaultColumns(columns: Int) {
        viewModelScope.launch {
            settingsRepository.setDefaultColumns(columns)
        }
    }
    
    // 像素提取方式
    val pixelExtractionMethod: StateFlow<String> = settingsRepository.pixelExtractionMethodFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "roi_avg" // 默认区域平均值
        )
    
    // 像素提取方式选项
    val pixelExtractionOptions = listOf(
        PixelExtractionOption("roi_avg", "pixel_extraction_option_roi_avg"),
        PixelExtractionOption("center_pixel", "pixel_extraction_option_center_pixel"),
        PixelExtractionOption("gaussian_avg", "pixel_extraction_option_gaussian_avg")
    )
    
    // 像素提取方式选项数据类
    data class PixelExtractionOption(val code: String, val resourceId: String)
    
    // 设置像素提取方式
    fun setPixelExtractionMethod(method: String) {
        viewModelScope.launch {
            settingsRepository.setPixelExtractionMethod(method)
        }
    }
    
    // 图像预处理设置
    val imagePreprocessingEnabled: StateFlow<Boolean> = settingsRepository.imagePreprocessingEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true // 默认开启
        )
    
    // 设置图像预处理开关
    fun setImagePreprocessingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setImagePreprocessingEnabled(enabled)
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
            settingsRepository.setSpectrumMinWavelength(min)
            settingsRepository.setSpectrumMaxWavelength(max)
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

    /**
     * 恢复光谱默认配置
     */
    fun resetSpectrumDefaults() {
        viewModelScope.launch {
            settingsRepository.setSpectrumMinWavelength(SettingsRepository.DEFAULT_SPECTRUM_MIN_WAVELENGTH)
            settingsRepository.setSpectrumMaxWavelength(SettingsRepository.DEFAULT_SPECTRUM_MAX_WAVELENGTH)
            settingsRepository.setSpectrumSmoothing(SettingsRepository.DEFAULT_SPECTRUM_SMOOTHING)
            settingsRepository.setSpectrumSensitivity(SettingsRepository.DEFAULT_SPECTRUM_SENSITIVITY)
            settingsRepository.setSpectrumDefaultTrackCount(SettingsRepository.DEFAULT_SPECTRUM_DEFAULT_TRACK_COUNT)
            settingsRepository.setSpectrumMaxTrackCount(SettingsRepository.DEFAULT_SPECTRUM_MAX_TRACK_COUNT)
        }
    }

    /**
     * 更新通道配置（默认/最大），需校验 default <= max 且 max > 0
     */
    fun updateTrackCountConfig(defaultTracks: Int, maxTracks: Int) {
        if (maxTracks <= 0 || defaultTracks > maxTracks || defaultTracks <= 0) {
            Log.w(TAG, "Invalid track config: default=$defaultTracks, max=$maxTracks")
            return
        }
        viewModelScope.launch {
            settingsRepository.setSpectrumDefaultTrackCount(defaultTracks)
            settingsRepository.setSpectrumMaxTrackCount(maxTracks)
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
    val spectrumDefaultTrackCount: Int = SettingsRepository.DEFAULT_SPECTRUM_DEFAULT_TRACK_COUNT,
    val spectrumMaxTrackCount: Int = SettingsRepository.DEFAULT_SPECTRUM_MAX_TRACK_COUNT
)

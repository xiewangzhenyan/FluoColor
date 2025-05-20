package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

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
} 
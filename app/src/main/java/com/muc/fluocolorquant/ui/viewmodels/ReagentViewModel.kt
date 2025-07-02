package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.ReagentRepository
import com.muc.fluocolorquant.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 试剂ViewModel
 * 管理试剂相关的UI状态和业务逻辑
 */
@HiltViewModel
class ReagentViewModel @Inject constructor(
    private val reagentRepository: ReagentRepository,
    private val analyteRepository: AnalyteRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {
    
    // 所有分析物列表
    private val _allAnalytes = analyteRepository.getAllAnalytes()
        .catch { e ->
            _error.value = e.message ?: "未知错误"
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    val allAnalytes: StateFlow<List<Analyte>> = _allAnalytes
    
    // 所有试剂列表
    private val _allReagents = reagentRepository.getAllReagents()
        .catch { e ->
            _error.value = e.message ?: "未知错误"
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // 按分析物分组的试剂
    val reagentsGroupedByAnalyte: StateFlow<Map<Analyte, List<Reagent>>> = combine(
        _allReagents,
        _allAnalytes
    ) { reagents, analytes ->
        val analyteMap = analytes.associateBy { it.id }
        reagents.groupBy { reagent ->
            analyteMap[reagent.analyteId] ?: 
            // 创建一个临时分析物对象，防止空指针异常
            Analyte(reagent.analyteId, "未知分析物")
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap()
    )
    
    // 可用的浓度单位
    val concentrationUnits = settingsRepository.concentrationUnitsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )
    
    // 加载状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    // 错误信息
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    /**
     * 添加试剂
     * @param analyteId 分析物ID
     * @param reagentName 试剂名称
     * @param reagentType 试剂类型 ("antigen" 或 "antibody")
     * @param manufacturer 制造商（可选）
     * @param molecularWeightStr 分子量（可选，字符串形式）
     * @param unit 浓度单位（可选）
     * @return 添加结果，成功返回true，失败返回false
     */
    suspend fun addReagent(
        analyteId: String,
        reagentName: String,
        reagentType: String,
        manufacturer: String? = null,
        molecularWeightStr: String? = null,
        unit: String? = null
    ): Boolean {
        if (reagentName.isBlank() || analyteId.isBlank() || reagentType.isBlank()) {
            return false
        }
        
        _isLoading.value = true
        
        return try {
            // 将分子量字符串转换为Double（如果提供）
            val molecularWeight = molecularWeightStr?.trim()?.toDoubleOrNull()
            
            val result = reagentRepository.addReagent(
                analyteId = analyteId,
                reagentName = reagentName,
                reagentType = reagentType,
                manufacturer = manufacturer,
                molecularWeight = molecularWeight,
                unit = unit
            )
            _error.value = null
            result
        } catch (e: Exception) {
            _error.value = e.message ?: "添加试剂时发生错误"
            false
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * 更新试剂
     * @param id 试剂ID
     * @param analyteId 分析物ID
     * @param reagentName 试剂名称
     * @param reagentType 试剂类型 ("antigen" 或 "antibody")
     * @param manufacturer 制造商（可选）
     * @param molecularWeightStr 分子量（可选，字符串形式）
     * @param unit 浓度单位（可选）
     * @return 更新结果，成功返回true，失败返回false
     */
    suspend fun updateReagent(
        id: String,
        analyteId: String,
        reagentName: String,
        reagentType: String,
        manufacturer: String? = null,
        molecularWeightStr: String? = null,
        unit: String? = null
    ): Boolean {
        if (reagentName.isBlank() || analyteId.isBlank() || reagentType.isBlank() || id.isBlank()) {
            return false
        }
        
        _isLoading.value = true
        
        return try {
            // 将分子量字符串转换为Double（如果提供）
            val molecularWeight = molecularWeightStr?.trim()?.toDoubleOrNull()
            
            val result = reagentRepository.updateReagent(
                id = id,
                analyteId = analyteId,
                reagentName = reagentName,
                reagentType = reagentType,
                manufacturer = manufacturer,
                molecularWeight = molecularWeight,
                unit = unit
            )
            _error.value = null
            result
        } catch (e: Exception) {
            _error.value = e.message ?: "更新试剂时发生错误"
            false
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * 删除试剂
     * @param reagent 要删除的试剂
     */
    fun deleteReagent(reagent: Reagent) {
        viewModelScope.launch {
            _isLoading.value = true
            
            try {
                reagentRepository.deleteReagent(reagent)
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message ?: "删除试剂时发生错误"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 清除错误信息
     */
    fun clearError() {
        _error.value = null
    }
} 
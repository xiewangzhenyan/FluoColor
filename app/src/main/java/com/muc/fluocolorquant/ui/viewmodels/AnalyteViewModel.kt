package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 分析物ViewModel
 * 管理分析物相关的UI状态和业务逻辑
 */
@HiltViewModel
class AnalyteViewModel @Inject constructor(
    private val analyteRepository: AnalyteRepository
) : ViewModel() {
    
    // 分析物列表状态
    private val _analytes = analyteRepository.getAllAnalytes()
        .catch { e ->
            _errorMessage.value = e.message ?: "未知错误"
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    val analytes: StateFlow<List<Analyte>> = _analytes
    
    // 加载状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    // 错误信息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage
    
    /**
     * 添加分析物
     * @param name 分析物名称
     */
    fun addAnalyte(name: String) {
        if (name.isBlank()) {
            _errorMessage.value = "分析物名称不能为空"
            return
        }
        
        viewModelScope.launch {
            _isLoading.value = true
            
            try {
                val result = analyteRepository.addAnalyte(name)
                if (!result) {
                    _errorMessage.value = "该分析物已存在"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "添加分析物时发生错误"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 更新分析物
     * @param id 分析物ID
     * @param name 新的分析物名称
     */
    fun updateAnalyte(id: String, name: String) {
        if (name.isBlank()) {
            _errorMessage.value = "分析物名称不能为空"
            return
        }
        
        viewModelScope.launch {
            _isLoading.value = true
            
            try {
                val result = analyteRepository.updateAnalyte(id, name)
                if (!result) {
                    _errorMessage.value = "该分析物名称已存在"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "更新分析物时发生错误"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 删除分析物
     * @param analyteId 要删除的分析物ID
     */
    fun deleteAnalyte(analyteId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            
            try {
                analyteRepository.deleteAnalyte(analyteId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "删除分析物时发生错误"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 清除错误信息
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }
} 
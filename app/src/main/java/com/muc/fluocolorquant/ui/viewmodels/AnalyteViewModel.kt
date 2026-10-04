package com.muc.fluocolorquant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.utils.UiText
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
        .catch { error ->
            Log.e(TAG, "加载分析物列表失败", error)
            _errorMessage.value = UiText.StringResource(R.string.analyte_load_error)
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
    private val _errorMessage = MutableStateFlow<UiText?>(null)
    val errorMessage: StateFlow<UiText?> = _errorMessage
    
    /**
     * 添加分析物
     * @param name 分析物名称
     */
    fun addAnalyte(name: String) {
        if (name.isBlank()) {
            _errorMessage.value = UiText.StringResource(R.string.analyte_name_empty)
            return
        }
        
        viewModelScope.launch {
            _isLoading.value = true
            
            try {
                val result = analyteRepository.addAnalyte(name)
                if (!result) {
                    _errorMessage.value = UiText.StringResource(R.string.analyte_already_exists)
                }
            } catch (error: Exception) {
                Log.e(TAG, "添加分析物失败", error)
                _errorMessage.value = UiText.StringResource(R.string.analyte_added_error)
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
            _errorMessage.value = UiText.StringResource(R.string.analyte_name_empty)
            return
        }
        
        viewModelScope.launch {
            _isLoading.value = true
            
            try {
                val result = analyteRepository.updateAnalyte(id, name)
                if (!result) {
                    _errorMessage.value = UiText.StringResource(R.string.analyte_already_exists)
                }
            } catch (error: Exception) {
                Log.e(TAG, "更新分析物失败: $id", error)
                _errorMessage.value = UiText.StringResource(R.string.analyte_update_error)
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
            } catch (error: Exception) {
                Log.e(TAG, "删除分析物失败: $analyteId", error)
                _errorMessage.value = UiText.StringResource(R.string.analyte_delete_error)
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

    private companion object {
        const val TAG: String = "AnalyteViewModel"
    }
}

package com.muc.fluocolorquant.ui.viewmodels

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.repository.CurveModelRepository
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.utils.math.FittingResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID
import javax.inject.Inject

/**
 * 曲线创建流程状态类
 */
sealed class CreationFlowState {
    /**
     * 手动曲线输入状态
     */
    data class ManualCurveInput(
        val selectedFunction: FittingFunction? = null,
        val selectedPixelType: PixelType? = null,
        val parameters: MutableMap<String, Double> = mutableMapOf()
    ) : CreationFlowState()
    
    /**
     * 手动数据输入状态
     */
    data class ManualDataInput(
        val dataTable: List<List<Double>> = listOf(emptyList()),
        val columnTypes: List<ColumnType> = listOf(ColumnType.NONE),
        val fittingResult: FittingResult? = null
    ) : CreationFlowState()
}

/**
 * 表格列类型
 */
enum class ColumnType {
    NONE,
    CONCENTRATION,
    PIXEL_VALUE
}

/**
 * 曲线模型ViewModel
 */
@HiltViewModel
class CurveModelViewModel @Inject constructor(
    private val curveModelRepository: CurveModelRepository
) : ViewModel() {
    
    // 所有曲线模型列表
    private val _models = MutableStateFlow<List<CurveModel>>(emptyList())
    val models: StateFlow<List<CurveModel>> = _models.asStateFlow()
    
    // 当前展开的模型ID
    private val _expandedModelId = MutableStateFlow<String?>(null)
    val expandedModelId: StateFlow<String?> = _expandedModelId.asStateFlow()
    
    // 当前创建流程状态
    private val _activeCreationFlow = MutableStateFlow<CreationFlowState?>(null)
    val activeCreationFlow: StateFlow<CreationFlowState?> = _activeCreationFlow.asStateFlow()
    
    // 是否正在加载
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // 错误信息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    init {
        loadCurveModels()
    }
    
    /**
     * 加载所有曲线模型
     */
    fun loadCurveModels() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                curveModelRepository.getAllCurveModels().collect {
                    _models.value = it
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                _errorMessage.value = "加载曲线模型失败: ${e.message}"
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 展开/折叠模型详情
     */
    fun toggleExpandModel(id: String) {
        _expandedModelId.value = if (_expandedModelId.value == id) null else id
    }
    
    /**
     * 删除模型
     */
    fun deleteModel(id: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                curveModelRepository.deleteCurveModelById(id)
                _isLoading.value = false
                // 自动刷新列表
            } catch (e: Exception) {
                _errorMessage.value = "删除模型失败: ${e.message}"
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 导出模型
     */
    fun exportModel(model: CurveModel, filePath: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val success = curveModelRepository.exportCurveModel(model, filePath)
                if (!success) {
                    _errorMessage.value = "导出模型失败"
                }
                _isLoading.value = false
            } catch (e: Exception) {
                _errorMessage.value = "导出模型失败: ${e.message}"
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 开始创建流程 - 手动曲线输入
     */
    fun startManualCurveInput() {
        _activeCreationFlow.value = CreationFlowState.ManualCurveInput()
    }
    
    /**
     * 开始创建流程 - 手动数据输入
     */
    fun startManualDataInput() {
        _activeCreationFlow.value = CreationFlowState.ManualDataInput(
            dataTable = listOf(listOf(0.0, 0.0)),
            columnTypes = listOf(ColumnType.CONCENTRATION, ColumnType.NONE)
        )
    }
    
    /**
     * 更新选择的函数类型
     */
    fun updateSelectedFunction(function: FittingFunction) {
        val currentState = _activeCreationFlow.value
        if (currentState is CreationFlowState.ManualCurveInput) {
            _activeCreationFlow.value = currentState.copy(
                selectedFunction = function,
                parameters = mutableMapOf() // 重置参数
            )
        }
    }
    
    /**
     * 更新选择的像素类型
     */
    fun updateSelectedPixelType(pixelType: PixelType) {
        val currentState = _activeCreationFlow.value
        if (currentState is CreationFlowState.ManualCurveInput) {
            _activeCreationFlow.value = currentState.copy(selectedPixelType = pixelType)
        }
    }


    /**
     * 更新函数参数
     * 【已修正】接受可为空的Double?。如果值为null，则从参数map中移除该键。
     */
    fun updateFunctionParameter(paramName: String, value: Double?) {
        val currentState = _activeCreationFlow.value
        if (currentState is CreationFlowState.ManualCurveInput) {
            val newParameters = currentState.parameters.toMutableMap()
            if (value == null) {
                newParameters.remove(paramName)
            } else {
                newParameters[paramName] = value
            }
            _activeCreationFlow.value = currentState.copy(parameters = newParameters)
        }
    }
    
    /**
     * 获取当前选定函数的曲线计算函数
     */
    fun getCurveFunction(): ((Double) -> Double)? {
        val currentState = _activeCreationFlow.value
        return when (currentState) {
            is CreationFlowState.ManualCurveInput -> {
                val function = currentState.selectedFunction ?: return null
                val params = currentState.parameters
                
                if (params.isEmpty()) return null
                
                // 使用FittingEngine创建函数
                try {
                    return FittingEngine.createFunctionFromParameters(function, params)
                } catch (e: Exception) {
                    _errorMessage.value = "创建函数失败: ${e.message}"
                    return null
                }
            }
            is CreationFlowState.ManualDataInput -> {
                return currentState.fittingResult?.function?.let { function ->
                    val params = currentState.fittingResult.params
                    try {
                        return@let FittingEngine.createFunctionFromParameters(function, params)
                    } catch (e: Exception) {
                        _errorMessage.value = "创建函数失败: ${e.message}"
                        null
                    }
                }
            }
            else -> null
        }
    }
    
    /**
     * 更新数据表格内容
     */
    fun updateDataTableCell(row: Int, col: Int, value: Double) {
        val currentState = _activeCreationFlow.value
        if (currentState is CreationFlowState.ManualDataInput) {
            val newTable = currentState.dataTable.toMutableList()
            
            // 确保行列都存在
            while (newTable.size <= row) {
                newTable.add(MutableList(currentState.columnTypes.size) { 0.0 })
            }
            val rowList = newTable[row].toMutableList()
            while (rowList.size <= col) {
                rowList.add(0.0)
            }
            
            rowList[col] = value
            newTable[row] = rowList
            
            _activeCreationFlow.value = currentState.copy(dataTable = newTable)
        }
    }
    
    /**
     * 更新列类型
     */
    fun updateColumnType(col: Int, type: ColumnType) {
        val currentState = _activeCreationFlow.value
        if (currentState is CreationFlowState.ManualDataInput) {
            val newColumnTypes = currentState.columnTypes.toMutableList()
            
            // 确保列存在
            while (newColumnTypes.size <= col) {
                newColumnTypes.add(ColumnType.NONE)
            }
            
            newColumnTypes[col] = type
            
            _activeCreationFlow.value = currentState.copy(columnTypes = newColumnTypes)
        }
    }
    
    /**
     * 添加新行
     */
    fun addDataRow() {
        val currentState = _activeCreationFlow.value
        if (currentState is CreationFlowState.ManualDataInput) {
            val newTable = currentState.dataTable.toMutableList()
            val columns = if (newTable.isNotEmpty()) newTable[0].size else currentState.columnTypes.size
            
            newTable.add(MutableList(columns) { 0.0 })
            
            _activeCreationFlow.value = currentState.copy(dataTable = newTable)
        }
    }
    
    /**
     * 添加新列
     */
    fun addDataColumn() {
        val currentState = _activeCreationFlow.value
        if (currentState is CreationFlowState.ManualDataInput) {
            val newTable = currentState.dataTable.toMutableList()
            val newColumnTypes = currentState.columnTypes.toMutableList()
            
            // 为每行添加新列
            for (i in newTable.indices) {
                val row = newTable[i].toMutableList()
                row.add(0.0)
                newTable[i] = row
            }
            
            // 添加新列类型
            newColumnTypes.add(ColumnType.NONE)
            
            _activeCreationFlow.value = currentState.copy(
                dataTable = newTable,
                columnTypes = newColumnTypes
            )
        }
    }
    
    /**
     * 删除最后一行
     */
    fun removeDataRow() {
        val currentState = _activeCreationFlow.value
        if (currentState is CreationFlowState.ManualDataInput) {
            val newTable = currentState.dataTable.toMutableList()
            
            // 确保至少保留一行
            if (newTable.size > 1) {
                newTable.removeAt(newTable.size - 1)
                
                _activeCreationFlow.value = currentState.copy(
                    dataTable = newTable
                )
            }
        }
    }
    
    /**
     * 删除最后一列
     */
    fun removeDataColumn() {
        val currentState = _activeCreationFlow.value
        if (currentState is CreationFlowState.ManualDataInput) {
            val newTable = currentState.dataTable.toMutableList()
            val newColumnTypes = currentState.columnTypes.toMutableList()
            
            // 确保至少保留一列
            if (newColumnTypes.size > 1) {
                // 删除每行的最后一个元素
                for (i in newTable.indices) {
                    val row = newTable[i].toMutableList()
                    if (row.isNotEmpty()) {
                        row.removeAt(row.size - 1)
                        newTable[i] = row
                    }
                }
                
                // 删除最后一列类型
                newColumnTypes.removeAt(newColumnTypes.size - 1)
                
                _activeCreationFlow.value = currentState.copy(
                    dataTable = newTable,
                    columnTypes = newColumnTypes
                )
            }
        }
    }
    
    /**
     * 执行数据拟合
     */
    fun performFitFromData() {
        val currentState = _activeCreationFlow.value
        if (currentState is CreationFlowState.ManualDataInput) {
            viewModelScope.launch {
                _isLoading.value = true
                try {
                    // 查找浓度列和像素值列
                    val concentrationColIndex = currentState.columnTypes.indexOf(ColumnType.CONCENTRATION)
                    val pixelValueColIndex = currentState.columnTypes.indexOf(ColumnType.PIXEL_VALUE)
                    
                    if (concentrationColIndex < 0 || pixelValueColIndex < 0) {
                        _errorMessage.value = "请先指定浓度列和像素值列"
                        _isLoading.value = false
                        return@launch
                    }
                    
                    // 提取数据点
                    val dataPoints = currentState.dataTable.map { row ->
                        if (row.size > concentrationColIndex && row.size > pixelValueColIndex) {
                            Pair(row[concentrationColIndex], row[pixelValueColIndex])
                        } else {
                            null
                        }
                    }.filterNotNull()
                    
                    if (dataPoints.isEmpty()) {
                        _errorMessage.value = "没有有效的数据点"
                        _isLoading.value = false
                        return@launch
                    }
                    
                    // 执行拟合
                    val result = FittingEngine.fit(dataPoints)
                    
                    if (!result.isSuccess) {
                        _errorMessage.value = "拟合失败: ${result.errorMessage}"
                        _isLoading.value = false
                        return@launch
                    }
                    
                    // 更新状态
                    _activeCreationFlow.value = currentState.copy(fittingResult = result)
                    _isLoading.value = false
                    
                } catch (e: Exception) {
                    _errorMessage.value = "拟合过程中出错: ${e.message}"
                    _isLoading.value = false
                }
            }
        }
    }
    
    /**
     * 从文件导入模型
     */
    fun importCurveFromFile(uri: Uri) {
        // 需要实现文件读取和解析逻辑
    }
    
    /**
     * 从Excel文件导入数据
     */
    fun importDataFromExcel(uri: Uri) {
        // 需要实现Excel读取逻辑
    }
    
    /**
     * 保存模型
     */
    fun saveModel(name: String) {
        viewModelScope.launch {
            _isLoading.value = true
            
            try {
                when (val state = _activeCreationFlow.value) {
                    is CreationFlowState.ManualCurveInput -> {
                        val function = state.selectedFunction
                        val pixelType = state.selectedPixelType
                        val params = state.parameters
                        
                        if (function == null || pixelType == null || params.isEmpty()) {
                            _errorMessage.value = "请完成所有必填项"
                            _isLoading.value = false
                            return@launch
                        }
                        
                        val model = CurveModel(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            function = function,
                            pixelType = pixelType,
                            parameters = params,
                            createdAt = Date(),
                            updatedAt = Date()
                        )
                        
                        curveModelRepository.saveCurveModel(model)
                        _activeCreationFlow.value = null // 关闭创建流程
                    }
                    
                    is CreationFlowState.ManualDataInput -> {
                        val result = state.fittingResult
                        
                        if (result == null || !result.isSuccess) {
                            _errorMessage.value = "请先执行拟合"
                            _isLoading.value = false
                            return@launch
                        }
                        
                        // 确定像素类型（需要从UI获取）
                        val pixelType = PixelType.GRAY_LUMINOSITY // 这里需要实际获取用户选择
                        
                        val model = CurveModel(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            function = result.function,
                            pixelType = pixelType,
                            parameters = result.params,
                            metrics = result.metrics,
                            dataPoints = result.dataPoints,
                            createdAt = Date(),
                            updatedAt = Date()
                        )
                        
                        curveModelRepository.saveCurveModel(model)
                        _activeCreationFlow.value = null // 关闭创建流程
                    }
                    
                    else -> {
                        _errorMessage.value = "没有活动的创建流程"
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "保存模型失败: ${e.message}"
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
    
    /**
     * 取消创建流程
     */
    fun cancelCreationFlow() {
        _activeCreationFlow.value = null
    }
    
    /**
     * 更新曲线模型
     */
    fun updateModel(model: CurveModel) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 更新模型时设置新的更新时间
                val updatedModel = model.copy(updatedAt = Date())
                curveModelRepository.saveCurveModel(updatedModel)
                _isLoading.value = false
            } catch (e: Exception) {
                _errorMessage.value = "更新模型失败: ${e.message}"
                _isLoading.value = false
            }
        }
    }
} 
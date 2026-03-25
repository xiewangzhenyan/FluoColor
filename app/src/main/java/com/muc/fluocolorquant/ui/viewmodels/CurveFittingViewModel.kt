package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.CurveModelRepository
import com.muc.fluocolorquant.data.repository.DetectionRunRepository
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.utils.math.ConcentrationPrediction
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.utils.math.FittingResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * 曲线拟合视图模型
 * 负责管理曲线拟合过程和结果
 */
@HiltViewModel
class CurveFittingViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val analyteRepository: AnalyteRepository,
    private val curveModelRepository: CurveModelRepository,
    private val detectionRunRepository: DetectionRunRepository
) : ViewModel() {
    
    // 当前项目ID
    private val _currentProjectId = MutableStateFlow<String?>(null)
    val currentProjectId: StateFlow<String?> = _currentProjectId.asStateFlow()
    
    // 项目中的分析物列表
    private val _analytes = MutableStateFlow<List<Analyte>>(emptyList())
    val analytes: StateFlow<List<Analyte>> = _analytes.asStateFlow()
    
    // 当前选中的分析物
    private val _selectedAnalyte = MutableStateFlow<Analyte?>(null)
    val selectedAnalyte: StateFlow<Analyte?> = _selectedAnalyte.asStateFlow()
    
    // 项目孔位结果
    private val _wellResults = MutableStateFlow<List<WellResult>>(emptyList())
    val wellResults: StateFlow<List<WellResult>> = _wellResults.asStateFlow()
    
    // 标准品孔位结果（用于拟合）
    private val _standardWells = MutableStateFlow<List<WellResult>>(emptyList())
    val standardWells: StateFlow<List<WellResult>> = _standardWells.asStateFlow()
    
    // 样本孔位结果（需要预测浓度）
    private val _sampleWells = MutableStateFlow<List<WellResult>>(emptyList())
    val sampleWells: StateFlow<List<WellResult>> = _sampleWells.asStateFlow()
    
    // 拟合结果列表
    private val _fittingResults = MutableStateFlow<List<FittingResult>>(emptyList())
    val fittingResults: StateFlow<List<FittingResult>> = _fittingResults.asStateFlow()
    
    // 选中的拟合结果
    private val _selectedFittingResult = MutableStateFlow<FittingResult?>(null)
    val selectedFittingResult: StateFlow<FittingResult?> = _selectedFittingResult.asStateFlow()
    
    // 加载状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // 标准品浓度输入
    private val _standardConcentrations = MutableStateFlow<Map<String, Double>>(emptyMap())
    val standardConcentrations: StateFlow<Map<String, Double>> = _standardConcentrations.asStateFlow()
    
    /**
     * 设置项目ID并加载项目数据
     */
    fun setProjectId(projectId: String) {
        _currentProjectId.value = projectId
        loadProjectData(projectId)
    }
    
    /**
     * 加载项目数据
     */
    private fun loadProjectData(projectId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 加载项目中的分析物
                val analytesList = withContext(Dispatchers.IO) {
                    // 使用getAllAnalytes()方法获取所有分析物，然后在内存中过滤
                    // 由于AnalyteRepository没有直接提供getAnalytesByProjectId方法
                    analyteRepository.getAllAnalytes().first()
                }
                _analytes.value = analytesList
                
                // 加载项目中的孔位结果
                val results = withContext(Dispatchers.IO) {
                    detectionRunRepository.getWellResultsByProjectId(projectId)
                }
                _wellResults.value = results
                
                _isLoading.value = false
            } catch (e: Exception) {
                _isLoading.value = false
                // 处理错误
            }
        }
    }
    
    /**
     * 根据分析物过滤孔位
     */
    private fun filterWellsByAnalyte(analyteId: String) {
        val allWells = _wellResults.value
        // 过滤出属于该分析物的孔位
        val analyteWells = allWells.filter { it.fkAnalyteId == analyteId }
        
        // 分离标准品和样本
        val standards = analyteWells.filter { it.roleType == "Standard" }
        val samples = analyteWells.filter { it.roleType == "Sample" }
        
        _standardWells.value = standards
        _sampleWells.value = samples
    }
    
    /**
     * 选择分析物
     */
    fun selectAnalyte(analyte: Analyte) {
        _selectedAnalyte.value = analyte
        filterWellsByAnalyte(analyte.id)
    }
    
    /**
     * 更新孔位角色类型
     */
    fun updateWellRole(wellId: String, analyteId: String, roleType: String) {
        viewModelScope.launch {
            try {
                val well = _wellResults.value.find { it.resultId.toString() == wellId }
                if (well != null) {
                    val updatedWell = well.copy(
                        fkAnalyteId = analyteId,
                        roleType = roleType
                    )
                    
                    // 更新数据库
                    withContext(Dispatchers.IO) {
                        detectionRunRepository.updateWellResult(updatedWell)
                    }
                    
                    // 更新本地缓存
                    val updatedWells = _wellResults.value.map {
                        if (it.resultId.toString() == wellId) updatedWell else it
                    }
                    _wellResults.value = updatedWells
                    
                    // 重新过滤孔位
                    filterWellsByAnalyte(_selectedAnalyte.value?.id ?: analyteId)
                }
            } catch (e: Exception) {
                // 处理错误
            }
        }
    }
    
    /**
     * 设置标准品浓度
     */
    fun setStandardConcentration(wellId: String, concentration: Double) {
        val currentConcentrations = _standardConcentrations.value.toMutableMap()
        currentConcentrations[wellId] = concentration
        _standardConcentrations.value = currentConcentrations
        
        // 更新孔位的真实浓度
        viewModelScope.launch {
            try {
                val well = _wellResults.value.find { it.resultId.toString() == wellId }
                if (well != null) {
                    val updatedWell = well.copy(
                        trueConcentration = concentration
                    )
                    
                    // 更新数据库
                    withContext(Dispatchers.IO) {
                        detectionRunRepository.updateWellResult(updatedWell)
                    }
                    
                    // 更新本地缓存
                    val updatedWells = _wellResults.value.map {
                        if (it.resultId.toString() == wellId) updatedWell else it
                    }
                    _wellResults.value = updatedWells
                    _standardWells.value = _standardWells.value.map {
                        if (it.resultId.toString() == wellId) updatedWell else it
                    }
                }
            } catch (e: Exception) {
                // 处理错误
            }
        }
    }
    
    /**
     * 执行曲线拟合
     */
    fun performCurveFitting(
        function: FittingFunction,
        pixelType: PixelType
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val standards = _standardWells.value
                
                if (standards.size < 3) {
                    // 标准品数量不足
                    _isLoading.value = false
                    return@launch
                }
                
                // 提取浓度和像素值
                val concentrationsAndPixels = standards.mapNotNull { well ->
                    val concentration = well.trueConcentration
                    if (concentration != null && well.pixelValueJson != null) {
                        // 从JSON中提取指定像素类型的值
                        val pixelValues = parsePixelValues(well.pixelValueJson)
                        val pixelValue = getPixelValue(pixelValues, pixelType)
                        
                        if (pixelValue != null) {
                            Pair(concentration, pixelValue)
                        } else null
                    } else null
                }
                
                if (concentrationsAndPixels.isEmpty()) {
                    _isLoading.value = false
                    return@launch
                }
                
                // 提取样本像素值用于预测
                val samples = _sampleWells.value
                val samplePixelValues = samples.mapNotNull { well ->
                    if (well.pixelValueJson != null) {
                        val pixelValues = parsePixelValues(well.pixelValueJson)
                        getPixelValue(pixelValues, pixelType)
                    } else null
                }
                
                // 使用 FittingEngine 执行完整的拟合和预测工作流
                val fittingResult = FittingEngine.fitWithPredictions(
                    standardPoints = concentrationsAndPixels,
                    samplePixelValues = samplePixelValues,
                    function = function,
                    pixelType = pixelType
                )
                
                // 更新样本孔位的浓度预测结果
                fittingResult.predictions.forEachIndexed { index, prediction ->
                    if (index < samples.size && prediction.isValid) {
                        val sample = samples[index]
                        // 更新样本的预测浓度（这里可以根据需要保存到数据库）
                        val updatedSample = sample.copy(
                            predictedConcentration = prediction.concentration
                        )
                        // 这里可以调用 updateWellResult(updatedSample) 来保存到数据库
                    }
                }
                
                // 更新拟合结果列表
                val currentResults = _fittingResults.value.toMutableList()
                currentResults.add(fittingResult)
                _fittingResults.value = currentResults
                
                // 选择当前拟合结果
                _selectedFittingResult.value = fittingResult
                
                _isLoading.value = false
            } catch (e: Exception) {
                _isLoading.value = false
                // 处理错误
            }
        }
    }
    
    /**
     * 从JSON字符串解析像素值
     */
    private fun parsePixelValues(json: String): Map<String, Double> {
        val type = object : TypeToken<Map<String, Double>>() {}.type
        return try {
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 兼容历史数据中使用枚举名、新数据使用 identifier 存储的像素键。
     */
    private fun getPixelValue(
        pixelValues: Map<String, Double>,
        pixelType: PixelType
    ): Double? = pixelValues[pixelType.identifier] ?: pixelValues[pixelType.name]
    
    /**
     * 获取孔位标签（如A1, B2等）
     */
    private fun getWellLabel(row: Int, col: Int): String {
        val rowChar = ('A' + row).toChar()
        return "$rowChar${col + 1}"
    }
    
    /**
     * 选择拟合结果
     */
    fun selectFittingResult(result: FittingResult) {
        _selectedFittingResult.value = result
    }
    
    /**
     * 加载拟合结果
     */
    fun loadFittingResults(projectId: String, analyteId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 加载曲线模型
                val allModels = withContext(Dispatchers.IO) {
                    curveModelRepository.getAllCurveModels().first()
                }
                
                // 过滤出与当前分析物相关的模型
                val models = allModels.filter { it.name.contains(analyteId) }
                
                // 加载孔位结果
                val wells = withContext(Dispatchers.IO) {
                    detectionRunRepository.getWellResultsByProjectId(projectId)
                }
                
                val analyteWells = wells.filter { it.fkAnalyteId == analyteId }
                val standards = analyteWells.filter { it.roleType == "Standard" }
                val samples = analyteWells.filter { it.roleType == "Sample" }
                
                _wellResults.value = wells
                _standardWells.value = standards
                _sampleWells.value = samples
                
                // 如果有现成的曲线模型，使用第一个
                if (models.isNotEmpty()) {
                    val model = models.first()
                    
                    // 提取标准品数据
                    val standardPoints = standards.mapNotNull { well ->
                        val concentration = well.trueConcentration
                        if (concentration != null && well.pixelValueJson != null) {
                            val pixelValues = parsePixelValues(well.pixelValueJson)
                            val pixelValue = getPixelValue(pixelValues, model.pixelType)
                            if (pixelValue != null) {
                                Pair(concentration, pixelValue)
                            } else null
                        } else null
                    }
                    
                    // 提取样本像素值
                    val samplePixelValues = samples.mapNotNull { well ->
                        if (well.pixelValueJson != null) {
                            val pixelValues = parsePixelValues(well.pixelValueJson)
                            getPixelValue(pixelValues, model.pixelType)
                        } else null
                    }
                    
                    // 使用 FittingEngine 重新构建拟合结果
                    val result = FittingEngine.fitWithPredictions(
                        standardPoints = standardPoints,
                        samplePixelValues = samplePixelValues,
                        function = model.function,
                        pixelType = model.pixelType
                    )
                    
                    _fittingResults.value = listOf(result)
                    _selectedFittingResult.value = result
                }
                
                _isLoading.value = false
            } catch (e: Exception) {
                _isLoading.value = false
                // 处理错误
            }
        }
    }
    
    /**
     * 保存拟合结果
     */
    fun saveFittingResult() {
        viewModelScope.launch {
            try {
                val result = _selectedFittingResult.value ?: return@launch
                val analyteId = _selectedAnalyte.value?.id ?: return@launch
                
                // 创建曲线模型
                val curveModel = CurveModel(
                    id = UUID.randomUUID().toString(),
                    name = "拟合模型-${result.function.displayName}-${result.pixelType?.displayName}",
                    function = result.function,
                    pixelType = result.pixelType ?: PixelType.GRAY_LUMINOSITY,
                    parameters = result.params,
                    metrics = result.allMetrics,
                    dataPoints = result.standardPoints
                )
                
                // 保存到数据库
                withContext(Dispatchers.IO) {
                    curveModelRepository.saveCurveModel(curveModel)
                }
                
                // 更新样本孔位的预测浓度
                result.predictions.forEach { prediction ->
                    // 对于 FittingEngine 预测的结果，通过 sampleIndex 来找到对应的样本
                    val samples = _sampleWells.value
                    if (prediction.sampleIndex >= 0 && prediction.sampleIndex < samples.size) {
                        val well = samples[prediction.sampleIndex]
                        val updatedWell = well.copy(
                            predictedConcentration = prediction.concentration
                        )
                        
                        // 调用已有的挂起函数
                        updateWellResult(updatedWell)
                    } else if (prediction.wellId.isNotEmpty()) {
                        // 如果有 wellId，通过 wellId 查找
                        val well = _wellResults.value.find { it.resultId.toString() == prediction.wellId }
                        if (well != null) {
                            val updatedWell = well.copy(
                                predictedConcentration = prediction.concentration
                            )
                            
                            // 调用已有的挂起函数
                            updateWellResult(updatedWell)
                        }
                    }
                }
            } catch (e: Exception) {
                // 处理错误
                android.util.Log.e("CurveFittingViewModel", "保存拟合结果失败", e)
            }
        }
    }
    
    /**
     * 更新孔位结果
     */
    private suspend fun updateWellResult(wellResult: WellResult) {
        detectionRunRepository.updateWellResult(wellResult)
    }
} 

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
import com.muc.fluocolorquant.utils.math.CurveFittingUtils
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
                        val pixelValue = pixelValues[pixelType.name]
                        
                        if (pixelValue != null) {
                            Pair(concentration, pixelValue)
                        } else null
                    } else null
                }
                
                if (concentrationsAndPixels.isEmpty()) {
                    _isLoading.value = false
                    return@launch
                }
                
                val concentrations = concentrationsAndPixels.map { it.first }
                val pixelValues = concentrationsAndPixels.map { it.second }
                
                // 执行拟合
                val parameters = when (function) {
                    FittingFunction.LINEAR -> {
                        // 简单线性回归
                        val sumX = concentrations.sum()
                        val sumY = pixelValues.sum()
                        val sumXY = concentrations.zip(pixelValues).sumOf { it.first * it.second }
                        val sumX2 = concentrations.sumOf { it * it }
                        val n = concentrations.size
                        
                        val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
                        val intercept = (sumY - slope * sumX) / n
                        
                        doubleArrayOf(intercept, slope)
                    }
                    FittingFunction.QUADRATIC -> {
                        // 简单二次多项式拟合
                        // 这里只是一个简化版本，实际应该使用矩阵求解
                        doubleArrayOf(0.0, 1.0, 0.1)
                    }
                    else -> {
                        // 默认线性拟合
                        val sumX = concentrations.sum()
                        val sumY = pixelValues.sum()
                        val sumXY = concentrations.zip(pixelValues).sumOf { it.first * it.second }
                        val sumX2 = concentrations.sumOf { it * it }
                        val n = concentrations.size
                        
                        val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
                        val intercept = (sumY - slope * sumX) / n
                        
                        doubleArrayOf(intercept, slope)
                    }
                }
                
                // 计算R²
                val yMean = pixelValues.average()
                val predictedValues = concentrations.map { x ->
                    when (function) {
                        FittingFunction.LINEAR -> parameters[0] + parameters[1] * x
                        FittingFunction.QUADRATIC -> parameters[0] + parameters[1] * x + parameters[2] * x * x
                        else -> parameters[0] + parameters[1] * x
                    }
                }
                
                val totalSS = pixelValues.sumOf { (it - yMean) * (it - yMean) }
                val residualSS = pixelValues.zip(predictedValues).sumOf { (y, yPred) -> 
                    (y - yPred) * (y - yPred) 
                }
                val rSquared = 1.0 - (residualSS / totalSS)
                
                // 生成公式
                val formula = generateFormula(function, parameters)
                
                // 生成曲线点
                val curvePoints = generateCurvePoints(function, parameters, concentrationsAndPixels)
                
                // 创建拟合结果
                val fittingResult = FittingResult(
                    function = function,
                    parameters = parameters,
                    formula = formula,
                    rSquared = rSquared,
                    standardPoints = concentrationsAndPixels,
                    curvePoints = curvePoints,
                    pixelType = pixelType
                )
                
                // 使用拟合结果预测样本浓度
                val samples = _sampleWells.value
                val predictions = samples.mapNotNull { well ->
                    if (well.pixelValueJson != null) {
                        val pixelValues = parsePixelValues(well.pixelValueJson)
                        val pixelValue = pixelValues[pixelType.name]
                        
                        if (pixelValue != null) {
                            // 预测浓度
                            val concentration = predictConcentration(
                                pixelValue = pixelValue,
                                function = function,
                                parameters = parameters
                            )
                            
                            // 创建预测结果
                            val wellLabel = getWellLabel(well.virtualRow ?: 0, well.virtualCol ?: 0)
                            ConcentrationPrediction(
                                wellLabel = wellLabel,
                                wellId = well.resultId.toString(),
                                pixelValue = pixelValue,
                                concentration = concentration,
                                pixelType = pixelType
                            )
                        } else null
                    } else null
                }
                
                // 保存拟合结果
                val resultWithPredictions = fittingResult.copy(
                    predictions = predictions
                )
                
                // 更新拟合结果列表
                val currentResults = _fittingResults.value.toMutableList()
                currentResults.add(resultWithPredictions)
                _fittingResults.value = currentResults
                
                // 选择当前拟合结果
                _selectedFittingResult.value = resultWithPredictions
                
                _isLoading.value = false
            } catch (e: Exception) {
                _isLoading.value = false
                // 处理错误
            }
        }
    }
    
    /**
     * 预测浓度
     */
    private fun predictConcentration(
        pixelValue: Double,
        function: FittingFunction,
        parameters: DoubleArray
    ): Double {
        return when (function) {
            FittingFunction.LINEAR -> {
                val intercept = parameters[0]
                val slope = parameters[1]
                
                if (slope == 0.0) return 0.0
                return (pixelValue - intercept) / slope
            }
            FittingFunction.QUADRATIC -> {
                val a = parameters[0]
                val b = parameters[1]
                val c = parameters[2]
                
                // 求解一元二次方程 ax^2 + bx + c - y = 0
                val p = b
                val q = a
                val r = c - pixelValue
                
                // 使用求根公式
                val discriminant = p * p - 4 * q * r
                if (discriminant < 0) return 0.0
                
                val x1 = (-p + Math.sqrt(discriminant)) / (2 * q)
                val x2 = (-p - Math.sqrt(discriminant)) / (2 * q)
                
                // 返回正值解
                return if (x1 > 0) x1 else if (x2 > 0) x2 else 0.0
            }
            else -> {
                // 默认线性
                val intercept = parameters[0]
                val slope = parameters[1]
                
                if (slope == 0.0) return 0.0
                return (pixelValue - intercept) / slope
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
                    
                    // 构造拟合结果
                    val function = model.function
                    val pixelType = model.pixelType
                    val parameters = model.parameters.values.toDoubleArray()
                    
                    // 提取标准品数据
                    val standardPoints = standards.mapNotNull { well ->
                        val concentration = well.trueConcentration
                        if (concentration != null && well.pixelValueJson != null) {
                            val pixelValues = parsePixelValues(well.pixelValueJson)
                            val pixelValue = pixelValues[pixelType.name]
                            if (pixelValue != null) {
                                Pair(concentration, pixelValue)
                            } else null
                        } else null
                    }
                    
                    // 生成曲线点
                    val curvePoints = generateCurvePoints(function, parameters, standardPoints)
                    
                    // 预测样本浓度
                    val predictions = samples.mapNotNull { well ->
                        if (well.pixelValueJson != null) {
                            val pixelValues = parsePixelValues(well.pixelValueJson)
                            val pixelValue = pixelValues[pixelType.name]
                            
                            if (pixelValue != null) {
                                val concentration = predictConcentration(
                                    pixelValue = pixelValue,
                                    function = function,
                                    parameters = parameters
                                )
                                
                                val wellLabel = getWellLabel(well.virtualRow ?: 0, well.virtualCol ?: 0)
                                ConcentrationPrediction(
                                    wellLabel = wellLabel,
                                    wellId = well.resultId.toString(),
                                    pixelValue = pixelValue,
                                    concentration = concentration,
                                    pixelType = pixelType
                                )
                            } else null
                        } else null
                    }
                    
                    val result = FittingResult(
                        function = function,
                        parameters = parameters,
                        formula = generateFormula(function, parameters),
                        rSquared = model.metrics?.get("rSquared") ?: 0.0,
                        standardPoints = standardPoints,
                        curvePoints = curvePoints,
                        pixelType = pixelType,
                        predictions = predictions
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
     * 根据函数类型和参数生成公式字符串
     */
    private fun generateFormula(function: FittingFunction, parameters: DoubleArray): String {
        return when (function) {
            FittingFunction.LINEAR -> {
                val a = parameters[0]
                val b = parameters[1]
                "y = ${String.format("%.4f", a)} + ${String.format("%.4f", b)}x"
            }
            FittingFunction.QUADRATIC -> {
                val a = parameters[0]
                val b = parameters[1]
                val c = parameters[2]
                "y = ${String.format("%.4f", a)} + ${String.format("%.4f", b)}x + ${String.format("%.4f", c)}x²"
            }
            // 其他函数类型的公式生成...
            else -> "y = f(x)"
        }
    }
    
    /**
     * 生成曲线点
     */
    private fun generateCurvePoints(
        function: FittingFunction,
        parameters: DoubleArray,
        standardPoints: List<Pair<Double, Double>>
    ): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        
        // 获取浓度范围
        val concentrations = standardPoints.map { it.first }
        val minConc = concentrations.minOrNull() ?: 0.0
        val maxConc = concentrations.maxOrNull() ?: 0.0
        val range = maxConc - minConc
        val start = if (minConc > 0) minConc / 2 else 0.0
        val end = maxConc + range / 2
        
        // 生成100个点
        val steps = 100
        val step = (end - start) / steps
        
        val f = { x: Double ->
            when (function) {
                FittingFunction.LINEAR -> parameters[0] + parameters[1] * x
                FittingFunction.QUADRATIC -> parameters[0] + parameters[1] * x + parameters[2] * x * x
                FittingFunction.CUBIC -> parameters[0] + parameters[1] * x + parameters[2] * x * x + parameters[3] * x * x * x
                FittingFunction.QUARTIC -> parameters[0] + parameters[1] * x + parameters[2] * x * x + parameters[3] * x * x * x + parameters[4] * Math.pow(x, 4.0)
                FittingFunction.LOG -> parameters[0] + parameters[1] * Math.log(x)
                FittingFunction.EXPONENTIAL -> parameters[0] * Math.exp(parameters[1] * x)
                FittingFunction.POWER -> parameters[0] * Math.pow(x, parameters[1])
                FittingFunction.RODBARD -> {
                    val a = parameters[0] // 最小渐近值
                    val b = parameters[1] // Hill斜率
                    val c = parameters[2] // 拐点（EC50）
                    val d = parameters[3] // 最大渐近值
                    d + (a - d) / (1 + Math.pow(x / c, b))
                }
                FittingFunction.LOGISTIC -> {
                    val a = parameters[0] // 最小渐近值
                    val b = parameters[1] // Hill斜率
                    val c = parameters[2] // 拐点（EC50）
                    val d = parameters[3] // 最大渐近值
                    val g = parameters[4] // 不对称因子
                    d + (a - d) / Math.pow(1 + Math.pow(x / c, b), g)
                }
                else -> parameters[0] + parameters[1] * x // 默认线性
            }
        }
        
        for (i in 0..steps) {
            val x = start + i * step
            points.add(Pair(x, f(x)))
        }
        
        return points
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
                    val well = _wellResults.value.find { it.resultId.toString() == prediction.wellId }
                    if (well != null) {
                        val updatedWell = well.copy(
                            predictedConcentration = prediction.concentration
                        )
                        
                        // 调用已有的挂起函数
                        updateWellResult(updatedWell)
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
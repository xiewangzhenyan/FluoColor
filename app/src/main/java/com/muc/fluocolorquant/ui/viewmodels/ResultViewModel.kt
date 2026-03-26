package com.muc.fluocolorquant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.dao.DetectionRunDao
import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.data.repository.ProjectAnalyteJoinRepository
import com.muc.fluocolorquant.data.model.AnalyteResultDetails
import com.muc.fluocolorquant.data.model.ValidationData
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.dao.AnalyteDao
import com.muc.fluocolorquant.data.dao.CurveModelDao
import com.muc.fluocolorquant.data.dao.ExperimentTemplateDao
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.components.charts.ChartPoint
import com.muc.fluocolorquant.utils.ResultTraceabilityUtils
import com.muc.fluocolorquant.utils.math.MetricsCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import android.app.Application
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 结果展示ViewModel
 * 管理检测结果展示的UI逻辑
 */
@HiltViewModel
class ResultViewModel @Inject constructor(
    private val wellResultDao: WellResultDao,
    private val projectDao: ProjectDao,
    private val detectionRunDao: DetectionRunDao,
    private val analyteDao: AnalyteDao,
    private val curveModelDao: CurveModelDao,
    private val experimentTemplateDao: ExperimentTemplateDao,
    val projectAnalyteJoinRepository: ProjectAnalyteJoinRepository,
    private val application: Application
) : ViewModel() {
    // 结果数据加载状态
    sealed class ResultState {
        object Loading : ResultState()
        data class Success(
            val project: Project,
            val wellResults: List<WellResult>,
            val detectionRun: DetectionRun?
        ) : ResultState()
        data class Error(val message: String) : ResultState()
    }

    // 当前结果状态
    private val _resultState = MutableStateFlow<ResultState>(ResultState.Loading)
    val resultState: StateFlow<ResultState> = _resultState.asStateFlow()

    // 浓度单位 - 将从项目数据动态更新
    private val _concentrationUnit = MutableStateFlow("ng/ml") // 默认值，会被覆盖
    val concentrationUnit: StateFlow<String> = _concentrationUnit.asStateFlow()

    // 热力图颜色范围
    private val _minConcentration = MutableStateFlow(0.0)
    val minConcentration: StateFlow<Double> = _minConcentration.asStateFlow()

    private val _maxConcentration = MutableStateFlow(100.0)
    val maxConcentration: StateFlow<Double> = _maxConcentration.asStateFlow()

    // 当前项目
    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    // 新增：以analyteId为键的分析物结果Map
    private val _analyteResultsMap = MutableStateFlow<Map<String, AnalyteResultDetails>>(emptyMap())
    val analyteResultsMap: StateFlow<Map<String, AnalyteResultDetails>> = _analyteResultsMap.asStateFlow()

    // 新增：项目中的分析物列表，用于构建Tab
    val analytesInProject = _analyteResultsMap.map { map ->
        map.values.map { it.analyte }.sortedBy { it.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 重命名为analytesList，保持与NewResultScreen.kt一致
    val analytesList = analytesInProject

    // 新增：当前选中的分析物ID
    private val _selectedAnalyteId = MutableStateFlow<String?>(null)
    val selectedAnalyteId: StateFlow<String?> = _selectedAnalyteId.asStateFlow()

    // 新增：获取当前选中的分析物详情
    val selectedAnalyteDetails = _analyteResultsMap.map { map ->
        _selectedAnalyteId.value?.let { map[it] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 设置当前选中的分析物
     */
    fun selectAnalyte(analyteId: String) {
        _selectedAnalyteId.value = analyteId
    }

    /**
     * 从项目分析物配置中获取浓度单位和最大浓度
     * @param projectId 项目ID
     * @return Pair<String, Double> 浓度单位和最大浓度
     */
    private suspend fun getProjectConfigValuesAsync(projectId: String): Pair<String, Double> {
        // 获取项目的第一个分析物配置
        val analyteJoin = withContext(Dispatchers.IO) {
            projectAnalyteJoinRepository.getFirstProjectAnalyteJoin(projectId)
        }

        // 如果存在配置，则返回其浓度单位和最大浓度；否则返回默认值
        return Pair(
            analyteJoin?.concentrationUnit ?: "ng/ml",
            analyteJoin?.maxConcentration ?: 100.0
        )
    }

    /**
     * 根据项目ID加载结果数据
     * 用于手动裁剪模式
     */
    fun loadResultsByProjectId(projectId: String) {
        _resultState.value = ResultState.Loading

        viewModelScope.launch {
            try {
                // 加载项目信息
                val project = withContext(Dispatchers.IO) {
                    projectDao.getProjectById(projectId)
                }

                if (project == null) {
                    _resultState.value = ResultState.Error(application.getString(R.string.error_project_not_found))
                    return@launch
                }

                // 更新当前项目
                _currentProject.value = project

                // 获取浓度单位和最大浓度
                val (concentrationUnitValue, maxConcentrationValue) = getProjectConfigValuesAsync(projectId)

                // 更新浓度单位
                _concentrationUnit.value = concentrationUnitValue

                // 对于手动模式，加载该项目下的所有孔位结果
                val wellResults = withContext(Dispatchers.IO) {
                    wellResultDao.getWellResultsByProjectId(projectId)
                }

                // 设置浓度范围
                updateConcentrationRange(wellResults, maxConcentrationValue)

                // 更新结果状态
                _resultState.value = ResultState.Success(
                    project = project,
                    wellResults = wellResults,
                    detectionRun = null // 手动模式下不需要检测运行记录
                )
            } catch (e: Exception) {
                Log.e("ResultViewModel", "加载项目结果失败", e)
                _resultState.value = ResultState.Error(application.getString(R.string.error_loading_results, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * 根据运行ID加载结果数据
     * 用于自动识别模式，构建以分析物为中心的报告
     */
    fun loadResultsByRunId(runId: String) {
        _resultState.value = ResultState.Loading

        viewModelScope.launch {
            try {
                // 加载检测运行记录
                val detectionRun = withContext(Dispatchers.IO) {
                    detectionRunDao.getDetectionRunById(runId)
                }

                if (detectionRun == null) {
                    _resultState.value = ResultState.Error(application.getString(R.string.error_run_not_found))
                    return@launch
                }

                // 加载项目信息
                val project = withContext(Dispatchers.IO) {
                    projectDao.getProjectById(detectionRun.projectId)
                }

                if (project == null) {
                    _resultState.value = ResultState.Error(application.getString(R.string.error_project_not_found))
                    return@launch
                }

                // 更新当前项目
                _currentProject.value = project

                // 加载孔位结果
                val allWellResults = withContext(Dispatchers.IO) {
                    wellResultDao.getWellResultsByRunId(runId)
                }

                // 加载项目中所有的分析物配置
                val projectAnalyteJoins = withContext(Dispatchers.IO) {
                    projectAnalyteJoinRepository.getProjectAnalyteJoins(project.id)
                }

                // 获取默认浓度单位和最大浓度（使用第一个分析物的配置）
                val defaultJoin = projectAnalyteJoins.firstOrNull()
                _concentrationUnit.value = defaultJoin?.concentrationUnit ?: "ng/ml"
                val defaultMaxConcentration = defaultJoin?.maxConcentration ?: 100.0

                // 设置浓度范围
                updateConcentrationRange(allWellResults, defaultMaxConcentration)

                // 构建分析物结果Map
                val analyteResultDetailsMap = mutableMapOf<String, AnalyteResultDetails>()

                // 对于每个分析物配置，构建其结果详情
                for (join in projectAnalyteJoins) {
                    val analyteId = join.analyteId

                    // 获取分析物信息
                    val analyte = withContext(Dispatchers.IO) {
                        analyteDao.getAnalyteById(analyteId)
                    } ?: continue // 如果找不到分析物，跳过

                    // 过滤出该分析物的孔位结果
                    val analyteWellResults = allWellResults.filter { it.fkAnalyteId == analyteId }

                    // 分析方法从项目记录中获取
                    val analysisMethod = project.analysisMethod ?: "CURVE_FIT"

                    // 获取模板信息（如果有）
                    val template = if (join.fkTemplateId != null) {
                        withContext(Dispatchers.IO) {
                            experimentTemplateDao.getTemplateById(join.fkTemplateId)
                        }
                    } else null

                    // 获取曲线模型信息（如果有）
                    val curveModel = if (join.fkCurveModelId != null) {
                        withContext(Dispatchers.IO) {
                            curveModelDao.getCurveModelById(join.fkCurveModelId)
                        }
                    } else null

                    // 【修复】确定浓度单位的优先级
                    val unit = template?.concentrationUnit ?: join.concentrationUnit ?: "ng/ml"

                    // 生成标准曲线图表数据（如果是曲线拟合模式）
                    val standardCurveChartData = if (analysisMethod == "CURVE_FIT" && curveModel != null) {
                        generateStandardCurveChartData(curveModel, unit) // 使用确定的单位
                    } else null

                    // 生成浓度趋势图表数据
                    val concentrationTrendChartData = generateConcentrationTrendChartData(
                        analyteWellResults,
                        analyte.name,
                        unit // 使用确定的单位
                    )

                    // 构建分析物结果详情对象
                    val resultDetails = AnalyteResultDetails(
                        analyte = analyte,
                        wellResults = analyteWellResults,
                        project = project,
                        concentrationUnit = unit, // 【修复】填充新字段
                        analysisMethod = analysisMethod,
                        usedTemplate = template,
                        fittedCurveModel = curveModel,
                        standardCurveChartData = standardCurveChartData,
                        concentrationTrendChartData = concentrationTrendChartData,
                        validationData = null, // 初始为空，需要用户触发验证分析
                        traceabilityInfo = ResultTraceabilityUtils.buildTraceabilityInfo(
                            project = project,
                            detectionRun = detectionRun,
                            template = template,
                            curveModel = curveModel
                        )
                    )

                    // 添加到结果Map
                    analyteResultDetailsMap[analyteId] = resultDetails
                }

                // 更新状态
                _analyteResultsMap.value = analyteResultDetailsMap

                // 选择第一个分析物作为默认选中
                if (analyteResultDetailsMap.isNotEmpty()) {
                    _selectedAnalyteId.value = analyteResultDetailsMap.keys.first()
                }

                // 更新原有的结果状态（兼容旧UI）
                _resultState.value = ResultState.Success(
                    project = project,
                    wellResults = allWellResults,
                    detectionRun = detectionRun
                )
            } catch (e: Exception) {
                Log.e("ResultViewModel", "加载运行结果失败", e)
                _resultState.value = ResultState.Error(application.getString(R.string.error_loading_results, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * 更新浓度范围
     */
    private fun updateConcentrationRange(wellResults: List<WellResult>, projectMaxConcentration: Double?) {
        // 确保有设定最大浓度值，否则使用默认值100.0
        val maxConc = projectMaxConcentration ?: 100.0

        // 【修正】现在 predictedConcentration 已经是最终浓度值，直接用它来计算范围
        val validConcentrations = wellResults
            .mapNotNull { it.predictedConcentration }
            .filter { it.isFinite() && it >= 0 }

        if (validConcentrations.isNotEmpty()) {
            _minConcentration.value = 0.0 // 浓度范围下限始终为0
            val actualMax = validConcentrations.maxOrNull() ?: maxConc
            _maxConcentration.value = maxOf(actualMax, maxConc)
        } else {
            // 默认范围
            _minConcentration.value = 0.0
            _maxConcentration.value = maxConc
        }
    }

    /**
     * 【新增】计算浓度百分比，用于UI显示
     * @param actualValue 实际浓度值
     * @param maxConcentration 最大浓度值
     * @return 浓度百分比
     */
    fun calculateConcentrationPercentage(actualValue: Double?, maxConcentration: Double?): Double? {
        if (actualValue == null || !actualValue.isFinite()) return null
        val maxConc = maxConcentration ?: 100.0
        if (maxConc <= 0) return 0.0
        return (actualValue / maxConc) * 100.0
    }

    /**
     * 获取孔位图像文件或URI
     * 处理两种不同的存储方式：文件路径和内容URI
     */
    fun getWellImageFile(wellResult: WellResult): Any? {
        val imageIdentifier = wellResult.croppedImageIdentifier ?: return null

        // 检查是否为URI格式（content://开头)
        return if (imageIdentifier.startsWith("content://") ||
            imageIdentifier.startsWith("file://")) {
            // 返回URI对象，AsyncImage可以直接使用
            android.net.Uri.parse(imageIdentifier)
        } else {
            // 作为文件路径处理
            val imageFile = File(imageIdentifier)
            if (imageFile.exists()) imageFile else null
        }
    }

    /**
     * 加载默认或最近的结果
     * 当没有提供runId或projectId时调用
     */
    fun loadDefaultOrMostRecentResults() {
        _resultState.value = ResultState.Loading

        viewModelScope.launch {
            try {
                // 尝试获取最新的项目
                val latestProject = withContext(Dispatchers.IO) {
                    projectDao.getLatestProject()
                }

                if (latestProject != null) {
                    // 更新当前项目
                    _currentProject.value = latestProject

                    // 获取浓度单位和最大浓度
                    val (concentrationUnitValue, maxConcentrationValue) = getProjectConfigValuesAsync(latestProject.id)

                    // 更新浓度单位
                    _concentrationUnit.value = concentrationUnitValue

                    // 如果找到最新项目，加载其结果
                    val wellResults = withContext(Dispatchers.IO) {
                        wellResultDao.getWellResultsByProjectId(latestProject.id)
                    }

                    // 获取最新的检测运行(如果有)
                    val latestRun = withContext(Dispatchers.IO) {
                        detectionRunDao.getLatestDetectionRunByProjectId(latestProject.id)
                    }

                    // 设置浓度范围
                    updateConcentrationRange(wellResults, maxConcentrationValue)

                    // 更新结果状态
                    _resultState.value = ResultState.Success(
                        project = latestProject,
                        wellResults = wellResults,
                        detectionRun = latestRun
                    )
                } else {
                    _resultState.value = ResultState.Error(application.getString(R.string.error_no_projects_found))
                }
            } catch (e: Exception) {
                Log.e("ResultViewModel", "加载默认结果失败", e)
                _resultState.value = ResultState.Error(application.getString(R.string.error_loading_results, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * 生成标准曲线图表数据
     */
    private fun generateStandardCurveChartData(
        curveModel: com.muc.fluocolorquant.data.model.CurveModel,
        unit: String
    ): ChartData {
        // 从曲线模型中提取数据点
        val dataPoints = curveModel.dataPoints ?: emptyList()

        // 确定X轴范围
        val xMin = dataPoints.minOfOrNull { it.first } ?: 0.0
        val xMax = dataPoints.maxOfOrNull { it.first } ?: 10.0

        // 创建拟合函数
        val fittedFunction: (Double) -> Double = { x ->
            when (curveModel.function) {
                FittingFunction.LINEAR -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    a * x + b
                }
                FittingFunction.QUADRATIC -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    a * x * x + b * x + c
                }
                FittingFunction.CUBIC -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    val d = curveModel.parameters["d"] ?: 0.0
                    a * x * x * x + b * x * x + c * x + d
                }
                FittingFunction.QUARTIC -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    val d = curveModel.parameters["d"] ?: 0.0
                    val e = curveModel.parameters["e"] ?: 0.0
                    a * x.pow(4) + b * x.pow(3) + c * x.pow(2) + d * x + e
                }
                FittingFunction.EXPONENTIAL -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    a * Math.exp(b * x)
                }
                FittingFunction.LOG -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    if (x <= 0) 0.0 else a + b * Math.log(x)
                }
                FittingFunction.POWER -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    a * x.pow(b)
                }
                FittingFunction.LOGISTIC -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    val d = curveModel.parameters["d"] ?: 0.0
                    val g = curveModel.parameters["g"] ?: 1.0
                    d + (a - d) / (1 + (x / c).pow(b)).pow(g)
                }
                FittingFunction.RODBARD -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    val d = curveModel.parameters["d"] ?: 0.0
                    d + (a - d) / (1 + (x / c).pow(b))
                }
                FittingFunction.GAMMA_VARIATE -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    val d = curveModel.parameters["d"] ?: 0.0
                    if (x <= b) 0.0 else a * (x - b).pow(c) * Math.exp(-(x - b) / d)
                }
                FittingFunction.CUSTOM_LOG -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    if (x <= c) 0.0 else a + b * Math.log(x - c)
                }
                FittingFunction.RODBARD_NIH -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    a / (1 + (x / c).pow(b))
                }
                FittingFunction.EXPONENTIAL_WITH_OFFSET -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    a * Math.exp(-b * x) + c
                }
                FittingFunction.GAUSSIAN -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    val d = curveModel.parameters["d"] ?: 0.0
                    a + (b - a) * Math.exp(-((x - c).pow(2)) / (2 * d * d))
                }
                FittingFunction.EXPONENTIAL_RECOVERY -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    a * (1 - Math.exp(-b * x))
                }
                FittingFunction.GOMPERTZ -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    a * Math.exp(-b * Math.exp(-c * x))
                }
                FittingFunction.HILL -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    a * x.pow(b) / (c.pow(b) + x.pow(b))
                }
                FittingFunction.GENERAL_GOMPERTZ -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    val d = curveModel.parameters["d"] ?: 0.0
                    a * Math.exp(-b * Math.exp(-c * x.pow(d)))
                }
                FittingFunction.RICHARDS -> {
                    val a = curveModel.parameters["a"] ?: 0.0
                    val b = curveModel.parameters["b"] ?: 0.0
                    val c = curveModel.parameters["c"] ?: 0.0
                    val d = curveModel.parameters["d"] ?: 0.0
                    a / (1 + b * Math.exp(-c * x)).pow(1 / d)
                }
                FittingFunction.INTERPOLATION -> 0.0 // 插值函数不在这里实现
            }
        }

        // 【修改】计算曲线上的点，以确定Y轴的实际范围
        val pointCount = 200 // 增加点数，提高精度
        val xStep = (xMax - xMin) / pointCount
        val curvePoints = mutableListOf<Pair<Double, Double>>()

        // 计算曲线上的所有点
        for (i in 0..pointCount) {
            val x = xMin + i * xStep
            try {
                val y = fittedFunction(x)
                if (!y.isNaN() && !y.isInfinite()) {
                    curvePoints.add(Pair(x, y))
                }
            } catch (e: Exception) {
                // 忽略计算错误
                continue
            }
        }

        // 【修改】确定Y轴范围，考虑曲线上所有点和原始数据点
        val yValues = curvePoints.map { it.second } + dataPoints.map { it.second }
        val yMin = yValues.minOfOrNull { it } ?: 0.0
        val yMax = yValues.maxOfOrNull { it } ?: 100.0

        // 【修改】为Y轴范围添加一些边距，确保曲线完全可见
        val yRange = yMax - yMin
        val adjustedYMin = yMin - yRange * 0.05 // 下方添加5%的边距
        val adjustedYMax = yMax + yRange * 0.05 // 上方添加5%的边距

        return ChartData(
            title = "Standard Curve",
            xRange = Pair(xMin, xMax),
            yRange = Pair(adjustedYMin, adjustedYMax), // 使用调整后的Y轴范围
            standardPoints = dataPoints,
            curvePoints = curvePoints,
            formula = curveModel.function.toString(),
            xAxisLabel = "Concentration ($unit)",
            yAxisLabel = "Pixel (${curveModel.pixelType?.name ?: "Unknown"})",
            fittedCurve = fittedFunction
        )
    }

    /**
     * 生成浓度趋势图表数据
     */
    private fun generateConcentrationTrendChartData(
        wellResults: List<WellResult>,
        analyteName: String,
        unit: String
    ): ChartData {
        // 过滤出有有效浓度的孔位
        val validResults = wellResults
            .filter { it.predictedConcentration != null && it.predictedConcentration!!.isFinite() }
            .sortedBy { it.wellIndex }

        // 创建图表点
        val scatterPoints = validResults.mapIndexed { index, result ->
            val concentration = result.predictedConcentration ?: 0.0
            val wellLabel = if (result.virtualRow != null && result.virtualCol != null) {
                "${('A' + result.virtualRow).toChar()}${result.virtualCol + 1}"
            } else {
                "Well ${result.wellIndex + 1}"
            }

            ChartPoint(
                x = index.toDouble(),
                y = concentration,
                label = wellLabel
            )
        }

        // 确定Y轴范围
        val yMin = 0.0
        val yMax = if (validResults.isNotEmpty()) {
            validResults.maxOfOrNull { it.predictedConcentration ?: 0.0 } ?: 100.0
        } else 100.0

        return ChartData(
            title = "$analyteName Concentration",
            xRange = Pair(0.0, (validResults.size - 1).toDouble().coerceAtLeast(1.0)),
            yRange = Pair(yMin, yMax * 1.1), // 留出10%的顶部空间
            standardPoints = emptyList(),
            curvePoints = scatterPoints.map { Pair(it.x, it.y) },
            xAxisLabel = "Wells",
            yAxisLabel = "Concentration ($unit)",
            scatterPoints = scatterPoints
        )
    }

    /**
     * 【最终修正】更新孔位的真实浓度值并立即触发验证分析
     * @param analyteId 分析物ID
     * @param trueValues 键为wellResultId，值为真实浓度的映射
     */
    fun updateTrueConcentrations(analyteId: String, trueValues: Map<Long, Double>) {
        viewModelScope.launch {
            try {
                Log.d("ResultViewModel", "开始更新真实浓度值: $analyteId, 共${trueValues.size}个值")
                // 1. 在后台线程更新数据库
                withContext(Dispatchers.IO) {
                    trueValues.forEach { (resultId, trueConcentration) ->
                        val wellResult = wellResultDao.getWellResultById(resultId)
                        wellResult?.let {
                            val updatedResult = it.copy(trueConcentration = trueConcentration)
                            wellResultDao.updateWellResult(updatedResult)
                        }
                    }
                }
                Log.d("ResultViewModel", "数据库更新完成。")

                // 2. 更新内存中的 StateFlow，这是解决问题的关键
                val currentMap = _analyteResultsMap.value
                val currentDetails = currentMap[analyteId]
                if (currentDetails == null) {
                    Log.e("ResultViewModel", "未找到分析物详情: $analyteId")
                    return@launch
                }

                // 创建一个新的孔位结果列表，包含更新后的真实浓度值
                val updatedWellResults = currentDetails.wellResults.map { well ->
                    if (trueValues.containsKey(well.resultId)) {
                        well.copy(trueConcentration = trueValues[well.resultId])
                    } else {
                        well
                    }
                }

                // 使用更新后的孔位列表创建新的详情对象
                val detailsWithNewTrueValues = currentDetails.copy(wellResults = updatedWellResults)

                // 更新整个Map，以确保StateFlow发出新值
                val newMap = currentMap.toMutableMap()
                newMap[analyteId] = detailsWithNewTrueValues
                _analyteResultsMap.value = newMap

                Log.d("ResultViewModel", "内存状态已更新，现在执行验证分析。")

                // 3. 执行验证分析
                performValidationAnalysis(analyteId, updatedWellResults)

            } catch (e: Exception) {
                Log.e("ResultViewModel", "更新真实浓度值失败", e)
            }
        }
    }

    /**
     * 【最终修正】执行验证分析
     * @param analyteId 分析物ID
     * @param wellResultsWithTrueValues 包含最新真实浓度值的孔位结果列表
     */
    private fun performValidationAnalysis(analyteId: String, wellResultsWithTrueValues: List<WellResult>) {
        viewModelScope.launch {
            try {
                Log.d("ResultViewModel", "开始执行验证分析: $analyteId")
                // 从 _analyteResultsMap 中获取最新的 details
                val currentDetails = _analyteResultsMap.value[analyteId]

                if (currentDetails == null) {
                    Log.e("ResultViewModel", "在验证分析中未找到分析物详情: $analyteId")
                    return@launch
                }

                // 使用传入的最新数据进行验证
                val validationWells = wellResultsWithTrueValues.filter {
                    it.predictedConcentration != null &&
                            it.trueConcentration != null &&
                            it.predictedConcentration!!.isFinite() &&
                            it.trueConcentration!!.isFinite()
                }

                if (validationWells.size < 2) { // 至少需要2个点才能进行回归分析
                    Log.w("ResultViewModel", "没有足够的数据进行验证分析，至少需要2个同时具有预测值和真实值的孔位。找到 ${validationWells.size} 个。")
                    return@launch
                }

                Log.d("ResultViewModel", "找到 ${validationWells.size} 个有效的验证孔位数据")

                val regressionData = generateRegressionAnalysisData(validationWells)
                val blandAltmanData = generateBlandAltmanAnalysisData(validationWells)

                val validationData = ValidationData(
                    regressionPlotData = regressionData.first,
                    regressionMetrics = regressionData.second,
                    blandAltmanPlotData = blandAltmanData.first,
                    blandAltmanMetrics = blandAltmanData.second
                )

                Log.d("ResultViewModel", "验证分析完成，准备更新UI状态")

                // 更新分析物结果详情
                val updatedDetails = currentDetails.copy(
                    wellResults = wellResultsWithTrueValues, // 确保wellResults也是最新的
                    validationData = validationData
                )

                // 更新 StateFlow
                val newMap = _analyteResultsMap.value.toMutableMap()
                newMap[analyteId] = updatedDetails
                _analyteResultsMap.value = newMap

                Log.d("ResultViewModel", "验证分析状态更新完成，validationData is now not null.")

            } catch (e: Exception) {
                Log.e("ResultViewModel", "执行验证分析时发生错误", e)
            }
        }
    }

    /**
     * 生成回归分析数据
     * @return Pair<图表数据, 指标映射>
     */
    private fun generateRegressionAnalysisData(
        validationWells: List<WellResult>
    ): Pair<ChartData, Map<String, String>> {
        // 提取预测值和真实值 (observed values)
        val modelPredictedValues = validationWells.map { it.predictedConcentration ?: 0.0 }
        val observedValues = validationWells.map { it.trueConcentration ?: 0.0 }

        // --- 1. 执行线性回归拟合 (只计算斜率和截距) ---
        val n = observedValues.size.toDouble()
        var slope = 0.0
        var intercept = 0.0
        if (n > 1) {
            val sumX = modelPredictedValues.sum()
            val sumY = observedValues.sum()
            val sumXY = modelPredictedValues.zip(observedValues).sumOf { it.first * it.second }
            val sumXX = modelPredictedValues.sumOf { it * it }
            val denominator = (n * sumXX - sumX * sumX)
            slope = if (denominator == 0.0) 1.0 else (n * sumXY - sumX * sumY) / denominator
            intercept = (sumY / n) - slope * (sumX / n)
        }

        // --- 2. 使用 MetricsCalculator 进行统一评估 ---
        val metrics = MetricsCalculator.calculateAllMetrics(
            observed = observedValues,
            predicted = modelPredictedValues,
            numParameters = 2 // For linear regression model
        )

        // --- 3. 准备图表数据 ---
        val scatterPoints = modelPredictedValues.zip(observedValues).mapIndexed { index, (predicted, actual) ->
            ChartPoint(x = predicted, y = actual, label = "Point ${index + 1}")
        }

        // 【修复】确定X轴和Y轴范围
        val xMin = scatterPoints.minOfOrNull { it.x }?.let { if (it > 0) 0.0 else it * 0.9 } ?: 0.0
        val yMin = scatterPoints.minOfOrNull { it.y }?.let { if (it > 0) 0.0 else it * 0.9 } ?: 0.0
        val minValue = minOf(xMin, yMin)

        val xMax = scatterPoints.maxOfOrNull { it.x }?.let { it * 1.1 } ?: 10.0
        val yMax = scatterPoints.maxOfOrNull { it.y }?.let { it * 1.1 } ?: 10.0
        val maxValue = maxOf(xMax, yMax)

        // Ideal line (y=x) and regression line
        val idealLinePoints = listOf(Pair(minValue, minValue), Pair(maxValue, maxValue))

        // 【修复】生成更多的回归线点，使曲线更平滑
        val regressionLinePoints = List(100) { i ->
            val x = minValue + (maxValue - minValue) * i / 99.0
            Pair(x, slope * x + intercept)
        }

        val chartData = ChartData(
            title = "Validation",
            xRange = Pair(minValue, maxValue),
            yRange = Pair(minValue, maxValue),
            standardPoints = idealLinePoints, // y=x line (标准参考线)
            curvePoints = regressionLinePoints, // regression line (回归线)
            formula = "y = ${String.format("%.4f", slope)}x + ${String.format("%.4f", intercept)}",
            xAxisLabel = "Predicted",
            yAxisLabel = "Actual",
            scatterPoints = scatterPoints,
            chartType = "REGRESSION" // 设置图表类型为回归分析
        )

        // --- 4. 准备指标映射用于显示 ---
        val metricsMap = mapOf(
            "R²" to String.format("%.4f", metrics["R²"] ?: 0.0),
            "Slope" to String.format("%.4f", slope),
            "Intercept" to String.format("%.4f", intercept),
            "MSE" to String.format("%.4f", metrics["MSE"] ?: 0.0),
            "RMSE" to String.format("%.4f", metrics["RMSE"] ?: 0.0),
            "MAE" to String.format("%.4f", metrics["MAE"] ?: 0.0)
        )

        return Pair(chartData, metricsMap)
    }

    /**
     * 生成Bland-Altman分析数据
     * @return Pair<图表数据, 指标映射>
     */
    private fun generateBlandAltmanAnalysisData(
        validationWells: List<WellResult>
    ): Pair<ChartData, Map<String, String>> {
        // 提取预测值和真实值
        val predictedValues = validationWells.map { it.predictedConcentration ?: 0.0 }
        val trueValues = validationWells.map { it.trueConcentration ?: 0.0 }

        // 计算平均值和差值（真实值 - 预测值）
        val averages = predictedValues.zip(trueValues).map { (predicted, actual) -> (predicted + actual) / 2 }
        val differences = trueValues.zip(predictedValues).map { (actual, predicted) -> actual - predicted }

        // 计算差值的平均值和标准差
        val meanDifference = differences.average()
        val sdDifference = if (differences.size > 1) {
            sqrt(differences.sumOf { (it - meanDifference).pow(2) } / (differences.size - 1))
        } else {
            0.0
        }

        // 计算95%一致性限
        val upperLimit = meanDifference + 1.96 * sdDifference
        val lowerLimit = meanDifference - 1.96 * sdDifference

        // 计算在95%一致性限内的点的百分比
        val pointsInRange = if (differences.isNotEmpty()) {
            differences.count { it in lowerLimit..upperLimit }.toDouble() / differences.size * 100
        } else {
            0.0
        }

        // 创建图表数据点
        val scatterPoints = averages.zip(differences).mapIndexed { index, (avg, diff) ->
            ChartPoint(
                x = avg,
                y = diff,
                label = "Point ${index + 1}"
            )
        }

        // 确定X轴范围
        val xMin = averages.minOrNull()?.let { it * 0.9 } ?: 0.0
        val xMax = averages.maxOrNull()?.let { it * 1.1 } ?: 10.0

        // 确定Y轴范围，确保下限线不紧贴坐标轴
        val diffRange = upperLimit - lowerLimit
        val yPadding = diffRange * 0.2 // 添加20%的内边距
        val yMin = lowerLimit - yPadding
        val yMax = upperLimit + yPadding

        // 创建水平的中心线、上限线和下限线
        val meanLine = listOf(Pair(xMin, meanDifference), Pair(xMax, meanDifference))
        val upperLimitLine = listOf(Pair(xMin, upperLimit), Pair(xMax, upperLimit))
        val lowerLimitLine = listOf(Pair(xMin, lowerLimit), Pair(xMax, lowerLimit))

        // 创建图表数据
        val chartData = ChartData(
            title = "Bland-Altman Analysis",
            xRange = Pair(xMin, xMax),
            yRange = Pair(yMin, yMax),
            xAxisLabel = "Mean ((Predicted+Actual)/2)",
            yAxisLabel = "Difference (Actual-Predicted)",
            scatterPoints = scatterPoints,
            chartType = "BLAND_ALTMAN", // 设置图表类型为Bland-Altman分析
            additionalLines = mapOf(
                "mean" to meanLine,
                "upperLimit" to upperLimitLine,
                "lowerLimit" to lowerLimitLine
            )
        )

        // 创建指标映射
        val metricsMap = mapOf(
            "Mean Difference" to String.format("%.4f", meanDifference),
            "Standard Deviation" to String.format("%.4f", sdDifference),
            "Upper Limit (+1.96 SD)" to String.format("%.4f", upperLimit),
            "Lower Limit (-1.96 SD)" to String.format("%.4f", lowerLimit),
            "Points within Limits" to String.format("%.2f%%", pointsInRange)
        )

        return Pair(chartData, metricsMap)
    }
}

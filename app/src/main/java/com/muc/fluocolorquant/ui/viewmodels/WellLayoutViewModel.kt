package com.muc.fluocolorquant.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.data.enums.WellRoleType
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.AnalyteWellLayout
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.TemplateLayout
import com.muc.fluocolorquant.data.model.WellAssignment
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.CurveModelRepository
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepository
import com.muc.fluocolorquant.data.repository.ProjectAnalyteJoinRepository
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.repository.WellResultRepository
import com.muc.fluocolorquant.utils.DetectionModeSupport
import com.muc.fluocolorquant.utils.PixelExtractionUtils
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.utils.math.FittingResult
import com.muc.fluocolorquant.utils.math.WellMappingUtils
import com.muc.fluocolorquant.utils.math.GridLayoutPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.util.UUID
import javax.inject.Inject
import java.util.Date
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.torchvision.TensorImageUtils
import java.io.IOException

/**
 * 孔位布局管理 ViewModel
 * 负责管理孔位布局的状态和操作
 */
@HiltViewModel
class WellLayoutViewModel @Inject constructor(
    @ApplicationContext private val context: Context, // <-- 在这里注入Context
    private val wellResultRepository: WellResultRepository,
    private val analyteRepository: AnalyteRepository,
    private val projectRepository: ProjectRepository,
    private val projectAnalyteJoinRepository: ProjectAnalyteJoinRepository,
    private val experimentTemplateRepository: ExperimentTemplateRepository,
    private val curveModelRepository: CurveModelRepository,
    private val wellResultDao: WellResultDao
) : ViewModel() {

    // 在类内部定义TAG常量
    companion object {
        private const val TAG = "WellLayoutViewModel"
    }

    // 当前项目
    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    // 当前运行ID
    private val _currentRunId = MutableStateFlow<String?>(null)
    val currentRunId: StateFlow<String?> = _currentRunId.asStateFlow()

    // 孔位结果列表
    private val _wellResults = MutableStateFlow<List<WellResult>>(emptyList())
    val wellResults: StateFlow<List<WellResult>> = _wellResults.asStateFlow()

    // 可用分析物列表
    private val _availableAnalytes = MutableStateFlow<List<Analyte>>(emptyList())
    val availableAnalytes: StateFlow<List<Analyte>> = _availableAnalytes.asStateFlow()

    // 当前选中的分析物
    private val _selectedAnalyte = MutableStateFlow<Analyte?>(null)
    val selectedAnalyte: StateFlow<Analyte?> = _selectedAnalyte.asStateFlow()

    // 当前选中的角色类型
    private val _selectedRoleType = MutableStateFlow(WellRoleType.NONE)
    val selectedRoleType: StateFlow<WellRoleType> = _selectedRoleType.asStateFlow()

    // 分析物孔位布局
    private val _analyteLayouts = MutableStateFlow<Map<String, AnalyteWellLayout>>(emptyMap())
    val analyteLayouts: StateFlow<Map<String, AnalyteWellLayout>> = _analyteLayouts.asStateFlow()

    // 可用模板列表
    private val _availableTemplates = MutableStateFlow<List<ExperimentTemplate>>(emptyList())
    val availableTemplates: StateFlow<List<ExperimentTemplate>> = _availableTemplates.asStateFlow()

    // 当前选中的模板
    private val _selectedTemplate = MutableStateFlow<ExperimentTemplate?>(null)
    val selectedTemplate: StateFlow<ExperimentTemplate?> = _selectedTemplate.asStateFlow()

    // 标准品浓度输入
    private val _standardConcentrations = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val standardConcentrations: StateFlow<Map<Int, Double>> = _standardConcentrations.asStateFlow()

    // 【新增】用一个Map来跟踪每个分析物ID对应的、已确认的拟合结果
    private val _analyteFittingStatus = MutableStateFlow<Map<String, FittingResult>>(emptyMap())
    val analyteFittingStatus: StateFlow<Map<String, FittingResult>> = _analyteFittingStatus.asStateFlow()
    // 【新增】一个计算属性，用于判断是否所有分析物都已配置完毕
    val allAnalytesConfigured: StateFlow<Boolean> =
        combine(_availableAnalytes, _analyteFittingStatus) { analytes, status ->
            analytes.isNotEmpty() && status.keys.containsAll(analytes.map { it.id })
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // 存储从上个页面传递过来的原始图像Bitmap
    /**
     * 获取当前项目对应的检测模式。
     */
    private fun currentDetectionMode() =
        DetectionModeSupport.fromStorageValue(_currentProject.value?.detectionMode)

    /**
     * 获取当前项目建议使用的默认像素特征。
     */
    private fun defaultPixelTypeForCurrentProject(): PixelType =
        DetectionModeSupport.defaultPixelType(currentDetectionMode())

    /**
     * 获取当前项目建议优先勾选的像素特征集合。
     */
    fun recommendedPixelTypesForCurrentProject(): Set<PixelType> =
        DetectionModeSupport.recommendedPixelTypes(currentDetectionMode())

    /**
     * 兼容历史数据中同时存在 identifier 和枚举名两种存储键。
     */
    private fun getPixelValue(
        pixelValues: Map<String, Double>,
        pixelType: PixelType
    ): Double? = pixelValues[pixelType.identifier] ?: pixelValues[pixelType.name]

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    // 像素提取进度
    private val _pixelExtractionProgress = MutableStateFlow(0)
    val pixelExtractionProgress: StateFlow<Int> = _pixelExtractionProgress.asStateFlow()

    // 【新增】可用的DL模型列表
    val availableDlModels = MutableStateFlow(listOf("concentration.ptl"))

    // 布局状态 - 增强版
    sealed class LayoutState {
        object Loading : LayoutState()
        data class Processing(val message: String, val progress: Int = 0) : LayoutState() // 新增处理中状态
        object Ready : LayoutState()
        data class Error(val message: String) : LayoutState()
        // 新增：用于DL模型自动处理流程的状态
        data class AutoProcessing(val message: String, val progress: Int = 0) : LayoutState()
    }

    private val _layoutState = MutableStateFlow<LayoutState>(LayoutState.Loading)
    val layoutState: StateFlow<LayoutState> = _layoutState.asStateFlow()

    // 当前选中分析物的标准品数量
    private val _standardWellsCount = MutableStateFlow(0)
    val standardWellsCount: StateFlow<Int> = _standardWellsCount.asStateFlow()

    // 手动拟合对话框状态
    private val _showManualFittingDialog = MutableStateFlow(false)
    val showManualFittingDialog: StateFlow<Boolean> = _showManualFittingDialog.asStateFlow()

    // 拟合结果列表
    private val _fittingResults = MutableStateFlow<List<FittingResult>>(emptyList())
    val fittingResults: StateFlow<List<FittingResult>> = _fittingResults.asStateFlow()

    // 拟合计算加载状态
    private val _isFittingLoading = MutableStateFlow(false)
    val isFittingLoading: StateFlow<Boolean> = _isFittingLoading.asStateFlow()

    /**
     * 设置原始图像
     * @param bitmap 原始图像
     */
    fun setOriginalBitmap(bitmap: Bitmap) {
        _originalBitmap.value = bitmap
        Log.d(TAG, "原始图像已设置，大小: ${bitmap.width}x${bitmap.height}")
    }

    /**
     * 初始化布局
     * @param projectId 项目ID
     * @param runId 运行ID
     * @param onNavigateToResult 处理完成后导航回调
     */
    fun initLayout(projectId: String, runId: String, onNavigateToResult: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            _layoutState.value = LayoutState.Loading

            try {
                val project = withContext(Dispatchers.IO) { projectRepository.getProjectById(projectId) }
                if (project == null) {
                    _layoutState.value = LayoutState.Error("项目不存在")
                    return@launch
                }
                _currentProject.value = project
                _currentRunId.value = runId

                // 核心改造：根据分析方法选择工作流
                if (project.analysisMethod == "DL_MODEL") {
                    // 执行深度学习模型预测工作流
                    runDlPredictionWorkflow(project, runId, onNavigateToResult)
                } else {
                    // 执行标准曲线拟合工作流
                    runCurveFittingWorkflow(project, runId)
                }
            } catch (e: Exception) {
                _layoutState.value = LayoutState.Error("初始化布局失败: ${e.message}")
                Log.e(TAG, "初始化布局失败", e)
            }
        }
    }

    /**
     * 为所有孔位提取像素值并更新数据库
     */
    private suspend fun extractPixelValuesForAllWells(wells: List<WellResult>) {
        try {
            val totalWells = wells.size
            Log.d(TAG, "开始为 $totalWells 个孔位提取像素值")

            val updatedWells = mutableListOf<WellResult>()

            wells.forEachIndexed { index, wellResult ->
                // 更新进度
                val progress = ((index + 1) * 100) / totalWells
                _pixelExtractionProgress.value = progress
                // 使用格式化的字符串资源
                _layoutState.value = LayoutState.Processing(
                    message = context.getString(R.string.pixel_extraction_in_progress, progress),
                    progress = 20 + (progress * 0.4).toInt()
                )

                val imagePath = wellResult.croppedImageIdentifier
                if (imagePath != null) {
                    val imageFile = File(imagePath)
                    if (imageFile.exists()) {
                        try {
                            val bitmap = withContext(Dispatchers.IO) {
                                BitmapFactory.decodeFile(imageFile.absolutePath)
                            }

                            if (bitmap != null) {
                                // 提取像素值
                                val pixelJson = withContext(Dispatchers.IO) {
                                    com.muc.fluocolorquant.utils.PixelExtractionUtils.extractAllPixelValues(bitmap)
                                }

                                // 更新孔位结果
                                val updatedWellResult = wellResult.copy(pixelValueJson = pixelJson)
                                updatedWells.add(updatedWellResult)

                                // 释放Bitmap
                                bitmap.recycle()
                            } else {
                                Log.e(TAG, "无法解码孔位图像: ${imageFile.absolutePath}")
                                updatedWells.add(wellResult) // 保持原样
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "处理孔位图像异常: ${e.message}", e)
                            updatedWells.add(wellResult) // 保持原样
                        }
                    } else {
                        Log.e(TAG, "孔位图像文件不存在: $imagePath")
                        updatedWells.add(wellResult) // 保持原样
                    }
                } else {
                    Log.e(TAG, "孔位缺少裁剪图像标识符")
                    updatedWells.add(wellResult) // 保持原样
                }

                // 每处理10个孔位，让主线程有机会更新UI
                if (index % 10 == 0) {
                    delay(50)
                }
            }

            // 批量更新数据库
            if (updatedWells.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    wellResultDao.updateWellResults(updatedWells)
                }
                Log.d(TAG, "已更新 ${updatedWells.size} 个孔位的像素值")
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取像素值异常", e)
            throw e
        }
    }

    /**
     * 加载项目相关的分析物
     * @param projectId 项目ID
     */
    private suspend fun loadProjectAnalytes(projectId: String) {
        try {
            // 使用 .first() 来获取一次性的数据快照，而不是持续监听
            val analytesList = withContext(Dispatchers.IO) {
                projectAnalyteJoinRepository.getAnalytesByProjectId(projectId).first()
            }

            _availableAnalytes.value = analytesList

            // 如果有分析物，默认选择第一个
            if (analytesList.isNotEmpty()) {
                _selectedAnalyte.value = analytesList.first()
                Log.d(TAG, "成功加载 ${analytesList.size} 个分析物，已默认选择: ${analytesList.first().name}")
            } else {
                Log.w(TAG, "项目 $projectId 没有关联的分析物。")
            }

        } catch (e: Exception) {
            Log.e(TAG, "加载项目分析物失败", e)
        }
    }

    /**
     * 加载可用模板
     */
    private suspend fun loadAvailableTemplates() {
        try {
            val selectedAnalyte = _selectedAnalyte.value ?: return

            // 获取与当前分析物关联的模板
            val templates = withContext(Dispatchers.IO) {
                experimentTemplateRepository.getTemplatesByAnalyteId(selectedAnalyte.id)
            }

            _availableTemplates.value = templates

        } catch (e: Exception) {
            Log.e(TAG, "加载可用模板失败", e)
        }
    }

    /**
     * 更新分析物布局
     */
    private suspend fun updateAnalyteLayouts() {
        try {
            val wellResults = _wellResults.value
            val analytes = _availableAnalytes.value
            val dimensions = GridLayoutPolicy.resolveProject(_currentProject.value)
            val layouts = mutableMapOf<String, AnalyteWellLayout>()

            analytes.forEach { analyte ->
                val analyteWells = wellResults.filter { it.fkAnalyteId == analyte.id }

                if (analyteWells.isNotEmpty()) {
                    val wellAssignments = analyteWells.map { well ->
                        // 历史 virtualRow/virtualCol 可能来自固定 12 列映射，统一由真实列数重算。
                        val (row, column) = WellMappingUtils.mapRealToVirtualCoordinates(
                            realIndex = well.wellIndex,
                            columns = dimensions.columns
                        )
                        WellAssignment(
                            wellIndex = well.wellIndex,
                            roleType = well.roleType ?: WellRoleType.NONE.code,
                            concentration = well.trueConcentration,
                            virtualRow = row,
                            virtualCol = column
                        )
                    }

                    layouts[analyte.id] = AnalyteWellLayout(
                        analyteId = analyte.id,
                        analyteName = analyte.name,
                        wellAssignments = wellAssignments
                    )
                }
            }

            _analyteLayouts.value = layouts

            // 更新当前选中分析物的标准品数量
            updateStandardWellsCount()

        } catch (e: Exception) {
            Log.e(TAG, "更新分析物布局失败", e)
        }
    }

    /**
     * 更新当前选中分析物的标准品数量
     */
    private fun updateStandardWellsCount() {
        val selectedAnalyte = _selectedAnalyte.value ?: return
        val standardWells = _wellResults.value.filter {
            it.fkAnalyteId == selectedAnalyte.id && it.roleType == WellRoleType.STANDARD.code
        }
        _standardWellsCount.value = standardWells.size
        Log.d(TAG, "更新标准品数量: ${standardWells.size}")
    }

    /**
     * 选择分析物
     * @param analyteId 分析物ID
     */
    fun selectAnalyte(analyteId: String) {
        val analyte = _availableAnalytes.value.find { it.id == analyteId }
        if (analyte != null) {
            _selectedAnalyte.value = analyte

            // 重置角色类型
            _selectedRoleType.value = WellRoleType.NONE

            // 更新可用模板
            viewModelScope.launch {
                loadAvailableTemplates()
                // 更新当前选中分析物的标准品数量
                updateStandardWellsCount()
            }
        }
    }

    /**
     * 选择角色类型
     * @param roleType 角色类型
     */
    fun selectRoleType(roleType: WellRoleType) {
        _selectedRoleType.value = roleType
    }

    /**
     * 选择模板
     * @param templateId 模板ID
     */
    fun selectTemplate(templateId: String) {
        viewModelScope.launch {
            try {
                val template = withContext(Dispatchers.IO) {
                    experimentTemplateRepository.getTemplateById(templateId)
                }

                if (template != null) {
                    _selectedTemplate.value = template

                    // 应用模板布局
                    applyTemplateLayout(template)
                }

            } catch (e: Exception) {
                Log.e(TAG, "选择模板失败", e)
            }
        }
    }

    /**
     * 应用模板布局并立即计算浓度 (最终修正版)
     * @param template 实验模板
     */
    private fun applyTemplateLayout(template: ExperimentTemplate) {
        viewModelScope.launch {
            val project = _currentProject.value ?: return@launch
            val selectedAnalyteId = _selectedAnalyte.value?.id ?: return@launch
            _isFittingLoading.value = true

            try {
                // 1. 加载曲线模型
                // 这是旧孔板模板应用链路。Room 11 的新多分析物模板不再强制绑定旧
                // CurveModel，因此只有兼容字段存在时才尝试加载，空值沿用下方错误处理。
                val curveModel = template.fkCurveModelId?.let { curveModelId ->
                    withContext(Dispatchers.IO) {
                        curveModelRepository.getCurveModelById(curveModelId)
                    }
                }
                if (curveModel == null) {
                    _layoutState.value = LayoutState.Error("模板关联的曲线模型未找到")
                    return@launch
                }

                // 2. 将CurveModel转换为FittingResult
                val fittingResult = FittingResult(
                    function = curveModel.function,
                    parameters = convertMapToParametersArray(curveModel.function, curveModel.parameters),
                    formula = FittingEngine.formatParametersToLatex(curveModel.function, curveModel.parameters),
                    rSquared = curveModel.metrics?.get("R²") ?: 1.0,
                    allMetrics = curveModel.metrics ?: mapOf("R²" to 1.0),
                    standardPoints = curveModel.dataPoints ?: emptyList(),
                    curvePoints = emptyList(),
                    pixelType = curveModel.pixelType,
                    isSuccess = true
                )

                // 3. 应用布局并计算浓度
                val wellsToUpdate = applyLayoutAndCalculateConcentrations(project, selectedAnalyteId, fittingResult, template)

                // 4. 批量更新数据库
                if (wellsToUpdate.isNotEmpty()) {
                    withContext(Dispatchers.IO) { wellResultDao.updateWellResults(wellsToUpdate) }
                    _wellResults.value = withContext(Dispatchers.IO) {
                        wellResultRepository.getWellResultsByRunId(_currentRunId.value!!)
                    }
                }

                // 5. 更新分析物处理状态
                val currentStatus = _analyteFittingStatus.value.toMutableMap()
                currentStatus[selectedAnalyteId] = fittingResult
                _analyteFittingStatus.value = currentStatus

                // 6. 【新增】更新ProjectAnalyteJoin表，保存模板ID和曲线模型ID
                withContext(Dispatchers.IO) {
                    try {
                        // 查找现有的关联记录
                        val join = projectAnalyteJoinRepository.getProjectAnalyteJoin(project.id, selectedAnalyteId)
                        
                        if (join != null) {
                            // 更新现有记录
                            val updatedJoin = join.copy(
                                fkTemplateId = template.id,
                                fkCurveModelId = template.fkCurveModelId
                            )
                            projectAnalyteJoinRepository.updateProjectAnalyteJoin(updatedJoin)
                            Log.d(TAG, "已更新ProjectAnalyteJoin: 模板ID=${template.id}, 曲线模型ID=${template.fkCurveModelId}")
                        } else {
                            Log.w(TAG, "未找到项目和分析物的关联记录，无法更新模板信息")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "更新ProjectAnalyteJoin失败: ${e.message}", e)
                    }
                }

                updateAnalyteLayouts()
            } catch (e: Exception) {
                Log.e(TAG, "应用模板布局失败", e)
                _layoutState.value = LayoutState.Error("应用模板布局失败: ${e.message}")
            } finally {
                _isFittingLoading.value = false
            }
        }
    }

    /**
     * 【新增】根据模板应用布局和计算浓度
     */
    private fun applyLayoutAndCalculateConcentrations(
        project: Project,
        analyteId: String,
        fittingResult: FittingResult,
        template: ExperimentTemplate
    ): List<WellResult> {
        val layoutJson = template.defaultLayoutJson ?: return emptyList()
        val dimensions = GridLayoutPolicy.resolveProject(_currentProject.value)
        val type = object : TypeToken<Map<String, String>>() {}.type
        val layoutMap: Map<Int, String> = try {
            Gson().fromJson<Map<String, String>>(layoutJson, type).mapKeys { it.key.toInt() }
        } catch (e: Exception) {
            Log.e(TAG, "解析模板布局JSON失败: ${e.message}", e)
            // 尝试直接解析为 Map<Int, String>
            val intType = object : TypeToken<Map<Int, String>>() {}.type
            Gson().fromJson(layoutJson, intType)
        }

        val wellsToUpdate = mutableListOf<WellResult>()
        val currentResults = _wellResults.value.toMutableList()

        // 清理旧布局
        currentResults.forEachIndexed { index, wellResult ->
            if (wellResult.fkAnalyteId == analyteId) {
                currentResults[index] = wellResult.copy(
                    fkAnalyteId = null,
                    roleType = null,
                    trueConcentration = null,
                    predictedConcentration = null,
                    virtualRow = null,
                    virtualCol = null
                )
            }
        }

        // 应用新布局和浓度
        layoutMap.forEach { (wellIndex, roleCode) ->
            val listIndex = currentResults.indexOfFirst { it.wellIndex == wellIndex }
            if (listIndex != -1) {
                // 使用新的映射工具获取虚拟坐标
                val virtualCoords = WellMappingUtils.mapRealToVirtualCoordinates(
                    realIndex = wellIndex,
                    columns = dimensions.columns
                )

                var updatedWell = currentResults[listIndex].copy(
                    fkAnalyteId = analyteId,
                    roleType = roleCode.uppercase(),
                    virtualRow = virtualCoords.first,
                    virtualCol = virtualCoords.second
                )

                // 【核心修正】为标准品和样本计算浓度
                updatedWell.pixelValueJson?.let { json ->
                    try {
                        val pixelValues = parsePixelValues(json)
                        val pixelType = fittingResult.pixelType ?: defaultPixelTypeForCurrentProject()
                        val pixelValue = getPixelValue(pixelValues, pixelType)
                        if (pixelValue != null) {
                            val predictedConc = FittingEngine.predictConcentration(
                                fittingResult.parameters,
                                fittingResult.function,
                                pixelValue
                            )
                            updatedWell = when (updatedWell.roleType) {
                                WellRoleType.STANDARD.code -> {
                                    // 对于标准品，预测值和真实值都设为反算出的浓度
                                    updatedWell.copy(
                                        trueConcentration = predictedConc,
                                        predictedConcentration = predictedConc
                                    )
                                }
                                WellRoleType.SAMPLE.code -> {
                                    updatedWell.copy(predictedConcentration = predictedConc)
                                }
                                else -> updatedWell
                            }
                        }else{
                            null;
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "计算孔位 $wellIndex 浓度失败: ${e.message}", e)
                    }
                }
                currentResults[listIndex] = updatedWell
                wellsToUpdate.add(updatedWell)
            }
        }

        return wellsToUpdate
    }

    /**
     * 标记孔位 (最终修正版)
     * @param virtualRow 虚拟行索引
     * @param virtualCol 虚拟列索引
     */
    fun markWell(virtualRow: Int, virtualCol: Int) {
        viewModelScope.launch {
            try {
                val project = _currentProject.value ?: return@launch
                val dimensions = GridLayoutPolicy.resolveProject(project)
                val selectedAnalyte = _selectedAnalyte.value ?: return@launch
                val roleType = _selectedRoleType.value

                // 【核心修正】使用新的、更简单的映射工具
                val wellIndex = WellMappingUtils.mapVirtualToRealIndex(
                    virtualRow,
                    virtualCol,
                    dimensions.columns
                )

                // 确保wellIndex在有效范围内
                val totalCells = dimensions.siteCount
                if (wellIndex >= totalCells) {
                    Log.w(TAG, "无效的孔位索引: $wellIndex (虚拟坐标: $virtualRow, $virtualCol)")
                    return@launch
                }

                // 查找对应的孔位结果
                val wellResults = _wellResults.value
                val wellResult = wellResults.find { it.wellIndex == wellIndex }

                if (wellResult != null) {
                    // 检查孔位是否已被其他分析物占用
                    if (wellResult.fkAnalyteId != null && wellResult.fkAnalyteId != selectedAnalyte.id && roleType != WellRoleType.NONE) {
                        // 忽略操作，孔位已被其他分析物占用
                        return@launch
                    }

                    // 更新孔位结果
                    val updatedWellResult = if (roleType == WellRoleType.NONE) {
                        // 清除标记
                        wellResult.copy(
                            fkAnalyteId = null,
                            roleType = null,
                            trueConcentration = null,
                            virtualRow = null,
                            virtualCol = null
                        )
                    } else {
                        // 标记孔位
                        wellResult.copy(
                            fkAnalyteId = selectedAnalyte.id,
                            roleType = roleType.code,
                            virtualRow = virtualRow,
                            virtualCol = virtualCol

                        )
                    }

                    // 保存更新的孔位结果
                    withContext(Dispatchers.IO) {
                        wellResultDao.updateWellResult(updatedWellResult)
                    }

                    // 重新加载孔位结果
                    val results = withContext(Dispatchers.IO) {
                        wellResultRepository.getWellResultsByRunId(_currentRunId.value!!)
                    }

                    _wellResults.value = results

                    // 更新分析物布局
                    updateAnalyteLayouts()
                }

            } catch (e: Exception) {
                Log.e(TAG, "标记孔位失败", e)
            }
        }
    }

    /**
     * 设置标准品浓度
     * @param wellIndex 孔位索引
     * @param concentration 浓度值
     */
    fun setStandardConcentration(wellIndex: Int, concentration: Double) {
        viewModelScope.launch {
            try {
                val wellResults = _wellResults.value
                val wellResult = wellResults.find { it.wellIndex == wellIndex }

                if (wellResult != null && wellResult.roleType == WellRoleType.STANDARD.code) {
                    // 更新孔位结果
                    val updatedWellResult = wellResult.copy(
                        trueConcentration = concentration
                    )

                    // 保存更新的孔位结果
                    withContext(Dispatchers.IO) {
                        wellResultDao.updateWellResult(updatedWellResult)
                    }

                    // 更新标准品浓度映射
                    val concentrations = _standardConcentrations.value.toMutableMap()
                    concentrations[wellIndex] = concentration
                    _standardConcentrations.value = concentrations

                    // 重新加载孔位结果
                    val results = withContext(Dispatchers.IO) {
                        wellResultRepository.getWellResultsByRunId(_currentRunId.value!!)
                    }

                    _wellResults.value = results
                }

            } catch (e: Exception) {
                Log.e(TAG, "设置标准品浓度失败", e)
            }
        }
    }

    /**
     * 获取标准品孔位列表
     * @return 标准品孔位列表
     */
    fun getStandardWells(): List<WellResult> {
        val selectedAnalyte = _selectedAnalyte.value ?: return emptyList()
        return _wellResults.value.filter {
            it.fkAnalyteId == selectedAnalyte.id && it.roleType == WellRoleType.STANDARD.code
        }
    }

    /**
     * 获取样本孔位列表
     * @return 样本孔位列表
     */
    fun getSampleWells(): List<WellResult> {
        val selectedAnalyte = _selectedAnalyte.value ?: return emptyList()
        return _wellResults.value.filter {
            it.fkAnalyteId == selectedAnalyte.id && it.roleType == WellRoleType.SAMPLE.code
        }
    }

    /**
     * 获取空白对照孔位列表
     * @return 空白对照孔位列表
     */
    fun getBlankWells(): List<WellResult> {
        val selectedAnalyte = _selectedAnalyte.value ?: return emptyList()
        return _wellResults.value.filter {
            it.fkAnalyteId == selectedAnalyte.id && it.roleType == WellRoleType.BLANK.code
        }
    }

    /**
     * 检查是否有足够的标准品进行拟合
     * @return 是否有足够的标准品
     */
    fun hasEnoughStandards(): Boolean {
        return standardWellsCount.value >= 4
    }

    /**
     * 打开手动拟合对话框
     */
    fun openManualFittingDialog() {
        // 重置之前的拟合结果
        _fittingResults.value = emptyList()
        _showManualFittingDialog.value = true
    }

    /**
     * 关闭手动拟合对话框
     */
    fun dismissManualFitDialog() {
        _showManualFittingDialog.value = false
    }

    /**
     * 【新增】处理单个分析物的拟合流程
     * @param analyteId 要处理的分析物ID
     * @param onComplete 处理完成回调，参数为处理是否成功
     */
    fun processSingleAnalyte(analyteId: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val analyte = _availableAnalytes.value.find { it.id == analyteId }
                if (analyte == null) {
                    Log.e(TAG, "处理分析物失败：找不到ID为 $analyteId 的分析物")
                    onComplete(false)
                    return@launch
                }

                _layoutState.value = LayoutState.Processing("正在处理分析物: ${analyte.name}...", 20)

                // 获取此分析物的标准品
                val standardWells = _wellResults.value.filter {
                    it.fkAnalyteId == analyteId && it.roleType == WellRoleType.STANDARD.code
                }

                // 检查标准品数量是否足够
                if (standardWells.size < 2) {
                    Log.w(TAG, "分析物 ${analyte.name} 的标准品数量不足，无法进行拟合")
                    _layoutState.value = LayoutState.Error("${analyte.name} 的标准品数量不足，需要至少2个标准品")
                    onComplete(false)
                    return@launch
                }

                // 提取标准品的浓度和像素值
                val standardPoints = mutableListOf<Pair<Double, Double>>()
                val pixelType = defaultPixelTypeForCurrentProject()

                standardWells.forEach { well ->
                    val concentration = well.trueConcentration
                    if (concentration != null && well.pixelValueJson != null) {
                        try {
                            val pixelValues = parsePixelValues(well.pixelValueJson!!)
                            val pixelValue = getPixelValue(pixelValues, pixelType) ?: return@forEach
                            standardPoints.add(Pair(concentration, pixelValue))
                        } catch (e: Exception) {
                            Log.e(TAG, "解析像素值失败: ${e.message}")
                        }
                    }
                }

                if (standardPoints.size < 2) {
                    Log.w(TAG, "分析物 ${analyte.name} 的有效标准点数量不足")
                    _layoutState.value = LayoutState.Error("${analyte.name} 的有效标准点数量不足")
                    onComplete(false)
                    return@launch
                }

                _layoutState.value = LayoutState.Processing("正在为 ${analyte.name} 执行曲线拟合...", 40)

                // 执行拟合
                val fittingResult = FittingEngine.fit(standardPoints)
                if (!fittingResult.isSuccess) {
                    Log.e(TAG, "分析物 ${analyte.name} 拟合失败: ${fittingResult.errorMessage}")
                    _layoutState.value = LayoutState.Error("${analyte.name} 拟合失败: ${fittingResult.errorMessage}")
                    onComplete(false)
                    return@launch
                }

                _layoutState.value = LayoutState.Processing("正在更新 ${analyte.name} 的拟合状态...", 60)

                // 更新分析物处理状态
                val currentStatus = _analyteFittingStatus.value.toMutableMap()
                currentStatus[analyteId] = fittingResult
                _analyteFittingStatus.value = currentStatus

                _layoutState.value = LayoutState.Processing("处理完成", 100)
                delay(500) // 短暂延迟，让用户看到完成状态
                _layoutState.value = LayoutState.Ready

                onComplete(true)

            } catch (e: Exception) {
                Log.e(TAG, "处理分析物失败", e)
                _layoutState.value = LayoutState.Error("处理失败: ${e.message}")
                onComplete(false)
            }
        }
    }

    /**
     * 【新增】处理下一个未配置的分析物
     * @return 如果所有分析物都已配置则返回true，否则返回false
     */
    fun processNextAnalyte(onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                // 获取所有分析物
                val allAnalytes = _availableAnalytes.value

                // 获取当前处理状态
                val currentStatus = _analyteFittingStatus.value

                // 查找第一个未配置的分析物
                val nextAnalyte = allAnalytes.find { it.id !in currentStatus }

                if (nextAnalyte != null) {
                    // 先选择此分析物
                    _selectedAnalyte.value = nextAnalyte

                    // 检查此分析物是否有模板可用
                    val templates = withContext(Dispatchers.IO) {
                        experimentTemplateRepository.getTemplatesByAnalyteId(nextAnalyte.id)
                    }

                    if (templates.isNotEmpty()) {
                        // 有模板可用，自动应用第一个模板
                        Log.d(TAG, "自动应用模板 ${templates[0].templateName} 到分析物 ${nextAnalyte.name}")

                        // 更新UI状态
                        _availableTemplates.value = templates
                        _selectedTemplate.value = templates[0]

                        // 应用模板布局
                        applyTemplateLayout(templates[0])

                        // 延迟一下，确保模板应用完成
                        delay(500)

                        // 返回成功，并传递分析物ID
                        onComplete(true, nextAnalyte.id)
                    } else {
                        // 无可用模板，需要手动配置
                        Log.d(TAG, "分析物 ${nextAnalyte.name} 无可用模板，需要手动配置")
                        onComplete(false, nextAnalyte.id)
                    }
                } else {
                    // 所有分析物已配置完成
                    Log.d(TAG, "所有分析物已完成配置")
                    onComplete(true, null)
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理下一分析物失败", e)
                onComplete(false, null)
            }
        }
    }

    /**
     * 【已修正】确认手动拟合结果，计算浓度，并自动切换到下一个待办分析物
     */
    fun confirmManualFit(result: FittingResult) {
        viewModelScope.launch {
            val analyteId = _selectedAnalyte.value?.id ?: return@launch
            val projectId = _currentProject.value?.id ?: return@launch
            _layoutState.value = LayoutState.Processing("正在应用拟合结果...", 0)
            _isFittingLoading.value = true

            try {
                // 1. 创建新的CurveModel并保存到数据库
                val curveModelId = UUID.randomUUID().toString()
                val curveModel = CurveModel(
                    id = curveModelId,
                    name = "${_selectedAnalyte.value?.name ?: "Unknown"}_Manual_${System.currentTimeMillis()}",
                    function = result.function,
                    pixelType = result.pixelType ?: defaultPixelTypeForCurrentProject(),
                    parameters = result.params,
                    metrics = result.allMetrics,
                    dataPoints = result.standardPoints,
                    createdAt = Date()
                )
                
                withContext(Dispatchers.IO) {
                    curveModelRepository.saveCurveModel(curveModel)
                    Log.d(TAG, "已创建并保存手动拟合曲线模型: $curveModelId")
                    
                    // 2. 更新ProjectAnalyteJoin表，保存曲线模型ID
                    try {
                        // 查找现有的关联记录
                        val join = projectAnalyteJoinRepository.getProjectAnalyteJoin(projectId, analyteId)
                        
                        if (join != null) {
                            // 更新现有记录，只更新fkCurveModelId，保留其他字段
                            val updatedJoin = join.copy(
                                fkCurveModelId = curveModelId
                            )
                            projectAnalyteJoinRepository.updateProjectAnalyteJoin(updatedJoin)
                            Log.d(TAG, "已更新ProjectAnalyteJoin: 曲线模型ID=$curveModelId")
                        } else {
                            Log.w(TAG, "未找到项目和分析物的关联记录，无法更新曲线模型信息")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "更新ProjectAnalyteJoin失败: ${e.message}", e)
                    }
                }

                // 3. 更新分析物处理状态
                val currentStatus = _analyteFittingStatus.value.toMutableMap()
                currentStatus[analyteId] = result
                _analyteFittingStatus.value = currentStatus

                // 4. 准备要更新的孔位列表
                val wellsToUpdate = mutableListOf<WellResult>()

                // 5. 为标准品回填预测浓度
                getStandardWells().forEach { well ->
                    if (well.trueConcentration != null) {
                        wellsToUpdate.add(well.copy(predictedConcentration = well.trueConcentration))
                    }
                }

                // 6. 计算并更新样本浓度
                _layoutState.value = LayoutState.Processing("正在计算样本浓度...", 60)
                withContext(Dispatchers.Default) {
                    getSampleWells().forEach { well ->
                        well.pixelValueJson?.let { json ->
                            try {
                                val pixelValues = parsePixelValues(json)
                                val pixelType = result.pixelType ?: defaultPixelTypeForCurrentProject()
                                val pixelValue = getPixelValue(pixelValues, pixelType)
                                if (pixelValue != null) {
                                    val concentration = FittingEngine.predictConcentration(
                                        result.parameters,
                                        result.function,
                                        pixelValue
                                    )
                                    wellsToUpdate.add(well.copy(predictedConcentration = concentration))
                                    Log.d(TAG, "样本孔位 ${well.wellIndex} 计算浓度: $concentration")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "计算样本浓度失败: ${e.message}", e)
                            }
                        }
                    }
                }

                // 7. 批量更新数据库
                _layoutState.value = LayoutState.Processing("正在更新数据库...", 80)
                if (wellsToUpdate.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        wellResultDao.updateWellResults(wellsToUpdate)
                    }
                    Log.d(TAG, "已更新 ${wellsToUpdate.size} 个孔位的浓度数据")

                    // 重新加载以刷新UI
                    _wellResults.value = withContext(Dispatchers.IO) {
                        wellResultRepository.getWellResultsByRunId(_currentRunId.value!!)
                    }
                }

                // 8. 【关键】自动切换到下一个未配置的分析物
                val nextAnalyte = _availableAnalytes.value.find { it.id !in _analyteFittingStatus.value.keys }
                if(nextAnalyte != null) {
                    selectAnalyte(nextAnalyte.id)
                    _layoutState.value = LayoutState.Ready
                    Log.d(TAG, "自动切换到下一个待处理分析物: ${nextAnalyte.name}")
                } else {
                    _layoutState.value = LayoutState.Ready
                    Log.d(TAG, "所有分析物处理完成")
                }

            } catch (e: Exception) {
                Log.e(TAG, "应用拟合结果失败", e)
                _layoutState.value = LayoutState.Error("应用拟合结果失败: ${e.message}")
            } finally {
                _isFittingLoading.value = false
                dismissManualFitDialog() // 关闭对话框
            }
        }
    }

    /**
     * 执行拟合计算
     * @param concentrations 标准品浓度映射 (wellIndex -> 浓度值)
     * @param functions 要尝试的拟合函数集合
     * @param pixelTypes 要尝试的像素类型集合
     */
    fun executeFitting(
        concentrations: Map<Int, Double>,
        functions: Set<FittingFunction>,
        pixelTypes: Set<PixelType>
    ) {
        viewModelScope.launch {
            try {
                _isFittingLoading.value = true

                // 1. 获取标准品和空白对照孔
                val allStandards = getStandardWells()
                val blanks = getBlankWells()

                // 2. 更新标准品的浓度（保存到数据库）
                val updatedStandards = mutableListOf<WellResult>()
                allStandards.forEach { well ->
                    concentrations[well.wellIndex]?.let { conc ->
                        updatedStandards.add(well.copy(trueConcentration = conc))
                    }
                }

                if (updatedStandards.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        wellResultDao.updateWellResults(updatedStandards)
                    }

                    // 重新加载孔位结果以获取更新后的数据
                    val results = withContext(Dispatchers.IO) {
                        wellResultRepository.getWellResultsByRunId(_currentRunId.value!!)
                    }
                    _wellResults.value = results
                }

                // 3. 计算背景值（如果存在空白对照）
                val backgroundPixelValues: Map<String, Double>? = if (blanks.isNotEmpty()) {
                    val combinedJson = blanks.mapNotNull { it.pixelValueJson }
                    PixelExtractionUtils.calculateAveragePixelValues(combinedJson)
                } else {
                    null
                }

                // 4. 准备拟合数据
                val fittingData = updatedStandards.mapNotNull { well ->
                    well.trueConcentration?.let { conc ->
                        well.pixelValueJson?.let { json ->
                            // 背景扣除
                            val pixelMap = PixelExtractionUtils.jsonToMap(json)
                            val correctedPixelMap = if (backgroundPixelValues != null) {
                                pixelMap.mapValues { (key, value) ->
                                    value - (backgroundPixelValues[key] ?: 0.0)
                                }
                            } else {
                                pixelMap
                            }
                            Pair(conc, correctedPixelMap)
                        }
                    }
                }

                if (fittingData.isEmpty()) {
                    Log.e(TAG, "没有有效的拟合数据")
                    _isFittingLoading.value = false
                    return@launch
                }

                // 5. 遍历像素特征；成熟自动函数使用反算验收排序，专家函数保留手动拟合能力。
                val results = mutableListOf<FittingResult>()

                withContext(Dispatchers.Default) {
                    for (pixelType in pixelTypes) {
                        val dataPoints = fittingData.mapNotNull { (conc, pixelMap) ->
                            val pixelValue = pixelMap[pixelType.identifier]
                            if (pixelValue != null) {
                                Pair(conc, pixelValue)
                            } else null
                        }.sortedBy { it.first } // 按浓度排序

                        if (dataPoints.size < 4) {
                            Log.w(TAG, "像素类型 ${pixelType.displayName} 的数据点不足，跳过拟合")
                            continue
                        }

                        val automaticFunctions = functions.intersect(
                            FittingEngine.automaticCalibrationFunctions()
                        )
                        if (automaticFunctions.isNotEmpty()) {
                            val calibrationResults = FittingEngine.fitCalibrationCandidates(
                                dataPoints = dataPoints,
                                allowedFunctions = automaticFunctions
                            ).map { result -> result.copy(pixelType = pixelType) }
                            results.addAll(calibrationResults)
                        }

                        // 用户明确勾选的其他函数属于专家路径，不参与默认自动推荐函数集合；这些
                        // 结果仍保留，方便历史项目或特殊实验手动比较，但排序时不会只靠R²置顶。
                        val expertFunctions = functions - automaticFunctions - FittingFunction.INTERPOLATION
                        for (function in expertFunctions) {
                            try {
                                val result = FittingEngine.fitCurve(
                                    dataPoints = dataPoints,
                                    function = function,
                                    pixelType = pixelType
                                )

                                if (result != null && result.rSquared.isFinite()) {
                                    results.add(result)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "拟合失败: ${function.displayName} 和 ${pixelType.displayName}", e)
                            }
                        }
                    }
                }

                // 6. 优先使用 ICH 风格标准点反算诊断；旧专家函数缺少诊断时才退回 R² 排序。
                val sortedResults = results.sortedWith(
                    compareByDescending<FittingResult> {
                        it.allMetrics["ICH M10 Accepted"] ?: 0.0
                    }.thenByDescending {
                        it.allMetrics["Endpoint Pass Count"] ?: 0.0
                    }.thenByDescending {
                        it.allMetrics["Accepted Standard Ratio"] ?: 0.0
                    }.thenBy {
                        it.allMetrics["Back-calculated RMSE (%)"] ?: Double.POSITIVE_INFINITY
                    }.thenByDescending {
                        it.rSquared
                    }
                )
                _fittingResults.value = sortedResults

                if (sortedResults.isEmpty()) {
                    Log.w(TAG, "没有找到有效的拟合结果")
                }

            } catch (e: Exception) {
                Log.e(TAG, "执行拟合计算失败", e)
            } finally {
                _isFittingLoading.value = false
            }
        }
    }

    /**
     * 当用户选择不同的拟合结果时调用
     */
    fun onFittingResultSelected(selected: FittingResult) {
        val currentResults = _fittingResults.value.toMutableList()
        // 将选中的结果移到列表首位
        if (currentResults.contains(selected)) {
            currentResults.remove(selected)
            currentResults.add(0, selected)
            _fittingResults.value = currentResults
        }
    }

    /**
     * 保存拟合结果为模板
     * @param result 要保存的拟合结果
     * @return 新创建的模板ID，如果保存失败则返回null
     */
    suspend fun saveFittingResultAsTemplate(result: FittingResult): String? {
        try {
            val analyte = _selectedAnalyte.value ?: return null
            val project = _currentProject.value ?: return null

            // 1. 创建曲线模型
            val curveModel = CurveModel(
                id = UUID.randomUUID().toString(),
                name = "${analyte.name}-${result.function.displayName}-${result.pixelType?.displayName}",
                function = result.function,
                pixelType = result.pixelType ?: PixelType.GRAY_LUMINOSITY,
                parameters = result.params,  // 使用FittingResult中的params计算属性，它返回Map<String, Double>
                metrics = result.allMetrics,
                dataPoints = result.standardPoints
            )

            // 保存曲线模型
            withContext(Dispatchers.IO) {
                curveModelRepository.saveCurveModel(curveModel)
            }

            // 2. 创建模板布局 - 修改为直接使用Map<Int, String>格式
            // 将孔位信息转换为Map<Int, String>格式 (wellIndex -> roleType)
            val layoutMap = _wellResults.value
                .filter { it.fkAnalyteId == analyte.id && it.roleType != null }
                .associate { it.wellIndex to it.roleType!! }

            // 生成JSON字符串
            val defaultLayoutJson = if (layoutMap.isNotEmpty()) {
                Gson().toJson(layoutMap)
            } else {
                null
            }

            // 3. 创建实验模板
            val template = ExperimentTemplate(
                id = UUID.randomUUID().toString(),
                templateName = "${analyte.name}-${project.name}-${result.function.displayName}",
                analyteId = analyte.id,
                reagentAntigenId = null, // 用户可以在模板编辑页面设置
                reagentAntibodyId = null, // 用户可以在模板编辑页面设置
                fkCurveModelId = curveModel.id,
                reliableRangeMin = result.standardPoints.minOfOrNull { it.first } ?: 0.0,
                reliableRangeMax = result.standardPoints.maxOfOrNull { it.first } ?: 100.0,
                concentrationUnit = "ng/mL", // 默认单位，用户可以在模板编辑页面修改
                defaultLayoutJson = defaultLayoutJson
            )

            // 保存模板
            withContext(Dispatchers.IO) {
                experimentTemplateRepository.saveTemplate(template)
            }

            return template.id

        } catch (e: Exception) {
            Log.e(TAG, "保存拟合结果为模板失败", e)
            return null
        }
    }

    /**
     * 处理样本并计算结果，准备导航到结果页面
     * * @return 当前运行ID，用于导航到结果页面
     */
    suspend fun processAndNavigateToResults(): String? {
        try {
            _layoutState.value = LayoutState.Processing("正在处理样本数据...", 0)

            val currentRunId = _currentRunId.value ?: return null
            val currentAnalyte = _selectedAnalyte.value ?: return null

            // 获取当前分析物的标准品和样本
            val allResults = _wellResults.value
            val standardWells = allResults.filter { well ->
                well.fkAnalyteId == currentAnalyte.id && well.roleType == WellRoleType.STANDARD.code
            }

            val sampleWells = allResults.filter { well ->
                well.fkAnalyteId == currentAnalyte.id && well.roleType == WellRoleType.SAMPLE.code
            }

            // 检查是否有足够的标准品进行拟合
            if (standardWells.size < 2) {
                _layoutState.value = LayoutState.Error("标准品数量不足，无法进行曲线拟合")
                return null
            }

            // 检查是否有样本需要处理
            if (sampleWells.isEmpty()) {
                _layoutState.value = LayoutState.Error("没有找到需要处理的样本")
                return null
            }

            // 提取标准品的浓度和像素值
            val standardPoints = standardWells.mapNotNull { well ->
                val concentration = well.trueConcentration
                if (concentration != null && well.pixelValueJson != null) {
                    try {
                        val pixelValues = parsePixelValues(well.pixelValueJson!!)
                        // 默认使用GREEN通道
                        val pixelValue = getPixelValue(pixelValues, defaultPixelTypeForCurrentProject())
                            ?: return@mapNotNull null
                        Pair(concentration, pixelValue)
                    } catch (e: Exception) {
                        Log.e(TAG, "解析像素值失败: ${e.message}")
                        null
                    }
                } else null
            }

            // 检查是否有有效的标准点
            if (standardPoints.size < 2) {
                _layoutState.value = LayoutState.Error("有效标准点数量不足，无法进行曲线拟合")
                return null
            }

            _layoutState.value = LayoutState.Processing("正在进行曲线拟合...", 30)

            // 使用FittingEngine进行曲线拟合
            val fittingResult = FittingEngine.fit(standardPoints)
            if (!fittingResult.isSuccess) {
                _layoutState.value = LayoutState.Error("曲线拟合失败: ${fittingResult.errorMessage}")
                return null
            }

            _layoutState.value = LayoutState.Processing("正在计算样本浓度...", 60)

            // 预测样本浓度
            val predictions = sampleWells.mapNotNull { well ->
                if (well.pixelValueJson != null) {
                    try {
                        val pixelValues = parsePixelValues(well.pixelValueJson!!)
                        // 默认使用GREEN通道
                        val pixelValue = getPixelValue(pixelValues, defaultPixelTypeForCurrentProject())
                            ?: return@mapNotNull null

                        // 使用拟合结果预测浓度
                        val concentration = FittingEngine.predictConcentration(
                            parameters = fittingResult.parameters,
                            function = fittingResult.function,
                            pixelValue = pixelValue
                        )

                        // 更新样本的预测浓度
                        val updatedWell = well.copy(
                            predictedConcentration = concentration
                        )

                        // 保存到数据库
                        wellResultRepository.updateWellResult(updatedWell)

                        updatedWell
                    } catch (e: Exception) {
                        Log.e(TAG, "处理样本失败: ${e.message}")
                        null
                    }
                } else null
            }

            _layoutState.value = LayoutState.Processing("正在保存拟合模型...", 80)

            // 保存拟合模型到数据库
            val curveModel = CurveModel(
                id = UUID.randomUUID().toString(),
                name = "${currentAnalyte.name}_${System.currentTimeMillis()}",
                function = fittingResult.function,
                pixelType = fittingResult.pixelType ?: defaultPixelTypeForCurrentProject(),
                parameters = fittingResult.params,  // 使用FittingResult中的params计算属性，它返回Map<String, Double>
                metrics = fittingResult.metrics,
                createdAt = Date()
            )

            curveModelRepository.saveCurveModel(curveModel)

            _layoutState.value = LayoutState.Processing("处理完成，准备导航...", 100)
            delay(500) // 短暂延迟，让用户看到完成状态
            _layoutState.value = LayoutState.Ready

            return currentRunId
        } catch (e: Exception) {
            Log.e(TAG, "处理样本时发生错误: ${e.message}", e)
            _layoutState.value = LayoutState.Error("处理失败: ${e.message}")
            return null
        }
    }

    /**
     * 预测浓度
     */
    /**
     * 从JSON字符串解析像素值
     */
    private fun parsePixelValues(pixelValueJson: String): Map<String, Double> {
        return try {
            val type = object : TypeToken<Map<String, Double>>() {}.type
            Gson().fromJson(pixelValueJson, type)
        } catch (e: Exception) {
            Log.e(TAG, "解析像素值JSON失败: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 【新增】处理所有样本的最终浓度计算
     * @param onComplete 计算完成后的回调函数，参数为计算是否成功
     */
    fun processAllSamples(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            _layoutState.value = LayoutState.Processing("正在计算所有样本浓度...", 0)

            try {
                val allWellResults = _wellResults.value.toMutableList()
                val wellsToUpdate = mutableListOf<WellResult>()
                val project = _currentProject.value
                
                // 检查是否是DL模型预测方法
                val isDlModel = project?.analysisMethod == "DL_MODEL"
                
                if (isDlModel) {
                    Log.d(TAG, "检测到深度学习模型预测方法，跳过曲线拟合浓度计算")
                    _layoutState.value = LayoutState.Ready
                    onComplete(true)
                    return@launch
                }

                // 遍历所有已配置的分析物
                _analyteFittingStatus.value.forEach { (analyteId, fittingResult) ->
                    val samples = allWellResults.filter {
                        it.fkAnalyteId == analyteId && it.roleType == WellRoleType.SAMPLE.code
                    }

                    Log.d(TAG, "处理分析物 $analyteId 的 ${samples.size} 个样本")

                    samples.forEach { well ->
                        well.pixelValueJson?.let { json ->
                            try {
                                // 确保pixelType不为null，如果为null则使用默认值
                                val pixelType = fittingResult.pixelType ?: run {
                                    Log.w(TAG, "样本 ${well.wellIndex} 的像素类型为空，已回退到当前模式的推荐特征")
                                    defaultPixelTypeForCurrentProject()
                                }
                                
                                val resolvedPixelType =
                                    if (fittingResult.pixelType == null) defaultPixelTypeForCurrentProject() else pixelType
                                val pixelMap = PixelExtractionUtils.jsonToMap(json)
                                val pixelValue = getPixelValue(pixelMap, resolvedPixelType)
                                if (pixelValue != null) {
                                    val concentration = FittingEngine.predictConcentration(
                                        fittingResult.parameters,
                                        fittingResult.function,
                                        pixelValue
                                    )
                                    val listIndex = allWellResults.indexOfFirst { it.resultId == well.resultId }
                                    if (listIndex != -1) {
                                        val updatedWell = allWellResults[listIndex].copy(
                                            predictedConcentration = concentration
                                        )
                                        allWellResults[listIndex] = updatedWell // 更新本地列表
                                        wellsToUpdate.add(updatedWell)

                                        Log.d(TAG, "样本 ${well.wellIndex} 计算浓度: $concentration")
                                    }
                                } else {
                                    Log.w(TAG, "样本 ${well.wellIndex} 没有所需的像素值类型: ${resolvedPixelType.displayName}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "处理样本 ${well.wellIndex} 出错: ${e.message}", e)
                            }
                        }
                    }
                }

                // 批量更新数据库
                if(wellsToUpdate.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        wellResultDao.updateWellResults(wellsToUpdate)
                    }
                    Log.d(TAG, "已更新 ${wellsToUpdate.size} 个样本的浓度数据")
                } else {
                    Log.w(TAG, "没有样本需要更新浓度")
                }

                _wellResults.value = allWellResults // 刷新UI状态
                _layoutState.value = LayoutState.Ready

                onComplete(true)
            } catch (e: Exception) {
                Log.e(TAG, "最终浓度计算失败", e)
                _layoutState.value = LayoutState.Error("计算失败: ${e.message}")
                onComplete(false)
            }
        }
    }

    /**
     * 将Map转换为DoubleArray
     */
    private fun convertMapToParametersArray(function: FittingFunction, parameters: Map<String, Double>): DoubleArray {
        return when (function) {
            FittingFunction.LINEAR -> doubleArrayOf(parameters["a"] ?: 0.0, parameters["b"] ?: 0.0)
            FittingFunction.QUADRATIC -> doubleArrayOf(parameters["a"] ?: 0.0, parameters["b"] ?: 0.0, parameters["c"] ?: 0.0)
            FittingFunction.CUBIC -> doubleArrayOf(parameters["a"] ?: 0.0, parameters["b"] ?: 0.0, parameters["c"] ?: 0.0, parameters["d"] ?: 0.0)
            FittingFunction.QUARTIC -> doubleArrayOf(parameters["a"] ?: 0.0, parameters["b"] ?: 0.0, parameters["c"] ?: 0.0, parameters["d"] ?: 0.0, parameters["e"] ?: 0.0)
            FittingFunction.EXPONENTIAL -> doubleArrayOf(parameters["a"] ?: 0.0, parameters["b"] ?: 0.0)
            FittingFunction.POWER -> doubleArrayOf(parameters["a"] ?: 0.0, parameters["b"] ?: 0.0)
            FittingFunction.LOG -> doubleArrayOf(parameters["a"] ?: 0.0, parameters["b"] ?: 0.0)
            FittingFunction.RODBARD -> doubleArrayOf(parameters["a"] ?: 0.0, parameters["b"] ?: 0.0, parameters["c"] ?: 0.0, parameters["d"] ?: 0.0)
            else -> doubleArrayOf(parameters["a"] ?: 0.0, parameters["b"] ?: 0.0)
        }
    }

    /**
     * 将DoubleArray转换为Map<String, Double>
     */
    private fun convertParametersArrayToMap(function: FittingFunction, parameters: DoubleArray): Map<String, Double> {
        return when (function) {
            FittingFunction.LINEAR -> mapOf(
                "a" to parameters[0],  // 截距
                "b" to parameters[1]   // 斜率
            )
            FittingFunction.QUADRATIC -> mapOf(
                "a" to parameters[0],  // 常数项
                "b" to parameters[1],  // 一次项系数
                "c" to parameters[2]   // 二次项系数
            )
            FittingFunction.CUBIC -> mapOf(
                "a" to parameters[0],
                "b" to parameters[1],
                "c" to parameters[2],
                "d" to parameters[3]
            )
            FittingFunction.QUARTIC -> mapOf(
                "a" to parameters[0],
                "b" to parameters[1],
                "c" to parameters[2],
                "d" to parameters[3],
                "e" to parameters[4]
            )
            FittingFunction.EXPONENTIAL -> mapOf(
                "a" to parameters[0],
                "b" to parameters[1]
            )
            FittingFunction.POWER -> mapOf(
                "a" to parameters[0],
                "b" to parameters[1]
            )
            FittingFunction.LOG -> mapOf(
                "a" to parameters[0],
                "b" to parameters[1]
            )
            FittingFunction.RODBARD -> mapOf(
                "a" to parameters[0],
                "b" to parameters[1],
                "c" to parameters[2],
                "d" to parameters[3]
            )
            else -> mapOf(
                "a" to parameters[0],
                "b" to parameters[1]
            )
        }
    }

    /**
     * 执行标准曲线拟合的准备工作流
     */
    private suspend fun runCurveFittingWorkflow(project: Project, runId: String) {
        try {
            Log.d(TAG, "开始执行曲线拟合工作流")

            // 加载孔位结果
            _layoutState.value = LayoutState.Processing(
                context.getString(R.string.loading_well_data),
                10
            )
            val initialResults = withContext(Dispatchers.IO) {
                wellResultRepository.getWellResultsByRunId(runId)
            }

            if (initialResults.isEmpty()) {
                Log.e(TAG, context.getString(R.string.well_detection_not_found))
                _layoutState.value = LayoutState.Error(context.getString(R.string.well_detection_not_found))
                return
            }

            // 处理裁切逻辑
            val needsCropping = initialResults.any { it.croppedImageIdentifier.isNullOrEmpty() }
            val originalBmp = _originalBitmap.value

            if (needsCropping && originalBmp != null) {
                // 如果需要裁切并且有原始图像
                _layoutState.value = LayoutState.Processing(
                    context.getString(R.string.cropping_well_images),
                    15
                )
                Log.d(TAG, "开始裁切孔位图像")

                // 调用 Repository 进行裁切并更新数据库
                val croppedResults = withContext(Dispatchers.IO) {
                    wellResultRepository.cropAndSaveWellImages(runId, originalBmp)
                }
                _wellResults.value = croppedResults
                Log.d(TAG, "孔位图像裁切完成")
            } else if (needsCropping && originalBmp == null) {
                Log.e(TAG, context.getString(R.string.missing_original_image))
                _layoutState.value = LayoutState.Error(context.getString(R.string.missing_original_image))
                return
            } else {
                // 如果不需要裁切，直接使用初始结果
                _wellResults.value = initialResults
                Log.d(TAG, "所有孔位已有裁切图像，跳过裁切")
            }

            // 检查是否需要提取像素值
            val wellsToCheck = _wellResults.value
            val needsPixelExtraction = wellsToCheck.any { it.pixelValueJson == null }

            if (needsPixelExtraction) {
                _layoutState.value = LayoutState.Processing(
                    context.getString(R.string.extracting_pixel_values),
                    20
                )
                Log.d(TAG, "开始提取像素值")

                // 提取像素值
                extractPixelValuesForAllWells(wellsToCheck)

                // 重新加载包含像素值的孔位结果
                val updatedResults = withContext(Dispatchers.IO) {
                    wellResultRepository.getWellResultsByRunId(runId)
                }
                _wellResults.value = updatedResults
                Log.d(TAG, "像素提取完成，已更新孔位结果")
            } else {
                Log.d(TAG, "所有孔位已有像素值，跳过提取")
            }

            // 加载项目相关的分析物
            _layoutState.value = LayoutState.Processing(
                context.getString(R.string.loading_analyte_info),
                60
            )
            loadProjectAnalytes(project.id)

            // 加载可用模板
            _layoutState.value = LayoutState.Processing(
                context.getString(R.string.loading_templates),
                80
            )
            loadAvailableTemplates()

            // 更新分析物布局
            updateAnalyteLayouts()

            _layoutState.value = LayoutState.Ready
            Log.d(TAG, "曲线拟合工作流初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.curve_fitting_workflow_failed), e)
            _layoutState.value = LayoutState.Error(context.getString(R.string.curve_fitting_preparation_failed, e.message))
        }
    }

    /**
     * 【新增】应用DL模型进行预测
     * @param modelName 模型名称
     * @param onComplete 完成回调
     */
    fun applyDlModel(modelName: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            val selectedAnalyteId = _selectedAnalyte.value?.id
            val projectId = _currentProject.value?.id
            
            if (selectedAnalyteId == null || projectId == null) {
                Log.e(TAG, context.getString(R.string.model_prediction_failed_no_analyte))
                onComplete()
                return@launch
            }

            // 【修正】只对标记为"样本"的孔位应用模型
            val sampleWells = _wellResults.value.filter {
                it.fkAnalyteId == selectedAnalyteId && it.roleType == WellRoleType.SAMPLE.code
            }

            if (sampleWells.isEmpty()) {
                Log.w(TAG, context.getString(R.string.no_sample_wells, selectedAnalyteId))
                onComplete()
                return@launch
            }

            try {
                // 设置初始状态为10%
                _layoutState.value = LayoutState.AutoProcessing(
                    context.getString(R.string.model_prediction_start), 
                    10
                )
                val runId = _currentRunId.value ?: return@launch
                
                // 1. 【新增】更新ProjectAnalyteJoin表，保存DL模型名称
                withContext(Dispatchers.IO) {
                    try {
                        // 查找现有的关联记录
                        val join = projectAnalyteJoinRepository.getProjectAnalyteJoin(projectId, selectedAnalyteId)
                        
                        if (join != null) {
                            // 更新现有记录，只更新dlModelName，保留其他字段
                            val updatedJoin = join.copy(
                                dlModelName = modelName
                            )
                            projectAnalyteJoinRepository.updateProjectAnalyteJoin(updatedJoin)
                            Log.d(TAG, "已更新ProjectAnalyteJoin: DL模型名称=$modelName")
                        } else {
                            Log.w(TAG, "未找到项目和分析物的关联记录，无法更新DL模型信息")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "更新ProjectAnalyteJoin失败: ${e.message}", e)
                    }
                }
                
                // 进度更新到25%
                delay(300)
                _layoutState.value = LayoutState.AutoProcessing(
                    context.getString(R.string.model_prediction_processing, 25), 
                    25
                )

                // 【修正】调用Repository对指定的样本孔位进行预测
                val predictedWells = withContext(Dispatchers.IO) {
                    // 进度更新到50%
                    withContext(Dispatchers.Main) {
                        _layoutState.value = LayoutState.AutoProcessing(
                            context.getString(R.string.model_prediction_processing, 50),
                            50
                        )
                    }
                    
                    // 需要在此处创建此函数或实现逻辑。
                    // 直接模拟预测逻辑。
                    val modelPath = wellResultRepository.assetFilePath(
                        context,
                        DetectionModeSupport.SHARED_CONCENTRATION_MODEL_ASSET
                    )
                        ?: throw IOException(context.getString(R.string.model_file_not_found, modelName))
                    val concentrationModel = LiteModuleLoader.load(modelPath)

                    val updatedWells = mutableListOf<WellResult>()
                    
                    // 遍历样本并预测浓度，同时更新进度
                    sampleWells.forEachIndexed { index, well ->
                        // 计算当前进度 (从50%到80%)
                        val progressPercent = 50 + ((index + 1) * 30) / sampleWells.size
                        withContext(Dispatchers.Main) {
                            _layoutState.value = LayoutState.AutoProcessing(
                                context.getString(R.string.model_prediction_processing, progressPercent),
                                progressPercent
                            )
                        }
                        
                        val imagePath = well.croppedImageIdentifier ?: return@forEachIndexed
                        val imageFile = File(imagePath)
                        if (!imageFile.exists()) return@forEachIndexed

                        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 128, 128, true)

                        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
                        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
                        val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(resizedBitmap, mean, std)

                        val outputTensor = concentrationModel.forward(IValue.from(inputTensor)).toTensor()
                        val concentrationPercent = outputTensor.dataAsFloatArray[0].toDouble()

                        val joins = projectAnalyteJoinRepository.getProjectAnalyteJoins(_currentProject.value!!.id)
                        val maxConc = joins.find { it.analyteId == selectedAnalyteId }?.maxConcentration ?: 100.0
                        val finalConc = (concentrationPercent / 100.0) * maxConc

                        updatedWells.add(well.copy(predictedConcentration = finalConc))
                        
                        // 每处理5个样本暂停一下，让UI有机会更新
                        if (index % 5 == 0) {
                            delay(100)
                        }
                    }
                    updatedWells
                }

                // 2. 更新数据库 - 进度更新到90%
                _layoutState.value = LayoutState.AutoProcessing(
                    context.getString(R.string.updating_database),
                    90
                )
                
                if (predictedWells.isNotEmpty()) {
                    withContext(Dispatchers.IO) { wellResultDao.updateWellResults(predictedWells) }
                    // 刷新本地状态
                    _wellResults.value = withContext(Dispatchers.IO) { wellResultDao.getWellResultsByRunId(runId) }
                }

                // 3. 标记当前分析物为已处理
                val dummyResult = FittingResult(
                    function = FittingFunction.LINEAR,
                    parameters = doubleArrayOf(1.0, 0.0),
                    formula = "DL Model Applied",
                    rSquared = 1.0,
                    standardPoints = emptyList(),
                    curvePoints = emptyList(),
                    isSuccess = true
                )
                val currentStatus = _analyteFittingStatus.value.toMutableMap()
                currentStatus[selectedAnalyteId] = dummyResult
                _analyteFittingStatus.value = currentStatus

                // 4. 完成状态 - 100%
                _layoutState.value = LayoutState.AutoProcessing(
                    context.getString(R.string.model_prediction_complete),
                    100
                )
                
                // 短暂延迟后恢复Ready状态
                delay(500)
                _layoutState.value = LayoutState.Ready
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, context.getString(R.string.model_prediction_failed, e.message), e)
                _layoutState.value = LayoutState.Error(context.getString(R.string.model_prediction_failed, e.message))
                onComplete()
            }
        }
    }

    /**
     * 执行深度学习模型预测的自动化工作流
     */
    private suspend fun runDlPredictionWorkflow(project: Project, runId: String, onNavigateToResult: ((String) -> Unit)?) {
        try {
            Log.d(TAG, "开始执行深度学习模型预测工作流")

            // 1. 确保图片已加载、裁切并提取像素
            _layoutState.value = LayoutState.AutoProcessing(
                context.getString(R.string.preparing_image_data), 
                10
            )
            val initialResults = withContext(Dispatchers.IO) {
                wellResultRepository.getWellResultsByRunId(runId)
            }

            if (initialResults.isEmpty()) {
                Log.e(TAG, context.getString(R.string.well_detection_not_found))
                _layoutState.value = LayoutState.Error(context.getString(R.string.well_detection_not_found))
                return
            }

            // 处理裁切和像素提取
            val wellResultsWithPixels = ensureWellsProcessed(runId, initialResults)

            // 2. 加载分析物信息
            _layoutState.value = LayoutState.AutoProcessing(
                context.getString(R.string.loading_analyte_info), 
                30
            )
            val analytes = withContext(Dispatchers.IO) {
                projectAnalyteJoinRepository.getAnalytesByProjectId(project.id).first()
            }

            if (analytes.isEmpty()) {
                Log.e(TAG, context.getString(R.string.no_analytes_found))
                _layoutState.value = LayoutState.Error(context.getString(R.string.no_analytes_found))
                return
            }

            // 【修改】统一处理单分析物和多分析物流程
            // 加载分析物并准备UI
            _availableAnalytes.value = analytes

            if (analytes.isNotEmpty()) {
                _selectedAnalyte.value = analytes.first()

                // 尝试加载模板
                loadAvailableTemplates()
            }

            // 如果是单分析物项目，自动标记所有孔位为样本，但不自动应用模型
            if (analytes.size == 1) {
                _layoutState.value = LayoutState.AutoProcessing(
                    context.getString(R.string.preparing_single_analyte_layout), 
                    60
                )
                val analyteId = analytes.first().id
                val dimensions = GridLayoutPolicy.resolveProject(project)

                // 自动标记所有孔位为样本
                val wellsToUpdate = wellResultsWithPixels.map {
                    // 使用新的映射工具获取虚拟坐标
                    val virtualCoords = WellMappingUtils.mapRealToVirtualCoordinates(
                        realIndex = it.wellIndex,
                        columns = dimensions.columns
                    )
                    it.copy(
                        fkAnalyteId = analyteId,
                        roleType = WellRoleType.SAMPLE.code,
                        virtualRow = virtualCoords.first,
                        virtualCol = virtualCoords.second
                    )
                }
                withContext(Dispatchers.IO) { wellResultDao.updateWellResults(wellsToUpdate) }
                _wellResults.value = wellsToUpdate

                // 立即更新分析物布局以刷新UI
                updateAnalyteLayouts()

                // 加载分析物配置的模型，但不自动应用
                val joins = withContext(Dispatchers.IO) {
                    projectAnalyteJoinRepository.getProjectAnalyteJoins(project.id)
                }
                val join = joins.firstOrNull { it.analyteId == analyteId }

                // 如果有保存的模型名称，预先选中它，但不自动应用
                join?.dlModelName?.let { modelName ->
                    // 检查这个模型名是否在可用列表中
                    if (availableDlModels.value.contains(modelName)) {
                        // 这里仅设置UI状态，不执行预测
                        Log.d(TAG, "预选模型: $modelName")
                    }
                }
            }

            _layoutState.value = LayoutState.Ready

        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.dl_prediction_workflow_failed), e)
            _layoutState.value = LayoutState.Error(context.getString(R.string.auto_prediction_failed, e.message))
        }
    }

    /**
     * 确保孔位已处理（裁切和提取像素）
     */
    private suspend fun ensureWellsProcessed(runId: String, initialResults: List<WellResult>): List<WellResult> {
        // 处理裁切逻辑
        val needsCropping = initialResults.any { it.croppedImageIdentifier.isNullOrEmpty() }
        val originalBmp = _originalBitmap.value

        var currentResults = initialResults

        if (needsCropping && originalBmp != null) {
            _layoutState.value = LayoutState.AutoProcessing(
                context.getString(R.string.cropping_well_images), 
                15
            )
            Log.d(TAG, "开始裁切孔位图像")

            // 裁切并更新
            currentResults = withContext(Dispatchers.IO) {
                wellResultRepository.cropAndSaveWellImages(runId, originalBmp)
            }
            _wellResults.value = currentResults
        } else if (needsCropping && originalBmp == null) {
            throw Exception(context.getString(R.string.missing_original_image))
        }

        // 检查是否需要提取像素值
        val needsPixelExtraction = currentResults.any { it.pixelValueJson == null }

        if (needsPixelExtraction) {
            _layoutState.value = LayoutState.AutoProcessing(
                context.getString(R.string.extracting_pixel_values), 
                20
            )
            Log.d(TAG, "开始提取像素值")

            // 提取像素
            extractPixelValuesForAllWells(currentResults)

            // 重新加载更新后的结果
            currentResults = withContext(Dispatchers.IO) {
                wellResultRepository.getWellResultsByRunId(runId)
            }
            _wellResults.value = currentResults
        }

        return currentResults
    }
}

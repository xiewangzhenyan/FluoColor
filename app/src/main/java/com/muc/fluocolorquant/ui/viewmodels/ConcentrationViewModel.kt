package com.muc.fluocolorquant.ui.viewmodels

import android.content.ContentValues.TAG
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.data.repository.WellResultRepository
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.repository.DetectionRunRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.muc.fluocolorquant.ui.viewmodels.EnhancedWellDetection

/**
 * 浓度预测ViewModel
 * 管理孔位裁剪和浓度预测的UI逻辑
 */
@HiltViewModel
class ConcentrationViewModel @Inject constructor(
    private val wellResultRepository: WellResultRepository,
    private val wellResultDao: WellResultDao, // 注入DAO以便直接查询
    private val projectRepository: ProjectRepository,
    private val detectionRunRepository: DetectionRunRepository
) : ViewModel() {
    // 浓度预测状态
    sealed class ConcentrationState {
        object Idle : ConcentrationState()
        object Loading : ConcentrationState()
        // 新增：图像裁剪完成但浓度预测还未完成
        data class ImagesCropped(val wellResults: List<WellResult>) : ConcentrationState()
        // 浓度预测完成
        data class Success(val wellResults: List<WellResult>) : ConcentrationState()
        data class Error(val message: String) : ConcentrationState()
    }
    
    // 浓度预测状态流
    private val _concentrationState = MutableStateFlow<ConcentrationState>(ConcentrationState.Idle)
    val concentrationState: StateFlow<ConcentrationState> = _concentrationState.asStateFlow()
    
    // 原始图像
    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()
    
    // 浓度预测进度（0-100）
    private val _predictionProgress = MutableStateFlow(0)
    val predictionProgress: StateFlow<Int> = _predictionProgress.asStateFlow()
    
    // 是否正在执行浓度预测
    private val _isPredicting = MutableStateFlow(false)
    val isPredicting: StateFlow<Boolean> = _isPredicting.asStateFlow()
    
    // 选中的孔位索引 (可能在此屏幕不需要，但保留)
    private val _selectedWellIndex = MutableStateFlow<Int?>(null)
    val selectedWellIndex: StateFlow<Int?> = _selectedWellIndex.asStateFlow()
    
    // 当前项目ID
    private val _currentProjectId = MutableStateFlow<String?>(null)
    val currentProjectId: StateFlow<String?> = _currentProjectId.asStateFlow()
    
    // 当前项目信息
    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()
    
    // 项目行数
    val projectRows: StateFlow<Int> = _currentProject.map { it?.rows ?: 8 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8)
        
    // 项目列数
    val projectColumns: StateFlow<Int> = _currentProject.map { it?.columns ?: 12 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 12)
    
    // 当前运行ID
    private val _currentRunId = MutableStateFlow<String?>(null)
    val currentRunId: StateFlow<String?> = _currentRunId.asStateFlow()
    
    /**
     * 获取当前运行ID
     * @return 当前运行ID，如果未设置则返回null
     */
    fun getCurrentRunId(): String? {
        return _currentRunId.value
    }
    
    // 保存增强型检测结果的状态
    private val _enhancedDetections = MutableStateFlow<List<EnhancedWellDetection>>(emptyList())
    val enhancedDetections: StateFlow<List<EnhancedWellDetection>> = _enhancedDetections.asStateFlow()
    
    /**
     * 设置当前项目ID并加载项目信息
     */
    fun setCurrentProjectId(projectId: String?) {
        _currentProjectId.value = projectId
        
        if (!projectId.isNullOrEmpty()) {
            viewModelScope.launch {
                try {
                    val project = projectRepository.getProjectById(projectId)
                    _currentProject.value = project
                    android.util.Log.d("ConcentrationViewModel", "项目加载成功，行数: ${project?.rows}, 列数: ${project?.columns}")
                } catch (e: Exception) {
                    android.util.Log.e("ConcentrationViewModel", "加载项目失败: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * 设置原始图像
     */
    fun setOriginalBitmap(bitmap: Bitmap) {
        _originalBitmap.value = bitmap
    }
    
    /**
     * 设置增强型检测结果
     * @param detections 增强型检测结果列表
     */
    fun setEnhancedDetections(detections: List<EnhancedWellDetection>) {
        _enhancedDetections.value = detections
    }
    
    /**
     * 从检测结果保存孔位数据 (此方法现在主要用于启动runId)
     * @param detections 检测到的孔位列表
     * @param projectId 项目ID
     * @param confThreshold 置信度阈值
     * @param iouThreshold IoU阈值
     * @return 生成的runId，如果保存失败则返回null
     */
    suspend fun saveDetectionResults(
        detections: List<WellDetection>,
        projectId: String,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f
    ): String? {
        _concentrationState.value = ConcentrationState.Loading
        
        return try {
            // 调用仓库保存检测结果
            val runId = wellResultRepository.saveDetectionResults(
                projectId = projectId,
                detections = detections,
                confThreshold = confThreshold,
                iouThreshold = iouThreshold
            )
            
            // 设置当前运行ID
            _currentRunId.value = runId
            
            // 如果有增强型检测结果，将其与runId关联并保存到数据库
            val enhancedResults = _enhancedDetections.value
            if (enhancedResults.isNotEmpty()) {
                // 保存增强型检测的颜色信息，仅保存检测到了圆心和颜色的信息
                val wellsWithColorInfo = enhancedResults
                    .filter { it.circleX != null && it.circleY != null && it.radius != null && it.centerColor != null }
                    .map { enhancedWell ->
                        WellResult(
                            runId = runId,
                            projectId = projectId,
                            wellIndex = enhancedWell.id,
                            predictedConcentration = null, // 暂未预测浓度
                            trueConcentration = null,
                            isStandard = false,
                            detectedRectLeft = enhancedWell.rect.left,
                            detectedRectTop = enhancedWell.rect.top,
                            detectedRectRight = enhancedWell.rect.right,
                            detectedRectBottom = enhancedWell.rect.bottom,
                            detectionConfidence = enhancedWell.confidence,
                            croppedImageIdentifier = null                            // 添加圆形检测信息到额外字段
//                          manualCropRectLeft = enhancedWell.circleX,
//                          manualCropRectTop = enhancedWell.circleY,
//                          manualCropRectRight = enhancedWell.radius,
//                          manualCropRectBottom = enhancedWell.centerColor?.toFloat() // 使用额外字段存储颜色值
                        )
                    }
                
                // 更新已有孔位信息
                if (wellsWithColorInfo.isNotEmpty()) {
                    wellResultDao.updateWellResults(wellsWithColorInfo)
                    android.util.Log.d("ConcentrationViewModel", "已保存 ${wellsWithColorInfo.size} 个增强型检测结果")
                }
            }
            
            // 状态可以保持Loading或Idle，因为下一步是predictConcentration
            _concentrationState.value = ConcentrationState.Idle
            android.util.Log.d("ConcentrationViewModel", "Detection results saved. Run ID: $runId")
            runId
            
        } catch (e: Exception) {
            _concentrationState.value = ConcentrationState.Error("保存检测结果失败: ${e.message}")
            android.util.Log.e("ConcentrationViewModel", "Failed to save detection results", e)
            null
        }
    }
    
    /**
     * 分阶段处理：先裁剪图像，显示给用户，然后在后台预测浓度
     * @param runId 运行ID
     */
    fun processInStages(runId: String? = null) {
        val actualRunId = runId ?: _currentRunId.value
        if (actualRunId == null) {
            _concentrationState.value = ConcentrationState.Error("缺少运行ID")
            android.util.Log.e("ConcentrationViewModel", "Run ID is missing for staged processing.")
            return
        }
        
        android.util.Log.d("ConcentrationViewModel", "开始分阶段处理，runId: $actualRunId")
        
        // 检查原始图像是否存在
        if (_originalBitmap.value == null) {
            _concentrationState.value = ConcentrationState.Error("缺少原始图像")
            android.util.Log.e("ConcentrationViewModel", "Original bitmap is missing for staged processing.")
            return
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 第一阶段：裁剪图像
                android.util.Log.d("ConcentrationViewModel", "第一阶段：开始裁剪孔位图像")
                
                // 更新UI状态为加载中
                withContext(Dispatchers.Main) {
                    _concentrationState.value = ConcentrationState.Loading
                }
                
                // 获取原始图像
                val bitmap = _originalBitmap.value
                if (bitmap == null) {
                    withContext(Dispatchers.Main) {
                        _concentrationState.value = ConcentrationState.Error("缺少原始图像")
                    }
                    android.util.Log.e("ConcentrationViewModel", "Original bitmap is missing for cropping.")
                    return@launch
                }
                
                // 并行裁剪孔位图像
                val croppedWellResults = wellResultRepository.cropWellsWithParallelProcessing(
                    runId = actualRunId,
                    originalBitmap = bitmap
                )
                
                if (croppedWellResults.isNotEmpty()) {
                    android.util.Log.d("ConcentrationViewModel", "成功裁剪 ${croppedWellResults.size} 个孔位图像")
                    
                    // 更新UI状态为图像裁剪完成
                    withContext(Dispatchers.Main) {
                        _concentrationState.value = ConcentrationState.ImagesCropped(croppedWellResults)
                    }
                    
                    // 第二阶段：在后台预测浓度
                    android.util.Log.d("ConcentrationViewModel", "第二阶段：开始在后台预测浓度")
                    
                    // 设置正在预测标志
                    withContext(Dispatchers.Main) {
                        _isPredicting.value = true
                        _predictionProgress.value = 0
                    }
                    
                    try {
                        // 在后台预测浓度，并传递进度回调函数
                        val predictedWellResults = wellResultRepository.predictConcentrationOnly(
                            runId = actualRunId,
                            progressCallback = { progress ->
                                // 更新进度条
                                _predictionProgress.value = progress
                                android.util.Log.d("ConcentrationViewModel", "浓度预测进度: $progress%")
                            }
                        )
                        
                        // 预测完成后更新进度和状态
                        _predictionProgress.value = 100
                        
                        if (predictedWellResults.isNotEmpty()) {
                            android.util.Log.d("ConcentrationViewModel", "浓度预测成功，共 ${predictedWellResults.size} 个结果")
                            
                            // 更新UI状态为预测完成
                            withContext(Dispatchers.Main) {
                                _concentrationState.value = ConcentrationState.Success(predictedWellResults)
                                _isPredicting.value = false
                            }
                        } else {
                            android.util.Log.e("ConcentrationViewModel", "浓度预测失败，未返回结果")
                            
                            // 保持ImagesCropped状态，但更新预测标志
                            withContext(Dispatchers.Main) {
                                _isPredicting.value = false
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ConcentrationViewModel", "浓度预测异常: ${e.message}", e)
                        
                        // 保持ImagesCropped状态，但更新预测标志
                        withContext(Dispatchers.Main) {
                            _isPredicting.value = false
                        }
                    }
                } else {
                    android.util.Log.e("ConcentrationViewModel", "孔位图像裁剪失败，未返回结果")
                    
                    withContext(Dispatchers.Main) {
                        _concentrationState.value = ConcentrationState.Error("孔位图像裁剪失败")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ConcentrationViewModel", "分阶段处理异常: ${e.message}", e)
                
                withContext(Dispatchers.Main) {
                    _concentrationState.value = ConcentrationState.Error("处理失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 原始方法：根据运行ID裁剪孔位图像并预测浓度（一次性完成所有处理）
     * 为兼容现有代码保留，建议使用processInStages方法代替
     * @param runId 运行ID
     */
    fun predictConcentration(runId: String? = null) {
        _concentrationState.value = ConcentrationState.Loading
        android.util.Log.d("ConcentrationViewModel", "Starting concentration prediction...")
        
        val actualRunId = runId ?: _currentRunId.value
        if (actualRunId == null) {
            _concentrationState.value = ConcentrationState.Error("缺少运行ID")
            android.util.Log.e("ConcentrationViewModel", "Run ID is missing for prediction.")
            return
        }
        
        val bitmap = _originalBitmap.value
        if (bitmap == null) {
            _concentrationState.value = ConcentrationState.Error("缺少原始图像")
            android.util.Log.e("ConcentrationViewModel", "Original bitmap is missing for prediction.")
            return
        }
        
        viewModelScope.launch {
            try {
                android.util.Log.d("ConcentrationViewModel", "Calling repository cropWellsAndPredictConcentration for runId: $actualRunId")
                // 裁剪孔位图像并预测浓度
                val success = wellResultRepository.cropWellsAndPredictConcentration(
                    runId = actualRunId,
                    originalBitmap = bitmap
                )
                
                if (success) {
                    android.util.Log.d("ConcentrationViewModel", "Repository processing successful for runId: $actualRunId. Fetching results...")
                    // 获取最新的孔位结果 (Repository更新了数据库, 我们从DAO读取)
                    val wellResults = withContext(Dispatchers.IO) {
                        wellResultDao.getWellResultsByRunId(actualRunId) // 直接通过DAO获取
                    }
                    
                    android.util.Log.d("ConcentrationViewModel", "Fetched ${wellResults.size} well results for runId: $actualRunId")
                    _concentrationState.value = ConcentrationState.Success(wellResults)
                } else {
                    android.util.Log.e("ConcentrationViewModel", "Repository processing failed for runId: $actualRunId")
                    _concentrationState.value = ConcentrationState.Error("浓度预测失败")
                }
            } catch (e: Exception) {
                android.util.Log.e("ConcentrationViewModel", "Exception during concentration prediction for runId: $actualRunId", e)
                _concentrationState.value = ConcentrationState.Error("浓度预测失败: ${e.message}")
            }
        }
    }
    
    /**
     * 手动模式：分析单个裁剪图像
     * @param projectId 项目ID
     * @param croppedImageUri 裁剪图像URI
     */
    fun analyzeManualCroppedImage(projectId: String, croppedImageUri: Uri) {
        _concentrationState.value = ConcentrationState.Loading
        
        // 设置当前项目ID，以便后续使用
        _currentProjectId.value = projectId
        
        viewModelScope.launch {
            try {
                android.util.Log.d("ConcentrationViewModel", "分析手动裁剪图像: projectId=$projectId, uri=$croppedImageUri")
                
                val concentration = wellResultRepository.analyzeManualCroppedImage(
                    projectId = projectId,
                    croppedImageUri = croppedImageUri
                )
                
                if (concentration != null) {
                    android.util.Log.d("ConcentrationViewModel", "浓度预测成功: $concentration")
                    
                    // 获取最新的手动模式孔位结果
                    val wellResults = withContext(Dispatchers.IO) {
                         wellResultDao.getWellResultsByProjectId(projectId)
                    }
                    
                    android.util.Log.d("ConcentrationViewModel", "获取到 ${wellResults.size} 个孔位结果")
                    _concentrationState.value = ConcentrationState.Success(wellResults)
                } else {
                    android.util.Log.e("ConcentrationViewModel", "浓度预测失败，返回值为null")
                    _concentrationState.value = ConcentrationState.Error("浓度预测失败")
                }
            } catch (e: Exception) {
                android.util.Log.e("ConcentrationViewModel", "分析手动裁剪图像异常", e)
                _concentrationState.value = ConcentrationState.Error("浓度预测失败: ${e.message}")
            }
        }
    }
    
    /**
     * 选择孔位
     */
    fun selectWell(index: Int?) {
        _selectedWellIndex.value = index
    }

    /**
     * 加载检测运行数据
     * @param runId 运行ID
     */
    fun loadDetectionRun(runId: String) {
        viewModelScope.launch {
            try {
                android.util.Log.d("ConcentrationViewModel", "开始加载检测运行数据: $runId")
                _currentRunId.value = runId
                
                // 加载项目信息
                val detectionRun = withContext(Dispatchers.IO) {
                    detectionRunRepository.getDetectionRunById(runId)
                }
                
                if (detectionRun != null) {
                    val projectId = detectionRun.projectId
                    android.util.Log.d("ConcentrationViewModel", "检测运行关联的项目ID: $projectId")
                    
                    val project = withContext(Dispatchers.IO) {
                        projectRepository.getProjectById(projectId)
                    }
                    
                    if (project != null) {
                        android.util.Log.d("ConcentrationViewModel", "成功加载项目: ${project.id}, ${project.name}, 分析方法: ${project.analysisMethod}")
                        _currentProject.value = project
                        _currentProjectId.value = projectId
                        
                        // 加载孔位结果
                        val results = withContext(Dispatchers.IO) {
                            wellResultRepository.getWellResultsByRunId(runId)
                        }
                        
                        android.util.Log.d("ConcentrationViewModel", "加载了 ${results.size} 个孔位结果")
                        
                        // 根据状态更新
                        if (results.isNotEmpty()) {
                            val hasCroppedImages = results.any { it.croppedImageIdentifier != null }
                            val hasPredictions = results.any { it.predictedConcentration != null }
                            
                            if (hasPredictions) {
                                android.util.Log.d("ConcentrationViewModel", "孔位结果包含浓度预测，更新为Success状态")
                                _concentrationState.value = ConcentrationState.Success(results)
                            } else if (hasCroppedImages) {
                                android.util.Log.d("ConcentrationViewModel", "孔位结果包含裁剪图像，更新为ImagesCropped状态")
                                _concentrationState.value = ConcentrationState.ImagesCropped(results)
                            } else {
                                android.util.Log.d("ConcentrationViewModel", "孔位结果不包含裁剪图像或浓度预测，更新为Idle状态")
                                _concentrationState.value = ConcentrationState.Idle
                            }
                        } else {
                            android.util.Log.w("ConcentrationViewModel", "没有找到孔位结果，更新为Idle状态")
                            _concentrationState.value = ConcentrationState.Idle
                        }
                    } else {
                        android.util.Log.e("ConcentrationViewModel", "项目不存在: $projectId")
                        _concentrationState.value = ConcentrationState.Error("项目不存在")
                    }
                } else {
                    android.util.Log.e("ConcentrationViewModel", "检测运行数据不存在: $runId")
                    _concentrationState.value = ConcentrationState.Error("检测运行数据不存在")
                }
            } catch (e: Exception) {
                android.util.Log.e("ConcentrationViewModel", "加载检测运行数据异常: ${e.message}", e)
                _concentrationState.value = ConcentrationState.Error("加载检测运行数据失败: ${e.message}")
            }
        }
    }

    /**
     * 更新孔位结果的像素值
     */
    fun updateWellResultPixelValues(resultId: Long, pixelValuesJson: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val wellResult = wellResultRepository.getWellResultById(resultId)
                    if (wellResult != null) {
                        val updatedWellResult = wellResult.copy(
                            pixelValueJson = pixelValuesJson
                        )
                        wellResultRepository.updateWellResult(updatedWellResult)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "更新孔位像素值失败: ${e.message}", e)
            }
        }
    }

    /**
     * 更新孔位结果
     */
    suspend fun updateWellResult(wellResult: WellResult) {
        withContext(Dispatchers.IO) {
            wellResultRepository.updateWellResult(wellResult)
        }
    }

    /**
     * 第一阶段：仅裁剪孔位图像，不进行浓度预测
     * 使用并行处理加速图像裁剪
     * @param runId 运行ID
     */
    fun cropWellImagesOnly(runId: String? = null) {
        val actualRunId = runId ?: _currentRunId.value
        if (actualRunId == null) {
            _concentrationState.value = ConcentrationState.Error("缺少运行ID")
            android.util.Log.e("ConcentrationViewModel", "Run ID is missing for cropping.")
            return
        }
        
        android.util.Log.d("ConcentrationViewModel", "开始裁剪孔位图像，runId: $actualRunId")
        
        // 检查原始图像是否存在
        val bitmap = _originalBitmap.value
        if (bitmap == null) {
            _concentrationState.value = ConcentrationState.Error("缺少原始图像")
            android.util.Log.e("ConcentrationViewModel", "Original bitmap is missing for cropping.")
            return
        }
        
        // 更新UI状态为加载中
        _concentrationState.value = ConcentrationState.Loading
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("ConcentrationViewModel", "调用仓库裁剪孔位图像，runId: $actualRunId")
                
                // 并行裁剪孔位图像
                val croppedWellResults = wellResultRepository.cropWellsWithParallelProcessing(
                    runId = actualRunId,
                    originalBitmap = bitmap
                )
                
                withContext(Dispatchers.Main) {
                    if (croppedWellResults.isNotEmpty()) {
                        android.util.Log.d("ConcentrationViewModel", "成功裁剪 ${croppedWellResults.size} 个孔位图像")
                        // 更新到图像裁剪完成状态
                        _concentrationState.value = ConcentrationState.ImagesCropped(croppedWellResults)
                    } else {
                        android.util.Log.e("ConcentrationViewModel", "孔位图像裁剪失败，未返回结果")
                        _concentrationState.value = ConcentrationState.Error("孔位图像裁剪失败")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ConcentrationViewModel", "裁剪孔位图像异常: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _concentrationState.value = ConcentrationState.Error("孔位图像裁剪失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 第二阶段：使用已裁剪的孔位图像进行浓度预测
     * 在后台执行，不阻塞UI
     * @param runId 运行ID
     */
    fun predictConcentrationInBackground(runId: String? = null) {
        val actualRunId = runId ?: _currentRunId.value
        if (actualRunId == null) {
            android.util.Log.e("ConcentrationViewModel", "Run ID is missing for background prediction.")
            return
        }
        
        // 设置正在预测标志
        _isPredicting.value = true
        _predictionProgress.value = 0
        
        viewModelScope.launch(Dispatchers.IO) { // 使用IO调度器，避免阻塞主线程
            try {
                android.util.Log.d("ConcentrationViewModel", "Starting background concentration prediction for runId: $actualRunId")
                // 在后台预测浓度，并传递进度回调函数
                val predictedWellResults = wellResultRepository.predictConcentrationOnly(
                    runId = actualRunId,
                    progressCallback = { progress ->
                        // 更新进度条
                        _predictionProgress.value = progress
                        android.util.Log.d("ConcentrationViewModel", "Prediction progress: $progress%")
                    }
                )
                
                // 预测完成后更新进度和状态
                _predictionProgress.value = 100
                
                if (predictedWellResults.isNotEmpty()) {
                    android.util.Log.d("ConcentrationViewModel", "Background prediction successful for ${predictedWellResults.size} wells")
                    
                    // 切换到主线程更新UI状态
                    withContext(Dispatchers.Main) {
                        // 只有当当前状态是ImagesCropped时才更新为Success，避免覆盖其他状态
                        val currentState = _concentrationState.value
                        if (currentState is ConcentrationState.ImagesCropped || 
                            currentState is ConcentrationState.Success || 
                            currentState is ConcentrationState.Loading) {
                            android.util.Log.d("ConcentrationViewModel", "更新状态为Success，从${currentState::class.simpleName}")
                            _concentrationState.value = ConcentrationState.Success(predictedWellResults)
                        } else {
                            android.util.Log.w("ConcentrationViewModel", "未更新状态，当前状态为: ${currentState::class.simpleName}")
                        }
                        
                        // 最后更新预测标志，确保UI状态先更新
                        _isPredicting.value = false
                    }
                } else {
                    android.util.Log.e("ConcentrationViewModel", "Background prediction failed to produce results")
                    
                    // 切换到主线程更新UI状态
                    withContext(Dispatchers.Main) {
                        // 如果没有结果但已经有裁剪图像，保持ImagesCropped状态
                        val currentState = _concentrationState.value
                        if (currentState !is ConcentrationState.ImagesCropped) {
                            _concentrationState.value = ConcentrationState.Error("浓度预测失败：未返回结果")
                        }
                        
                        // 更新预测标志
                        _isPredicting.value = false
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ConcentrationViewModel", "Exception during background prediction", e)
                
                // 切换到主线程更新UI状态
                withContext(Dispatchers.Main) {
                    // 如果出现异常但已经有裁剪图像，保持ImagesCropped状态
                    val currentState = _concentrationState.value
                    if (currentState !is ConcentrationState.ImagesCropped) {
                        _concentrationState.value = ConcentrationState.Error("浓度预测失败: ${e.message}")
                    }
                    
                    // 更新预测标志
                    _isPredicting.value = false
                }
            }
        }
    }
} 
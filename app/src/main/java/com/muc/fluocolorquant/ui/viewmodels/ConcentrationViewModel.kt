package com.muc.fluocolorquant.ui.viewmodels

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.data.repository.WellResultRepository
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.model.Project
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
    private val projectRepository: ProjectRepository
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
                            croppedImageIdentifier = null,
                            // 添加圆形检测信息到额外字段
                            manualCropRectLeft = enhancedWell.circleX,
                            manualCropRectTop = enhancedWell.circleY,
                            manualCropRectRight = enhancedWell.radius,
                            manualCropRectBottom = enhancedWell.centerColor?.toFloat() // 使用额外字段存储颜色值
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
     * 第一阶段：仅裁剪孔位图像，不进行浓度预测
     * 使用并行处理加速图像裁剪
     * @param runId 运行ID
     */
    fun cropWellImagesOnly(runId: String? = null) {
        _concentrationState.value = ConcentrationState.Loading
        android.util.Log.d("ConcentrationViewModel", "Starting well images cropping...")
        
        val actualRunId = runId ?: _currentRunId.value
        if (actualRunId == null) {
            _concentrationState.value = ConcentrationState.Error("缺少运行ID")
            android.util.Log.e("ConcentrationViewModel", "Run ID is missing for cropping.")
            return
        }
        
        val bitmap = _originalBitmap.value
        if (bitmap == null) {
            _concentrationState.value = ConcentrationState.Error("缺少原始图像")
            android.util.Log.e("ConcentrationViewModel", "Original bitmap is missing for cropping.")
            return
        }
        
        viewModelScope.launch {
            try {
                android.util.Log.d("ConcentrationViewModel", "Calling repository cropWellsWithParallelProcessing for runId: $actualRunId")
                // 并行裁剪孔位图像
                val croppedWellResults = wellResultRepository.cropWellsWithParallelProcessing(
                    runId = actualRunId,
                    originalBitmap = bitmap
                )
                
                if (croppedWellResults.isNotEmpty()) {
                    android.util.Log.d("ConcentrationViewModel", "Successfully cropped ${croppedWellResults.size} well images")
                    // 更新到图像裁剪完成状态
                    _concentrationState.value = ConcentrationState.ImagesCropped(croppedWellResults)
                } else {
                    android.util.Log.e("ConcentrationViewModel", "Failed to crop well images")
                    _concentrationState.value = ConcentrationState.Error("孔位图像裁剪失败")
                }
            } catch (e: Exception) {
                android.util.Log.e("ConcentrationViewModel", "Exception during cropping well images", e)
                _concentrationState.value = ConcentrationState.Error("孔位图像裁剪失败: ${e.message}")
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
        
        viewModelScope.launch {
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
                _isPredicting.value = false
                
                if (predictedWellResults.isNotEmpty()) {
                    android.util.Log.d("ConcentrationViewModel", "Background prediction successful for ${predictedWellResults.size} wells")
                    // 只有当当前状态是ImagesCropped时才更新为Success，避免覆盖其他状态
                    val currentState = _concentrationState.value
                    if (currentState is ConcentrationState.ImagesCropped || currentState is ConcentrationState.Success) {
                        _concentrationState.value = ConcentrationState.Success(predictedWellResults)
                    }
                } else {
                    android.util.Log.e("ConcentrationViewModel", "Background prediction failed to produce results")
                    // 不更新UI状态，让用户继续看到图像，只记录日志
                }
            } catch (e: Exception) {
                android.util.Log.e("ConcentrationViewModel", "Exception during background prediction", e)
                _isPredicting.value = false
                // 不更新UI状态，让用户继续看到图像，只记录日志
            }
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
        
        viewModelScope.launch {
            // 第一阶段：裁剪图像
            cropWellImagesOnly(actualRunId)
            
            // 等待裁剪完成
            while (_concentrationState.value !is ConcentrationState.ImagesCropped) {
                // 检查是否失败
                if (_concentrationState.value is ConcentrationState.Error) {
                    return@launch
                }
                kotlinx.coroutines.delay(100)
            }
            
            // 第二阶段：在后台预测浓度
            predictConcentrationInBackground(actualRunId)
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
} 
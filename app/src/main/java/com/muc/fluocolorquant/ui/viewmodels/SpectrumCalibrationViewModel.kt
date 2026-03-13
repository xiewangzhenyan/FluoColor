package com.muc.fluocolorquant.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.R
import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.SpectrumCalibrationType
import com.muc.fluocolorquant.data.model.SpectrumCalibration
import com.muc.fluocolorquant.data.model.SpectrumResult
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.data.repository.SpectrumRepository
import com.muc.fluocolorquant.utils.UiText
import com.muc.fluocolorquant.utils.math.SpectrumCVUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.math3.fitting.PolynomialCurveFitter
import org.apache.commons.math3.fitting.WeightedObservedPoint
import org.opencv.android.OpenCVLoader
import java.util.UUID
import javax.inject.Inject

enum class CalibrationMode { NONE, AUTO, MANUAL }

data class ManualMark(
    val id: String = UUID.randomUUID().toString(), // 唯一 ID,用于拖动和删除操作
    val point: PointF,
    val wavelength: Float
)

data class SpectrumCalibrationUiState(
    val originalBitmap: Bitmap? = null,
    val trackRects: List<Rect> = emptyList(),
    val calibrationMode: CalibrationMode = CalibrationMode.NONE,
    val calibrationImageBitmap: Bitmap? = null,
    val referenceWavelengths: List<Float> = emptyList(),
    val currentTrackIndex: Int = 0,
    val manualPoints: Map<Int, List<ManualMark>> = emptyMap(),
    val coefficients: Map<Int, DoubleArray> = emptyMap(),
    val isLoading: Boolean = false,
    val isAutoFitting: Boolean = false,
    val errorMessage: UiText? = null,
    val infoMessage: UiText? = null, // 用于传递操作成功的提示信息
    val showModeDialog: Boolean = false,
    val showDimensionMismatchDialog: Boolean = false,
    val showRetryDialog: Boolean = false, // 自动标定失败时显示重试建议对话框
    val canUndo: Boolean = false // 是否可以撤销
)

/**
 * 撤销操作的历史快照数据
 */
private data class CalibrationSnapshot(
    val manualPoints: Map<Int, List<ManualMark>>,
    val coefficients: Map<Int, DoubleArray>
)

@HiltViewModel
class SpectrumCalibrationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spectrumRepository: SpectrumRepository,
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpectrumCalibrationUiState())
    val uiState: StateFlow<SpectrumCalibrationUiState> = _uiState.asStateFlow()

    private val gson = Gson()

    /** 撤销栈：保存标定点和系数的历史快照，最多保存 20 步 */
    private val undoStack = ArrayDeque<CalibrationSnapshot>()
    private val maxUndoSteps = 20

    private fun text(resId: Int, vararg args: Any): UiText {
        return UiText.StringResource(resId, args.toList())
    }

    fun loadOriginal(projectId: String, imageUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                _uiState.updateLoading(true)

                // Ensure OpenCV native libs are loaded before any Mat usage
                if (!OpenCVLoader.initDebug()) {
                    error(context.getString(R.string.spectrum_calibration_opencv_failed))
                }

                val bitmap = decodeBitmap(imageUri) ?: error(context.getString(R.string.spectrum_calibration_image_load_failed))
                val project = projectRepository.getProjectById(projectId)
                val expectedTracks = project?.spectrumColumnCount ?: 1
                val trackRects = SpectrumCVUtils.detectSpectrumTracks(bitmap, expectedTracks)
                _uiState.update {
                    it.copy(
                        originalBitmap = bitmap,
                        trackRects = trackRects,
                        showModeDialog = true,
                        currentTrackIndex = 0
                    )
                }
            }.onFailure { e ->
                _uiState.updateError(
                    UiText.DynamicString(
                        e.message ?: context.getString(R.string.spectrum_calibration_load_failed)
                    )
                )
            }
            _uiState.updateLoading(false)
        }
    }

    fun setCalibrationMode(mode: CalibrationMode) {
        _uiState.update { it.copy(calibrationMode = mode, showModeDialog = false, errorMessage = null) }
    }

    fun resetModeDialog() {
        _uiState.update { it.copy(showModeDialog = false) }
    }

    /**
     * 加载标定图片 - 允许任意尺寸的图片
     * 不再要求标定图与原始图尺寸一致，通过通道检测实现自动对齐
     */
    fun onCalibrationImageLoaded(bitmap: Bitmap) {
        val original = _uiState.value.originalBitmap ?: return
        // 移除尺寸限制，接受任意尺寸的标定图
        // 对齐计算将在 performAutoCalibration 中完成
        _uiState.update { 
            it.copy(
                calibrationImageBitmap = bitmap, 
                showDimensionMismatchDialog = false, 
                errorMessage = null
            ) 
        }
        Log.d("SpectrumCalibration", "标定图已加载: ${bitmap.width}x${bitmap.height} (原始图: ${original.width}x${original.height})")
    }

    fun dismissDimensionMismatchDialog() {
        _uiState.update { it.copy(showDimensionMismatchDialog = false) }
    }

    fun switchToManualMode() {
        _uiState.update {
            it.copy(
                calibrationMode = CalibrationMode.MANUAL,
                showDimensionMismatchDialog = false,
                errorMessage = null
            )
        }
    }

    fun setReferenceWavelengths(values: List<Float>) {
        _uiState.update { it.copy(referenceWavelengths = values) }
    }

    /**
     * 切换到指定通道，切换前校验当前通道的标定点数量
     */
    fun updateCurrentTrack(index: Int) {
        val state = _uiState.value
        val currentIndex = state.currentTrackIndex
        val currentPoints = state.manualPoints[currentIndex]?.size ?: 0

        // 如果是向前/向后切换（非初始化），检查当前通道标定点是否足够
        if (index != currentIndex && currentPoints in 1..1) {
            _uiState.update {
                it.copy(errorMessage = text(R.string.spectrum_calibration_channel_points_required, currentIndex + 1))
            }
            return // 阻止切换
        }

        _uiState.update {
            it.copy(currentTrackIndex = index.coerceIn(0, (it.trackRects.size - 1).coerceAtLeast(0)))
        }
    }

    fun addManualPoint(trackIndex: Int, point: PointF, wavelength: Float) {
        pushUndoSnapshot() // 推入撤销快照
        val updated = _uiState.value.manualPoints.toMutableMap()
        val points = updated[trackIndex]?.toMutableList() ?: mutableListOf()
        points.add(ManualMark(point = point, wavelength = wavelength))
        updated[trackIndex] = points

        _uiState.update { it.copy(manualPoints = updated) }
        recalcManualCoefficients(trackIndex)
    }

    private fun recalcManualCoefficients(trackIndex: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            calculateCoefficientsForTrack(trackIndex)
        }
    }

    private fun calculateCoefficientsForTrack(channelIndex: Int) {
        val points = _uiState.value.manualPoints[channelIndex] ?: emptyList()
        if (points.size < 2) {
            // 标定点不足2个时，静默清除该通道的系数（不弹错误提示）
            val coeffMap = _uiState.value.coefficients.toMutableMap()
            coeffMap.remove(channelIndex)
            _uiState.update { it.copy(coefficients = coeffMap) }
            return
        }
        val data = points.map { mark ->
            mark.point.y.toDouble() to mark.wavelength.toDouble()
        }
        // 优先使用线性拟合以避免边缘异常
        val coef = if (points.size <= 3) {
            fitLinear(data)
        } else {
            fitQuadratic(data)
        }
        _uiState.update {
            it.copy(coefficients = it.coefficients + (channelIndex to coef), errorMessage = null)
        }
    }

    /**
     * 更新手动标定点的 Y 坐标(用于拖动操作)
     */
    fun updateManualPointY(trackIndex: Int, pointId: String, newY: Float) {
        pushUndoSnapshot() // 推入撤销快照
        val updated = _uiState.value.manualPoints.toMutableMap()
        val points = updated[trackIndex]?.toMutableList() ?: return
        val index = points.indexOfFirst { it.id == pointId }
        if (index == -1) return
        
        val oldMark = points[index]
        points[index] = oldMark.copy(point = PointF(oldMark.point.x, newY))
        updated[trackIndex] = points
        
        _uiState.update { it.copy(manualPoints = updated) }
        recalcManualCoefficients(trackIndex)
    }

    /**
     * 删除指定的手动标定点
     */
    fun deleteManualPoint(trackIndex: Int, pointId: String) {
        pushUndoSnapshot() // 推入撤销快照
        val updated = _uiState.value.manualPoints.toMutableMap()
        val points = updated[trackIndex]?.toMutableList() ?: return
        points.removeAll { it.id == pointId }
        updated[trackIndex] = points
        
        _uiState.update { it.copy(manualPoints = updated) }
        recalcManualCoefficients(trackIndex)
    }

    fun applyMagicFromTrack0() {
        viewModelScope.launch(Dispatchers.Default) {
            val state = _uiState.value
            val basePoints = state.manualPoints[0]
            val baseRect = state.trackRects.getOrNull(0)
            
            // 检查通道 0 是否有足够的标定点
            if (basePoints == null || basePoints.size < 2) {
                _uiState.update {
                    it.copy(errorMessage = text(R.string.spectrum_calibration_track_zero_points_required))
                }
                return@launch
            }
            
            if (baseRect == null) {
                _uiState.update { it.copy(errorMessage = text(R.string.spectrum_calibration_track_zero_missing)) }
                return@launch
            }
            
            val baseCenterX = baseRect.exactCenterX()
            val baseCenterY = baseRect.exactCenterY()
            
            val newManualPoints = mutableMapOf<Int, List<ManualMark>>()
            val coeffMap = mutableMapOf<Int, DoubleArray>()
            
            // 保留通道 0 的原始数据
            newManualPoints[0] = basePoints
            state.coefficients[0]?.let { coeffMap[0] = it }
            
            // 遍历所有其他通道
            state.trackRects.forEachIndexed { index, rect ->
                if (index == 0) return@forEachIndexed
                
                // 计算当前通道与通道 0 的中心点偏移量
                val deltaX = rect.exactCenterX() - baseCenterX
                val deltaY = rect.exactCenterY() - baseCenterY
                
                // 复制通道 0 的所有标定点,并应用偏移量
                val shiftedPoints = basePoints.map { baseMark ->
                    ManualMark(
                        id = UUID.randomUUID().toString(), // 生成新的 UUID
                        point = PointF(
                            baseMark.point.x + deltaX,
                            baseMark.point.y + deltaY
                        ),
                        wavelength = baseMark.wavelength
                    )
                }
                
                newManualPoints[index] = shiftedPoints
                
                // 计算新的拟合系数
                val baseCoeffs = state.coefficients[0]
                if (baseCoeffs != null) {
                    val shifted = SpectrumCVUtils.calculateOffsetCalibration(baseRect, rect, baseCoeffs)
                    coeffMap[index] = shifted
                }
            }
            
            // 更新状态:保存新的标定点、系数,并设置成功提示
            _uiState.update { 
                it.copy(
                    manualPoints = newManualPoints,
                    coefficients = coeffMap,
                    infoMessage = text(R.string.spectrum_calibration_apply_all_success)
                ) 
            }
        }
    }
    
    /**
     * 清除提示信息
     */
    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    /** 关闭重试建议对话框 */
    fun dismissRetryDialog() {
        _uiState.update { it.copy(showRetryDialog = false) }
    }

    // ==================== 撤销机制 ====================

    /** 将当前标定点和系数状态推入撤销栈 */
    private fun pushUndoSnapshot() {
        val state = _uiState.value
        undoStack.addLast(
            CalibrationSnapshot(
                manualPoints = state.manualPoints.toMap(),
                coefficients = state.coefficients.toMap()
            )
        )
        if (undoStack.size > maxUndoSteps) undoStack.removeFirst()
        _uiState.update { it.copy(canUndo = true) }
    }

    /** 执行撤销操作，恢复到上一个快照 */
    fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        _uiState.update {
            it.copy(
                manualPoints = snapshot.manualPoints,
                coefficients = snapshot.coefficients,
                canUndo = undoStack.isNotEmpty()
            )
        }
    }

    // ==================== 波长记忆 ====================

    /** 从 DataStore 加载上次保存的参考波长列表 */
    fun loadLastReferenceWavelengths(onLoaded: (List<String>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = settingsRepository.spectrumLastReferenceWavelengthsFlow.first()
            if (saved.isNotBlank()) {
                val list = saved.split(",").map { it.trim() }
                withContext(Dispatchers.Main) { onLoaded(list) }
            }
        }
    }

    /** 将参考波长列表保存到 DataStore */
    private fun saveReferenceWavelengths(wavelengths: List<Float>) {
        viewModelScope.launch(Dispatchers.IO) {
            val csv = wavelengths.joinToString(",")
            settingsRepository.setSpectrumLastReferenceWavelengths(csv)
        }
    }

    /**
     * 执行自动标定 - 支持不同尺寸图片的对齐
     * 
     * 流程:
     * 1. 对原始图和标定图分别检测通道
     * 2. 计算对齐偏移量
     * 3. 在标定图上寻峰
     * 4. 将峰值坐标转换到原始图坐标系
     * 5. 使用转换后的坐标进行拟合
     */
    fun performAutoCalibration() {
        val calibrationBitmap = _uiState.value.calibrationImageBitmap
        val original = _uiState.value.originalBitmap
        val ref = _uiState.value.referenceWavelengths
        if (calibrationBitmap == null || original == null) {
            _uiState.updateError(text(R.string.spectrum_calibration_upload_required))
            return
        }
        if (ref.isEmpty()) {
            _uiState.updateError(text(R.string.spectrum_calibration_reference_required))
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isAutoFitting = true, errorMessage = null) }
            runCatching {
                val originalRects = _uiState.value.trackRects
                val expectedTracks = originalRects.size
                
                // 1. 检测标定图中的通道
                val calibrationRects = try {
                    SpectrumCVUtils.detectSpectrumTracks(calibrationBitmap, expectedTracks)
                } catch (e: Exception) {
                    Log.e("SpectrumCalibration", "标定图通道检测失败: ${e.message}")
                    emptyList()
                }
                
                // 2. 计算对齐参数
                val alignment = if (calibrationRects.size == expectedTracks) {
                    SpectrumCVUtils.calculateAlignmentOffset(
                        originalTracks = originalRects,
                        calibrationTracks = calibrationRects,
                        originalWidth = original.width,
                        originalHeight = original.height,
                        calibrationWidth = calibrationBitmap.width,
                        calibrationHeight = calibrationBitmap.height
                    )
                } else {
                    null
                }
                
                // 如果对齐失败，使用保底策略（居中对齐）
                val finalAlignment = alignment ?: run {
                    Log.w("SpectrumCalibration", "使用保底对齐策略")
                    SpectrumCVUtils.calculateFallbackAlignment(
                        originalWidth = original.width,
                        originalHeight = original.height,
                        calibrationWidth = calibrationBitmap.width,
                        calibrationHeight = calibrationBitmap.height
                    )
                }
                
                Log.d("SpectrumCalibration", "对齐参数: scale=${finalAlignment.scale}, deltaY=${finalAlignment.deltaY}")
                
                // 3. 确定用于寻峰的通道矩形
                // 如果标定图尺寸与原始图相同，使用原始通道；否则使用检测到的标定图通道
                val peakSearchRects = if (calibrationRects.size == expectedTracks) {
                    calibrationRects
                } else if (calibrationBitmap.width == original.width && calibrationBitmap.height == original.height) {
                    originalRects
                } else {
                    // 保底：在标定图上创建对应的通道区域（基于偏移量）
                    originalRects.map { rect ->
                        val offsetLeft = ((rect.left + finalAlignment.deltaX) / finalAlignment.scale).toInt().coerceIn(0, calibrationBitmap.width - 1)
                        val offsetRight = ((rect.right + finalAlignment.deltaX) / finalAlignment.scale).toInt().coerceIn(1, calibrationBitmap.width)
                        Rect(offsetLeft, 0, offsetRight, calibrationBitmap.height)
                    }
                }
                
                val coeffMap = mutableMapOf<Int, DoubleArray>()
                
                // 4. 对每个通道进行标定
                originalRects.forEachIndexed { index, origRect ->
                    val searchRect = peakSearchRects.getOrElse(index) { origRect }
                    
                    // 在标定图上寻峰
                    val peaksInCalib = SpectrumCVUtils.findPeaksInTrack(calibrationBitmap, searchRect, ref.size)
                    if (peaksInCalib.size < ref.size) {
                        error(
                            context.getString(
                                R.string.spectrum_calibration_peak_insufficient,
                                index + 1,
                                peaksInCalib.size,
                                ref.size
                            )
                        )
                    }
                    
                    // 5. 将标定图坐标转换为原始图坐标
                    // 转换公式: Y_orig = Y_calib * scale - deltaY
                    val peaksInOrig = peaksInCalib.map { yCalib ->
                        (yCalib * finalAlignment.scale - finalAlignment.deltaY).toInt()
                    }
                    
                    Log.d("SpectrumCalibration", "通道 ${index + 1} 峰值坐标转换: $peaksInCalib -> $peaksInOrig")
                    
                    // 按Y坐标排序（从大到小，即底部到顶部）
                    val sortedPeaks = peaksInOrig.sortedDescending()
                    
                    // 6. 拟合波长-像素关系
                    val data = sortedPeaks.zip(ref).map { (y, wavelength) ->
                        y.toDouble() to wavelength.toDouble()
                    }
                    
                    // 优先使用线性拟合
                    coeffMap[index] = if (ref.size <= 3) fitLinear(data) else fitQuadratic(data)
                }
                
                _uiState.update { it.copy(coefficients = coeffMap) }
            }.onFailure { e ->
                Log.e("SpectrumCalibration", "自动标定失败", e)
                // 触发重试建议对话框，提供用户友好的操作指引
                _uiState.update {
                    it.copy(
                        errorMessage = UiText.DynamicString(
                            e.message ?: context.getString(R.string.spectrum_calibration_auto_failed)
                        ),
                        showRetryDialog = true,
                        isAutoFitting = false
                    )
                }
            }
            _uiState.update { it.copy(isAutoFitting = false) }
        }
    }

    fun completeCalibration(projectId: String, onSuccess: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val coeffs = state.coefficients
            val rects = state.trackRects
            val bitmap = state.originalBitmap
            
            if (coeffs.size < rects.size) {
                _uiState.updateError(text(R.string.spectrum_calibration_complete_all_required))
                return@launch
            }
            
            if (bitmap == null) {
                _uiState.updateError(text(R.string.spectrum_calibration_original_missing))
                return@launch
            }

            runCatching {
                val type = when (state.calibrationMode) {
                    CalibrationMode.AUTO -> SpectrumCalibrationType.AUTO_IMAGE
                    CalibrationMode.MANUAL -> SpectrumCalibrationType.MANUAL_POINT
                    else -> SpectrumCalibrationType.MANUAL_POINT
                }

                // 保存标定参数
                rects.forEachIndexed { index, rect ->
                    val coef = coeffs[index] ?: return@forEachIndexed
                    val calibration = SpectrumCalibration(
                        projectId = projectId,
                        columnIndex = index,
                        roiRect = gson.toJson(rect),
                        calibrationType = type,
                        coefficients = gson.toJson(coef),
                        referencePoints = gson.toJson(state.manualPoints[index])
                    )
                    spectrumRepository.insertCalibration(calibration)
                }
                
                // 获取项目配置以获取分析物映射
                val project = projectRepository.getProjectById(projectId)
                val analyteMapping = project?.let {
                    runCatching {
                        val mappingJson = it.spectrumColumnMappingJson
                        if (!mappingJson.isNullOrBlank()) {
                            gson.fromJson(mappingJson, Map::class.java) as? Map<String, String>
                        } else null
                    }.getOrNull()
                } ?: emptyMap()
                
                // 为所有通道提取光谱数据并保存结果
                rects.forEachIndexed { index, rect ->
                    val coef = coeffs[index] ?: return@forEachIndexed
                    
                    // 1. 裁切通道图片并保存
                    val croppedImagePath = try {
                        // 确保rect在图片范围内
                        val safeLeft = rect.left.toInt().coerceIn(0, bitmap.width - 1)
                        val safeTop = rect.top.toInt().coerceIn(0, bitmap.height - 1)
                        val safeWidth = rect.width().toInt().coerceIn(1, bitmap.width - safeLeft)
                        val safeHeight = rect.height().toInt().coerceIn(1, bitmap.height - safeTop)
                        
                        // 裁切图片
                        val croppedBitmap = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeWidth, safeHeight)
                        
                        // 保存到应用私有目录
                        val fileName = "${projectId}_channel_${index}_${System.currentTimeMillis()}.png"
                        val file = java.io.File(context.filesDir, "spectrum_channels/$fileName")
                        file.parentFile?.mkdirs()
                        java.io.FileOutputStream(file).use { out ->
                            croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        croppedBitmap.recycle()
                        
                        file.absolutePath
                    } catch (e: Exception) {
                        Log.e("SpectrumCalibration", "裁切通道图片失败: ${e.message}")
                        ""
                    }
                    
                    // 2. 提取强度曲线
                    val rawIntensities = SpectrumCVUtils.extractIntensityProfile(bitmap, rect)
                    
                    // 3. 归一化到 0.0 - 1.0
                    val maxIntensity = rawIntensities.maxOrNull() ?: 1f
                    val minIntensity = rawIntensities.minOrNull() ?: 0f
                    val intensityRange = (maxIntensity - minIntensity).coerceAtLeast(1f)
                    val normalizedIntensities = rawIntensities.map { 
                        ((it - minIntensity) / intensityRange).toDouble()
                    }
                    
                    // 4. 波长映射
                    val a = coef[0]
                    val b = coef[1]
                    val c = coef[2]
                    val wavelengths = rawIntensities.indices.map { y ->
                        val yD = y.toDouble()
                        a * yD * yD + b * yD + c
                    }
                    
                    // 5. 寻峰
                    var peakIndex = 0
                    var peakIntensity = 0.0
                    normalizedIntensities.forEachIndexed { idx, intensity ->
                        if (intensity > peakIntensity) {
                            peakIntensity = intensity
                            peakIndex = idx
                        }
                    }
                    val peakWavelength = wavelengths.getOrNull(peakIndex)?.toFloat()
                    
                    // 6. 获取当前通道的分析物 ID
                    val analyteId = analyteMapping[(index + 1).toString()]
                    
                    // 7. 保存 SpectrumResult (包含裁切后的图片路径)
                    val result = SpectrumResult(
                        projectId = projectId,
                        columnIndex = index,
                        analyteId = analyteId,
                        imagePath = croppedImagePath,  // 使用裁切后的图片路径
                        wavelengths = gson.toJson(wavelengths),
                        intensities = gson.toJson(normalizedIntensities),
                        peakWavelength = peakWavelength
                    )
                    spectrumRepository.insertResult(result)
                }

                // 标定成功后保存参考波长到 DataStore
                val refWavelengths = state.referenceWavelengths
                if (refWavelengths.isNotEmpty()) {
                    saveReferenceWavelengths(refWavelengths)
                }

                withContext(Dispatchers.Main) { onSuccess(projectId) }
            }.onFailure { e ->
                _uiState.updateError(
                    UiText.DynamicString(
                        e.message ?: context.getString(R.string.spectrum_calibration_save_failed)
                    )
                )
            }
        }
    }

    private fun decodeBitmap(path: String): Bitmap? {
        return runCatching {
            val uri = Uri.parse(path)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    /**
     * 线性拟合: wavelength = a*y + b
     * 返回格式: [a, b, 0.0] 以保持与二次方程格式一致
     */
    private fun fitLinear(points: List<Pair<Double, Double>>): DoubleArray {
        if (points.size < 2) return doubleArrayOf(0.0, 1.0, 0.0)
        
        val n = points.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        
        points.forEach { (x, y) ->
            sumX += x
            sumY += y
            sumXY += x * y
            sumXX += x * x
        }
        
        val denominator = n * sumXX - sumX * sumX
        if (denominator == 0.0) {
            return doubleArrayOf(0.0, sumY / n, 0.0)
        }
        
        val a = (n * sumXY - sumX * sumY) / denominator
        val b = (sumY - a * sumX) / n
        
        return doubleArrayOf(0.0, a, b) // [0, a, b] 表示 wavelength = a*y + b
    }
    
    private fun fitQuadratic(points: List<Pair<Double, Double>>): DoubleArray {
        if (points.size < 3) return fitLinear(points)
        val fitter = PolynomialCurveFitter.create(2)
        val obs = points.map { (x, y) -> WeightedObservedPoint(1.0, x, y) }
        val coef = fitter.fit(obs) // [c, b, a]
        return doubleArrayOf(coef.getOrElse(2) { 0.0 }, coef.getOrElse(1) { 0.0 }, coef.getOrElse(0) { 0.0 })
    }

    private fun MutableStateFlow<SpectrumCalibrationUiState>.update(block: (SpectrumCalibrationUiState) -> SpectrumCalibrationUiState) {
        this.value = block(this.value)
    }

    private fun MutableStateFlow<SpectrumCalibrationUiState>.updateLoading(loading: Boolean) {
        this.value = this.value.copy(isLoading = loading)
    }

    private fun MutableStateFlow<SpectrumCalibrationUiState>.updateError(message: UiText) {
        this.value = this.value.copy(errorMessage = message, isLoading = false, isAutoFitting = false)
    }
}

package com.muc.fluocolorquant.ui.viewmodels

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.SpectrumCalibrationType
import com.muc.fluocolorquant.data.model.SpectrumCalibration
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.repository.SpectrumRepository
import com.muc.fluocolorquant.utils.math.SpectrumCVUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val errorMessage: String? = null,
    val infoMessage: String? = null, // 用于传递操作成功的提示信息
    val showModeDialog: Boolean = false,
    val showDimensionMismatchDialog: Boolean = false
)

@HiltViewModel
class SpectrumCalibrationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spectrumRepository: SpectrumRepository,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpectrumCalibrationUiState())
    val uiState: StateFlow<SpectrumCalibrationUiState> = _uiState.asStateFlow()

    private val gson = Gson()

    fun loadOriginal(projectId: String, imageUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                _uiState.updateLoading(true)

                // Ensure OpenCV native libs are loaded before any Mat usage
                if (!OpenCVLoader.initDebug()) {
                    error("OpenCV 初始化失败，请确认依赖已正确引入")
                }

                val bitmap = decodeBitmap(imageUri) ?: error("无法加载光谱图像")
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
                _uiState.updateError(e.message ?: "加载光谱图失败")
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

    fun onCalibrationImageLoaded(bitmap: Bitmap) {
        val original = _uiState.value.originalBitmap ?: return
        if (original.width == bitmap.width && original.height == bitmap.height) {
            _uiState.update { it.copy(calibrationImageBitmap = bitmap, showDimensionMismatchDialog = false, errorMessage = null) }
        } else {
            _uiState.update { it.copy(showDimensionMismatchDialog = true, calibrationImageBitmap = null) }
        }
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

    fun updateCurrentTrack(index: Int) {
        _uiState.update { it.copy(currentTrackIndex = index.coerceIn(0, (it.trackRects.size - 1).coerceAtLeast(0))) }
    }

    fun addManualPoint(trackIndex: Int, point: PointF, wavelength: Float) {
        val updated = _uiState.value.manualPoints.toMutableMap()
        val points = updated[trackIndex]?.toMutableList() ?: mutableListOf()
        points.add(ManualMark(point = point, wavelength = wavelength))
        updated[trackIndex] = points

        _uiState.update { it.copy(manualPoints = updated) }
        recalcManualCoefficients(trackIndex)
    }

    private fun recalcManualCoefficients(trackIndex: Int) {
        viewModelScope.launch(Dispatchers.Default) {
            val points = _uiState.value.manualPoints[trackIndex] ?: return@launch
            if (points.size < 2) {
                // 如果标定点少于2个,清除该通道的系数
                val coeffMap = _uiState.value.coefficients.toMutableMap()
                coeffMap.remove(trackIndex)
                _uiState.update { it.copy(coefficients = coeffMap) }
                return@launch
            }
            val coeffs = fitQuadratic(points.map { it.point.y.toDouble() to it.wavelength.toDouble() })
            _uiState.update { it.copy(coefficients = it.coefficients + (trackIndex to coeffs)) }
        }
    }

    /**
     * 更新手动标定点的 Y 坐标(用于拖动操作)
     */
    fun updateManualPointY(trackIndex: Int, pointId: String, newY: Float) {
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
                _uiState.update { it.copy(errorMessage = "通道 1 标定点不足,至少需要 2 个点") }
                return@launch
            }
            
            if (baseRect == null) {
                _uiState.update { it.copy(errorMessage = "通道 1 信息缺失") }
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
                    infoMessage = "已成功应用到所有通道"
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

    fun performAutoCalibration() {
        val calibrationBitmap = _uiState.value.calibrationImageBitmap
        val original = _uiState.value.originalBitmap
        val ref = _uiState.value.referenceWavelengths
        if (calibrationBitmap == null || original == null) {
            _uiState.updateError("请先上传标定图")
            return
        }
        if (ref.isEmpty()) {
            _uiState.updateError("请先输入参考波长")
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isAutoFitting = true, errorMessage = null) }
            runCatching {
                val rects = _uiState.value.trackRects
                val coeffMap = mutableMapOf<Int, DoubleArray>()
                rects.forEachIndexed { index, rect ->
                    val peaks = SpectrumCVUtils.findPeaksInTrack(calibrationBitmap, rect, ref.size)
                    if (peaks.size < ref.size) error("通道 ${index + 1} 波峰数量不足")
                    val sortedPeaks = peaks.sortedDescending() // bottom-to-top
                    val data = sortedPeaks.zip(ref).map { (y, wavelength) ->
                        y.toDouble() to wavelength.toDouble()
                    }
                    coeffMap[index] = fitQuadratic(data)
                }
                _uiState.update { it.copy(coefficients = coeffMap) }
            }.onFailure { e ->
                _uiState.updateError(e.message ?: "自动标定失败")
            }
            _uiState.update { it.copy(isAutoFitting = false) }
        }
    }

    fun completeCalibration(projectId: String, onSuccess: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val coeffs = state.coefficients
            val rects = state.trackRects
            if (coeffs.size < rects.size) {
                _uiState.updateError("请先完成所有通道的标定")
                return@launch
            }

            runCatching {
                val type = when (state.calibrationMode) {
                    CalibrationMode.AUTO -> SpectrumCalibrationType.AUTO_IMAGE
                    CalibrationMode.MANUAL -> SpectrumCalibrationType.MANUAL_POINT
                    else -> SpectrumCalibrationType.MANUAL_POINT
                }

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
                withContext(Dispatchers.Main) { onSuccess() }
            }.onFailure { e ->
                _uiState.updateError(e.message ?: "保存标定结果失败")
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

    private fun fitQuadratic(points: List<Pair<Double, Double>>): DoubleArray {
        if (points.size < 2) return doubleArrayOf(0.0, 1.0, 0.0)
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

    private fun MutableStateFlow<SpectrumCalibrationUiState>.updateError(message: String) {
        this.value = this.value.copy(errorMessage = message, isLoading = false, isAutoFitting = false)
    }
}

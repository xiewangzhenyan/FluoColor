package com.muc.fluocolorquant.ui.viewmodels

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSnapshot
import com.muc.fluocolorquant.domain.detection.array.ArrayOriginCorner
import com.muc.fluocolorquant.domain.detection.array.ArrayCoordinateTransformer
import com.muc.fluocolorquant.domain.detection.array.ArrayImagePoint
import com.muc.fluocolorquant.domain.detection.array.ArrayQuarterTurn
import com.muc.fluocolorquant.domain.detection.plate96.Plate96BitmapNormalizer
import com.muc.fluocolorquant.domain.detection.plate96.Plate96Locator
import com.muc.fluocolorquant.utils.image.ScientificBitmapLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Plate96ImageViewMode {
    NORMALIZED,
    ORIGINAL
}

enum class Plate96LocalizationStage {
    LOADING_IMAGE,
    LOCATING,
    PREPARING_PREVIEW
}

enum class Plate96LocalizationError {
    IMAGE_LOAD_FAILED,
    LOCALIZATION_FAILED
}

/** 96孔板定位确认页状态；Bitmap只存在当前会话，不写入SavedState或数据库。 */
sealed interface Plate96LocalizationUiState {
    data object Idle : Plate96LocalizationUiState
    data class Loading(val stage: Plate96LocalizationStage) : Plate96LocalizationUiState

    data class Ready(
        val sourceBitmap: Bitmap,
        val normalizedBitmap: Bitmap,
        val session: Plate96Locator.Session,
        val imageViewMode: Plate96ImageViewMode,
        val locatorMode: ArrayLocatorMode,
        val showOutlines: Boolean,
        val showLabels: Boolean,
        val selectedSiteIndex: Int?,
        val orientationConfirmed: Boolean,
        val exifRotationDegrees: Int,
        val exifFlipped: Boolean
    ) : Plate96LocalizationUiState

    data class Error(val reason: Plate96LocalizationError) : Plate96LocalizationUiState
}

/**
 * 新96孔板定位状态机。
 *
 * 切换算法会取消旧任务；A1确认只重建坐标，不重复模型推理；页面重组不会重新运行定位。
 */
@HiltViewModel
class Plate96LocalizationViewModel @Inject constructor(
    private val bitmapLoader: ScientificBitmapLoader,
    private val locator: Plate96Locator
) : ViewModel() {
    private val _uiState = MutableStateFlow<Plate96LocalizationUiState>(Plate96LocalizationUiState.Idle)
    val uiState: StateFlow<Plate96LocalizationUiState> = _uiState.asStateFlow()

    private var localizationJob: Job? = null
    private var currentUri: String? = null
    private var sourceBitmap: Bitmap? = null
    /** 同一原图最多缓存四种整数方向工作图，避免确认A1时反复分配和提前回收正在绘制的Bitmap。 */
    private val normalizedBitmapCache = mutableMapOf<NormalizationKey, Bitmap>()
    private var exifRotationDegrees: Int = 0
    private var exifFlipped: Boolean = false

    fun start(imageUri: String?) {
        if (imageUri.isNullOrBlank()) {
            _uiState.value = Plate96LocalizationUiState.Error(Plate96LocalizationError.IMAGE_LOAD_FAILED)
            return
        }
        if (currentUri == imageUri && _uiState.value is Plate96LocalizationUiState.Ready) return
        currentUri = imageUri
        localizationJob?.cancel()
        localizationJob = viewModelScope.launch {
            _uiState.value = Plate96LocalizationUiState.Loading(Plate96LocalizationStage.LOADING_IMAGE)
            try {
                val loaded = bitmapLoader.load(imageUri)
                    ?: run {
                        _uiState.value = Plate96LocalizationUiState.Error(
                            Plate96LocalizationError.IMAGE_LOAD_FAILED
                        )
                        return@launch
                    }
                replaceSourceBitmap(loaded.bitmap)
                exifRotationDegrees = loaded.exifRotationDegrees
                exifFlipped = loaded.exifFlipped
                runLocalization(ArrayLocatorMode.AUTO)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.value = Plate96LocalizationUiState.Error(
                    Plate96LocalizationError.LOCALIZATION_FAILED
                )
            }
        }
    }

    fun retry() {
        val uri = currentUri ?: return
        currentUri = null
        start(uri)
    }

    fun selectImageView(mode: Plate96ImageViewMode) {
        updateReady { it.copy(imageViewMode = mode) }
    }

    fun setShowOutlines(show: Boolean) {
        updateReady { it.copy(showOutlines = show) }
    }

    fun setShowLabels(show: Boolean) {
        updateReady { it.copy(showLabels = show) }
    }

    fun selectSite(siteIndex: Int?) {
        updateReady { it.copy(selectedSiteIndex = siteIndex) }
    }

    fun confirmCurrentOrientation() {
        val ready = _uiState.value as? Plate96LocalizationUiState.Ready ?: return
        applyOriginCorner(ready.session.result.orientation.originCorner)
    }

    fun applyOriginCorner(originCorner: ArrayOriginCorner) {
        val ready = _uiState.value as? Plate96LocalizationUiState.Ready ?: return
        val updatedSession = runCatching {
            locator.reorientSession(
                sourceWidth = ready.sourceBitmap.width,
                sourceHeight = ready.sourceBitmap.height,
                session = ready.session,
                originCorner = originCorner
            )
        }.getOrElse { return }
        val updatedNormalized = normalizedBitmapFor(ready.sourceBitmap, updatedSession.result.orientation)
        _uiState.value = ready.copy(
            normalizedBitmap = updatedNormalized,
            session = updatedSession,
            selectedSiteIndex = null,
            orientationConfirmed = true
        )
    }

    /** 恢复算法的初始方向建议；对称孔板仍会要求用户再次确认A1。 */
    fun restoreAutomaticOrientation() {
        val ready = _uiState.value as? Plate96LocalizationUiState.Ready ?: return
        val restoredSession = runCatching {
            locator.restoreAutomaticOrientation(
                sourceWidth = ready.sourceBitmap.width,
                sourceHeight = ready.sourceBitmap.height,
                session = ready.session
            )
        }.getOrElse { return }
        val restoredNormalized = normalizedBitmapFor(
            ready.sourceBitmap,
            restoredSession.result.orientation
        )
        _uiState.value = ready.copy(
            normalizedBitmap = restoredNormalized,
            session = restoredSession,
            selectedSiteIndex = null,
            orientationConfirmed = !restoredSession.orientationResolution.requiresOriginConfirmation
        )
    }

    /**
     * 按用户当前看到的图像方向移动选中孔位。
     *
     * 原图模式中的“向上”会先在原图坐标移动，再映射回标准图；因此竖拍图片不会出现
     * 按钮方向与屏幕视觉相反的问题。
     */
    fun nudgeSelectedSite(horizontalSteps: Int, verticalSteps: Int) {
        val ready = _uiState.value as? Plate96LocalizationUiState.Ready ?: return
        val siteIndex = ready.selectedSiteIndex ?: return
        val site = ready.session.result.sites.getOrNull(siteIndex) ?: return
        val stepPx = maxOf(MINIMUM_NUDGE_PX, (site.radiusPx ?: MINIMUM_NUDGE_PX) * NUDGE_RADIUS_RATIO)
        val requestedNormalizedCenter = if (ready.imageViewMode == Plate96ImageViewMode.NORMALIZED) {
            ArrayImagePoint(
                x = site.normalizedCenter.x + horizontalSteps * stepPx,
                y = site.normalizedCenter.y + verticalSteps * stepPx
            )
        } else {
            ArrayCoordinateTransformer.sourceToNormalized(
                point = ArrayImagePoint(
                    x = site.sourceCenter.x + horizontalSteps * stepPx,
                    y = site.sourceCenter.y + verticalSteps * stepPx
                ),
                transform = ready.session.result.imageTransform
            )
        }
        updateSelectedSiteGeometry(
            ready = ready,
            siteIndex = siteIndex,
            normalizedCenter = requestedNormalizedCenter,
            radiusPx = site.radiusPx ?: return
        )
    }

    /** 半径变化只改变选中孔，不会影响同一行列的其他孔位。 */
    fun setSelectedSiteRadius(radiusPx: Double) {
        val ready = _uiState.value as? Plate96LocalizationUiState.Ready ?: return
        val siteIndex = ready.selectedSiteIndex ?: return
        val site = ready.session.result.sites.getOrNull(siteIndex) ?: return
        updateSelectedSiteGeometry(
            ready = ready,
            siteIndex = siteIndex,
            normalizedCenter = site.normalizedCenter,
            radiusPx = radiusPx
        )
    }

    /** 恢复当前孔位的自动定位基线，其他孔位的微调保持不变。 */
    fun restoreSelectedSite() {
        val ready = _uiState.value as? Plate96LocalizationUiState.Ready ?: return
        val siteIndex = ready.selectedSiteIndex ?: return
        val restoredSession = runCatching {
            locator.restoreAutomaticSite(ready.session, siteIndex)
        }.getOrElse { return }
        _uiState.value = ready.copy(session = restoredSession)
    }

    fun setLocatorMode(mode: ArrayLocatorMode) {
        val ready = _uiState.value as? Plate96LocalizationUiState.Ready ?: return
        if (ready.locatorMode == mode) return
        localizationJob?.cancel()
        localizationJob = viewModelScope.launch {
            try {
                runLocalization(mode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.value = Plate96LocalizationUiState.Error(
                    Plate96LocalizationError.LOCALIZATION_FAILED
                )
            }
        }
    }

    private suspend fun runLocalization(mode: ArrayLocatorMode) {
        val source = sourceBitmap ?: return
        _uiState.value = Plate96LocalizationUiState.Loading(Plate96LocalizationStage.LOCATING)
        val config = Plate96Locator.DEFAULT_CONFIG.copy(mode = mode)
        val session = locator.localizeSession(source, config)
        _uiState.value = Plate96LocalizationUiState.Loading(Plate96LocalizationStage.PREPARING_PREVIEW)
        val normalized = normalizedBitmapFor(source, session.result.orientation)
        _uiState.value = Plate96LocalizationUiState.Ready(
            sourceBitmap = source,
            normalizedBitmap = normalized,
            session = session,
            imageViewMode = Plate96ImageViewMode.NORMALIZED,
            locatorMode = mode,
            showOutlines = true,
            showLabels = false,
            selectedSiteIndex = null,
            orientationConfirmed = !session.orientationResolution.requiresOriginConfirmation,
            exifRotationDegrees = exifRotationDegrees,
            exifFlipped = exifFlipped
        )
    }

    private fun updateReady(transform: (Plate96LocalizationUiState.Ready) -> Plate96LocalizationUiState.Ready) {
        val ready = _uiState.value as? Plate96LocalizationUiState.Ready ?: return
        _uiState.value = transform(ready)
    }

    private fun updateSelectedSiteGeometry(
        ready: Plate96LocalizationUiState.Ready,
        siteIndex: Int,
        normalizedCenter: ArrayImagePoint,
        radiusPx: Double
    ) {
        val adjustedSession = runCatching {
            locator.adjustSite(
                session = ready.session,
                siteIndex = siteIndex,
                normalizedCenter = normalizedCenter,
                radiusPx = radiusPx
            )
        }.getOrElse { return }
        _uiState.value = ready.copy(session = adjustedSession)
    }

    private fun replaceSourceBitmap(bitmap: Bitmap) {
        normalizedBitmapCache.values.distinct().forEach { cached ->
            cached.takeIf { it !== sourceBitmap && !it.isRecycled }?.recycle()
        }
        normalizedBitmapCache.clear()
        sourceBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        sourceBitmap = bitmap
    }

    private fun normalizedBitmapFor(
        source: Bitmap,
        orientation: ArrayOrientationSnapshot
    ): Bitmap {
        val key = NormalizationKey(orientation.rotation, orientation.mirrored)
        return normalizedBitmapCache.getOrPut(key) {
            Plate96BitmapNormalizer.normalize(source, orientation)
        }
    }

    override fun onCleared() {
        localizationJob?.cancel()
        normalizedBitmapCache.values.distinct().forEach { bitmap ->
            bitmap.takeIf { it !== sourceBitmap && !it.isRecycled }?.recycle()
        }
        normalizedBitmapCache.clear()
        sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
        sourceBitmap = null
        super.onCleared()
    }

    private data class NormalizationKey(
        val rotation: ArrayQuarterTurn,
        val mirrored: Boolean
    )

    private companion object {
        const val MINIMUM_NUDGE_PX: Double = 1.0
        const val NUDGE_RADIUS_RATIO: Double = 0.08
    }
}

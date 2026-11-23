package com.muc.fluocolorquant.ui.viewmodels

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisPreferences
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisScope
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSnapshot
import com.muc.fluocolorquant.domain.detection.array.ArrayOriginCorner
import com.muc.fluocolorquant.domain.detection.array.ArrayCoordinateTransformer
import com.muc.fluocolorquant.domain.detection.array.ArrayImagePoint
import com.muc.fluocolorquant.domain.detection.array.ArrayQuarterTurn
import com.muc.fluocolorquant.domain.detection.plate96.Plate96BitmapNormalizer
import com.muc.fluocolorquant.domain.detection.plate96.Plate96Locator
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.ManualGridGeometry
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocator
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocatorConfig
import com.muc.fluocolorquant.domain.detection.grid.RegularGridGeometry
import com.muc.fluocolorquant.domain.detection.grid.compareOnImage
import com.muc.fluocolorquant.utils.image.ScientificBitmapLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    data class LocatingArray(val attempt: ArrayLocatorAttempt) : Plate96LocalizationUiState

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
        val exifFlipped: Boolean,
        val analysisPreferences: ArrayAnalysisPreferences = ArrayAnalysisPreferences()
    ) : Plate96LocalizationUiState

    data class Manual(val draft: GridDetectionUiState.ManualCorners) : Plate96LocalizationUiState

    data class Error(val reason: Plate96LocalizationError) : Plate96LocalizationUiState
}

/**
 * 用户确认定位后移交给统一布局与定量工作台的内存对象。
 *
 * Bitmap与定位会话均来自同一次算法运行，接收方不得重新解码图片或重新定位，否则EXIF、
 * 手动微调和最终科学采样可能发生漂移。
 */
data class Plate96LocalizationSelection(
    val sourceBitmap: Bitmap,
    val normalizedBitmap: Bitmap,
    val session: Plate96Locator.Session,
    val exifRotationDegrees: Int,
    val exifFlipped: Boolean,
    val analysisPreferences: ArrayAnalysisPreferences = ArrayAnalysisPreferences()
)

/**
 * 新96孔板定位状态机。
 *
 * 切换算法会取消旧任务；A1确认只重建坐标，不重复模型推理；页面重组不会重新运行定位。
 */
@HiltViewModel
class Plate96LocalizationViewModel @Inject constructor(
    private val bitmapLoader: ScientificBitmapLoader,
    private val locator: Plate96Locator,
    private val sharedGridLocator: PgGridLocator
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
    private var preferences = ArrayAnalysisPreferences()
    private var beforeManual: Plate96LocalizationUiState? = null
    /** 显式恢复时保留原 A1 确认、微调和取值。失败或对照不会覆写这个正式会话。 */
    private var previousReady: Plate96LocalizationUiState.Ready? = null
    private var manualPreviewJob: Job? = null
    private var manualNormalizedBitmap: Bitmap? = null

    fun start(imageUri: String?, initialPreferences: ArrayAnalysisPreferences = ArrayAnalysisPreferences()) {
        if (imageUri.isNullOrBlank()) {
            _uiState.value = Plate96LocalizationUiState.Error(Plate96LocalizationError.IMAGE_LOAD_FAILED)
            return
        }
        if (currentUri == imageUri && (sourceBitmap != null || localizationJob?.isActive == true)) return
        currentUri = imageUri
        previousReady = null
        beforeManual = null
        preferences = initialPreferences.normalized(ArrayAnalysisScope.STANDARD_PLATE)
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
                runLocalization(preferences.locator)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                publishLocalizationError(error)
            } catch (error: LinkageError) {
                publishLocalizationError(error)
            }
        }
    }

    fun retry() {
        val uri = currentUri ?: return
        when (val current = _uiState.value) {
            is Plate96LocalizationUiState.LocatingArray -> {
                if (current.attempt.status == ArrayLocatorAttemptStatus.Running) return
                if (current.attempt.mode.isComparisonOnly) launchComparison(current.attempt.bitmap, current.attempt.mode)
                else launchLocalization(current.attempt.bitmap, current.attempt.mode)
            }
            is Plate96LocalizationUiState.Error -> {
                currentUri = null
                start(uri, preferences)
            }
            else -> Unit
        }
    }

    /** 只在Ready状态生成移交对象；返回值共享当前会话Bitmap，不复制大图。 */
    fun currentSelection(): Plate96LocalizationSelection? {
        val ready = _uiState.value as? Plate96LocalizationUiState.Ready ?: return null
        if (!ready.orientationConfirmed || ready.session.locatorMode.isComparisonOnly) return null
        return Plate96LocalizationSelection(
            sourceBitmap = ready.sourceBitmap,
            normalizedBitmap = ready.normalizedBitmap,
            session = ready.session,
            exifRotationDegrees = ready.exifRotationDegrees,
            exifFlipped = ready.exifFlipped,
            analysisPreferences = ready.analysisPreferences
        )
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
        val current = _uiState.value
        val source = sourceBitmap ?: return
        if (mode !in ArrayAnalysisScope.STANDARD_PLATE.localizationChoices) return
        when (current) {
            is Plate96LocalizationUiState.Ready -> {
                if (current.locatorMode == mode) return
                previousReady = current
            }
            is Plate96LocalizationUiState.LocatingArray -> {
                if (current.attempt.status == ArrayLocatorAttemptStatus.Running || current.attempt.mode == mode) return
                if (previousReady?.locatorMode == mode) {
                    restorePreviousLocalization()
                    return
                }
            }
            else -> return
        }
        if (mode.isComparisonOnly) launchComparison(source, mode) else launchLocalization(source, mode)
    }

    fun restorePreviousLocalization() {
        val attempt = (_uiState.value as? Plate96LocalizationUiState.LocatingArray)?.attempt ?: return
        if (attempt.status == ArrayLocatorAttemptStatus.Running) return
        val ready = previousReady ?: return
        preferences = ready.analysisPreferences
        _uiState.value = ready
        previousReady = null
    }

    private fun locatorAttempt(source: Bitmap, mode: ArrayLocatorMode, status: ArrayLocatorAttemptStatus) =
        Plate96LocalizationUiState.LocatingArray(ArrayLocatorAttempt(source, ArrayAnalysisScope.STANDARD_PLATE,
            mode, status, previousReady?.locatorMode))

    private fun launchComparison(source: Bitmap, mode: ArrayLocatorMode) {
        localizationJob?.cancel()
        _uiState.value = locatorAttempt(source, mode, ArrayLocatorAttemptStatus.Running)
        localizationJob = viewModelScope.launch {
            val status = try {
                // 共用芯片的斑点/聚类内核。这里不构造圆孔半径，不猜 A1，也不运行 YOLO。
                val plate = Plate96Locator.DEFAULT_CONFIG
                sharedGridLocator.compareOnImage(source, plate.canonicalRows, plate.canonicalColumns, mode, allowTransposed = true)
                    ?.let { ArrayLocatorAttemptStatus.Preview(it) } ?: ArrayLocatorAttemptStatus.NoGrid
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                android.util.Log.w("Plate96Localization", "${mode.name} 对照执行失败", error)
                ArrayLocatorAttemptStatus.Failed
            } catch (error: LinkageError) {
                android.util.Log.w("Plate96Localization", "对照定位组件不可用", error)
                ArrayLocatorAttemptStatus.Failed
            }
            _uiState.value = locatorAttempt(source, mode, status)
        }
    }

    private fun launchLocalization(source: Bitmap, mode: ArrayLocatorMode) {
        localizationJob?.cancel()
        _uiState.value = locatorAttempt(source, mode, ArrayLocatorAttemptStatus.Running)
        localizationJob = viewModelScope.launch {
            try {
                runLocalization(mode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                publishLocalizationError(error)
            } catch (error: LinkageError) {
                publishLocalizationError(error)
            }
        }
    }

    private fun publishLocalizationError(error: Throwable) {
        android.util.Log.w("Plate96Localization", "孔板处理没有完成", error)
        val current = _uiState.value as? Plate96LocalizationUiState.LocatingArray
        _uiState.value = current?.let { it.copy(attempt = it.attempt.copy(status = ArrayLocatorAttemptStatus.Failed)) }
            ?: Plate96LocalizationUiState.Error(Plate96LocalizationError.IMAGE_LOAD_FAILED)
    }

    fun setAnalysisPreferences(value: ArrayAnalysisPreferences) {
        updateReady { ready ->
            preferences = value.normalized(ArrayAnalysisScope.STANDARD_PLATE).copy(locator = preferences.locator)
            ready.copy(analysisPreferences = preferences)
        }
    }

    fun openManualCorners() {
        val current = _uiState.value
        val bitmap = sourceBitmap ?: return
        if (current !is Plate96LocalizationUiState.Ready &&
            !(current is Plate96LocalizationUiState.LocatingArray && current.attempt.status != ArrayLocatorAttemptStatus.Running)) return
        beforeManual = current
        val previous = ((current as? Plate96LocalizationUiState.Ready) ?: previousReady)
            ?.session?.takeIf { it.locatorMode == ArrayLocatorMode.MANUAL_CORNERS }
        val anchorIndices = listOf(0, 11, 95, 84)
        val anchors = previous?.result?.sites?.let { sites -> anchorIndices.map { index ->
            sites[index].sourceCenter.let { GridPoint(it.x, it.y) }
        } }.orEmpty()
        val ratio = previous?.result?.sites?.let { sites ->
            val pitch = RegularGridGeometry.estimatePitch(sites.map { GridPoint(it.normalizedCenter.x, it.normalizedCenter.y) }, 8, 12)
            (2 * requireNotNull(sites.first().radiusPx) / minOf(pitch.horizontalPx, pitch.verticalPx)).coerceIn(.26, .84)
        } ?: .66
        updateManual(GridDetectionUiState.ManualCorners(bitmap, 8, 12, anchors = anchors,
            diameterRatio = ratio, diameterConfirmed = previous != null))
    }

    fun addManualCorner(point: GridPoint) = editManual { draft ->
        if (draft.anchors.size >= 4 || point.x !in 0.0..(draft.bitmap.width-1.0) || point.y !in 0.0..(draft.bitmap.height-1.0)) draft
        else draft.copy(anchors = draft.anchors + point, diameterConfirmed = false)
    }

    fun undoManualCorner() = editManual { it.copy(anchors = it.anchors.dropLast(1), diameterConfirmed = false) }
    fun clearManualCorners() = editManual { it.copy(anchors = emptyList(), diameterConfirmed = false) }
    fun setManualDiameter(value: Double) = editManual { it.copy(diameterRatio = value.coerceIn(.26, .84), diameterConfirmed = false) }
    fun confirmManualDiameter() {
        val state = _uiState.value as? Plate96LocalizationUiState.Manual ?: return
        if (state.draft.previewSites.isNotEmpty()) _uiState.value = state.copy(draft = state.draft.copy(diameterConfirmed = true))
    }

    fun cancelManual() {
        if (_uiState.value !is Plate96LocalizationUiState.Manual) return
        manualPreviewJob?.cancel()
        beforeManual?.let { _uiState.value = it }
        beforeManual = null
    }

    private fun editManual(change: (GridDetectionUiState.ManualCorners) -> GridDetectionUiState.ManualCorners) {
        val current = (_uiState.value as? Plate96LocalizationUiState.Manual)?.draft ?: return
        val next = change(current)
        if (next != current) updateManual(next)
    }

    private fun updateManual(input: GridDetectionUiState.ManualCorners) {
        val draft = input.copy(previewSites = emptyList(), rimPreviews = emptyList(), applyFailed = false)
        val state = Plate96LocalizationUiState.Manual(draft)
        _uiState.value = state
        manualPreviewJob?.cancel()
        if (draft.anchors.size != 4) return
        manualPreviewJob = viewModelScope.launch {
            val enriched = withContext(Dispatchers.Default) {
                try {
                    val grid = ManualGridGeometry.build(draft.bitmap.width, draft.bitmap.height,
                        PgGridLocatorConfig(8, 12, GridTargetPolarity.DARK), draft.anchors)
                    val pitch = RegularGridGeometry.estimatePitch(grid.sites.map { it.rectified }, 8, 12)
                    val radius = minOf(pitch.horizontalPx, pitch.verticalPx) * requireNotNull(draft.diameterRatio) / 2
                    val inverse = grid.homography.inverse.toDoubleArray()
                    draft.copy(previewSites = grid.sites.map { it.original },
                        rimPreviews = grid.sites.map { ManualGridGeometry.circleOutline(it.rectified, radius, inverse) })
                } catch (_: IllegalArgumentException) { draft.copy(applyFailed = true) }
            }
            // 撤销或快速拖动内径后，旧的投影计算不能覆盖新的角点草稿。
            if (_uiState.value === state) _uiState.value = Plate96LocalizationUiState.Manual(enriched)
        }
    }

    fun applyManualCorners() {
        val state = (_uiState.value as? Plate96LocalizationUiState.Manual)?.draft ?: return
        if (!state.diameterConfirmed || state.previewSites.isEmpty()) return
        _uiState.value = Plate96LocalizationUiState.Loading(Plate96LocalizationStage.PREPARING_PREVIEW)
        localizationJob = viewModelScope.launch {
            try {
                val (session, preview) = withContext(Dispatchers.Default) {
                    val manual = locator.localizeManual(state.bitmap, state.anchors, requireNotNull(state.diameterRatio))
                    manual to Plate96BitmapNormalizer.normalizePerspective(state.bitmap, manual.result.imageTransform)
                }
                manualNormalizedBitmap?.recycle()
                manualNormalizedBitmap = preview
                beforeManual = null
                previousReady = null
                _uiState.value = ready(state.bitmap, preview, session, Plate96ImageViewMode.ORIGINAL)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _uiState.value = Plate96LocalizationUiState.Manual(state.copy(applyFailed = true))
            } catch (_: LinkageError) {
                _uiState.value = Plate96LocalizationUiState.Manual(state.copy(applyFailed = true))
            }
        }
    }

    private suspend fun runLocalization(mode: ArrayLocatorMode) {
        val source = sourceBitmap ?: return
        _uiState.value = locatorAttempt(source, mode, ArrayLocatorAttemptStatus.Running)
        val config = Plate96Locator.DEFAULT_CONFIG.copy(mode = mode)
        val session = withContext(Dispatchers.Default) { locator.localizeSession(source, config) }
        val normalized = normalizedBitmapFor(source, session.result.orientation)
        val previousView = previousReady?.imageViewMode ?: Plate96ImageViewMode.NORMALIZED
        preferences = preferences.copy(locator = mode)
        _uiState.value = ready(source, normalized, session, previousView)
        previousReady = null
    }

    private fun ready(source: Bitmap, normalized: Bitmap, session: Plate96Locator.Session, view: Plate96ImageViewMode) =
        Plate96LocalizationUiState.Ready(
            sourceBitmap = source,
            normalizedBitmap = normalized,
            session = session,
            imageViewMode = view,
            locatorMode = session.locatorMode,
            showOutlines = true,
            showLabels = false,
            selectedSiteIndex = null,
            orientationConfirmed = !session.orientationResolution.requiresOriginConfirmation,
            exifRotationDegrees = exifRotationDegrees,
            exifFlipped = exifFlipped,
            analysisPreferences = preferences
        )

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
        manualNormalizedBitmap?.recycle()
        manualNormalizedBitmap = null
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
        manualPreviewJob?.cancel()
        manualNormalizedBitmap?.recycle()
        manualNormalizedBitmap = null
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

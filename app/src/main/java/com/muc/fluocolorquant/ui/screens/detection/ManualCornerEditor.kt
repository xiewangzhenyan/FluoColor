package com.muc.fluocolorquant.ui.screens.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.ui.components.ArrayPercentSlider
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.components.FluoSectionHeader
import com.muc.fluocolorquant.ui.components.FluoStatusChip
import com.muc.fluocolorquant.ui.screens.result.array.calculateArrayImageFitTransform
import com.muc.fluocolorquant.ui.theme.FluoMotion
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.theme.FluoTheme
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 借鉴 GenePix Pro（手册 p.16, 51）的纯显示层图像对比度模式。
 *
 * 所有变换只作用于界面缩略副本，绝不修改参与定位、分割和光度采样的原始 Bitmap。
 */
internal enum class ArrayDisplayFilterMode {
    ORIGINAL,
    SQRT_BOOST,
    INVERT
}

/** 缩小的位图只用于显示；画布上的坐标仍以未增强的原图尺寸为基准。 */
@Composable
internal fun rememberArrayDisplayImage(
    source: Bitmap,
    filter: ArrayDisplayFilterMode = ArrayDisplayFilterMode.ORIGINAL
): State<ImageBitmap?> = produceState<ImageBitmap?>(null, source, filter) {
    value = withContext(Dispatchers.Default) {
        val scale = minOf(1.0, 2000.0 / maxOf(source.width, source.height))
        val scaled = if (scale == 1.0) {
            source
        } else {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        }
        val filtered = applyDisplayFilter(scaled, filter)
        if (scaled !== source && scaled !== filtered) {
            scaled.recycle()
        }
        filtered.asImageBitmap()
    }
}

/** 从本地证据文件路径加载纯显示增强位图；文件不存在时返回空并交由外层回退默认视图。 */
@Composable
internal fun rememberFilteredFileImage(
    filePath: String?,
    filter: ArrayDisplayFilterMode,
    revision: Int = 0
): State<ImageBitmap?> = produceState<ImageBitmap?>(null, filePath, filter, revision) {
    if (filePath.isNullOrBlank() || filter == ArrayDisplayFilterMode.ORIGINAL) {
        value = null
        return@produceState
    }
    value = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) return@withContext null
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
        try {
            val scale = minOf(1.0, 1600.0 / maxOf(decoded.width, decoded.height))
            val scaled = if (scale == 1.0) {
                decoded
            } else {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).roundToInt().coerceAtLeast(1),
                    (decoded.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
            }
            val enhanced = applyDisplayFilter(scaled, filter)
            if (scaled !== decoded && scaled !== enhanced) scaled.recycle()
            val result = enhanced.asImageBitmap()
            result
        } finally {
            if (filter != ArrayDisplayFilterMode.ORIGINAL) decoded.recycle()
        }
    }
}

/**
 * 对显示副本执行 GenePix 风格平方根提亮（`√I` + 稳健分位拉伸）或反色变换。
 *
 * 256 级查找表在毫秒级完成映射，使暗场弱荧光孔与芯片暗角清晰可辨，且不污染定量原图。
 */
internal fun applyDisplayFilter(source: Bitmap, filter: ArrayDisplayFilterMode): Bitmap {
    if (filter == ArrayDisplayFilterMode.ORIGINAL) return source
    val width = source.width
    val height = source.height
    if (width <= 0 || height <= 0) return source
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    val lut = IntArray(256)
    when (filter) {
        ArrayDisplayFilterMode.ORIGINAL -> return source
        ArrayDisplayFilterMode.INVERT -> {
            for (v in 0..255) lut[v] = 255 - v
        }
        ArrayDisplayFilterMode.SQRT_BOOST -> {
            val histogram = IntArray(256)
            for (argb in pixels) {
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                histogram[maxOf(r, g, b)]++
            }
            val total = pixels.size.coerceAtLeast(1)
            val lowTarget = (total * 0.01).toInt()
            val highTarget = (total * 0.995).toInt()
            var cumulative = 0
            var low = 0
            var high = 255
            for (i in 0..255) {
                cumulative += histogram[i]
                if (cumulative <= lowTarget) low = i
                if (cumulative >= highTarget) {
                    high = i
                    break
                }
            }
            val span = max(16, high - low).toDouble()
            for (v in 0..255) {
                val normalized = ((v - low) / span).coerceIn(0.0, 1.0)
                lut[v] = (255.0 * sqrt(normalized)).roundToInt().coerceIn(0, 255)
            }
        }
    }

    for (i in pixels.indices) {
        val argb = pixels[i]
        val a = argb and -0x1000000
        val r = lut[(argb shr 16) and 0xFF]
        val g = lut[(argb shr 8) and 0xFF]
        val b = lut[argb and 0xFF]
        pixels[i] = a or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/** 紧凑的纯显示对比度控制条，适配 360dp 窄屏与 1.3 倍大字体。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArrayDisplayFilterBar(
    selected: ArrayDisplayFilterMode,
    onSelect: (ArrayDisplayFilterMode) -> Unit,
    modifier: Modifier = Modifier,
    trailingAction: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(end = 2.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.BrightnessMedium,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.array_display_filter_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
            ArrayDisplayFilterMode.entries.forEach { mode ->
                val isSelected = mode == selected
                val containerColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
                    },
                    animationSpec = FluoMotion.micro(),
                    label = "displayFilterBg"
                )
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = FluoMotion.micro(),
                    label = "displayFilterFg"
                )
                Surface(
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = containerColor,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clickable { onSelect(mode) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = stringResource(
                                when (mode) {
                                    ArrayDisplayFilterMode.ORIGINAL -> R.string.array_display_filter_raw
                                    ArrayDisplayFilterMode.SQRT_BOOST -> R.string.array_display_filter_sqrt
                                    ArrayDisplayFilterMode.INVERT -> R.string.array_display_filter_invert
                                }
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        trailingAction?.invoke()
    }
}

/** 用户点的是四个已知编号的角孔中心，不是外框。只做坐标交互，正反单应及全格预览从领域层读取。 */
@Composable
internal fun ManualCornerEditor(
    state: GridDetectionUiState.ManualCorners,
    onPoint: (GridPoint) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onApply: () -> Unit,
    onDiameterChange: (Double) -> Unit = {},
    onConfirmDiameter: () -> Unit = {}
) {
    val labels = listOf(
        siteCoordinateLabel(0, 0),
        siteCoordinateLabel(0, state.columns - 1),
        siteCoordinateLabel(state.rows - 1, state.columns - 1),
        siteCoordinateLabel(state.rows - 1, 0)
    )
    val instruction = when {
        state.applyFailed -> stringResource(
            if (state.previewSites.isEmpty()) R.string.grid_manual_bad_shape else R.string.grid_manual_failed
        )
        state.anchors.size < 4 -> stringResource(
            R.string.grid_manual_next,
            state.anchors.size + 1,
            labels[state.anchors.size]
        )
        state.previewSites.isEmpty() -> stringResource(R.string.grid_manual_projecting)
        else -> stringResource(R.string.grid_manual_review, state.rows, state.columns)
    }
    var displayFilter by rememberSaveable { mutableStateOf(ArrayDisplayFilterMode.ORIGINAL) }
    val image by rememberArrayDisplayImage(state.bitmap, displayFilter)
    val markPoint by rememberUpdatedState(onPoint)
    var zoom by remember(state.bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(state.bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var reviewingZoom by remember { mutableStateOf(false) }

    fun fitGrid() {
        val fit = calculateArrayImageFitTransform(
            viewport.width.toFloat(),
            viewport.height.toFloat(),
            state.bitmap.width.toFloat(),
            state.bitmap.height.toFloat()
        ) ?: return
        if (state.anchors.size != 4) return
        val points = state.anchors.map { fit.map(it.x + 0.5, it.y + 0.5) }
        val left = points.minOf { it.x }
        val right = points.maxOf { it.x }
        val top = points.minOf { it.y }
        val bottom = points.maxOf { it.y }
        // 复核时给芯片和角孔标签留出空白；避免原图中占比很小的芯片只剩一团标记。
        zoom = minOf(
            viewport.width * 0.78f / max(1f, right - left),
            viewport.height * 0.78f / max(1f, bottom - top)
        ).coerceIn(1f, 6f)
        pan = Offset(
            (viewport.width / 2f - (left + right) * zoom / 2).coerceIn(viewport.width * (1f - zoom), 0f),
            (viewport.height / 2f - (top + bottom) * zoom / 2).coerceIn(viewport.height * (1f - zoom), 0f)
        )
    }

    LaunchedEffect(state.previewSites.isNotEmpty(), viewport) {
        if (state.previewSites.isNotEmpty()) {
            fitGrid()
            reviewingZoom = true
        } else if (reviewingZoom) {
            zoom = 1f
            pan = Offset.Zero
            reviewingZoom = false
        }
    }

    val numberPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            isFakeBoldText = true
        }
    }
    val badgeBgPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(205, 18, 28, 38)
            style = Paint.Style.FILL
        }
    }
    val badgeStrokePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(220, 255, 205, 117)
            style = Paint.Style.STROKE
        }
    }

    val isReady = state.previewSites.isNotEmpty() && !state.applyFailed
    val statusAccent = when {
        state.applyFailed -> MaterialTheme.colorScheme.error
        isReady -> FluoTheme.semantic.success
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FluoSpacing.lg, vertical = FluoSpacing.md),
        verticalArrangement = Arrangement.spacedBy(FluoSpacing.md)
    ) {
        // 1. 顶部引导与顺时针四角进度卡
        FluoSectionCard(
            contentPadding = FluoSpacing.md,
            verticalSpacing = FluoSpacing.sm,
            accentColor = statusAccent
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = statusAccent.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.CropFree,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = statusAccent
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.grid_manual_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val (statusText, statusIcon, statusBg, statusFg) = when {
                    state.applyFailed -> ManualStatusVisual(
                        text = stringResource(R.string.grid_manual_status_error),
                        icon = Icons.Default.ErrorOutline,
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer
                    )
                    isReady -> ManualStatusVisual(
                        text = stringResource(R.string.grid_manual_status_ready),
                        icon = Icons.Default.CheckCircle,
                        container = FluoTheme.semantic.successContainer,
                        content = FluoTheme.semantic.onSuccessContainer
                    )
                    else -> ManualStatusVisual(
                        text = stringResource(R.string.grid_manual_status_progress, state.anchors.size),
                        icon = Icons.Outlined.CropFree,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                FluoStatusChip(
                    text = statusText,
                    icon = statusIcon,
                    contentColor = statusFg,
                    containerColor = statusBg
                )
            }

            // 四步顺时针角孔指示条：明确当前待点角孔与已完成状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                labels.forEachIndexed { index, cornerLabel ->
                    val completed = index < state.anchors.size
                    val active = index == state.anchors.size && !state.applyFailed
                    val chipBg by animateColorAsState(
                        targetValue = when {
                            completed -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        animationSpec = FluoMotion.micro(),
                        label = "cornerStepBg"
                    )
                    val chipBorder by animateColorAsState(
                        targetValue = when {
                            active -> MaterialTheme.colorScheme.primary
                            completed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                        animationSpec = FluoMotion.micro(),
                        label = "cornerStepBorder"
                    )
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(FluoRadius.badge),
                        color = chipBg,
                        border = BorderStroke(if (active) 1.5.dp else 1.dp, chipBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (completed) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(3.dp))
                            }
                            Text(
                                text = stringResource(R.string.grid_manual_corner_step, index + 1, cornerLabel),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active || completed) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    active || completed -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Text(
                text = instruction,
                color = if (state.applyFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (state.previewSites.isEmpty() && !state.applyFailed) {
                Text(
                    text = stringResource(R.string.grid_manual_help, labels.joinToString(" → ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 2. 仪器级暗底视口卡（顶部纯显示提亮工具栏 + 零内边距坐标对齐画布）
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(FluoRadius.card),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ArrayDisplayFilterBar(
                    selected = displayFilter,
                    onSelect = { displayFilter = it },
                    trailingAction = {
                        if (state.previewSites.isNotEmpty()) {
                            FilledTonalIconButton(
                                onClick = {
                                    if (zoom > 1.01f) {
                                        zoom = 1f
                                        pan = Offset.Zero
                                    } else {
                                        fitGrid()
                                    }
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ZoomOutMap,
                                    contentDescription = stringResource(
                                        if (zoom > 1.01f) R.string.grid_manual_full_image else R.string.grid_manual_focus
                                    ),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )
                if (displayFilter != ArrayDisplayFilterMode.ORIGINAL) {
                    Text(
                        text = stringResource(R.string.array_display_filter_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                val maxImageHeight = LocalConfiguration.current.screenHeightDp.dp * 0.44f
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // 画布本身不加内边距，确保触控坐标与原图像素严格一一映射。
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(minOf(maxWidth, maxImageHeight))
                            .clip(RoundedCornerShape(FluoRadius.control))
                            .onSizeChanged { viewport = it }
                            .clipToBounds()
                            .background(Color(0xFF141C24))
                            .testTag("grid_manual_canvas")
                            .semantics { contentDescription = instruction }
                            .pointerInput(state.bitmap) {
                                detectTransformGestures { focus, delta, factor, _ ->
                                    val newZoom = (zoom * factor).coerceIn(1f, 6f)
                                    val next = focus + (pan - focus) * (newZoom / zoom) + delta
                                    pan = Offset(
                                        next.x.coerceIn(size.width * (1f - newZoom), 0f),
                                        next.y.coerceIn(size.height * (1f - newZoom), 0f)
                                    )
                                    zoom = newZoom
                                }
                            }
                            .pointerInput(state.bitmap, image) {
                                detectTapGestures { tap ->
                                    if (image == null) return@detectTapGestures
                                    val fit = calculateArrayImageFitTransform(
                                        size.width.toFloat(),
                                        size.height.toFloat(),
                                        state.bitmap.width.toFloat(),
                                        state.bitmap.height.toFloat()
                                    ) ?: return@detectTapGestures
                                    val base = (tap - pan) / zoom
                                    val pixelX = (base.x - fit.left) / fit.scale - 0.5
                                    val pixelY = (base.y - fit.top) / fit.scale - 0.5
                                    // Fit 的留白不是照片。只把真实边缘半像素归到边缘像素，绝不把留白点击夹到角点。
                                    if (pixelX >= -0.5 && pixelY >= -0.5 &&
                                        pixelX < state.bitmap.width - 0.5 && pixelY < state.bitmap.height - 0.5
                                    ) {
                                        markPoint(
                                            GridPoint(
                                                pixelX.coerceIn(0.0, state.bitmap.width - 1.0),
                                                pixelY.coerceIn(0.0, state.bitmap.height - 1.0)
                                            )
                                        )
                                    }
                                }
                            }
                    ) {
                        val bitmap = image ?: return@Canvas
                        val fit = calculateArrayImageFitTransform(
                            size.width,
                            size.height,
                            state.bitmap.width.toFloat(),
                            state.bitmap.height.toFloat()
                        ) ?: return@Canvas
                        val origin = pan + Offset(fit.left, fit.top) * zoom
                        val scale = fit.scale * zoom
                        drawImage(
                            bitmap,
                            dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                            dstSize = IntSize(
                                (state.bitmap.width * scale).roundToInt().coerceAtLeast(1),
                                (state.bitmap.height * scale).roundToInt().coerceAtLeast(1)
                            )
                        )
                        fun display(point: GridPoint) =
                            origin + Offset((point.x + 0.5).toFloat(), (point.y + 0.5).toFloat()) * scale

                        val anchors = state.anchors.map(::display)
                        if (anchors.size == 4) {
                            val visiblePitch = minOf(
                                (anchors[1] - anchors[0]).getDistance() / max(1, state.columns - 1),
                                (anchors[3] - anchors[0]).getDistance() / max(1, state.rows - 1)
                            )
                            val radius = (visiblePitch * 0.25f).coerceIn(0.8f, 3.dp.toPx())
                            // 空心标记随孔距缩小，原图缩略时也不以实心色块遮住需要核对的孔中心。
                            val stroke = Stroke(minOf(1.dp.toPx(), radius / 2f))
                            state.previewSites.forEach { point ->
                                drawCircle(Color(0xFF78DCE7), radius, display(point), style = stroke)
                            }
                            state.rimPreviews.forEach { rim ->
                                rim.forEachIndexed { index, point ->
                                    drawLine(
                                        Color(0xFF78DCE7),
                                        display(point),
                                        display(rim[(index + 1) % rim.size]),
                                        0.75.dp.toPx()
                                    )
                                }
                            }
                        }

                        val edgeColor = Color(0xFFFFCD75)
                        anchors.forEachIndexed { index, position ->
                            if (index > 0) drawLine(edgeColor, anchors[index - 1], position, 1.1.dp.toPx())
                            if (index == 3) drawLine(edgeColor, position, anchors.first(), 1.1.dp.toPx())
                            // 双环准星靶标：不遮挡角孔正中心像素，方便肉眼核对准度
                            val outerR = 6.dp.toPx()
                            val crossR = 3.dp.toPx()
                            drawCircle(Color(0x55141C24), outerR + 1.dp.toPx(), position)
                            drawCircle(edgeColor, outerR, position, style = Stroke(1.6.dp.toPx()))
                            drawLine(edgeColor, position - Offset(crossR, 0f), position + Offset(crossR, 0f), 1.dp.toPx())
                            drawLine(edgeColor, position - Offset(0f, crossR), position + Offset(0f, crossR), 1.dp.toPx())

                            numberPaint.textSize = 12.dp.toPx()
                            val text = labels[index]
                            val labelWidth = numberPaint.measureText(text)
                            val padH = 5.dp.toPx()
                            val padV = 3.dp.toPx()
                            val pillW = labelWidth + padH * 2
                            val pillH = numberPaint.textSize + padV * 2
                            val rawLeft = if (position.x < size.width / 2f) {
                                position.x - 8.dp.toPx() - pillW
                            } else {
                                position.x + 8.dp.toPx()
                            }
                            val rawTop = if (position.y < size.height / 2f) {
                                position.y - 8.dp.toPx() - pillH
                            } else {
                                position.y + 8.dp.toPx()
                            }
                            if (position.x in 0f..size.width && position.y in 0f..size.height) {
                                val left = rawLeft.coerceIn(2.dp.toPx(), (size.width - pillW - 2.dp.toPx()).coerceAtLeast(2.dp.toPx()))
                                val top = rawTop.coerceIn(2.dp.toPx(), (size.height - pillH - 2.dp.toPx()).coerceAtLeast(2.dp.toPx()))
                                val rect = RectF(left, top, left + pillW, top + pillH)
                                badgeStrokePaint.strokeWidth = 0.8.dp.toPx()
                                val cornerR = 4.dp.toPx()
                                drawContext.canvas.nativeCanvas.drawRoundRect(rect, cornerR, cornerR, badgeBgPaint)
                                drawContext.canvas.nativeCanvas.drawRoundRect(rect, cornerR, cornerR, badgeStrokePaint)
                                drawContext.canvas.nativeCanvas.drawText(
                                    text,
                                    left + padH,
                                    top + pillH - padV - 2.dp.toPx(),
                                    numberPaint
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. 圆孔内径确认卡（仅圆孔阵列出现）
        if (state.diameterRatio != null && state.anchors.size == 4) {
            FluoSectionCard(contentPadding = FluoSpacing.md, verticalSpacing = FluoSpacing.sm) {
                Text(
                    text = stringResource(R.string.array_manual_diameter_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ArrayPercentSlider(
                    R.string.array_manual_diameter_percent,
                    state.diameterRatio,
                    26..84,
                    onChange = onDiameterChange
                )
                FilterChip(
                    selected = state.diameterConfirmed,
                    onClick = onConfirmDiameter,
                    enabled = state.previewSites.isNotEmpty(),
                    leadingIcon = if (state.diameterConfirmed) {
                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    } else null,
                    label = { Text(stringResource(R.string.array_manual_diameter_confirm)) },
                    modifier = Modifier.testTag("grid_manual_confirm_diameter")
                )
            }
        }

        // 4. 统一圆角规范的操作按钮区（保留在滚动列内以兼容小屏横屏与 UI 滚动测试）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onUndo,
                enabled = state.anchors.isNotEmpty(),
                shape = RoundedCornerShape(FluoRadius.control),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("grid_manual_undo")
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.grid_manual_undo), maxLines = 1)
            }
            OutlinedButton(
                onClick = onClear,
                enabled = state.anchors.isNotEmpty(),
                shape = RoundedCornerShape(FluoRadius.control),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.grid_manual_clear), maxLines = 1)
            }
        }

        Button(
            onClick = onApply,
            enabled = state.previewSites.isNotEmpty() &&
                (state.diameterRatio == null || state.diameterConfirmed),
            shape = RoundedCornerShape(FluoRadius.control),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag("grid_manual_apply")
        ) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.grid_manual_apply), fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class ManualStatusVisual(
    val text: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val container: Color,
    val content: Color
)

package com.muc.fluocolorquant.ui.screens.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBitmapCropper
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutAssignmentDraft
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationAnalyte
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationPreview
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationSitePreview
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ceil
import kotlin.math.roundToInt
import com.muc.fluocolorquant.ui.theme.FluoRadius

/** 运行时真实孔位缩略图；Android Bitmap 在页面离开时统一释放。 */
internal data class RealSiteCropBitmap(
    val site: GridLocalizationSitePreview,
    val bitmap: Bitmap
)

/**
 * 阵列布局页只共享交互骨架，不强行统一载体外观。
 *
 * 微流控芯片继续使用方形位点；96孔板使用圆形位点。该枚举同时控制真实裁切、虚拟布局、
 * 选中边框和放大预览，防止同一页面出现“真实圆孔、虚拟方格”的视觉语义漂移。
 */
internal enum class ArraySiteVisualStyle {
    SQUARE,
    CIRCLE
}

/** 96孔板统一使用的圆孔视觉策略，后续结果页可以沿用同一语义。 */
internal val Plate96SiteVisualStyle: ArraySiteVisualStyle = ArraySiteVisualStyle.CIRCLE

/** 通用布局工作台的稳定测试标签，微流控与96孔板设备回归共用。 */
internal object ArrayLayoutEditorTestTags {
    const val ROOT: String = "array_layout_editor_root"
    const val REAL_GRID: String = "array_layout_real_grid"
    const val VIRTUAL_GRID: String = "array_layout_virtual_grid"
    const val QUANTITATION: String = "array_layout_quantitation"
    const val CROP_PREFIX: String = "array_layout_crop_"
    const val CROP_DIALOG: String = "array_layout_crop_dialog"
}

/**
 * 按通用单元分割器给出的真实紧致边界从无增强透视矫正图逐孔裁切。
 *
 * 页面只解码一次矫正图，225 个小裁切总内存远低于同时加载 225 张原始图片；裁切结果不会
 * 写入磁盘；科学定量读取的是同一 [GridLocalizationSitePreview.cropRegion] 的前景掩膜，
 * 因此页面显示边界与比色/荧光实际使用边界不会再发生漂移。
 */
@Composable
internal fun RealSiteCropGrid(
    preview: GridLocalizationPreview,
    sourceBitmap: Bitmap? = null,
    visualStyle: ArraySiteVisualStyle = ArraySiteVisualStyle.SQUARE,
    selectedSiteIndex: Int? = null,
    onSiteSelected: (Int) -> Unit = {}
) {
    val crops = remember(
        preview.runId,
        preview.rectifiedImagePath,
        preview.cropHalfSizePx,
        preview.sites,
        sourceBitmap
    ) {
        buildRealSiteCrops(preview, sourceBitmap)
    }
    var expandedSiteIndex by rememberSaveable(preview.runId) { mutableStateOf<Int?>(null) }
    DisposableEffect(crops) {
        onDispose {
            crops.forEach { crop ->
                if (!crop.bitmap.isRecycled) crop.bitmap.recycle()
            }
        }
    }

    if (crops.size != preview.siteCount) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(FluoRadius.control),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ) {
            Text(
                text = stringResource(R.string.grid_layout_crop_unavailable),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ArrayLayoutEditorTestTags.REAL_GRID)
    ) {
        val metrics = rememberGridMetrics(
            maxWidth = maxWidth,
            rows = preview.rows,
            columns = preview.columns
        )
        val horizontalScroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(
                    state = horizontalScroll,
                    enabled = metrics.requiresNavigation
                ),
            verticalArrangement = Arrangement.spacedBy(metrics.gap)
        ) {
            GridColumnHeader(preview.columns, metrics)
            repeat(preview.rows) { rowIndex ->
                Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
                    GridRowHeader(rowIndex, metrics)
                    repeat(preview.columns) { columnIndex ->
                        val siteIndex = rowIndex * preview.columns + columnIndex
                        val crop = crops[siteIndex]
                        RealSiteCropCell(
                            crop = crop,
                            cellSize = metrics.cellSize,
                            visualStyle = visualStyle,
                            selected = crop.site.siteIndex == selectedSiteIndex,
                            onClick = {
                                onSiteSelected(crop.site.siteIndex)
                                expandedSiteIndex = crop.site.siteIndex
                            }
                        )
                    }
                }
            }
        }
    }

    crops.firstOrNull { it.site.siteIndex == expandedSiteIndex }?.let { crop ->
        Dialog(onDismissRequest = { expandedSiteIndex = null }) {
            Surface(
                modifier = Modifier.testTag(ArrayLayoutEditorTestTags.CROP_DIALOG),
                shape = RoundedCornerShape(FluoRadius.sheet),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = siteCoordinateLabel(crop.site.rowIndex, crop.site.columnIndex),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        modifier = Modifier.size(220.dp),
                        shape = if (visualStyle == ArraySiteVisualStyle.CIRCLE) CircleShape
                        else RoundedCornerShape(FluoRadius.card),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Image(
                            bitmap = crop.bitmap.asImageBitmap(),
                            contentDescription = siteCoordinateLabel(
                                crop.site.rowIndex,
                                crop.site.columnIndex
                            ),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

/** 分析物使用带颜色识别条的紧凑卡片，不再只是没有层级的文字 Chip。 */
@Composable
internal fun GridAnalyteSelector(
    analytes: List<GridLocalizationAnalyte>,
    selectedAnalyteId: String?,
    onSelected: (String) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(analytes, key = GridLocalizationAnalyte::id) { analyte ->
            val analyteIndex = analytes.indexOfFirst { it.id == analyte.id }.coerceAtLeast(0)
            val accent = gridAnalyteColor(analyteIndex)
            val selected = analyte.id == selectedAnalyteId
            Surface(
                modifier = Modifier
                    .width(132.dp)
                    .clickable { onSelected(analyte.id) },
                shape = RoundedCornerShape(FluoRadius.control),
                color = if (selected) accent.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(accent, RoundedCornerShape(50))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = analyte.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = analyte.concentrationUnit,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 角色工具板沿用 imagegen 视觉稿的“液滴、烧瓶、空白、正负盾牌、靶心”语义，运行时使用
 * Compose 原生矢量图标实现，保证 24dp 小尺寸仍清晰、主题色可适配且无需维护多套位图。
 */
@Composable
internal fun GridRolePalette(
    selectedRole: TemplateSiteRole,
    clearMode: Boolean,
    onRoleSelected: (TemplateSiteRole) -> Unit,
    onClearSelected: () -> Unit
) {
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedMoreRole = selectedRole.takeIf(gridLayoutMoreRoles::contains)

    /*
     * 一级工具只保留实验中最高频的样本、标准品、空白和清除。低频质控/参考角色没有删除，
     * 而是集中到“更多角色”，既保留科研完整性，也避免用户每次都在八个画笔中横向寻找。
     */
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        gridLayoutPrimaryRoles.forEach { role ->
            RoleToolCard(
                label = gridRoleLabel(role),
                icon = gridRoleIcon(role),
                accent = gridRoleAccent(role),
                selected = !clearMode && selectedRole == role,
                onClick = { onRoleSelected(role) },
                modifier = Modifier.weight(1f)
            )
        }

        RoleToolCard(
            label = stringResource(R.string.grid_layout_clear_short),
            icon = Icons.Default.DeleteSweep,
            accent = MaterialTheme.colorScheme.error,
            selected = clearMode,
            onClick = onClearSelected,
            modifier = Modifier.weight(1f)
        )

        Box(modifier = Modifier.weight(1f)) {
            RoleToolCard(
                label = selectedMoreRole?.let { gridRoleLabel(it) }
                    ?: stringResource(R.string.grid_layout_more_short),
                icon = selectedMoreRole?.let(::gridRoleIcon) ?: Icons.Default.MoreHoriz,
                accent = selectedMoreRole?.let { gridRoleAccent(it) }
                    ?: MaterialTheme.colorScheme.secondary,
                selected = !clearMode && selectedMoreRole != null,
                onClick = { moreExpanded = true },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(
                expanded = moreExpanded,
                onDismissRequest = { moreExpanded = false }
            ) {
                gridLayoutMoreRoles.forEach { role ->
                    DropdownMenuItem(
                        text = { Text(gridRoleLabel(role)) },
                        leadingIcon = {
                            Icon(
                                imageVector = gridRoleIcon(role),
                                contentDescription = null,
                                tint = gridRoleAccent(role)
                            )
                        },
                        onClick = {
                            moreExpanded = false
                            onRoleSelected(role)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 虚拟布局板：15×15 及以下按屏幕宽度完整显示；任一维度达到16时才启用横向浏览。
 * 小阵列默认是画笔，大阵列显式切换“浏览/涂抹”，避免滚动手势和批量标记互相抢占。
 */
@Composable
internal fun CompactVirtualLayoutGrid(
    preview: GridLocalizationPreview,
    assignments: Map<Int, GridLayoutAssignmentDraft>,
    onPaintIndices: (Set<Int>) -> Unit,
    visualStyle: ArraySiteVisualStyle = ArraySiteVisualStyle.SQUARE,
    selectedSiteIndex: Int? = null,
    onSiteSelected: (Int) -> Unit = {}
) {
    val largeGrid = preview.rows >= 16 || preview.columns >= 16
    var brushMode by rememberSaveable(preview.runId) { mutableStateOf(!largeGrid) }

    /*
     * Surface 的 content 本质上允许多个子项占据同一层级；如果直接依次输出说明文字和网格，
     * 二者会从相同原点开始绘制，造成说明覆盖列号。这里显式使用 Column 建立纵向排版，
     * 同时留出舒适的卡片内边距，保证窄屏与大字体模式下仍不会互相遮挡。
     */
    Column(
        modifier = Modifier
            .padding(10.dp)
            .testTag(ArrayLayoutEditorTestTags.VIRTUAL_GRID),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (largeGrid) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !brushMode,
                    onClick = { brushMode = false },
                    leadingIcon = { Icon(Icons.Default.PanTool, contentDescription = null) },
                    label = { Text(stringResource(R.string.grid_layout_browse_mode)) }
                )
                FilterChip(
                    selected = brushMode,
                    onClick = { brushMode = true },
                    leadingIcon = { Icon(Icons.Default.TouchApp, contentDescription = null) },
                    label = { Text(stringResource(R.string.grid_layout_brush_mode)) }
                )
            }
        }
        Text(
            text = stringResource(
                if (brushMode) R.string.grid_layout_brush_hint
                else R.string.grid_layout_browse_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val metrics = rememberGridMetrics(
                maxWidth = maxWidth,
                rows = preview.rows,
                columns = preview.columns
            )
            val horizontalScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(
                        state = horizontalScroll,
                        enabled = metrics.requiresNavigation && !brushMode
                    ),
                verticalArrangement = Arrangement.spacedBy(metrics.gap)
            ) {
                GridColumnHeader(preview.columns, metrics)
                Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
                    Column(verticalArrangement = Arrangement.spacedBy(metrics.gap)) {
                        repeat(preview.rows) { rowIndex -> GridRowHeader(rowIndex, metrics) }
                    }
                    Box(
                        modifier = Modifier
                            .width(metrics.gridWidth)
                            .height(metrics.gridHeight)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(metrics.gap)) {
                            repeat(preview.rows) { rowIndex ->
                                Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
                                    repeat(preview.columns) { columnIndex ->
                                        val siteIndex = rowIndex * preview.columns + columnIndex
                                        VirtualSiteCell(
                                            rowIndex = rowIndex,
                                            columnIndex = columnIndex,
                                            assignment = assignments[siteIndex],
                                            analytes = preview.analytes,
                                            cellSize = metrics.cellSize,
                                            visualStyle = visualStyle,
                                            selected = selectedSiteIndex == siteIndex
                                        )
                                    }
                                }
                            }
                        }
                        if (brushMode) {
                            BrushGestureLayer(
                                rows = preview.rows,
                                columns = preview.columns,
                                cellSize = metrics.cellSize,
                                gap = metrics.gap,
                                onPaintIndices = onPaintIndices,
                                onSiteSelected = onSiteSelected
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleToolCard(
    label: String,
    icon: ImageVector,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(FluoRadius.control),
        color = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                modifier = Modifier.size(31.dp),
                shape = RoundedCornerShape(FluoRadius.badge),
                color = accent.copy(alpha = if (selected) 0.22f else 0.11f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = accent
                    )
                }
            }
            Text(
                text = label,
                minLines = 1,
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RealSiteCropCell(
    crop: RealSiteCropBitmap,
    cellSize: Dp,
    visualStyle: ArraySiteVisualStyle,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(cellSize)
            .testTag("${ArrayLayoutEditorTestTags.CROP_PREFIX}${crop.site.siteIndex}")
            .clickable(onClick = onClick),
        shape = if (visualStyle == ArraySiteVisualStyle.CIRCLE) CircleShape
        else RoundedCornerShape(if (cellSize < 24.dp) 3.dp else 5.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box {
            Image(
                bitmap = crop.bitmap.asImageBitmap(),
                contentDescription = siteCoordinateLabel(crop.site.rowIndex, crop.site.columnIndex),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Surface(
                modifier = Modifier.align(
                    if (visualStyle == ArraySiteVisualStyle.CIRCLE) Alignment.TopCenter
                    else Alignment.TopStart
                ),
                color = Color.Black.copy(alpha = 0.46f),
                shape = RoundedCornerShape(bottomEnd = 3.dp)
            ) {
                Text(
                    text = siteCoordinateLabel(crop.site.rowIndex, crop.site.columnIndex),
                    modifier = Modifier.padding(horizontal = 1.5.dp, vertical = 0.dp),
                    color = Color.White,
                    fontSize = if (cellSize < 28.dp) 4.5.sp else 5.5.sp,
                    lineHeight = 6.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
private fun VirtualSiteCell(
    rowIndex: Int,
    columnIndex: Int,
    assignment: GridLayoutAssignmentDraft?,
    analytes: List<GridLocalizationAnalyte>,
    cellSize: Dp,
    visualStyle: ArraySiteVisualStyle,
    selected: Boolean
) {
    val analyteIndex = analytes.indexOfFirst { it.id == assignment?.analyteId }
    val accent = when {
        analyteIndex >= 0 -> gridAnalyteColor(analyteIndex)
        assignment != null -> Color(0xFF607D8B)
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = if (assignment == null) MaterialTheme.colorScheme.onSurfaceVariant
    else Color.White
    Surface(
        modifier = Modifier.size(cellSize),
        shape = if (visualStyle == ArraySiteVisualStyle.CIRCLE) CircleShape
        else RoundedCornerShape(if (cellSize < 24.dp) 3.dp else 5.dp),
        color = accent,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else if (assignment == null) MaterialTheme.colorScheme.outlineVariant
            else accent.copy(alpha = 0.9f)
        )
    ) {
        Box(modifier = Modifier.padding(if (visualStyle == ArraySiteVisualStyle.CIRCLE) 2.dp else 1.dp)) {
            Text(
                text = siteCoordinateLabel(rowIndex, columnIndex),
                modifier = Modifier.align(
                    if (visualStyle == ArraySiteVisualStyle.CIRCLE) Alignment.TopCenter
                    else Alignment.TopStart
                ),
                color = contentColor,
                fontSize = if (cellSize < 24.dp) 5.sp else 6.5.sp,
                lineHeight = 7.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
            Text(
                text = gridRoleShortLabel(assignment?.role),
                modifier = Modifier.align(
                    if (visualStyle == ArraySiteVisualStyle.CIRCLE) Alignment.BottomCenter
                    else Alignment.BottomEnd
                ),
                color = contentColor,
                fontSize = if (cellSize < 24.dp) 6.sp else 8.sp,
                lineHeight = 8.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun BrushGestureLayer(
    rows: Int,
    columns: Int,
    cellSize: Dp,
    gap: Dp,
    onPaintIndices: (Set<Int>) -> Unit,
    onSiteSelected: (Int) -> Unit
) {
    val currentOnPaintIndices by rememberUpdatedState(onPaintIndices)
    val currentOnSiteSelected by rememberUpdatedState(onSiteSelected)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(rows, columns, cellSize, gap) {
                val cellPx = cellSize.toPx()
                val gapPx = gap.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val visited = linkedSetOf<Int>()
                    var previousX = down.position.x
                    var previousY = down.position.y

                    fun record(positionX: Float, positionY: Float) {
                        gridSiteIndexAt(
                            positionX = positionX,
                            positionY = positionY,
                            rows = rows,
                            columns = columns,
                            cellSizePx = cellPx,
                            gapPx = gapPx
                        )?.let(visited::add)
                    }

                    record(down.position.x, down.position.y)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        /*
                         * Android 可能在快速滑动时合并触摸事件。只记录离散事件会跳过中间孔位，
                         * 因此对相邻触点做短线段采样，确保快速横划、竖划和跨行拖动仍连续铺满。
                         */
                        visited += gridSiteIndicesAlongSegment(
                            startX = previousX,
                            startY = previousY,
                            endX = change.position.x,
                            endY = change.position.y,
                            rows = rows,
                            columns = columns,
                            cellSizePx = cellPx,
                            gapPx = gapPx
                        )
                        previousX = change.position.x
                        previousY = change.position.y
                        change.consume()
                    } while (event.changes.any { it.pressed })

                    if (visited.isNotEmpty()) {
                        currentOnPaintIndices(visited)
                        currentOnSiteSelected(visited.last())
                    }
                }
            }
    )
}

/**
 * 补齐两个触摸事件之间经过的孔位，解决快速画笔因系统触点采样稀疏而出现的断格。
 * 采样间距不超过半个孔位边长，既能覆盖小孔位，也不会产生与阵列规模相关的大量计算。
 */
internal fun gridSiteIndicesAlongSegment(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    rows: Int,
    columns: Int,
    cellSizePx: Float,
    gapPx: Float
): Set<Int> {
    if (cellSizePx <= 0f) return emptySet()
    val distance = hypot(endX - startX, endY - startY)
    val stepCount = ceil(distance / (cellSizePx * 0.5f)).toInt().coerceAtLeast(1)
    return buildSet {
        for (step in 0..stepCount) {
            val progress = step.toFloat() / stepCount
            gridSiteIndexAt(
                positionX = startX + (endX - startX) * progress,
                positionY = startY + (endY - startY) * progress,
                rows = rows,
                columns = columns,
                cellSizePx = cellSizePx,
                gapPx = gapPx
            )?.let(::add)
        }
    }
}

private data class GridMetrics(
    val cellSize: Dp,
    val gap: Dp,
    val rowHeaderWidth: Dp,
    val gridWidth: Dp,
    val gridHeight: Dp,
    val requiresNavigation: Boolean
)

@Composable
private fun rememberGridMetrics(maxWidth: Dp, rows: Int, columns: Int): GridMetrics {
    val gap = 1.dp
    val rowHeaderWidth = 20.dp
    val requiresNavigation = rows >= 16 || columns >= 16
    val available = maxWidth - rowHeaderWidth - gap - gap * (columns - 1)
    // 15×15 必须在常见 320–432dp 手机宽度内完整呈现，因此小阵列允许压缩到 14dp；
    // 16×16 及以上固定为可浏览的 28dp，避免为了“塞下”而牺牲可读性和点击精度。
    val fittedCell = (available / columns).coerceIn(14.dp, 34.dp)
    val cellSize = if (requiresNavigation) 28.dp else fittedCell
    return remember(maxWidth, rows, columns) {
        GridMetrics(
            cellSize = cellSize,
            gap = gap,
            rowHeaderWidth = rowHeaderWidth,
            gridWidth = cellSize * columns + gap * (columns - 1),
            gridHeight = cellSize * rows + gap * (rows - 1),
            requiresNavigation = requiresNavigation
        )
    }
}

@Composable
private fun GridColumnHeader(columns: Int, metrics: GridMetrics) {
    Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
        Spacer(Modifier.width(metrics.rowHeaderWidth).height(16.dp))
        repeat(columns) { columnIndex ->
            Text(
                text = (columnIndex + 1).toString(),
                modifier = Modifier.width(metrics.cellSize),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                fontSize = if (metrics.cellSize < 24.dp) 7.sp else 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun GridRowHeader(rowIndex: Int, metrics: GridMetrics) {
    Box(
        modifier = Modifier
            .width(metrics.rowHeaderWidth)
            .height(metrics.cellSize),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = rowCoordinateLabel(rowIndex),
            style = MaterialTheme.typography.labelSmall,
            fontSize = if (metrics.cellSize < 24.dp) 8.sp else 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

internal fun buildRealSiteCrops(
    preview: GridLocalizationPreview,
    sourceBitmap: Bitmap? = null
): List<RealSiteCropBitmap> {
    val source = sourceBitmap ?: run {
        val path = preview.rectifiedImagePath ?: return emptyList()
        BitmapFactory.decodeFile(path) ?: return emptyList()
    }
    val ownsSource = sourceBitmap == null
    return try {
        preview.sites.sortedBy(GridLocalizationSitePreview::siteIndex).mapNotNull { site ->
            runCatching {
                val region = site.cropRegion
                val crop = if (region != null) {
                    ArrayUnitBitmapCropper.crop(
                        source = source,
                        region = region,
                        // 圆孔和自定义轮廓在预览中隐藏外接矩形四角背景；方块保留完整表面纹理。
                        transparentOutsideMask = region.shape != ArrayUnitShape.SQUARE
                    )
                } else {
                    // 只为旧内存会话保留固定窗兼容；新检测运行必定携带通用分割区域。
                    val half = preview.cropHalfSizePx.roundToInt().coerceAtLeast(2)
                    val left = (site.rectifiedX.roundToInt() - half).coerceIn(0, source.width - 1)
                    val top = (site.rectifiedY.roundToInt() - half).coerceIn(0, source.height - 1)
                    val right = (site.rectifiedX.roundToInt() + half).coerceIn(left + 1, source.width)
                    val bottom = (site.rectifiedY.roundToInt() + half).coerceIn(top + 1, source.height)
                    Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                }
                RealSiteCropBitmap(
                    site = site,
                    bitmap = crop
                )
            }.getOrNull()
        }
    } finally {
        // 页面传入的标准方向Bitmap由定位ViewModel管理生命周期；这里只回收本函数自行解码的源图。
        if (ownsSource && !source.isRecycled) source.recycle()
    }
}

/**
 * 把画笔在网格内容区域内的像素坐标映射为行优先位点编号。
 *
 * 间距区域明确返回 null，避免手指从一个孔位跨到相邻孔位时误标；该纯函数同时用于 JVM
 * 单元测试，保证 10×10、15×15 和未来大阵列的拖动画笔坐标规则一致。
 */
internal fun gridSiteIndexAt(
    positionX: Float,
    positionY: Float,
    rows: Int,
    columns: Int,
    cellSizePx: Float,
    gapPx: Float
): Int? {
    if (
        positionX < 0f || positionY < 0f || rows <= 0 || columns <= 0 ||
        cellSizePx <= 0f || gapPx < 0f
    ) {
        return null
    }
    val stepPx = cellSizePx + gapPx
    val column = floor(positionX / stepPx).toInt()
    val row = floor(positionY / stepPx).toInt()
    if (row !in 0 until rows || column !in 0 until columns) return null

    val localX = positionX - column * stepPx
    val localY = positionY - row * stepPx
    return if (localX in 0f..cellSizePx && localY in 0f..cellSizePx) {
        row * columns + column
    } else {
        null
    }
}

private val gridLayoutPrimaryRoles = listOf(
    TemplateSiteRole.SAMPLE,
    TemplateSiteRole.STANDARD,
    TemplateSiteRole.BLANK
)

private val gridLayoutMoreRoles = listOf(
    TemplateSiteRole.NEGATIVE_CONTROL,
    TemplateSiteRole.POSITIVE_CONTROL,
    TemplateSiteRole.REFERENCE,
    TemplateSiteRole.DISABLED
)

@Composable
private fun gridRoleLabel(role: TemplateSiteRole): String = when (role) {
    TemplateSiteRole.SAMPLE -> stringResource(R.string.template_array_role_sample)
    TemplateSiteRole.STANDARD -> stringResource(R.string.template_array_role_standard)
    TemplateSiteRole.BLANK -> stringResource(R.string.template_array_role_blank)
    TemplateSiteRole.NEGATIVE_CONTROL -> stringResource(R.string.template_array_role_negative_control)
    TemplateSiteRole.POSITIVE_CONTROL -> stringResource(R.string.template_array_role_positive_control)
    TemplateSiteRole.REFERENCE -> stringResource(R.string.template_array_role_reference)
    TemplateSiteRole.DISABLED -> stringResource(R.string.template_array_role_disabled)
}

private fun gridRoleIcon(role: TemplateSiteRole): ImageVector = when (role) {
    TemplateSiteRole.SAMPLE -> Icons.Default.WaterDrop
    TemplateSiteRole.STANDARD -> Icons.Default.Science
    TemplateSiteRole.BLANK -> Icons.Default.Block
    TemplateSiteRole.NEGATIVE_CONTROL -> Icons.Default.RemoveCircle
    TemplateSiteRole.POSITIVE_CONTROL -> Icons.Default.AddCircle
    TemplateSiteRole.REFERENCE -> Icons.Default.GpsFixed
    TemplateSiteRole.DISABLED -> Icons.Default.Block
}

@Composable
private fun gridRoleAccent(role: TemplateSiteRole): Color = when (role) {
    TemplateSiteRole.SAMPLE -> MaterialTheme.colorScheme.primary
    TemplateSiteRole.STANDARD -> Color(0xFF00897B)
    TemplateSiteRole.BLANK -> Color(0xFF607D8B)
    TemplateSiteRole.NEGATIVE_CONTROL -> Color(0xFFB26A00)
    TemplateSiteRole.POSITIVE_CONTROL -> Color(0xFF2E7D32)
    TemplateSiteRole.REFERENCE -> Color(0xFF6A4BA3)
    TemplateSiteRole.DISABLED -> MaterialTheme.colorScheme.outline
}

@Composable
private fun gridRoleShortLabel(role: TemplateSiteRole?): String = when (role) {
    TemplateSiteRole.SAMPLE -> stringResource(R.string.grid_layout_role_sample_short)
    TemplateSiteRole.STANDARD -> stringResource(R.string.grid_layout_role_standard_short)
    TemplateSiteRole.BLANK -> stringResource(R.string.grid_layout_role_blank_short)
    TemplateSiteRole.NEGATIVE_CONTROL -> stringResource(R.string.grid_layout_role_negative_short)
    TemplateSiteRole.POSITIVE_CONTROL -> stringResource(R.string.grid_layout_role_positive_short)
    TemplateSiteRole.REFERENCE -> stringResource(R.string.grid_layout_role_reference_short)
    TemplateSiteRole.DISABLED -> stringResource(R.string.grid_layout_role_disabled_short)
    null -> ""
}

private fun gridAnalyteColor(index: Int): Color {
    val colors = listOf(
        Color(0xFF1F6FD2),
        Color(0xFF00897B),
        Color(0xFF7B4FA3),
        Color(0xFFCF6A00),
        Color(0xFF455A64),
        Color(0xFFC04773)
    )
    return colors[index % colors.size]
}

private fun rowCoordinateLabel(rowIndex: Int): String {
    return if (rowIndex in 0..25) ('A'.code + rowIndex).toChar().toString()
    else (rowIndex + 1).toString()
}

internal fun siteCoordinateLabel(rowIndex: Int, columnIndex: Int): String {
    return if (rowIndex in 0..25) {
        "${('A'.code + rowIndex).toChar()}${columnIndex + 1}"
    } else {
        "R${rowIndex + 1}C${columnIndex + 1}"
    }
}

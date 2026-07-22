package com.muc.fluocolorquant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.WellRoleType
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.utils.math.WellMappingUtils
import com.muc.fluocolorquant.utils.math.GridLayoutPolicy
import java.io.File
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * 真实位点预览网格。
 *
 * 项目行列就是界面的真实行列，不再根据“行数是否大于 8”自动转置。旧 12×8 默认
 * 孔板只通过 [GridLayoutPolicy] 的显式历史兼容规则恢复为 8×12。
 */
@Composable
fun RealWellPreviewGrid(
    wellResults: List<WellResult>,
    project: Project,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.real_wells_preview),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val dimensions = remember(project) { GridLayoutPolicy.resolveProject(project) }
        val wellsByIndex = remember(wellResults) { wellResults.associateBy { it.wellIndex } }

        // 计算网格所需的高度
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val availableWidth = screenWidth - 32.dp
        val itemSize = (availableWidth / dimensions.columns) - 2.dp
        val gridHeight = (itemSize * dimensions.rows) + (2.dp * (dimensions.rows - 1)) + 16.dp

        LazyVerticalGrid(
            columns = GridCells.Fixed(dimensions.columns),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight)
                .nestedScroll(connection = object : NestedScrollConnection {}),
            userScrollEnabled = false,
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(dimensions.siteCount) { index ->
                RealWellItem(
                    wellResult = wellsByIndex[index],
                    label = WellMappingUtils.getWellLabelForIndex(index, dimensions.columns)
                )
            }
        }
    }
}

/**
 * 真实孔位项 (最终修正版)
 */
@Composable
fun RealWellItem(
    wellResult: WellResult?,
    label: String, // 直接接收预先计算好的标签字符串
    modifier: Modifier = Modifier
) {
    val roleType = wellResult?.roleType?.let { WellRoleType.fromCode(it) } ?: WellRoleType.NONE

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .border(
                width = 2.dp,
                color = if (wellResult?.fkAnalyteId != null) roleType.color else Color.DarkGray.copy(alpha=0.5f), // 未分配时用深灰色边框
                shape = RoundedCornerShape(4.dp)
            )
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        // 显示孔位图像
        if (wellResult?.croppedImageIdentifier != null) {
            val imageFile = File(wellResult.croppedImageIdentifier)
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageFile)
                    .crossfade(true)
                    .build(),
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(2.dp))
            )
        } else {
            // 黑色的占位符
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
            )
        }

        // 【关键】现在总是显示物理标签
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        )

        // 如果孔位已分配角色，显示角色标签
        if (wellResult?.fkAnalyteId != null && wellResult.roleType != null) {
            Text(
                text = roleType.shortName,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(roleType.color.copy(alpha = 0.8f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * 虚拟布局交互板
 * 提供孔位布局的交互界面，能够处理两种不同的工作流
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualLayoutInteractionBoard(
    project: Project,
    availableAnalytes: List<Analyte>,
    selectedAnalyte: Analyte?,
    selectedRoleType: WellRoleType,
    availableTemplates: List<ExperimentTemplate>,
    wellResults: List<WellResult>,
    standardWellsCount: Int,
    availableDlModels: List<String>, // 新增: DL模型参数
    analyteFittingStatus: Set<String> = emptySet(), // 新增: 已配置的分析物ID集合
    onAnalyteSelected: (String) -> Unit,
    onRoleTypeSelected: (WellRoleType) -> Unit,
    onTemplateSelected: (String) -> Unit,
    onWellClicked: (Int, Int) -> Unit,
    onManualFittingClicked: () -> Unit,
    onApplyModel: (String) -> Unit, // 新增: 应用DL模型的回调
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.virtual_layout_board),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 分析物选择器 - 所有模式通用
        AnalyteSelector(
            availableAnalytes = availableAnalytes,
            selectedAnalyte = selectedAnalyte,
            onAnalyteSelected = onAnalyteSelected
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 角色画笔选择器 - 所有模式通用
        RoleTypePaintSelector(
            selectedRoleType = selectedRoleType,
            onRoleTypeSelected = onRoleTypeSelected
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 虚拟网格 - 所有模式通用
        VirtualGrid(
            project = project,
            wellResults = wellResults,
            selectedAnalyte = selectedAnalyte,
            onWellClicked = onWellClicked
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 【核心】根据项目分析方法显示不同的操作面板
        if (project.analysisMethod == "DL_MODEL") {
            // 深度学习模型选择器
            var modelExpanded by remember { mutableStateOf(false) }
            var selectedModel by remember { mutableStateOf(availableDlModels.firstOrNull() ?: "") }

            Text(
                text = stringResource(R.string.select_model),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = !modelExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.available_models)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        availableDlModels.forEach { modelName ->
                            DropdownMenuItem(
                                text = { Text(modelName) },
                                onClick = {
                                    selectedModel = modelName
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { onApplyModel(selectedModel) },
                    enabled = selectedModel.isNotBlank(),
                    modifier = Modifier.weight(0.5f)
                ) {
                    Text(stringResource(R.string.apply_model))
                }
            }
        } else {
            // 曲线拟合模式 - 模板选择或手动拟合
            if (selectedAnalyte != null) {
                // 检查当前选中的分析物是否已配置
                val isCurrentAnalyteConfigured = selectedAnalyte.id in analyteFittingStatus
                
                TemplateSelector(
                    availableTemplates = availableTemplates.filter { it.analyteId == selectedAnalyte.id },
                    onTemplateSelected = onTemplateSelected,
                    onManualFittingClicked = onManualFittingClicked,
                    standardWellsCount = standardWellsCount,
                    hasEnoughStandards = standardWellsCount >= 4,
                    isAnalyteConfigured = isCurrentAnalyteConfigured // 传递当前分析物是否已配置
                )
            }
        }
    }
}

/**
 * 分析物选择器
 */
@Composable
fun AnalyteSelector(
    availableAnalytes: List<Analyte>,
    selectedAnalyte: Analyte?,
    onAnalyteSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.select_analyte),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedAnalyte?.name ?: stringResource(R.string.no_analyte_selected),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                availableAnalytes.forEach { analyte ->
                    DropdownMenuItem(
                        text = { Text(analyte.name) },
                        onClick = {
                            onAnalyteSelected(analyte.id)
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Science,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * 角色画笔选择器
 */
@Composable
fun RoleTypePaintSelector(
    selectedRoleType: WellRoleType,
    onRoleTypeSelected: (WellRoleType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.select_role_type),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 标准品按钮
            RoleTypeButton(
                roleType = WellRoleType.STANDARD,
                isSelected = selectedRoleType == WellRoleType.STANDARD,
                onClick = { onRoleTypeSelected(WellRoleType.STANDARD) }
            )

            // 样本按钮
            RoleTypeButton(
                roleType = WellRoleType.SAMPLE,
                isSelected = selectedRoleType == WellRoleType.SAMPLE,
                onClick = { onRoleTypeSelected(WellRoleType.SAMPLE) }
            )

            // 空白对照按钮
            RoleTypeButton(
                roleType = WellRoleType.BLANK,
                isSelected = selectedRoleType == WellRoleType.BLANK,
                onClick = { onRoleTypeSelected(WellRoleType.BLANK) }
            )

            // 质控品按钮
            RoleTypeButton(
                roleType = WellRoleType.QUALITY_CONTROL,
                isSelected = selectedRoleType == WellRoleType.QUALITY_CONTROL,
                onClick = { onRoleTypeSelected(WellRoleType.QUALITY_CONTROL) }
            )

            // 清除按钮
            RoleTypeButton(
                roleType = WellRoleType.NONE,
                isSelected = selectedRoleType == WellRoleType.NONE,
                onClick = { onRoleTypeSelected(WellRoleType.NONE) }
            )
        }
    }
}

/**
 * 角色类型按钮
 */
@Composable
fun RoleTypeButton(
    roleType: WellRoleType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonColor = if (isSelected) {
        roleType.color
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = if (isSelected) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val icon = if (roleType == WellRoleType.NONE) {
        Icons.Default.Clear
    } else {
        Icons.Default.CheckCircle
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = buttonColor,
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(
                    when (roleType) {
                        WellRoleType.STANDARD -> R.string.role_standard
                        WellRoleType.SAMPLE -> R.string.role_sample
                        WellRoleType.BLANK -> R.string.role_blank
                        WellRoleType.QUALITY_CONTROL -> R.string.role_qc
                        WellRoleType.UNKNOWN -> R.string.role_unknown
                        WellRoleType.NONE -> R.string.role_none
                    }
                ),
                tint = textColor
            )
        }

        Text(
            text = stringResource(
                when (roleType) {
                    WellRoleType.STANDARD -> R.string.role_standard
                    WellRoleType.SAMPLE -> R.string.role_sample
                    WellRoleType.BLANK -> R.string.role_blank
                    WellRoleType.QUALITY_CONTROL -> R.string.role_qc
                    WellRoleType.UNKNOWN -> R.string.role_unknown
                    WellRoleType.NONE -> R.string.role_none
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * 虚拟网格 (支持拖动标记)
 */
@Composable
fun VirtualGrid(
    project: Project,
    wellResults: List<WellResult>,
    selectedAnalyte: Analyte?,
    onWellClicked: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = remember(project) { GridLayoutPolicy.resolveProject(project) }
    val virtualRows = dimensions.rows
    val virtualCols = dimensions.columns
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    // --- 创建一个以虚拟坐标为Key的Map，用于快速查找 ---
    val wellsByVirtualCoord = remember(wellResults, selectedAnalyte, virtualCols) {
        wellResults.associateBy { well ->
            WellMappingUtils.mapRealToVirtualCoordinates(well.wellIndex, virtualCols)
        }
    }

    // 用于跟踪拖动过程中已标记的孔位，防止重复调用
    val draggedWells = remember { mutableSetOf<Pair<Int, Int>>() }

    val dragGestureModifier = Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = {
                draggedWells.clear()
                // 立即标记起始单元格
                val startCol = ((it.x - 24.dp.toPx()) / ((layoutSize.width - 24.dp.toPx()) / virtualCols)).toInt().coerceIn(0, virtualCols - 1)
                val startRow = (it.y / (layoutSize.height / virtualRows)).toInt().coerceIn(0, virtualRows - 1)
                if (draggedWells.add(startRow to startCol)) {
                    onWellClicked(startRow, startCol)
                }
            },
            onDragEnd = { draggedWells.clear() },
            onDrag = { change, _ ->
                val (x, y) = change.position
                if (layoutSize.width > 0 && layoutSize.height > 0) {
                    val colWidth = (layoutSize.width - 24.dp.toPx()) / virtualCols
                    val rowHeight = layoutSize.height / virtualRows

                    if (x > 24.dp.toPx()) { // 确保我们已经过了行标签
                        val col = ((x - 24.dp.toPx()) / colWidth).toInt().coerceIn(0, virtualCols - 1)
                        val row = (y / rowHeight).toInt().coerceIn(0, virtualRows - 1)

                        if (draggedWells.add(row to col)) {
                            onWellClicked(row, col)
                        }
                    }
                }
                change.consume()
            }
        )
    }


    Column(
        modifier = modifier
            .onSizeChanged { layoutSize = it }
            .then(dragGestureModifier)
    ) {
        // --- 列标签 (1, 2, 3...) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(24.dp)) // 左上角空白
            for (col in 0 until virtualCols) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (col + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // --- 核心网格区域 ---
        for (row in 0 until virtualRows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 行标签 (A, B, C...)
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = WellMappingUtils.getRowLabel(row),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 渲染每一行的孔位
                for (col in 0 until virtualCols) {
                    // --- 核心修改：直接从Map中查找当前坐标的孔位数据 ---
                    val wellResult = wellsByVirtualCoord[Pair(row, col)]

                    // 检查是否超出实际范围
                    val realIndex = WellMappingUtils.mapVirtualToRealIndex(row, col, virtualCols)
                    val isOutOfBounds = realIndex >= dimensions.siteCount

                    VirtualWellItem(
                        wellResult = wellResult,
                        virtualRow = row,
                        virtualCol = col,
                        selectedAnalyte = selectedAnalyte,
                        isOutOfBounds = isOutOfBounds,
                        onWellClicked = onWellClicked,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 虚拟孔位项 (支持拖动标记)
 */
@Composable
fun VirtualWellItem(
    wellResult: WellResult?,
    virtualRow: Int,
    virtualCol: Int,
    selectedAnalyte: Analyte?,
    isOutOfBounds: Boolean = false,
    onWellClicked: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val wellLabel = WellMappingUtils.getWellLabel(virtualRow, virtualCol)
    val roleType = wellResult?.roleType?.let { WellRoleType.fromCode(it) } ?: WellRoleType.NONE

    val isOccupied = wellResult?.fkAnalyteId != null
    val isOccupiedBySameAnalyte = isOccupied && wellResult?.fkAnalyteId == selectedAnalyte?.id

    val backgroundColor = when {
        isOutOfBounds -> Color.Gray.copy(alpha = 0.1f)  // 超出范围的孔位使用灰色背景
        isOccupiedBySameAnalyte -> roleType.color.copy(alpha = 0.3f)
        isOccupied -> Color.LightGray.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    val borderColor = when {
        isOutOfBounds -> Color.Gray.copy(alpha = 0.3f)  // 超出范围的孔位使用浅灰色边框
        isOccupiedBySameAnalyte -> roleType.color
        isOccupied -> Color.LightGray
        else -> Color.LightGray.copy(alpha = 0.5f)
    }

    val textColor = when {
        isOutOfBounds -> Color.Gray.copy(alpha = 0.5f)  // 超出范围的孔位使用浅灰色文本
        else -> LocalContentColor.current
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(enabled = !isOutOfBounds) { onWellClicked(virtualRow, virtualCol) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = wellLabel,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            textAlign = TextAlign.Center
        )

        // 如果孔位已分配角色，显示角色标签
        if (isOccupied) {
            Text(
                text = roleType.shortName,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = if (isOccupiedBySameAnalyte) Color.Black else Color.Gray,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
            )
        }
    }
}

/**
 * 模板选择器
 */
@Composable
fun TemplateSelector(
    availableTemplates: List<ExperimentTemplate>,
    onTemplateSelected: (String) -> Unit,
    onManualFittingClicked: () -> Unit,
    standardWellsCount: Int,
    hasEnoughStandards: Boolean,
    isAnalyteConfigured: Boolean = false, // 新增参数：当前分析物是否已配置
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.select_template),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 模板下拉菜单
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    // 当没有可用模板时或分析物已配置时，禁用按钮
                    enabled = availableTemplates.isNotEmpty() && !isAnalyteConfigured
                ) {
                    Text(
                        text = if (availableTemplates.isNotEmpty())
                            stringResource(R.string.apply_template)
                        else
                            stringResource(R.string.no_template_available), // 提示用户没有模板
                        textAlign = TextAlign.Center
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    availableTemplates.forEach { template ->
                        DropdownMenuItem(
                            text = { Text(template.templateName) },
                            onClick = {
                                onTemplateSelected(template.id)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 手动拟合按钮 - 根据标准品数量控制状态，并考虑分析物是否已配置
            Button(
                onClick = onManualFittingClicked,
                enabled = hasEnoughStandards && !isAnalyteConfigured, // 同时考虑标准品数量和分析物配置状态
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.manual_fitting),
                    textAlign = TextAlign.Center
                )
            }
        }

        // 显示提示信息
        if (!hasEnoughStandards && !isAnalyteConfigured) {
            // 仅当分析物未配置且标准品不足时显示
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.standard_wells_count_hint,
                    standardWellsCount,
                    4 - standardWellsCount
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (isAnalyteConfigured) {
            // 当分析物已配置时显示已配置提示
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.analyte_already_configured),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@file:OptIn(ExperimentalLayoutApi::class)

package com.muc.fluocolorquant.ui.screens.settings.template

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BorderAll
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.TemplateReferenceScope
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.Analyte

/**
 * 阵列编辑器使用的稳定语义标签。
 *
 * 标签只服务自动化测试和无障碍工具，不参与业务持久化；位点标签包含中性坐标，能够同时
 * 适配孔板和微流控芯片，避免重新引入 A1 这类孔板专属约定。
 */
object TemplateArrayLayoutTestTags {
    const val HORIZONTAL_VIEWPORT = "template_array_horizontal_viewport"
    const val VERTICAL_VIEWPORT = "template_array_vertical_viewport"
    const val APPLY_BUTTON = "template_array_apply_button"
    private const val SITE_PREFIX = "template_array_site_"
    private const val ROW_PREFIX = "template_array_row_"
    private const val COLUMN_PREFIX = "template_array_column_"

    fun site(coordinate: TemplateSiteCoordinate): String = SITE_PREFIX + coordinate.displayName
    fun rowHeader(rowIndex: Int): String = ROW_PREFIX + rowIndex
    fun columnHeader(columnIndex: Int): String = COLUMN_PREFIX + columnIndex
}

/**
 * 通用规则阵列位点编辑器。
 *
 * 组件保持无状态：选区、矩形模式和批量配置全部由 ViewModel 或测试宿主管理。这样阵列
 * 编辑器只负责呈现和发送意图，不会在页面重组或屏幕旋转后偷偷丢失实验配置。
 *
 * 10×10 与 15×15 均使用固定可读尺寸的单元格，再通过水平、垂直两个独立滚动视口承载；
 * 不会为了塞进一屏而把 225 个位点压缩成难以点击的像素点。
 */
@Composable
fun TemplateArrayLayoutEditor(
    layout: TemplateArrayLayoutDraft,
    analytes: List<Analyte>,
    batchDraft: TemplateSiteDraft,
    rectangleSelectionEnabled: Boolean,
    onRectangleSelectionEnabledChange: (Boolean) -> Unit,
    onToggleSite: (TemplateSiteCoordinate) -> Unit,
    onSelectRectanglePoint: (TemplateSiteCoordinate) -> Unit,
    onSelectRow: (Int) -> Unit,
    onSelectColumn: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onBatchDraftChange: (TemplateSiteDraft) -> Unit,
    onApplyToSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ArrayEditorSummary(layout = layout)

        SelectionToolbar(
            selectedCount = layout.selectedSites.size,
            rectangleSelectionEnabled = rectangleSelectionEnabled,
            onRectangleSelectionEnabledChange = onRectangleSelectionEnabledChange,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection
        )

        AnimatedVisibility(visible = layout.selectedSites.isNotEmpty()) {
            BatchAssignmentPanel(
                analytes = analytes,
                batchDraft = batchDraft,
                onBatchDraftChange = onBatchDraftChange,
                onApplyToSelection = onApplyToSelection
            )
        }

        if (layout.rows <= 0 || layout.columns <= 0) {
            EmptyArrayState()
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.template_array_scroll_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ArrayGridViewport(
                layout = layout,
                rectangleSelectionEnabled = rectangleSelectionEnabled,
                onToggleSite = onToggleSite,
                onSelectRectanglePoint = onSelectRectanglePoint,
                onSelectRow = onSelectRow,
                onSelectColumn = onSelectColumn,
                onSelectAll = onSelectAll
            )
        }
    }
}

/** 顶部摘要使用“科研仪器控制台”式蓝灰信息条，快速确认当前载体几何和选区规模。 */
@Composable
private fun ArrayEditorSummary(layout: TemplateArrayLayoutDraft) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.64f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.GridView,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.template_array_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        R.string.template_array_summary,
                        layout.rows,
                        layout.columns,
                        layout.siteCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f)
                )
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
            ) {
                Text(
                    text = stringResource(
                        R.string.template_array_selected_count,
                        layout.selectedSites.size
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 全选、清除与矩形模式放在同一工具条，降低 15×15 阵列的重复点击成本。 */
@Composable
private fun SelectionToolbar(
    selectedCount: Int,
    rectangleSelectionEnabled: Boolean,
    onRectangleSelectionEnabledChange: (Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.template_array_selection_tools),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onSelectAll) {
                    Icon(Icons.Default.BorderAll, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.select_all))
                }
                OutlinedButton(
                    onClick = onClearSelection,
                    enabled = selectedCount > 0
                ) {
                    Icon(Icons.Default.ClearAll, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.template_array_clear_selection))
                }
                FilterChip(
                    selected = rectangleSelectionEnabled,
                    onClick = {
                        onRectangleSelectionEnabledChange(!rectangleSelectionEnabled)
                    },
                    label = { Text(stringResource(R.string.template_array_rectangle_mode)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }
    }
}

/**
 * 批量赋值面板集中处理分析物、角色、标准浓度和重复组。
 *
 * 角色切换只修改“待应用配置”，不会立即覆盖位点；用户必须点击应用按钮，避免在大阵列
 * 上因误触角色芯片造成不可见的大范围数据修改。
 */
@Composable
private fun BatchAssignmentPanel(
    analytes: List<Analyte>,
    batchDraft: TemplateSiteDraft,
    onBatchDraftChange: (TemplateSiteDraft) -> Unit,
    onApplyToSelection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.template_array_batch_settings),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            BatchFieldLabel(stringResource(R.string.template_array_analyte))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = batchDraft.analyteId == null,
                    onClick = { onBatchDraftChange(batchDraft.copy(analyteId = null)) },
                    label = { Text(stringResource(R.string.template_array_no_analyte)) }
                )
                analytes.forEach { analyte ->
                    FilterChip(
                        selected = batchDraft.analyteId == analyte.id,
                        onClick = {
                            onBatchDraftChange(batchDraft.copy(analyteId = analyte.id))
                        },
                        label = { Text(analyte.name) }
                    )
                }
            }
            if (analytes.isEmpty()) {
                Text(
                    text = stringResource(R.string.template_array_no_analytes_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            BatchFieldLabel(stringResource(R.string.template_array_role))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TemplateSiteRole.entries.forEach { role ->
                    FilterChip(
                        selected = batchDraft.role == role,
                        onClick = {
                            onBatchDraftChange(
                                batchDraft.copy(
                                    role = role,
                                    enabled = role != TemplateSiteRole.DISABLED
                                )
                            )
                        },
                        label = { Text(templateSiteRoleLabel(role)) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(templateSiteRoleColor(role))
                            )
                        }
                    )
                }
            }

            AnimatedVisibility(visible = batchDraft.role == TemplateSiteRole.STANDARD) {
                OutlinedTextField(
                    value = batchDraft.standardConcentrationInput,
                    onValueChange = {
                        onBatchDraftChange(batchDraft.copy(standardConcentrationInput = it))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.template_array_standard_concentration)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            AnimatedVisibility(
                visible = batchDraft.role == TemplateSiteRole.BLANK ||
                    batchDraft.role == TemplateSiteRole.REFERENCE
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatchFieldLabel(stringResource(R.string.template_array_reference_scope))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = batchDraft.referenceScope == TemplateReferenceScope.ANALYTE,
                            onClick = {
                                onBatchDraftChange(
                                    batchDraft.copy(referenceScope = TemplateReferenceScope.ANALYTE)
                                )
                            },
                            label = { Text(stringResource(R.string.template_array_scope_analyte)) }
                        )
                        FilterChip(
                            selected = batchDraft.referenceScope == TemplateReferenceScope.GLOBAL,
                            onClick = {
                                onBatchDraftChange(
                                    batchDraft.copy(referenceScope = TemplateReferenceScope.GLOBAL)
                                )
                            },
                            label = { Text(stringResource(R.string.template_array_scope_global)) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = batchDraft.repeatGroup,
                    onValueChange = {
                        onBatchDraftChange(batchDraft.copy(repeatGroup = it))
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.template_array_repeat_group)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = batchDraft.defaultSampleSlot,
                    onValueChange = {
                        onBatchDraftChange(batchDraft.copy(defaultSampleSlot = it))
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.template_array_sample_slot)) },
                    singleLine = true
                )
            }

            Button(
                onClick = onApplyToSelection,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TemplateArrayLayoutTestTags.APPLY_BUTTON)
            ) {
                Text(stringResource(R.string.template_array_apply))
            }
        }
    }
}

@Composable
private fun BatchFieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium
    )
}

/** 双向滚动容器完整创建全部位点语义节点，方便科研人员和自动化测试逐点访问。 */
@Composable
private fun ArrayGridViewport(
    layout: TemplateArrayLayoutDraft,
    rectangleSelectionEnabled: Boolean,
    onToggleSite: (TemplateSiteCoordinate) -> Unit,
    onSelectRectanglePoint: (TemplateSiteCoordinate) -> Unit,
    onSelectRow: (Int) -> Unit,
    onSelectColumn: (Int) -> Unit,
    onSelectAll: () -> Unit
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(20.dp)
            )
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .verticalScroll(verticalScrollState)
            .testTag(TemplateArrayLayoutTestTags.VERTICAL_VIEWPORT)
    ) {
        Box(
            modifier = Modifier
                .horizontalScroll(horizontalScrollState)
                .padding(12.dp)
                .testTag(TemplateArrayLayoutTestTags.HORIZONTAL_VIEWPORT)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ColumnHeader(layout.columns, onSelectAll, onSelectColumn)
                repeat(layout.rows) { rowIndex ->
                    ArraySiteRow(
                        rowIndex = rowIndex,
                        columns = layout.columns,
                        layout = layout,
                        rectangleSelectionEnabled = rectangleSelectionEnabled,
                        onToggleSite = onToggleSite,
                        onSelectRectanglePoint = onSelectRectanglePoint,
                        onSelectRow = onSelectRow
                    )
                }
            }
        }
    }
}

/** 左上角按钮负责全选，列头负责整列选择。 */
@Composable
private fun ColumnHeader(
    columns: Int,
    onSelectAll: () -> Unit,
    onSelectColumn: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onSelectAll,
            modifier = Modifier.size(width = 54.dp, height = 38.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.BorderAll,
                    contentDescription = stringResource(R.string.select_all),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        repeat(columns) { columnIndex ->
            Surface(
                onClick = { onSelectColumn(columnIndex) },
                modifier = Modifier
                    .size(width = 62.dp, height = 38.dp)
                    .testTag(TemplateArrayLayoutTestTags.columnHeader(columnIndex)),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            R.string.template_array_column_header,
                            columnIndex + 1
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** 每行固定 54dp 行头与 62dp 位点，保证 15×15 阵列仍具有可靠点击面积。 */
@Composable
private fun ArraySiteRow(
    rowIndex: Int,
    columns: Int,
    layout: TemplateArrayLayoutDraft,
    rectangleSelectionEnabled: Boolean,
    onToggleSite: (TemplateSiteCoordinate) -> Unit,
    onSelectRectanglePoint: (TemplateSiteCoordinate) -> Unit,
    onSelectRow: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = { onSelectRow(rowIndex) },
            modifier = Modifier
                .size(width = 54.dp, height = 58.dp)
                .testTag(TemplateArrayLayoutTestTags.rowHeader(rowIndex)),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.template_array_row_header, rowIndex + 1),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        repeat(columns) { columnIndex ->
            val coordinate = TemplateSiteCoordinate(rowIndex, columnIndex)
            ArraySiteCell(
                coordinate = coordinate,
                assignment = layout.assignments[coordinate],
                selected = coordinate in layout.selectedSites,
                onClick = {
                    if (rectangleSelectionEnabled) {
                        onSelectRectanglePoint(coordinate)
                    } else {
                        onToggleSite(coordinate)
                    }
                }
            )
        }
    }
}

/** 位点使用角色色条而非大面积高饱和填充，长时间编辑时更易辨认坐标和选区。 */
@Composable
private fun ArraySiteCell(
    coordinate: TemplateSiteCoordinate,
    assignment: TemplateSiteDraft?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val role = assignment?.role
    val baseColor = role?.let(::templateSiteRoleColor)
        ?: MaterialTheme.colorScheme.surface
    val selectedBorder = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    val roleLabel = role?.let { templateSiteRoleLabel(it) }
        ?: stringResource(R.string.template_array_no_analyte)
    val accessibilityLabel = stringResource(
        R.string.template_array_site_accessibility,
        coordinate.displayName,
        roleLabel
    )

    Surface(
        modifier = Modifier
            .size(width = 62.dp, height = 58.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Checkbox
            )
            .semantics { contentDescription = accessibilityLabel }
            .testTag(TemplateArrayLayoutTestTags.site(coordinate)),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = selectedBorder,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(baseColor)
            )
            Text(
                text = coordinate.displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 9.sp,
                maxLines = 1
            )
            // 细小状态点用于区分已配置与未配置，完整角色名称通过上方图例和无障碍描述提供。
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (assignment == null) {
                            MaterialTheme.colorScheme.outlineVariant
                        } else {
                            baseColor
                        }
                    )
            )
        }
    }
}

@Composable
private fun EmptyArrayState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Text(
            text = stringResource(R.string.template_array_empty),
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 位点角色配色保持跨页面稳定，后续结果热力图可以复用同一语义色。 */
private fun templateSiteRoleColor(role: TemplateSiteRole): Color = when (role) {
    TemplateSiteRole.SAMPLE -> Color(0xFF1976D2)
    TemplateSiteRole.STANDARD -> Color(0xFF00897B)
    TemplateSiteRole.BLANK -> Color(0xFF78909C)
    TemplateSiteRole.NEGATIVE_CONTROL -> Color(0xFFF9A825)
    TemplateSiteRole.POSITIVE_CONTROL -> Color(0xFFE64A19)
    TemplateSiteRole.REFERENCE -> Color(0xFF6A1B9A)
    TemplateSiteRole.DISABLED -> Color(0xFFB0BEC5)
}

@Composable
private fun templateSiteRoleLabel(role: TemplateSiteRole): String = when (role) {
    TemplateSiteRole.SAMPLE -> stringResource(R.string.template_array_role_sample)
    TemplateSiteRole.STANDARD -> stringResource(R.string.template_array_role_standard)
    TemplateSiteRole.BLANK -> stringResource(R.string.template_array_role_blank)
    TemplateSiteRole.NEGATIVE_CONTROL -> {
        stringResource(R.string.template_array_role_negative_control)
    }
    TemplateSiteRole.POSITIVE_CONTROL -> {
        stringResource(R.string.template_array_role_positive_control)
    }
    TemplateSiteRole.REFERENCE -> stringResource(R.string.template_array_role_reference)
    TemplateSiteRole.DISABLED -> stringResource(R.string.template_array_role_disabled)
}

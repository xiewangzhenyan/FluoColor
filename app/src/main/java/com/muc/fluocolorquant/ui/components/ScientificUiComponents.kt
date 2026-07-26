@file:OptIn(ExperimentalMaterial3Api::class)

package com.muc.fluocolorquant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 科研方案选择器使用的稳定展示项。
 *
 * 页面只向组件传入已经资源化的标题和摘要；组件不持有业务对象，也不拼接用户可见文本，
 * 因此可同时复用于实验模板、载体、采集设备和分析模型选择。
 */
data class ScientificPickerOption(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    /** 可选的数学公式。存在时使用 jlatexmath-android 渲染，不显示 LaTeX 源码。 */
    val latexSubtitle: String? = null
)

/**
 * 科研选择字段的视觉密度。
 *
 * [STANDARD] 用于全宽资源选择；[COMPACT] 用于“最大浓度 + 单位”等双列场景。紧凑模式
 * 只缩小装饰空间，不缩小正文可读字号，避免窄屏把 `g/ml`、`μmol/L` 等单位压成省略号。
 */
enum class ScientificSelectionFieldDensity {
    STANDARD,
    COMPACT
}

/**
 * 紧凑的章节标题。
 *
 * 只使用图标和短标题建立视觉锚点，不默认渲染说明段落。真正需要解释的内容应放在错误
 * 状态、帮助弹层或用户主动展开的区域，避免软件页面呈现成说明文档。
 */
@Composable
fun ScientificSectionTitle(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(11.dp),
            color = accentColor.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = accentColor
                )
            }
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailingContent?.invoke()
    }
}

/**
 * 面向移动端的科研资源选择字段。
 *
 * 相比把只读 OutlinedTextField 伪装成下拉框，本组件使用完整可点击表面、明确图标、两级
 * 文本和箭头表达“选择”语义；长方案名称会自然省略，不会挤压或覆盖页面其他内容。
 */
@Composable
fun ScientificSelectionField(
    label: String?,
    value: String?,
    placeholder: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingValue: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    density: ScientificSelectionFieldDensity = ScientificSelectionFieldDensity.STANDARD
) {
    val horizontalPadding = if (density == ScientificSelectionFieldDensity.COMPACT) 9.dp else 13.dp
    val verticalPadding = if (density == ScientificSelectionFieldDensity.COMPACT) 10.dp else 12.dp
    val contentSpacing = if (density == ScientificSelectionFieldDensity.COMPACT) 7.dp else 11.dp
    val iconContainerSize = if (density == ScientificSelectionFieldDensity.COMPACT) 32.dp else 40.dp
    val iconSize = if (density == ScientificSelectionFieldDensity.COMPACT) 18.dp else 21.dp
    val arrowSize = if (density == ScientificSelectionFieldDensity.COMPACT) 20.dp else 24.dp
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // 当外层章节已经提供明确标题时，调用方可以传入空标签，避免在同一张卡片内重复
        // 出现“实验方案 / 使用实验方案”一类近义文字。
        label?.takeIf(String::isNotBlank)?.let { visibleLabel ->
            Text(
                text = visibleLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
        val borderColor = when {
            isError -> MaterialTheme.colorScheme.error
            value.isNullOrBlank() -> MaterialTheme.colorScheme.outlineVariant
            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // 紧凑选择器与同一行的最大浓度输入框保持统一触控高度，避免标签对齐后
                // 仍出现控件底边错位。STANDARD 保留自适应高度，不影响其他资源选择页。
                .then(
                    if (density == ScientificSelectionFieldDensity.COMPACT) {
                        Modifier.heightIn(min = 64.dp)
                    } else {
                        Modifier
                    }
                )
                .clickable(enabled = enabled, onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            color = if (value.isNullOrBlank()) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
            },
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(contentSpacing)
            ) {
                Surface(
                    modifier = Modifier.size(iconContainerSize),
                    shape = RoundedCornerShape(if (density == ScientificSelectionFieldDensity.COMPACT) 10.dp else 12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = value?.takeIf(String::isNotBlank) ?: placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (value.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = if (value.isNullOrBlank()) FontWeight.Normal
                        else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    supportingValue?.takeIf(String::isNotBlank)?.let { supporting ->
                        Text(
                            text = supporting,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(arrowSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 全宽底部选择面板。
 *
 * 方案或分析物数量较多时，底部面板比悬浮小菜单更适合触屏：选项宽度稳定、可滚动、当前
 * 选择明确，并且不会出现截图中菜单只占屏幕左侧一小条的失控布局。
 */
@Composable
fun ScientificPickerSheet(
    title: String,
    options: List<ScientificPickerOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(options, key = ScientificPickerOption::id) { option ->
                    val selected = option.id == selectedId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(option.id)
                                onDismiss()
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
                        } else {
                            Color.Transparent
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            option.icon?.let { optionIcon ->
                                Surface(
                                    modifier = Modifier.size(38.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = optionIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = option.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                when {
                                    !option.latexSubtitle.isNullOrBlank() -> {
                                        // 专业函数使用真正的数学排版；较小字号和左对齐适合移动端长列表。
                                        LatexView(
                                            latex = option.latexSubtitle,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 24.dp),
                                            textSize = 15.sp,
                                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            alignment = LatexAlignment.START
                                        )
                                    }
                                    !option.subtitle.isNullOrBlank() -> {
                                        Text(
                                            text = option.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(21.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 短元数据标签，用图标和单行文本取代大段锁定配置说明。 */
@Composable
fun ScientificMetadataChip(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = accentColor.copy(alpha = 0.09f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = accentColor
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 科研结果页通用的折叠信息区。
 *
 * 默认状态只保留图标、标题和一行摘要，使热力图、浓度等主要结果可以优先进入首屏；
 * 用户主动展开后再展示完整的方案或追溯字段。展开状态由调用方持有，便于在分析物切换、
 * 状态恢复和自动化测试中得到确定行为。
 */
@Composable
fun ScientificExpandableSection(
    title: String,
    summary: String,
    icon: ImageVector,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    toggleContentDescription: String,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit
) {
    val arrowRotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "scientificSectionArrow"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = accentColor.copy(alpha = 0.11f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = accentColor
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = toggleContentDescription,
                    modifier = Modifier.rotate(arrowRotation.value),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(modifier = Modifier.padding(14.dp)) {
                        content()
                    }
                }
            }
        }
    }
}

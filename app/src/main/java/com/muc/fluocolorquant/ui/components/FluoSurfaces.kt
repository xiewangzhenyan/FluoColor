package com.muc.fluocolorquant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.ui.theme.FluoIconSize
import com.muc.fluocolorquant.ui.theme.FluoMotion
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing

/**
 * 统一的分区卡片、标题、指标块、状态标签与占位状态。
 *
 * 这些形状此前在结果页、检测页和设置页里被各自复写了十几遍：圆角 14/16/18/20/22dp 混用，
 * 描边有的用 outlineVariant、有的用 outline.copy(alpha)，内边距从 12dp 到 18dp 都出现过。
 * 收敛到同一实现后，"同一功能域内卡片圆角、边框、内边距和标题层级保持一致"
 * （AGENTS.md 7.3）才有可执行的落点。
 *
 * 所有组件都不持有业务状态，也不拼接用户可见文本：调用方传入的字符串必须已经过
 * `stringResource()` 资源化（AGENTS.md 5）。
 */

/**
 * 页面级分区卡片。
 *
 * 用描边 + 低层级表面而不是阴影表达分区，符合"避免过多渐变、阴影、发光"的约束
 * （AGENTS.md 7.1）。默认开启 [animateContentSize]，卡片内展开详情、切换分析物或追加校验
 * 条目时高度按标准节奏过渡，不会瞬间跳变。
 *
 * @param accentColor 非空时在卡片左侧绘制一条 3dp 色条，用于区分"正常 / 需复核 / 失败"等
 *   分区语义。色条只是辅助，语义仍必须由卡片内的图标与文字表达（AGENTS.md 10）。
 */
@Composable
fun FluoSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = FluoSpacing.lg,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    accentColor: Color? = null,
    verticalSpacing: Dp = FluoSpacing.md,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = FluoMotion.standard()),
        shape = RoundedCornerShape(FluoRadius.card),
        color = containerColor,
        border = BorderStroke(width = 1.dp, color = borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // IntrinsicSize.Min 让左侧色条能通过 fillMaxHeight() 与内容等高；
                // 没有它时 Box 高度为 0，长卡片会出现"色条只画了一小段"。
                .height(IntrinsicSize.Min)
        ) {
            if (accentColor != null) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accentColor)
                )
            }
            Column(
                modifier = Modifier.padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                content = content
            )
        }
    }
}

/**
 * 分区标题。
 *
 * 结构固定为"图标底板 + 标题 + 可选一行摘要 + 可选尾部内容"。摘要限制为单行，真正需要
 * 解释的内容放进帮助入口或用户主动展开的区域，避免主操作页变成说明文档（AGENTS.md 7.4）。
 */
@Composable
fun FluoSectionHeader(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FluoSpacing.md)
    ) {
        FluoIconBadge(icon = icon, accentColor = accentColor)
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
            subtitle?.takeIf(String::isNotBlank)?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailingContent?.invoke()
    }
}

/**
 * 图标底板。
 *
 * 统一 36dp 圆角方块 + 20dp 图标 + 12% 主色底，是整个应用识别"这是一个分区"的视觉锚点。
 * 纯装饰用途，因此 contentDescription 固定为 null（AGENTS.md 8）。
 */
@Composable
fun FluoIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    containerSize: Dp = FluoIconSize.badgeContainer,
    iconSize: Dp = FluoIconSize.medium
) {
    Surface(
        modifier = modifier.size(containerSize),
        shape = RoundedCornerShape(FluoRadius.badge),
        color = accentColor.copy(alpha = 0.12f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = accentColor
            )
        }
    }
}

/**
 * 指标块。
 *
 * 数值优先、标签其次的两行结构，用于"位点数 / 平均置信度 / R² / RMSE"一类摘要。
 * 数值与单位分开传入并同排显示，单位使用较小字号；单位过长时整体换行而不是截断成省略号
 * （AGENTS.md 7.4）。
 */
@Composable
fun FluoMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.control),
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(horizontal = FluoSpacing.md, vertical = FluoSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(FluoSpacing.xs)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                unit?.takeIf(String::isNotBlank)?.let { unitText ->
                    Text(
                        text = unitText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                        maxLines = 1
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 状态标签。
 *
 * 强制"图标 + 文字"组合，不允许只靠颜色区分状态（AGENTS.md 10）。颜色由调用方从
 * `FluoTheme.semantic` 取用，组件本身不判断业务语义。
 */
@Composable
fun FluoStatusChip(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.chip),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = FluoSpacing.sm, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FluoSpacing.xs)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(FluoIconSize.small),
                tint = contentColor
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 页面内联展开卡片。
 *
 * 详情优先内联展开而不是弹窗遮挡上下文（AGENTS.md 7.3）。折叠态只保留图标、标题和一行
 * 摘要，让热力图、浓度等主要结论优先进入首屏；展开状态由调用方持有，便于在分析物切换、
 * 屏幕旋转和自动化测试中获得确定行为。
 */
@Composable
fun FluoExpandableCard(
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
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = FluoMotion.micro(),
        label = "fluoExpandableArrow"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = FluoMotion.standard()),
        shape = RoundedCornerShape(FluoRadius.card),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    // 48dp 最小触控高度（AGENTS.md 10）。
                    .heightIn(min = 56.dp)
                    .padding(horizontal = FluoSpacing.lg, vertical = FluoSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FluoSpacing.md)
            ) {
                FluoIconBadge(icon = icon, accentColor = accentColor)
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
                    modifier = Modifier
                        .size(FluoIconSize.large)
                        .rotate(arrowRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = FluoMotion.expandEnter,
                exit = FluoMotion.expandExit
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(modifier = Modifier.padding(FluoSpacing.lg)) {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * 加载 / 空数据 / 失败的统一占位。
 *
 * 三种状态共用同一布局，只更换图标、文案和动作，使用户在不同页面遇到同类状态时得到一致
 * 反馈。加载态显示确定的进度指示器与阶段文本，不用无限动画掩盖无响应（AGENTS.md 9.2）。
 *
 * @param icon 为 null 时显示进度指示器（加载态）；非 null 时显示图标（空态或失败态）。
 * @param actionText 与 [onAction] 同时非空时显示一个文字按钮，例如"重试"。
 */
@Composable
fun FluoStatePlaceholder(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingText: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FluoSpacing.xl, vertical = FluoSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FluoSpacing.md)
    ) {
        if (icon == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                strokeWidth = 3.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = iconTint
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        supportingText?.takeIf(String::isNotBlank)?.let { supporting ->
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(FluoSpacing.xs))
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

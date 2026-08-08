@file:OptIn(ExperimentalMaterial3Api::class)

package com.muc.fluocolorquant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.theme.FluoMotion
import com.muc.fluocolorquant.ui.theme.FluoSpacing

/**
 * 统一页面骨架与顶栏。
 *
 * 项目里 30 余个页面各自手写 `Scaffold + CenterAlignedTopAppBar`：有的设置了容器色，有的
 * 没有；返回图标的无障碍描述有的用 `R.string.back`，有的用 `R.string.go_back`；标题字重
 * 有的 SemiBold 有的用默认值。这些差异单看每个页面都成立，连起来就是"每页都略有不同"。
 * 本文件把顶栏、页面背景、内容内边距和入场节奏收敛为一处实现。
 *
 * 组件只负责容器与视觉，不持有任何业务状态，也不拼接用户可见文本——调用方传入的
 * 标题与描述必须已经过 `stringResource()` 资源化（AGENTS.md 5）。
 */

/**
 * 页面级顶栏。
 *
 * 采用紧凑标题栏 + 可选一行摘要（AGENTS.md 7.3）：摘要用于呈现项目名、载体规格、运行状态
 * 一类关键上下文，不承载说明文字。标题与摘要都限制为单行省略，保证长中文分析物名称和
 * 长英文单位不会把顶栏撑成两三行。
 *
 * @param title 已资源化的页面标题。
 * @param subtitle 可选的一行关键摘要；为空时顶栏保持单行高度。
 * @param onBack 返回回调；为 null 时不显示返回按钮（用于根级页面）。
 * @param actions 顶栏右侧操作区，遵循"图标 + 无障碍描述"的既有约定。
 */
@Composable
fun FluoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val backDescription = stringResource(R.string.back)
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.takeIf(String::isNotBlank)?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backDescription
                    )
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            // 滚动到顶栏下方时抬升为 surfaceContainer，用明度而不是阴影表达层级，
            // 符合"少用阴影与发光"的风格约束（AGENTS.md 7.1）。
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/**
 * 统一页面容器。
 *
 * 负责三件事：接管顶栏与底部操作栏、统一页面背景色、统一内容区水平边距。内容区不强制
 * 滚动策略——热力图、相机预览等需要自行控制布局的页面直接使用本组件的 content 插槽。
 *
 * @param bottomBar 固定在底部的主操作区；主流程页面的主按钮应保持常驻可见（AGENTS.md 7.3）。
 */
@Composable
fun FluoScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.background,
    contentWindowInsets: WindowInsets = androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            FluoTopBar(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                actions = actions
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        containerColor = containerColor,
        contentWindowInsets = contentWindowInsets,
        content = content
    )
}

/**
 * 可滚动页面的标准内容列。
 *
 * 统一水平边距 16dp、区块间距 16dp、底部额外留白 24dp，避免各页面用 12/14/18dp 各写一遍。
 * 底部留白保证最后一张卡片不会被底部操作栏或手势条压住。
 */
@Composable
fun FluoScrollableContent(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    horizontalPadding: androidx.compose.ui.unit.Dp = FluoSpacing.lg,
    verticalSpacing: androidx.compose.ui.unit.Dp = FluoSpacing.lg,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = FluoSpacing.md,
                bottom = FluoSpacing.xl
            ),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content
    )
}

/**
 * 区块入场动画包装。
 *
 * 页面首次进入时让区块淡入并轻微上移，形成"从上到下建立层级"的阅读引导。刻意做成一次性
 * 效果：`remember` 之后不再重放，避免用户在页面内切换分析物或展开详情时反复触发入场动画。
 *
 * 只用于页面级区块（通常一屏 3–6 个）。长列表禁止逐项使用，防止同时启动大量动画导致
 * 掉帧和内存抖动（AGENTS.md 9.3）。
 *
 * @param delayMillis 相对于页面进入的错峰延迟；建议按区块顺序取 0/60/120ms，总延迟不超过
 *   200ms，保证用户不会觉得页面"慢半拍"。
 */
@Composable
fun FluoAnimatedSection(
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayMillis > 0) kotlinx.coroutines.delay(delayMillis.toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth(),
        enter = FluoMotion.sectionEnter
    ) {
        content()
    }
}

package com.muc.fluocolorquant.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * FluoColor 统一设计令牌。
 *
 * 页面此前各自写死 7.dp、9.dp、11.dp、13.dp、17.dp、18.dp、22.dp 等一次性数值，同一功能域内
 * 的卡片圆角、内边距和图标尺寸互不一致。这里把可复用的视觉常量集中到一处，使"统一风格"
 * 成为可检查的事实而不是靠人工记忆。令牌只覆盖真正跨页面复用的量，不追求穷举所有尺寸。
 */

/**
 * 间距体系。
 *
 * 仅提供 4/8/12/16/24 五档（AGENTS.md 7.3）。需要更大间隔时使用多档叠加而不是新增档位，
 * 避免间距体系再次退化成任意数值。
 */
object FluoSpacing {
    /** 4dp：图标与其紧邻文字、同一行内两个强关联元素。 */
    val xs = 4.dp

    /** 8dp：卡片内相邻控件、chip 之间。 */
    val sm = 8.dp

    /** 12dp：卡片内不同小节之间。 */
    val md = 12.dp

    /** 16dp：页面水平边距、卡片内边距。 */
    val lg = 16.dp

    /** 24dp：页面级区块之间、底部主按钮上方留白。 */
    val xl = 24.dp
}

/**
 * 圆角体系。
 *
 * 同一功能域内的卡片必须使用同一圆角（AGENTS.md 7.3）。三档分别对应"页面级容器 /
 * 可点击控件 / 标签"，层级越小圆角越小，形成稳定的形状节奏。
 */
object FluoRadius {
    /** 20dp：页面级分区卡片、底部面板。 */
    val card = 20.dp

    /** 14dp：输入框、选择器、分段控件等可点击控件。 */
    val control = 14.dp

    /** 12dp：图标底板、缩略图。 */
    val badge = 12.dp

    /** 10dp：状态标签、元数据 chip。 */
    val chip = 10.dp
}

/**
 * 图标尺寸。常规图标 18–24dp（AGENTS.md 8）；图标底板按 1.9 倍取整，保证视觉重心居中。
 */
object FluoIconSize {
    /** 16dp：chip 内的辅助图标。 */
    val small = 16.dp

    /** 20dp：卡片标题、列表项图标。 */
    val medium = 20.dp

    /** 24dp：主操作按钮、顶栏动作图标。 */
    val large = 24.dp

    /** 36dp：medium 图标的圆角底板。 */
    val badgeContainer = 36.dp
}

/**
 * 动效节奏。
 *
 * 数值取自 AGENTS.md 9.2：微交互 120–220ms，卡片展开与内容切换 200–320ms。所有动画共用
 * 这两档时长和 Material 标准 easing，避免同一次交互里出现快慢不一的过渡。动画只表达层级
 * 与状态变化，不用于延迟操作，也不改变任何科学数值。
 */
object FluoMotion {
    /** 微交互时长：颜色、缩放、旋转、选中态。 */
    const val MICRO_MS: Int = 180

    /** 标准时长：卡片展开、内容切换、列表项进出。 */
    const val STANDARD_MS: Int = 260

    /** 页面导航时长：略长于内容切换，让层级切换可被感知。 */
    const val NAVIGATION_MS: Int = 300

    /**
     * Material 标准 easing（fast-out slow-in）。
     * 直接使用常量而不是 FastOutSlowInEasing，便于在同一处调整整体手感。
     */
    val Standard: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** 元素进入时的减速曲线，起步快、收尾稳。 */
    val Decelerate: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    /** 元素退出时的加速曲线，避免退出动画拖慢下一步操作。 */
    val Accelerate: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    /** 微交互动画规格，用于 animateColorAsState / animateFloatAsState 等。 */
    fun <T> micro(): FiniteAnimationSpec<T> = tween(durationMillis = MICRO_MS, easing = Standard)

    /** 标准动画规格，用于 animateContentSize / AnimatedVisibility 等。 */
    fun <T> standard(): FiniteAnimationSpec<T> = tween(durationMillis = STANDARD_MS, easing = Standard)

    /**
     * 详情展开的统一进入动画。
     *
     * 淡入与纵向展开同时进行；不使用位移或弹跳，防止展开时把下方的数值和热力图"甩"出视野。
     */
    val expandEnter: EnterTransition
        get() = fadeIn(animationSpec = tween(STANDARD_MS, easing = Decelerate)) +
            expandVertically(animationSpec = tween(STANDARD_MS, easing = Standard))

    /** 详情收起的统一退出动画。 */
    val expandExit: ExitTransition
        get() = fadeOut(animationSpec = tween(MICRO_MS, easing = Accelerate)) +
            shrinkVertically(animationSpec = tween(STANDARD_MS, easing = Standard))

    /**
     * 页面区块的入场动画。
     *
     * 只做小幅度上移（约 12dp 对应的像素）加淡入，属于"层级提示"而不是装饰性位移；
     * 大列表不要对每个元素套用，避免同时启动大量动画导致掉帧（AGENTS.md 9.3）。
     */
    val sectionEnter: EnterTransition
        get() = fadeIn(animationSpec = tween(STANDARD_MS, easing = Decelerate)) +
            slideInVertically(animationSpec = tween(STANDARD_MS, easing = Decelerate)) { height ->
                (height / 6).coerceAtMost(48)
            }
}

/**
 * 语义状态色集合。
 *
 * Material 3 的 ColorScheme 只内置 error，成功/警告/提示必须由主题补齐，否则各页面会各自
 * 拼凑颜色。每种语义提供"主色 / 主色前景 / 容器 / 容器前景"四元组，用法与 M3 的 error 系列
 * 完全一致：主色用于图标与描边，容器用于背景块，前景色保证对比度。
 *
 * 约束：这些颜色只允许表达对应语义，不得当作普通装饰色或图表配色使用（AGENTS.md 7.2）。
 */
@Immutable
data class FluoSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color
)

internal val LightSemanticColors = FluoSemanticColors(
    success = Success,
    onSuccess = OnSuccess,
    successContainer = SuccessContainer,
    onSuccessContainer = OnSuccessContainer,
    warning = Warning,
    onWarning = OnWarning,
    warningContainer = WarningContainer,
    onWarningContainer = OnWarningContainer,
    info = Info,
    onInfo = OnInfo,
    infoContainer = InfoContainer,
    onInfoContainer = OnInfoContainer
)

internal val DarkSemanticColors = FluoSemanticColors(
    success = DarkSuccess,
    onSuccess = DarkOnSuccess,
    successContainer = DarkSuccessContainer,
    onSuccessContainer = DarkOnSuccessContainer,
    warning = DarkWarning,
    onWarning = DarkOnWarning,
    warningContainer = DarkWarningContainer,
    onWarningContainer = DarkOnWarningContainer,
    info = DarkInfo,
    onInfo = DarkOnInfo,
    infoContainer = DarkInfoContainer,
    onInfoContainer = DarkOnInfoContainer
)

/**
 * 语义色的读取入口，用法：`FluoTheme.semantic.warningContainer`。
 *
 * 使用 staticCompositionLocalOf：语义色只在明暗主题切换时整体更换，不需要按值细粒度重组。
 */
val LocalFluoSemanticColors = staticCompositionLocalOf { LightSemanticColors }

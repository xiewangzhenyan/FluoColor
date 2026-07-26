package com.muc.fluocolorquant.utils

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry
import com.muc.fluocolorquant.ui.theme.FluoMotion

/**
 * 页面导航过渡。
 *
 * 旧实现使用 500ms 的缩放淡入淡出，既超出 AGENTS.md 9.2 规定的节奏上限，也让每次前进/
 * 后退都出现一次明显的"页面缩放"，在检测→定位→布局→结果这条多步链路上尤其拖沓；
 * 该实现此前被注释掉未启用，全应用因此完全没有导航动画。
 *
 * 这里改为 Material 标准的横向层级过渡：前进时新页面自右侧滑入、旧页面向左退让，返回时
 * 方向相反。位移幅度只取容器宽度的四分之一并叠加淡入淡出，属于"表达层级关系"而不是
 * 装饰性大幅位移（AGENTS.md 9.3）。时长统一取 [FluoMotion.NAVIGATION_MS]。
 *
 * 注意：这些过渡挂在 NavHost 上统一生效，页面自身不再各写一套，避免不同页面节奏不一致。
 */

/** 位移幅度：容器宽度的 1/4。整页位移会让长列表在过渡期间产生明显撕裂感。 */
private const val SLIDE_FRACTION_DIVISOR = 4

private val navigationTween get() = tween<Float>(
    durationMillis = FluoMotion.NAVIGATION_MS,
    easing = FluoMotion.Standard
)

private val navigationOffsetTween get() = tween<androidx.compose.ui.unit.IntOffset>(
    durationMillis = FluoMotion.NAVIGATION_MS,
    easing = FluoMotion.Standard
)

/** 前进：新页面自右侧滑入。 */
val fluoEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = navigationOffsetTween,
        initialOffset = { fullWidth -> fullWidth / SLIDE_FRACTION_DIVISOR }
    ) + fadeIn(animationSpec = navigationTween)
}

/** 前进：旧页面向左退让并淡出，形成前后层级关系。 */
val fluoExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = navigationOffsetTween,
        targetOffset = { fullWidth -> fullWidth / SLIDE_FRACTION_DIVISOR }
    ) + fadeOut(animationSpec = navigationTween)
}

/** 返回：上一页面自左侧回到原位。 */
val fluoPopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = navigationOffsetTween,
        initialOffset = { fullWidth -> fullWidth / SLIDE_FRACTION_DIVISOR }
    ) + fadeIn(animationSpec = navigationTween)
}

/** 返回：当前页面向右滑出。 */
val fluoPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = navigationOffsetTween,
        targetOffset = { fullWidth -> fullWidth / SLIDE_FRACTION_DIVISOR }
    ) + fadeOut(animationSpec = navigationTween)
}

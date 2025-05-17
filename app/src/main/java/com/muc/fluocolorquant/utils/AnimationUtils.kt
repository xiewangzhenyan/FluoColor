package com.muc.fluocolorquant.utils

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

// 设置动画的基本时长
private const val ANIMATION_DURATION = 500

/**
 * 带动画效果的页面导航
 */
fun NavGraphBuilder.animatedComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit
) = composable(
    route = route,
    arguments = arguments,
    enterTransition = {
        fadeIn(animationSpec = tween(ANIMATION_DURATION)) +
                scaleIn(initialScale = 0.95f, animationSpec = tween(ANIMATION_DURATION))
    },
    exitTransition = {
        fadeOut(animationSpec = tween(ANIMATION_DURATION)) +
                scaleOut(targetScale = 0.95f, animationSpec = tween(ANIMATION_DURATION))
    },
    popEnterTransition = {
        fadeIn(animationSpec = tween(ANIMATION_DURATION)) +
                scaleIn(initialScale = 0.95f, animationSpec = tween(ANIMATION_DURATION))
    },
    popExitTransition = {
        fadeOut(animationSpec = tween(ANIMATION_DURATION)) +
                scaleOut(targetScale = 0.95f, animationSpec = tween(ANIMATION_DURATION))
    },
    content = content
)

/**
 * 自定义导航过渡效果 - 左右滑动过渡
 */
fun slideInTransition(duration: Int = ANIMATION_DURATION): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = tween(duration)
    )
}

fun slideOutTransition(duration: Int = ANIMATION_DURATION): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = tween(duration)
    )
}

fun slideInPopTransition(duration: Int = ANIMATION_DURATION): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = tween(duration)
    )
}

fun slideOutPopTransition(duration: Int = ANIMATION_DURATION): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = tween(duration)
    )
} 
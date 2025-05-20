package com.muc.fluocolorquant.utils

/**
 * 定义应用的所有页面路由
 */
sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Register : Screen("register")
    object Home : Screen("home")
    object NewProject : Screen("new_project")
    object ImageCrop : Screen("image_crop")
    object WellDetection : Screen("well_detection")
    object CurveFitting : Screen("curve_fitting")
    object Result : Screen("result")
    object Profile : Screen("profile")
    object History : Screen("history")
    object Settings : Screen("settings")
} 
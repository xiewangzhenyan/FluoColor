package com.muc.fluocolorquant.ui.navigation

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
    object ImageCorrection : Screen("image_correction") {
        fun createRoute(imageUri: String, projectId: String): String {
            return "$route/$imageUri/$projectId"
        }
    }
    object WellDetection : Screen("well_detection") {
        fun createRoute(imageUri: String, projectId: String): String {
            // imageUri可能已经经过URL编码，这里不需要再次编码
            return "$route/$imageUri/$projectId"
        }
    }
    object CurveFitting : Screen("curve_fitting") {
        fun createRoute(runId: String, imageUri: String? = null): String {
            return if (imageUri != null) {
                "$route/$runId?imageUri=$imageUri"
            } else {
                "$route/$runId"
            }
        }
    }
    object Result : Screen("result") {
        fun createRoute(runId: String): String {
            return "$route/$runId"
        }
    }
    object Profile : Screen("profile")
    object History : Screen("history")
    object Settings : Screen("settings")
} 
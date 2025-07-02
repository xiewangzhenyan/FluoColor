package com.muc.fluocolorquant.ui.navigation

/**
 * 定义应用的所有页面路由
 */
sealed class Screen(open val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Register : Screen("register")
    object Home : Screen("home")
    object NewProject : Screen("new_project")
    object ImageCapture : Screen("image_capture")
    object ImageCrop : Screen("image_crop") {
        fun createRoute(imageUri: String? = null): String {
            return imageUri?.let { "$route?imageUri=$it" } ?: route
        }
    }
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
    object History : Screen("history")
    object Settings : Screen("settings")
    object Profile : Screen("profile")
    object AppSettings : Screen("app_settings")
    object DetectionSettings : Screen("detection_settings")
    object AnalyteManagement : Screen("analyte_management")
    object ReagentLibrary : Screen("reagent_library")
    object CurveModelLibrary : Screen("curve_model_library")
    
    // 实验模板管理相关路由
    object ExperimentTemplateManagement : Screen("experiment_template_management")
    object CreateExperimentTemplate : Screen("create_experiment_template") {
        fun createRoute(templateId: String? = null): String {
            return templateId?.let { "$route?templateId=$it" } ?: route
        }
    }
    
    // 曲线模型输入相关路由
    object ManualCurveInput : Screen("manual_curve_input")
    object ManualDataInput : Screen("manual_data_input")
    
    // 带参数的路由
    class DetailRoute(val id: String) : Screen("detail/$id")
} 
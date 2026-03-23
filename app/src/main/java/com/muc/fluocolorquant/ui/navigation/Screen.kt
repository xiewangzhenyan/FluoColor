package com.muc.fluocolorquant.ui.navigation

/**
 * 定义应用的所有页面路由
 */
sealed class Screen(open val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Register : Screen("register")
    object Home : Screen("home")
    object NewProject : Screen("new_project") {
        fun createRoute(): String = route
    }
    object ImageCapture : Screen("image_capture") {
        fun createRoute(
            outputPath: String,
            captureMode: String? = null,
            expectedSpectrumTracks: Int? = null
        ): String {
            val queryParts = buildList {
                add("outputPath=$outputPath")
                captureMode?.let { add("captureMode=$it") }
                expectedSpectrumTracks?.let { add("expectedSpectrumTracks=$it") }
            }
            return "$route?${queryParts.joinToString(separator = "&")}"
        }
    }
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
        // projectId, runId, imageUri 现在都是路由路径的一部分
        fun createRoute(projectId: String, runId: String, imageUri: String): String {
            return "$route/$projectId/$runId/$imageUri"
        }
    }
    // 添加了 CurveFittingResult 屏幕对象
    object CurveFittingResult : Screen("curve_fitting_result") {
        fun createRoute(projectId: String, analyteId: String): String {
            return "$route/$projectId/$analyteId"
        }
    }

    object Result : Screen("result") {
        fun createRoute(runId: String): String {
            return "$route/$runId"
        }
    }
    
    // 添加新的结果展示页面路由
    object NewResult : Screen("new_result") {
        fun createRoute(runId: String): String {
            return "$route/$runId"
        }
    }
    object History : Screen("history")
    object Settings : Screen("settings")
    object Profile : Screen("profile")
    object AppSettings : Screen("app_settings")
    object DetectionSettings : Screen("detection_settings")
    object SpectrumSettings : Screen("spectrum_settings")
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
    
    // 光谱标定页面路由
    object SpectrumCalibration : Screen("spectrum_calibration/{projectId}/{imagePath}") {
        fun createRoute(projectId: String, imageUri: String): String {
            // 对 imageUri 进行编码，防止路径中的 '/' 导致导航错误
            val encodedPath = android.net.Uri.encode(imageUri)
            return "spectrum_calibration/$projectId/$encodedPath"
        }
    }
    
    // 光谱结果展示页面路由
    object SpectrumResult : Screen("spectrum_result/{projectId}") {
        fun createRoute(projectId: String): String = "spectrum_result/$projectId"
    }

    // 带参数的路由
    class DetailRoute(val id: String) : Screen("detail/$id")
}

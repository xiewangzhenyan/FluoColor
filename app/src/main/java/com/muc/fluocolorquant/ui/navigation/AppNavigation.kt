package com.muc.fluocolorquant.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.muc.fluocolorquant.ui.screens.auth.LoginScreen
import com.muc.fluocolorquant.ui.screens.auth.RegisterScreen
import com.muc.fluocolorquant.ui.screens.detection.WellDetectionScreen
import com.muc.fluocolorquant.ui.screens.home.HomeScreen
import com.muc.fluocolorquant.ui.screens.image.CameraCaptureScreen
import com.muc.fluocolorquant.ui.screens.imagecrop.ImageCropScreen
import com.muc.fluocolorquant.ui.screens.image.ImageCorrectionScreen
import com.muc.fluocolorquant.ui.screens.profile.ProfileScreen
import com.muc.fluocolorquant.ui.screens.project.NewProjectScreen
import com.muc.fluocolorquant.ui.screens.project.DirectCreateProjectScreen
import com.muc.fluocolorquant.ui.screens.result.ResultGatewayScreen
import com.muc.fluocolorquant.ui.screens.settings.AppSettingsScreen
import com.muc.fluocolorquant.ui.screens.settings.DetectionSettingsScreen
import com.muc.fluocolorquant.ui.screens.settings.SettingsScreen
import com.muc.fluocolorquant.ui.screens.settings.SpectrumSettingsScreen
import com.muc.fluocolorquant.ui.screens.splash.SplashScreen
import com.muc.fluocolorquant.ui.screens.history.HistoryScreen
import com.muc.fluocolorquant.ui.screens.settings.AnalyteManagementScreen
import com.muc.fluocolorquant.ui.screens.settings.ReagentLibraryScreen
import com.muc.fluocolorquant.ui.screens.settings.CurveModelManagementScreen
import com.muc.fluocolorquant.ui.screens.settings.ManualCurveInputScreen
import com.muc.fluocolorquant.ui.screens.settings.ManualDataInputScreen
import com.muc.fluocolorquant.ui.screens.settings.StandardCurveLibraryScreen
import com.muc.fluocolorquant.ui.screens.settings.CalibrationSettingsScreen
import com.muc.fluocolorquant.ui.screens.settings.ExperimentTemplateManagementScreen
import com.muc.fluocolorquant.ui.screens.settings.template.ExperimentTemplateWizardScreen
import com.muc.fluocolorquant.ui.screens.settings.resources.AcquisitionProfileManagementScreen
import com.muc.fluocolorquant.ui.screens.settings.resources.CarrierProfileManagementScreen
import com.muc.fluocolorquant.ui.screens.spectrum.SpectrumCalibrationScreen
import com.muc.fluocolorquant.ui.screens.spectrum.SpectrumResultScreen
import com.muc.fluocolorquant.ui.viewmodels.SettingsViewModel
// import com.muc.fluocolorquant.utils.animatedComposable

@Composable
fun AppNavigation(navController: NavHostController, startDestination: String = Screen.Splash.route) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(route = Screen.Splash.route) {
            SplashScreen(navController = navController)
        }

        composable(route = Screen.Login.route) {
            LoginScreen(navController = navController)
        }

        composable(route = Screen.Register.route) {
            RegisterScreen(navController = navController)
        }

        composable(route = Screen.Home.route) {
            HomeScreen(navController = navController)
        }

        // 添加新项目创建页面，支持可选mode参数
        composable(
            route = Screen.NewProject.route
        ) {
            NewProjectScreen(navController = navController)
        }

        // 快速新建只引用已发布模板，不再后台合成或归档一次性模板。
        composable(
            route = Screen.QuickCreateProject.route
        ) {
            DirectCreateProjectScreen(navController = navController)
        }

        composable(
            route = "${Screen.ImageCapture.route}?outputPath={outputPath}&captureMode={captureMode}&expectedSpectrumTracks={expectedSpectrumTracks}",
            arguments = listOf(
                navArgument("outputPath") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("captureMode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("expectedSpectrumTracks") {
                    type = NavType.IntType
                    defaultValue = 1
                }
            )
        ) { backStackEntry ->
            val outputPath = backStackEntry.arguments?.getString("outputPath")?.let(Uri::decode)
            val captureMode = backStackEntry.arguments?.getString("captureMode")
            val expectedSpectrumTracks = backStackEntry.arguments?.getInt("expectedSpectrumTracks") ?: 1
            CameraCaptureScreen(
                navController = navController,
                outputPath = outputPath,
                captureMode = captureMode,
                expectedSpectrumTracks = expectedSpectrumTracks
            )
        }

        // 图片裁剪页面
        composable(
            route = Screen.ImageCrop.createRoute("{imageUri}"), // Use the createRoute pattern without calling it directly
            arguments = listOf(
                navArgument("imageUri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val imageUriString = backStackEntry.arguments?.getString("imageUri")
            ImageCropScreen(
                navController = navController,
                imageUri = imageUriString
            )
        }

        // 图像矫正页面
        composable(
            route = Screen.ImageCorrection.createRoute("{imageUri}", "{projectId}"), // Use the createRoute pattern
            arguments = listOf(
                navArgument("imageUri") {
                    type = NavType.StringType
                },
                navArgument("projectId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val imageUriString = backStackEntry.arguments?.getString("imageUri")
            val projectId = backStackEntry.arguments?.getString("projectId")
            ImageCorrectionScreen(
                navController = navController,
                imageUri = imageUriString,
                projectId = projectId
            )
        }

        // 孔阵检测页面
        composable(
            route = Screen.WellDetection.createRoute("{imageUri}", "{projectId}"), // Use the createRoute pattern
            arguments = listOf(
                navArgument("imageUri") {
                    type = NavType.StringType
                },
                navArgument("projectId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val imageUriString = backStackEntry.arguments?.getString("imageUri")
            val projectId = backStackEntry.arguments?.getString("projectId")

            // 日志输出，记录URI参数
            android.util.Log.d("AppNavigation", "WellDetection接收到imageUri: $imageUriString")

            WellDetectionScreen(
                navController = navController,
                imageUri = imageUriString,
                projectId = projectId
            )
        }

        // 结果展示页面 - 使用新的结果展示页面替代旧版
        composable(
            route = Screen.Result.createRoute("{runId}"),
            arguments = listOf(
                navArgument("runId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val runId = backStackEntry.arguments?.getString("runId")
            ResultGatewayScreen(
                navController = navController,
                runId = runId
            )
        }

        // 新的结果展示页面（显式路由）
        composable(
            route = Screen.NewResult.createRoute("{runId}"),
            arguments = listOf(
                navArgument("runId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val runId = backStackEntry.arguments?.getString("runId")
            ResultGatewayScreen(
                navController = navController,
                runId = runId
            )
        }

        composable(route = Screen.Profile.route) {
            ProfileScreen(navController = navController)
        }

        // 设置页面
        composable(route = Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }

        // 应用设置页面
        composable(route = Screen.AppSettings.route) {
            AppSettingsScreen(navController = navController)
        }

        // 检测设置页面
        composable(route = Screen.DetectionSettings.route) {
            DetectionSettingsScreen(navController = navController)
        }

        composable(route = Screen.SpectrumSettings.route) {
            SpectrumSettingsScreen(
                navController = navController,
                viewModel = hiltViewModel<SettingsViewModel>()
            )
        }

        // 分析物管理页面
        composable(route = Screen.AnalyteManagement.route) {
            AnalyteManagementScreen(
                navigateBack = { navController.navigateUp() }
            )
        }

        // 试剂库页面
        composable(route = Screen.ReagentLibrary.route) {
            ReagentLibraryScreen(
                navigateBack = { navController.navigateUp() }
            )
        }

        // 普通设置统一进入标准曲线库。统一分析模型的底层表继续承载科学契约，
        // 但不再把模型文件、SHA、尺寸和参数 JSON 表单暴露给普通用户。
        composable(route = Screen.CurveModelLibrary.route) {
            StandardCurveLibraryScreen(navController = navController)
        }

        composable(route = Screen.CalibrationSettings.route) {
            CalibrationSettingsScreen(navController = navController)
        }

        // 历史 CurveModel、手动曲线和旧项目查询继续从兼容入口访问。
        composable(route = Screen.LegacyCurveModelLibrary.route) {
            CurveModelManagementScreen(navController = navController)
        }

        // 版本化载体与布局库
        composable(route = Screen.CarrierProfileManagement.route) {
            CarrierProfileManagementScreen(navController = navController)
        }

        // 版本化采集设备档案库
        composable(route = Screen.AcquisitionProfileManagement.route) {
            AcquisitionProfileManagementScreen(navController = navController)
        }

        // 手动曲线输入页面
        composable(route = Screen.ManualCurveInput.route) {
            ManualCurveInputScreen(navController = navController)
        }

        // 手动数据输入页面
        composable(
            route = "${Screen.ManualDataInput.route}?modelId={modelId}",
            arguments = listOf(
                navArgument("modelId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            ManualDataInputScreen(navController = navController)
        }

        // 实验模板管理页面
        composable(route = Screen.ExperimentTemplateManagement.route) {
            ExperimentTemplateManagementScreen(navController = navController)
        }

        // 创建/编辑实验模板页面
        composable(
            route = "${Screen.CreateExperimentTemplate.route}?templateId={templateId}&sourceTemplateId={sourceTemplateId}",
            arguments = listOf(
                navArgument("templateId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("sourceTemplateId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val templateId = backStackEntry.arguments?.getString("templateId")
            val sourceTemplateId = backStackEntry.arguments?.getString("sourceTemplateId")
            // 直接编辑仅用于草稿；复制已发布方案会先创建独立的新版本草稿。
            ExperimentTemplateWizardScreen(
                navController = navController,
                templateId = templateId,
                sourceTemplateId = sourceTemplateId
            )
        }

        // 历史记录页面
        composable(route = Screen.History.route) {
            HistoryScreen(navController = navController)
        }

        // 光谱标定页面
        composable(
            route = Screen.SpectrumCalibration.route,
            arguments = listOf(
                navArgument("projectId") {
                    type = NavType.StringType
                },
                navArgument("imagePath") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId")
            val imagePath = backStackEntry.arguments?.getString("imagePath")
            SpectrumCalibrationScreen(
                navController = navController,
                projectId = projectId,
                imageUri = imagePath
            )
        }
        
        // 光谱结果展示页面
        composable(
            route = Screen.SpectrumResult.route,
            arguments = listOf(
                navArgument("projectId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            SpectrumResultScreen(
                navController = navController,
                projectId = projectId
            )
        }

        // 其他导航路由...
    }
}

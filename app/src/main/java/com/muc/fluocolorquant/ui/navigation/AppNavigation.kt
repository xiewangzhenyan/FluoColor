package com.muc.fluocolorquant.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.muc.fluocolorquant.ui.screens.auth.LoginScreen
import com.muc.fluocolorquant.ui.screens.auth.RegisterScreen
import com.muc.fluocolorquant.ui.screens.curvefitting.CurveFittingScreen
import com.muc.fluocolorquant.ui.screens.detection.WellDetectionScreen
import com.muc.fluocolorquant.ui.screens.home.HomeScreen
import com.muc.fluocolorquant.ui.screens.imagecrop.ImageCropScreen
import com.muc.fluocolorquant.ui.screens.image.ImageCorrectionScreen
import com.muc.fluocolorquant.ui.screens.profile.ProfileScreen
import com.muc.fluocolorquant.ui.screens.project.NewProjectScreen
import com.muc.fluocolorquant.ui.screens.result.ResultScreen
import com.muc.fluocolorquant.ui.screens.settings.AppSettingsScreen
import com.muc.fluocolorquant.ui.screens.settings.DetectionSettingsScreen
import com.muc.fluocolorquant.ui.screens.settings.SettingsScreen
import com.muc.fluocolorquant.ui.screens.splash.SplashScreen
import com.muc.fluocolorquant.ui.screens.history.HistoryScreen
import com.muc.fluocolorquant.ui.screens.settings.AnalyteManagementScreen
import com.muc.fluocolorquant.ui.screens.settings.ReagentLibraryScreen
import com.muc.fluocolorquant.ui.screens.settings.CurveModelManagementScreen
import com.muc.fluocolorquant.ui.screens.settings.ManualCurveInputScreen
import com.muc.fluocolorquant.ui.screens.settings.ManualDataInputScreen
import com.muc.fluocolorquant.ui.screens.settings.ExperimentTemplateManagementScreen
import com.muc.fluocolorquant.ui.screens.settings.CreateExperimentTemplateScreen
import com.muc.fluocolorquant.utils.animatedComposable

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
        
        // 添加新项目创建页面
        composable(route = Screen.NewProject.route) {
            NewProjectScreen(navController = navController)
        }
        
        // 图片裁剪页面
        composable(
            route = "${Screen.ImageCrop.route}?imageUri={imageUri}",
            arguments = listOf(
                navArgument("imageUri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val imageUriString = backStackEntry.arguments?.getString("imageUri")
            val imageUri = if (imageUriString != null) Uri.parse(imageUriString) else null
            ImageCropScreen(
                navController = navController,
                imageUri = imageUriString
            )
        }
        
        // 图像矫正页面 - 更新为路径参数格式
        composable(
            route = "${Screen.ImageCorrection.route}/{imageUri}/{projectId}",
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
        
        // 孔阵检测页面 - 更新为路径参数格式
        composable(
            route = "${Screen.WellDetection.route}/{imageUri}/{projectId}",
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
        
        // 曲线拟合/浓度预测页面 - 更新为路径参数格式
        composable(
            route = "${Screen.CurveFitting.route}/{runId}?imageUri={imageUri}",
            arguments = listOf(
                navArgument("runId") {
                    type = NavType.StringType
                },
                navArgument("imageUri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val runId = backStackEntry.arguments?.getString("runId")
            val imageUri = backStackEntry.arguments?.getString("imageUri")
            CurveFittingScreen(
                navController = navController,
                runId = runId,
                imageUri = imageUri
            )
        }
        
        // 结果展示页面 - 更新为路径参数格式
        composable(
            route = "${Screen.Result.route}/{runId}",
            arguments = listOf(
                navArgument("runId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val runId = backStackEntry.arguments?.getString("runId")
            ResultScreen(
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

        // 分析物管理页面
        composable(route = Screen.AnalyteManagement.route) {
            AnalyteManagementScreen(
                // 移除 navController = navController
                navigateBack = { navController.navigateUp() }
            )
        }

        // 试剂库页面
        composable(route = Screen.ReagentLibrary.route) {
            ReagentLibraryScreen(
                // 移除 navController = navController
                navigateBack = { navController.navigateUp() }
            )
        }
        
        // 曲线模型库页面
        composable(route = Screen.CurveModelLibrary.route) {
            CurveModelManagementScreen(navController = navController)
        }
        
        // 手动曲线输入页面
        composable(route = Screen.ManualCurveInput.route) {
            ManualCurveInputScreen(navController = navController)
        }
        
        // 手动数据输入页面
        composable(route = Screen.ManualDataInput.route) {
            ManualDataInputScreen(navController = navController)
        }
        
        // 实验模板管理页面
        composable(route = Screen.ExperimentTemplateManagement.route) {
            ExperimentTemplateManagementScreen(navController = navController)
        }
        
        // 创建/编辑实验模板页面
        composable(
            route = "${Screen.CreateExperimentTemplate.route}?templateId={templateId}",
            arguments = listOf(
                navArgument("templateId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val templateId = backStackEntry.arguments?.getString("templateId")
            CreateExperimentTemplateScreen(
                navController = navController,
                templateId = templateId
            )
        }
        
        // 历史记录页面
        composable(route = Screen.History.route) {
            HistoryScreen(navController = navController)
        }
        
        // 其他导航路由...
    }
} 
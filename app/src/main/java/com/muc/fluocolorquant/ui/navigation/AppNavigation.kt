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
import com.muc.fluocolorquant.ui.screens.settings.SettingsScreen
import com.muc.fluocolorquant.ui.screens.splash.SplashScreen
import com.muc.fluocolorquant.ui.screens.history.HistoryScreen
import com.muc.fluocolorquant.ui.navigation.Screen

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
        
        // 历史记录页面
        composable(route = Screen.History.route) {
            HistoryScreen(navController = navController)
        }
        
        // 其他导航路由...
    }
} 
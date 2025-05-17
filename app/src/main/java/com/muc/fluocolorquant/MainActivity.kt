package com.muc.fluocolorquant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastHost
import com.muc.fluocolorquant.ui.components.ToastManager
import com.muc.fluocolorquant.ui.navigation.AppNavigation
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 确保状态栏可见（不全屏）
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        // 创建全局Toast管理器
        val toastManager = ToastManager()
        
        setContent {
            // 提供Toast管理器给所有子Composable
            CompositionLocalProvider(LocalToastManager provides toastManager) {
                FluoColorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavigation(navController = navController)
                        
                        // 添加Toast宿主，用于显示全局Toast
                        ToastHost()
                    }
                }
            }
        }
    }
}
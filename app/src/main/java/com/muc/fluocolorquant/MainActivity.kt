package com.muc.fluocolorquant

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastHost
import com.muc.fluocolorquant.ui.components.ToastManager
import com.muc.fluocolorquant.ui.navigation.AppNavigation
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.utils.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    @Inject
    lateinit var settingsRepository: SettingsRepository
    
    private val toastManager by lazy { ToastManager() }
    
    override fun attachBaseContext(newBase: Context) {
        // FluoColorApp 提供的 newBase 上下文已经本地化。
        // 无需在此处重新包装或再次应用语言环境。
        super.attachBaseContext(newBase)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 直接读取并立即应用当前存储的语言设置
        val currentLanguage = runBlocking { 
            try {
                settingsRepository.languageFlow.first()
            } catch (e: Exception) {
                LocaleHelper.getSystemLanguage()
            }
        }

        // 强制更新当前Activity的配置
        LocaleHelper.updateActivityLocale(this, currentLanguage)
        Log.d(TAG, "onCreate: Forcefully applied language: $currentLanguage")

        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        // 监听语言变化 - 仅监听语言变化，不监听其他设置
        lifecycleScope.launch {
            // 直接使用languageFlow而不是通过DataStore间接监听
            // 这样可以确保只有语言变化时才会触发重启
            settingsRepository.languageFlow
                .drop(1) // 忽略初始值，只对后续变化做出反应
                .distinctUntilChanged() 
                .collect { newLanguage ->
                    Log.d(TAG, "Language preference changed to: $newLanguage. Recreating activity.")
                    // 使用更明确的方式重启Activity，确保重建时加载新语言
                    restartActivity()
                }
        }
        
        setContent {
        // Activity 的上下文（因此默认情况下为 LocalContext.current）
        // 已由 FluoColorApp.attachBaseContext 和此 Activity 的 attachmentBaseContext 配置。
        // 可组合函数（如 stringResource()）将使用此上下文。
            ActualAppContent()
        }
    }

    /**
     * 使用更明确的方式重启Activity，确保正确应用语言设置
     */
    fun restartActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        finish()
        startActivity(intent)
    }

    @Composable
    private fun ActualAppContent() {
        CompositionLocalProvider(LocalToastManager provides toastManager) {
            FluoColorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavigation(navController = navController)
                    ToastHost()
                }
            }
        }
    }
}
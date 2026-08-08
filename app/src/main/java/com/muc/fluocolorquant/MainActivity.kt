package com.muc.fluocolorquant

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.muc.fluocolorquant.data.repository.AppLanguageStore
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
    
    /**
     * 用当前语言包装 Activity 的 Context。
     *
     * 这一步不能省：`recreate()` 只重建 Activity，不会再次触发
     * `FluoColorApp.attachBaseContext`，因此语言切换后必须由这里重新套用新 Locale，
     * 否则界面要等到进程重启才会变。
     *
     * 这里也是全应用唯一读取语言偏好的地方。Application 的 attachBaseContext 无法可靠读取
     * DataStore（那时 applicationContext 尚未就绪），因此不在那里做。首次读盘后结果进缓存，
     * 语言切换触发的重建只命中内存。
     */
    override fun attachBaseContext(newBase: Context) {
        val languageCode = AppLanguageStore.currentLanguageBlocking(newBase)
        Log.i(TAG, "attachBaseContext: 应用语言 $languageCode")
        super.attachBaseContext(LocaleHelper.updateLocale(newBase, languageCode))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 语言不在此处再读一次：FluoColorApp.attachBaseContext 已经用保存的语言构造了
        // 本地化 Context，Activity 继承该 Context，stringResource() 直接取到正确资源。
        // 原实现在这里又做了一次 runBlocking 读盘并调用已废弃的
        // resources.updateConfiguration()，属于对同一件事的重复处理。
        //
        // 主题模式仍需一个初始值：themeModeFlow 是冷流，collectAsState 的首帧会先用
        // initial 值渲染。若这里给 DEFAULT_THEME_MODE，深色模式用户每次冷启动都会先闪
        // 一帧浅色。因此保留这次同步读取，代价是一次磁盘读。
        val initialThemeMode = runBlocking {
            runCatching { settingsRepository.themeModeFlow.first() }
                .getOrDefault(SettingsRepository.DEFAULT_THEME_MODE)
        }
        Log.d(TAG, "onCreate: initial theme mode = $initialThemeMode")

        WindowCompat.setDecorFitsSystemWindows(window, true)

        // 语言变化需要重新创建 Activity，让 Application 的 attachBaseContext 用新语言
        // 重新构造 Context。这里只监听语言，其他设置（主题等）由 Compose 重组处理，
        // 不触发重建。
        lifecycleScope.launch {
            settingsRepository.languageFlow
                .drop(1) // 忽略初始值，只对后续变化做出反应
                .distinctUntilChanged()
                .collect { newLanguage ->
                    Log.d(TAG, "语言偏好变为 $newLanguage，重建 Activity 以套用新资源")
                    recreate()
                }
        }

        setContent {
            // Activity 的 Context 已由 FluoColorApp.attachBaseContext 本地化，
            // stringResource() 等可组合函数直接使用该 Context。
            ActualAppContent(initialThemeMode = initialThemeMode)
        }
    }

    @Composable
    private fun ActualAppContent(initialThemeMode: String) {
        val themeMode by settingsRepository.themeModeFlow.collectAsState(initial = initialThemeMode)
        val useDarkTheme = when (themeMode) {
            SettingsRepository.THEME_MODE_LIGHT -> false
            SettingsRepository.THEME_MODE_DARK -> true
            else -> isSystemInDarkTheme()
        }

        CompositionLocalProvider(LocalToastManager provides toastManager) {
            FluoColorTheme(darkTheme = useDarkTheme) {
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

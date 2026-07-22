package com.muc.fluocolorquant

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.utils.LocaleHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.opencv.android.OpenCVLoader
import javax.inject.Inject

// 在 FluoColorApp 无需 Hilt 实例即可访问的级别上定义 DataStore 名称和键
private const val LANGUAGE_SETTINGS_NAME = "language_settings"
private val LANGUAGE_PREF_KEY = stringPreferencesKey("language")
private val Context.appLanguageDataStore by preferencesDataStore(name = LANGUAGE_SETTINGS_NAME)

@HiltAndroidApp
class FluoColorApp : Application() {

    // 在 onCreate 之后，仍可注入 SettingsRepository 以供应用程序的其他部分使用
    @Inject
    lateinit var settingsRepository: SettingsRepository
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun attachBaseContext(base: Context) {
        val languageCode = runBlocking {
            try {
                base.appLanguageDataStore.data.map {
                    it[LANGUAGE_PREF_KEY] ?: LocaleHelper.getSystemLanguage()
                }.first()
            } catch (e: Exception) {
                // 如果读取 DataStore 时出现任何错误，则返回系统语言
                LocaleHelper.getSystemLanguage()
            }
        }
        val localizedContext = LocaleHelper.updateLocale(base, languageCode)
        super.attachBaseContext(localizedContext)
    }

    override fun onCreate() {
        super.onCreate()

        // PG-Grid、孔板校正和光谱处理都会直接创建 OpenCV Mat。必须在任何页面或
        // ViewModel 启动后台任务之前统一加载本地库，否则测试环境可以通过、正式页面却会
        // 在首次调用 Mat.n_Mat() 时触发 UnsatisfiedLinkError 并使整个进程崩溃。
        val openCvReady = runCatching { OpenCVLoader.initDebug() }
            .onFailure { error ->
                Log.e("FluoColorApp", "OpenCV 全局初始化异常", error)
            }
            .getOrDefault(false)
        if (openCvReady) {
            Log.i("FluoColorApp", "OpenCV 全局初始化成功")
        } else {
            // 这里不主动结束应用：非图像管理页面仍可打开；实际检测入口会显示执行失败，
            // 比直接产生 native linkage 崩溃更便于用户恢复和开发阶段定位问题。
            Log.e("FluoColorApp", "OpenCV 全局初始化失败，图像检测功能暂不可用")
        }

        // Hilt 注入到此完成。settingsRepository 可用。
        // 如果还有其他依赖于 settingsRepository 的应用级初始化，
        // 也可以在此处完成。对于语言，attachBaseContext 已经处理了初始设置。
        applicationScope.launch {
            val currentLang = settingsRepository.languageFlow.first() // For logging or other non-UI tasks
            Log.i("FluoColorApp", "Application onCreate: Language set to: $currentLang")
            
            // 记录其他设置信息
            val detectionMode = settingsRepository.defaultDetectionModeFlow.first()
            val concentrationUnit = settingsRepository.defaultConcentrationUnitFlow.first()
            Log.i("FluoColorApp", "Default settings: Detection mode: $detectionMode, Concentration unit: $concentrationUnit")
        }
    }
}

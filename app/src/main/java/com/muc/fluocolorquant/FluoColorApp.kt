package com.muc.fluocolorquant

import android.app.Application
import android.content.Context
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
        // Hilt 注入到此完成。settingsRepository 可用。
        // 如果还有其他依赖于 settingsRepository 的应用级初始化，
        // 也可以在此处完成。对于语言，attachBaseContext 已经处理了初始设置。
        applicationScope.launch {
            val currentLang = settingsRepository.languageFlow.first() // For logging or other non-UI tasks
            android.util.Log.i("FluoColorApp", "Application onCreate: Language set to: $currentLang")
            
            // 记录其他设置信息
            val detectionMode = settingsRepository.defaultDetectionModeFlow.first()
            val concentrationUnit = settingsRepository.defaultConcentrationUnitFlow.first()
            android.util.Log.i("FluoColorApp", "Default settings: Detection mode: $detectionMode, Concentration unit: $concentrationUnit")
        }
    }
} 
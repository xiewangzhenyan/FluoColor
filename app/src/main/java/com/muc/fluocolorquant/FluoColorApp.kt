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

// Define DataStore name and key at a level accessible by FluoColorApp without Hilt instance
private const val LANGUAGE_SETTINGS_NAME = "language_settings"
private val LANGUAGE_PREF_KEY = stringPreferencesKey("language")
private val Context.appLanguageDataStore by preferencesDataStore(name = LANGUAGE_SETTINGS_NAME)

@HiltAndroidApp
class FluoColorApp : Application() {
    
    // SettingsRepository can still be injected for use in other parts of the app after onCreate
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
                // Fallback to system language in case of any error reading DataStore
                LocaleHelper.getSystemLanguage()
            }
        }
        val localizedContext = LocaleHelper.updateLocale(base, languageCode)
        super.attachBaseContext(localizedContext)
    }

    override fun onCreate() {
        super.onCreate()
        // Hilt injection is complete here. settingsRepository is available.
        // If there are other app-wide initializations that depend on settingsRepository,
        // they can be done here. For language, attachBaseContext has already handled initial setup.
        // We might still want to log the applied language or perform other related tasks.
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
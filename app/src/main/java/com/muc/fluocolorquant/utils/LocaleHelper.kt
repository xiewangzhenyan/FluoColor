package com.muc.fluocolorquant.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.muc.fluocolorquant.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * 语言设置辅助类，用于在运行时更改应用的语言
 */
object LocaleHelper {
    
    private const val TAG = "LocaleHelper"

    /**
     * 获取系统默认语言代码
     */
    fun getSystemLanguage(): String {
        val locale = Locale.getDefault()
        return when (locale.language) {
            "zh" -> "zh"
            else -> "en"
        }
    }
    
    /**
     * 更新Context的语言配置
     * @param context 原始Context
     * @param languageCode 语言代码 (如 "en", "zh")
     * @return 更新语言设置后的Context
     */
    fun updateLocale(context: Context, languageCode: String): ContextWrapper {
        var newContext = context
        val resources = context.resources
        val configuration = Configuration(resources.configuration)
        val locale = createLocale(languageCode)
        
        Locale.setDefault(locale)
        Log.d(TAG, "updateLocale: Setting locale to $languageCode")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
            newContext = context.createConfigurationContext(configuration)
        } else {
            configuration.locale = locale
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        
        return ContextWrapper(newContext)
    }
    
    /**
     * 更新Activity的语言配置
     * 在onCreate中直接调用，确保UI正确应用语言
     */
    fun updateActivityLocale(activity: Activity, languageCode: String) {
        val locale = createLocale(languageCode)
        Locale.setDefault(locale)
        
        val resources = activity.resources
        val configuration = Configuration(resources.configuration)
        
        Log.d(TAG, "updateActivityLocale: Forcefully setting Activity locale to $languageCode")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
        } else {
            configuration.locale = locale
        }
        
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
    
    /**
     * 创建Locale实例
     */
    private fun createLocale(languageCode: String): Locale {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> {
                Locale.forLanguageTag(languageCode)
            }
            else -> {
                Locale(languageCode)
            }
        }
    }
    
    /**
     * 为Activity应用语言设置的便捷方法
     * @param context 原始Context
     * @param settingsRepository 设置存储库实例
     * @return 更新了语言设置的Context
     */
    fun applyLanguageContext(context: Context, settingsRepository: SettingsRepository): Context {
        return runBlocking {
            try {
                // 获取当前语言设置
                val languageCode = settingsRepository.languageFlow.first()
                Log.d(TAG, "applyLanguageContext: Applied language $languageCode from repository")
                updateLocale(context, languageCode).baseContext
            } catch (e: Exception) {
                Log.e(TAG, "applyLanguageContext: Failed to get language from repository", e)
                // 如果出现异常，使用系统默认语言
                context
            }
        }
    }
    
    /**
     * 不依赖SettingsRepository的安全方法
     * 使用系统默认语言，适用于依赖注入尚未完成的情况
     * @param context 原始Context
     * @return 更新了语言设置的Context
     */
    fun applyDefaultLanguageContext(context: Context): Context {
        val systemLanguage = getSystemLanguage()
        Log.d(TAG, "applyDefaultLanguageContext: Using system language: $systemLanguage")
        return updateLocale(context, systemLanguage).baseContext
    }
} 
package com.muc.fluocolorquant.utils

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 语言环境包装。
 *
 * 职责收敛为一件事：把给定 Context 包装成使用指定语言的新 Context。调用点只有两处，
 * 都是 `attachBaseContext`——`FluoColorApp` 决定应用级资源，`MainActivity` 保证
 * `recreate()` 后能套用新语言。
 *
 * 语言偏好的读写不在这里，统一由 `AppLanguageStore` 负责。
 *
 * 此前本类还提供 `updateActivityLocale`、`applyLanguageContext` 和
 * `applyDefaultLanguageContext` 三个方法，均已无人调用：其中
 * `updateActivityLocale` 使用 API 25 起废弃的 `Resources.updateConfiguration()`，
 * 直接改写全局资源配置，是语言状态难以追踪的来源之一，已随本次清理删除。
 */
object LocaleHelper {

    /**
     * 返回使用 [languageCode] 的 Context。
     *
     * 同时设置 JVM 默认 Locale：日期、数字格式化和部分第三方库读取的是
     * `Locale.getDefault()`，只换 Context 会让它们仍按系统语言输出。
     */
    fun updateLocale(context: Context, languageCode: String): Context {
        val locale = Locale.forLanguageTag(languageCode)
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }
}

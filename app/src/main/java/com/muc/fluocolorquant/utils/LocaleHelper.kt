package com.muc.fluocolorquant.utils

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 语言环境包装。
 *
 * 职责收敛为一件事：把给定 Context 包装成使用指定语言的新 Context。Activity 的
 * `attachBaseContext` 用它决定界面语言；后台报告导出也用同一规则构造只影响本次任务的
 * 本地化 Context，避免 `ApplicationContext` 按系统语言读取资源。
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

        return createLocalizedContext(context, languageCode)
    }

    /**
     * 创建只影响调用方的本地化 Context，不改写进程默认 Locale。
     *
     * 后台 PDF 导出需要跟随应用内语言，但不应在工作线程再次修改全局 Locale；否则并行的
     * 日期格式化或第三方库可能在一次任务中途切换语言。Activity 启动仍使用 [updateLocale]，
     * 因为界面语言切换确实需要同步进程默认 Locale。
     */
    fun createLocalizedContext(context: Context, languageCode: String): Context {
        val locale = Locale.forLanguageTag(languageCode)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }
}

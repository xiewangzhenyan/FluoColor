package com.muc.fluocolorquant.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * 应用语言偏好的唯一存储入口。
 *
 * 此前 `FluoColorApp` 与 `SettingsRepository` 各自用 `preferencesDataStore(name = "language_settings")`
 * 声明了一个委托，同一进程里因此存在两个指向同一文件的 DataStore 实例。DataStore 明确
 * 禁止这种用法：`SingleProcessDataStore` 会把文件路径登记到进程级的 activeFiles 集合，
 * 第二个实例初始化时抛 `IllegalStateException`。
 *
 * 之所以一直没有暴露成崩溃，是因为 `FluoColorApp.attachBaseContext` 把异常整个吞掉并回退
 * 到系统语言——也就是说一旦触发，用户保存的语言偏好会被静默忽略，冷启动直接变回系统语言。
 * 这类"看起来能用、偶尔失效"的行为正是语言设置反复出问题的根因。
 *
 * 这里把委托收敛到唯一一处：`FluoColorApp` 在 Hilt 尚未就绪时按 Context 直接取用，
 * `SettingsRepository` 注入后仍走同一个实例，两边共享同一份状态。
 */
private val Context.languagePreferences by preferencesDataStore(name = AppLanguageStore.STORE_NAME)

object AppLanguageStore {

    /** DataStore 文件名。历史数据沿用该名称，改名会让已保存的语言偏好丢失。 */
    const val STORE_NAME: String = "language_settings"

    /** 语言偏好键。历史数据沿用该键名。 */
    val LANGUAGE_KEY: Preferences.Key<String> = stringPreferencesKey("language")

    /**
     * 取得进程内唯一的语言 DataStore。
     *
     * `preferencesDataStore` 委托在首次访问时创建实例并记忆下来，之后无论传入哪个 Context
     * 都返回同一个对象。因此这里不需要（也不应该）自行做 applicationContext 兜底：
     * `attachBaseContext` 阶段的 applicationContext 尚未完成构造，反而会引入不确定性。
     */
    fun dataStore(context: Context): DataStore<Preferences> = context.languagePreferences

    /**
     * 进程内的语言缓存。
     *
     * `Application` 与 `MainActivity` 的 `attachBaseContext` 都必须同步拿到语言：前者决定
     * 应用级资源，后者保证 `recreate()` 之后能套用新语言（`recreate()` 不会重新触发
     * Application 的 attachBaseContext）。若两处各读一次 DataStore，冷启动就会在主线程
     * 阻塞读盘两次。
     *
     * 这里让首次读取填充缓存，后续 attachBaseContext 直接命中内存；[setLanguage] 写入时
     * 同步更新缓存，因此语言切换后重建的 Activity 立即拿到新值。
     */
    @Volatile
    private var cachedLanguage: String? = null

    /** 当前语言偏好；未设置过时回退到系统语言。 */
    fun languageFlow(context: Context): Flow<String> = dataStore(context).data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: systemLanguage()
    }

    /**
     * 供 `Activity.attachBaseContext` 使用的同步读取。
     *
     * 该阶段无法异步：方法返回后系统立刻用该 Context 解析资源。首次调用读盘并缓存，
     * 之后只命中内存。
     *
     * 只能在 Activity 阶段调用，不能在 `Application.attachBaseContext` 调用：
     * `preferencesDataStore` 委托创建实例时要取 `thisRef.applicationContext` 来定位数据文件，
     * 而 Application 执行 attachBaseContext 时自身尚未构造完成，该值不可用，读取会失败。
     * 这正是"用户明明选了中文、冷启动却回到英文"的根因——失败被回退逻辑掩盖成了系统语言。
     *
     * 读取失败时返回系统语言但**不写入缓存**：失败多为时序问题，缓存下来会让后续本可成功
     * 的读取也一直拿到错误值。
     */
    fun currentLanguageBlocking(context: Context): String {
        cachedLanguage?.let { return it }
        val result = runBlocking {
            runCatching { languageFlow(context).first() }
        }
        return result.getOrNull()?.also { cachedLanguage = it } ?: systemLanguage()
    }

    /** 写入语言偏好，并同步更新进程内缓存。 */
    suspend fun setLanguage(context: Context, languageCode: String) {
        dataStore(context).edit { preferences ->
            preferences[LANGUAGE_KEY] = languageCode
        }
        cachedLanguage = languageCode
    }

    /** 系统语言。应用只提供中英文两种资源，其余一律按英文处理。 */
    fun systemLanguage(): String = when (Locale.getDefault().language) {
        "zh" -> "zh"
        else -> "en"
    }
}

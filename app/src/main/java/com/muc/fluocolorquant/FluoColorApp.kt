package com.muc.fluocolorquant

import android.app.Application
import android.content.Context
import android.util.Log
import com.muc.fluocolorquant.data.repository.AppLanguageStore
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.data.storage.ProjectFileCleaner
import com.muc.fluocolorquant.utils.LocaleHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.opencv.android.OpenCVLoader
import javax.inject.Inject

@HiltAndroidApp
class FluoColorApp : Application() {

    private companion object {
        const val TAG = "FluoColorApp"
        const val SCIENTIFIC_GENERATION_PREFERENCES = "scientific_data_generation"
        const val SCIENTIFIC_GENERATION_KEY = "generation"
        const val SCIENTIFIC_GENERATION_V2 = 2
    }

    // 在 onCreate 之后，仍可注入 SettingsRepository 以供应用程序的其他部分使用
    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** 强制数据库先完成 17→18 显式代际迁移，再清理对应私有运行文件。 */
    @Inject
    lateinit var appDatabase: AppDatabase

    @Inject
    lateinit var projectFileCleaner: ProjectFileCleaner
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 在进程创建的最早阶段套用语言。
     *
     * 这里必须同步读取：`attachBaseContext` 返回后 Android 就会用该 Context 解析资源，
     * 异步读取来不及。此处的 `runBlocking` 是这条链路上唯一保留的同步读盘。
     *
     * 语言存储走 [AppLanguageStore] 这一个入口。此前本类另建了一个指向同一文件的
     * DataStore 委托，与 `SettingsRepository` 的实例冲突；异常又被整个 catch 吞掉并静默
     * 回退系统语言，导致"用户明明选了中文、冷启动却变回英文"这类难以复现的问题。
     *
     * 读取失败时仍回退系统语言（否则应用无资源可用），但会记录错误而不是静默吞掉。
     */
    /**
     * 刻意不在这里读取语言偏好。
     *
     * `preferencesDataStore` 委托创建实例时需要 `thisRef.applicationContext` 定位数据文件，
     * 而 Application 执行 attachBaseContext 时自身尚未构造完成，该值不可用，读取必然失败。
     * 旧实现在这里读 DataStore 并把异常整个 catch 掉回退系统语言，于是"用户选了中文、
     * 冷启动却是英文"——存储里明明是 zh，日志里 attachBaseContext 却打印 en。
     *
     * 语言改由 `MainActivity.attachBaseContext` 套用：那时 Application 已就绪，读取可靠，
     * 且 `LocaleHelper.updateLocale` 会同时设置进程级 `Locale.getDefault()`，
     * 依赖它的日期与数字格式化同样跟随。
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()

        // PG-Grid、孔板校正和光谱处理都会直接创建 OpenCV Mat。必须在任何页面或
        // ViewModel 启动后台任务之前统一加载本地库，否则测试环境可以通过、正式页面却会
        // 在首次调用 Mat.n_Mat() 时触发 UnsatisfiedLinkError 并使整个进程崩溃。
        val openCvReady = runCatching { OpenCVLoader.initDebug() }
            .onFailure { error ->
                Log.e(TAG, "OpenCV 全局初始化异常", error)
            }
            .getOrDefault(false)
        if (openCvReady) {
            Log.i(TAG, "OpenCV 全局初始化成功")
        } else {
            // 这里不主动结束应用：非图像管理页面仍可打开；实际检测入口会显示执行失败，
            // 比直接产生 native linkage 崩溃更便于用户恢复和开发阶段定位问题。
            Log.e(TAG, "OpenCV 全局初始化失败，图像检测功能暂不可用")
        }

        // Hilt 注入到此完成。settingsRepository 可用。
        // 如果还有其他依赖于 settingsRepository 的应用级初始化，
        // 也可以在此处完成。对于语言，attachBaseContext 已经处理了初始设置。
        applicationScope.launch {
            val currentLang = settingsRepository.languageFlow.first() // For logging or other non-UI tasks
            Log.i(TAG, "Application onCreate: Language set to: $currentLang")
            
            // 记录其他设置信息
            val detectionMode = settingsRepository.defaultDetectionModeFlow.first()
            val concentrationUnit = settingsRepository.defaultConcentrationUnitFlow.first()
            Log.i(TAG, "Default settings: Detection mode: $detectionMode, Concentration unit: $concentrationUnit")
        }

        applicationScope.launch(Dispatchers.IO) {
            val generationStore = getSharedPreferences(
                SCIENTIFIC_GENERATION_PREFERENCES,
                Context.MODE_PRIVATE
            )
            if (generationStore.getInt(SCIENTIFIC_GENERATION_KEY, 0) < SCIENTIFIC_GENERATION_V2) {
                runCatching {
                    // 打开 writableDatabase 会同步执行 Room 17→18 迁移；文件清理必须排在
                    // 它之后，避免数据库仍指向已经删除的证据文件。
                    appDatabase.openHelper.writableDatabase
                    projectFileCleaner.cleanLegacyScientificGeneration()
                }.onSuccess { report ->
                    if (report.failedCount == 0) {
                        generationStore.edit()
                            .putInt(SCIENTIFIC_GENERATION_KEY, SCIENTIFIC_GENERATION_V2)
                            .apply()
                        Log.i(TAG, "V2 科研数据代际已就绪，清理文件 ${report.deletedCount} 个")
                    }
                }.onFailure { error ->
                    // 不写完成标记，下次冷启动自动重试。异常不会阻止设置或非检测页面打开。
                    Log.e(TAG, "V2 科研数据代际初始化失败，将在下次启动重试", error)
                }
            }
        }
    }

}

package com.muc.fluocolorquant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.R // 新增：导入R文件以便访问字符串资源
import com.muc.fluocolorquant.data.dao.DetectionRunDao
import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import android.app.Application // 新增：为了访问应用上下文以获取字符串

/**
 * 结果展示ViewModel
 * 管理检测结果展示的UI逻辑
 */
@HiltViewModel
class ResultViewModel @Inject constructor(
    private val wellResultDao: WellResultDao,
    private val projectDao: ProjectDao,
    private val detectionRunDao: DetectionRunDao,
    private val application: Application // 新增：注入Application以获取Context
) : ViewModel() {
    // 结果数据加载状态
    sealed class ResultState {
        object Loading : ResultState()
        data class Success(
            val project: Project,
            val wellResults: List<WellResult>,
            val detectionRun: DetectionRun?
        ) : ResultState()
        data class Error(val message: String) : ResultState()
    }

    // 当前结果状态
    private val _resultState = MutableStateFlow<ResultState>(ResultState.Loading)
    val resultState: StateFlow<ResultState> = _resultState.asStateFlow()

    // 浓度单位 - 将从项目数据动态更新
    private val _concentrationUnit = MutableStateFlow("ng/ml") // 默认值，会被覆盖
    val concentrationUnit: StateFlow<String> = _concentrationUnit.asStateFlow()

    // 热力图颜色范围
    private val _minConcentration = MutableStateFlow(0.0)
    val minConcentration: StateFlow<Double> = _minConcentration.asStateFlow()

    private val _maxConcentration = MutableStateFlow(100.0)
    val maxConcentration: StateFlow<Double> = _maxConcentration.asStateFlow()

    /**
     * 根据项目ID加载结果数据
     * 用于手动裁剪模式
     */
    fun loadResultsByProjectId(projectId: String) {
        _resultState.value = ResultState.Loading

        viewModelScope.launch {
            try {
                // 加载项目信息
                val project = withContext(Dispatchers.IO) {
                    projectDao.getProjectById(projectId)
                }

                if (project == null) {
                    _resultState.value = ResultState.Error(application.getString(R.string.error_project_not_found))
                    return@launch
                }

                // 更新浓度单位
                _concentrationUnit.value = project.concentrationUnit ?: "ng/ml" // 如果项目中没有单位，则回退到 "ng/ml"

                // 对于手动模式，加载该项目下的所有孔位结果
                val wellResults = withContext(Dispatchers.IO) {
                    wellResultDao.getWellResultsByProjectId(projectId)
                }

                // 设置浓度范围
                updateConcentrationRange(wellResults, project.maxConcentration)

                // 更新结果状态
                _resultState.value = ResultState.Success(
                    project = project,
                    wellResults = wellResults,
                    detectionRun = null // 手动模式下不需要检测运行记录
                )
            } catch (e: Exception) {
                Log.e("ResultViewModel", "加载项目结果失败", e)
                _resultState.value = ResultState.Error(application.getString(R.string.error_loading_results, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * 根据运行ID加载结果数据
     * 用于自动识别模式
     */
    fun loadResultsByRunId(runId: String) {
        _resultState.value = ResultState.Loading

        viewModelScope.launch {
            try {
                // 加载检测运行记录
                val detectionRun = withContext(Dispatchers.IO) {
                    detectionRunDao.getDetectionRunById(runId)
                }

                if (detectionRun == null) {
                    _resultState.value = ResultState.Error(application.getString(R.string.error_run_not_found))
                    return@launch
                }

                // 加载项目信息
                val project = withContext(Dispatchers.IO) {
                    projectDao.getProjectById(detectionRun.projectId)
                }

                if (project == null) {
                    _resultState.value = ResultState.Error(application.getString(R.string.error_project_not_found))
                    return@launch
                }

                // 更新浓度单位
                _concentrationUnit.value = project.concentrationUnit ?: "ng/ml" // 如果项目中没有单位，则回退到 "ng/ml"

                // 加载孔位结果
                val wellResults = withContext(Dispatchers.IO) {
                    wellResultDao.getWellResultsByRunId(runId)
                }

                // 设置浓度范围
                updateConcentrationRange(wellResults, project.maxConcentration)

                // 更新结果状态
                _resultState.value = ResultState.Success(
                    project = project,
                    wellResults = wellResults,
                    detectionRun = detectionRun
                )
            } catch (e: Exception) {
                Log.e("ResultViewModel", "加载运行结果失败", e)
                _resultState.value = ResultState.Error(application.getString(R.string.error_loading_results, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * 更新浓度范围
     */
    private fun updateConcentrationRange(wellResults: List<WellResult>, projectMaxConcentration: Double?) {
        // 确保有设定最大浓度值，否则使用默认值100.0
        val maxConc = projectMaxConcentration ?: 100.0

        // 找出所有有效浓度百分比
        val validPercentages = wellResults
            .mapNotNull { it.predictedConcentration }
            .filter { it.isFinite() && it >= 0 && it <= 100 }

        if (validPercentages.isNotEmpty()) {
            // 最小浓度总是从0开始
            _minConcentration.value = 0.0

            // 最大浓度是百分比的最大值（最大100%）乘以项目设定的最大浓度值
            // 如果没有有效预测值，则使用100%（完全饱和）
            val maxPercentage = validPercentages.maxOrNull() ?: 100.0
            _maxConcentration.value = (maxPercentage / 100.0) * maxConc
        } else {
            // 默认范围
            _minConcentration.value = 0.0
            _maxConcentration.value = maxConc
        }
    }

    /**
     * 计算实际浓度值（将百分比转换为实际浓度）
     * @param percentValue 浓度百分比（0-100）
     * @param maxConcentration 最大浓度值
     * @return 实际浓度值
     */
    fun calculateActualConcentration(percentValue: Double?, maxConcentration: Double?): Double? {
        if (percentValue == null || !percentValue.isFinite() || percentValue < 0) {
            return null
        }

        val maxConc = maxConcentration ?: 100.0 // 如果项目没有最大浓度，默认使用100.0
        return (percentValue / 100.0) * maxConc
    }

    /**
     * 获取孔位图像文件或URI
     * 处理两种不同的存储方式：文件路径和内容URI
     */
    fun getWellImageFile(wellResult: WellResult): Any? {
        val imageIdentifier = wellResult.croppedImageIdentifier ?: return null

        // 检查是否为URI格式（content://开头)
        return if (imageIdentifier.startsWith("content://") ||
            imageIdentifier.startsWith("file://")) {
            // 返回URI对象，AsyncImage可以直接使用
            android.net.Uri.parse(imageIdentifier)
        } else {
            // 作为文件路径处理
            val imageFile = File(imageIdentifier)
            if (imageFile.exists()) imageFile else null
        }
    }

    /**
     * 加载默认或最近的结果
     * 当没有提供runId或projectId时调用
     */
    fun loadDefaultOrMostRecentResults() {
        _resultState.value = ResultState.Loading

        viewModelScope.launch {
            try {
                // 尝试获取最新的项目
                val latestProject = withContext(Dispatchers.IO) {
                    projectDao.getLatestProject()
                }

                if (latestProject != null) {
                    // 更新浓度单位
                    _concentrationUnit.value = latestProject.concentrationUnit ?: "ng/ml" // 如果项目中没有单位，则回退到 "ng/ml"

                    // 如果找到最新项目，加载其结果
                    val wellResults = withContext(Dispatchers.IO) {
                        wellResultDao.getWellResultsByProjectId(latestProject.id)
                    }

                    // 获取最新的检测运行(如果有)
                    val latestRun = withContext(Dispatchers.IO) {
                        detectionRunDao.getLatestDetectionRunByProjectId(latestProject.id)
                    }

                    // 设置浓度范围
                    updateConcentrationRange(wellResults, latestProject.maxConcentration)

                    // 更新结果状态
                    _resultState.value = ResultState.Success(
                        project = latestProject,
                        wellResults = wellResults,
                        detectionRun = latestRun
                    )
                } else {
                    // 如果没有找到项目，显示错误
                    _resultState.value = ResultState.Error(application.getString(R.string.error_no_projects_found))
                }
            } catch (e: Exception) {
                Log.e("ResultViewModel", "加载默认结果失败", e)
                _resultState.value = ResultState.Error(application.getString(R.string.error_loading_results, e.message ?: "Unknown error"))
            }
        }
    }
}
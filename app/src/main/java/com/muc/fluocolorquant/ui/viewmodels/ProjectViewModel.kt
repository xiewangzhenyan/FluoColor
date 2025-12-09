package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.enums.SpectrumLightSource
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.ProjectAnalyteJoinRepository
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.ui.screens.project.DetectionMode
import com.muc.fluocolorquant.ui.screens.project.AnalysisMethod
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID
import javax.inject.Inject

/**
 * 分析物配置数据类
 * 用于暂存用户为单个分析物所做的所有配置
 */
data class AnalyteConfig(
    val analyte: Analyte,
    var maxConcentration: String = "", // 使用String方便TextField双向绑定
    var concentrationUnit: String = "ng/ml" // 默认单位
)

@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val analyteRepository: AnalyteRepository,
    private val projectAnalyteJoinRepository: ProjectAnalyteJoinRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // 项目列表的StateFlow
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    // 当前选中的项目
    private val _selectedProject = MutableStateFlow<Project?>(null)
    val selectedProject: StateFlow<Project?> = _selectedProject.asStateFlow()

    // 分析方法：AUTO (浓度预测模型) 或 MANUAL (标准曲线拟合)
    private val _analysisMethod = MutableStateFlow("DL_MODEL")
    val analysisMethod: StateFlow<String> = _analysisMethod.asStateFlow()

    // 所有可用的分析物
    private val _availableAnalytes = MutableStateFlow<List<Analyte>>(emptyList())
    val availableAnalytes: StateFlow<List<Analyte>> = _availableAnalytes.asStateFlow()

    // 光谱相关状态
    val availableLightSources: List<SpectrumLightSource> = SpectrumLightSource.values().toList()
    private val _spectrumLightSource = MutableStateFlow(SpectrumLightSource.LED_WHITE)
    val spectrumLightSource: StateFlow<SpectrumLightSource> = _spectrumLightSource.asStateFlow()
    private val _spectrumTrackCount = MutableStateFlow(SettingsRepository.DEFAULT_SPECTRUM_DEFAULT_TRACK_COUNT)
    val spectrumTrackCount: StateFlow<Int> = _spectrumTrackCount.asStateFlow()
    private val _spectrumMaxTrackCount = MutableStateFlow(SettingsRepository.DEFAULT_SPECTRUM_MAX_TRACK_COUNT)
    val spectrumMaxTrackCount: StateFlow<Int> = _spectrumMaxTrackCount.asStateFlow()
    private val _spectrumColumnMapping = MutableStateFlow<Map<Int, Analyte>>(emptyMap())
    val spectrumColumnMapping: StateFlow<Map<Int, Analyte>> = _spectrumColumnMapping.asStateFlow()

    // 选中的分析物配置列表 - 核心状态，UI将直接观察和修改这个列表
    private val _selectedAnalyteConfigs = MutableStateFlow<List<AnalyteConfig>>(emptyList())
    val selectedAnalyteConfigs: StateFlow<List<AnalyteConfig>> = _selectedAnalyteConfigs.asStateFlow()

    // 用于控制模板选择对话框显示的状态
    private val _showTemplateSelectionDialog = MutableStateFlow(false)
    val showTemplateSelectionDialog: StateFlow<Boolean> = _showTemplateSelectionDialog.asStateFlow()

    init {
        // 初始化时加载所有项目和分析物
        loadProjects()
        loadAnalytes()
        loadSpectrumDefaults()
    }

    // 加载所有项目
    private fun loadProjects() {
        viewModelScope.launch {
            try {
                val allProjects = projectRepository.getAllProjects()
                _projects.value = allProjects
            } catch (e: Exception) {
                // 处理错误
            }
        }
    }

    // 加载所有分析物
    private fun loadAnalytes() {
        viewModelScope.launch {
            try {
                analyteRepository.getAllAnalytes()
                    .collect { analytes ->
                        _availableAnalytes.value = analytes
                    }
            } catch (e: Exception) {
                // 处理错误
            }
        }
    }

    private fun loadSpectrumDefaults() {
        viewModelScope.launch {
            try {
                val defaultTracks = settingsRepository.spectrumDefaultTrackCountFlow.first()
                val maxTracks = settingsRepository.spectrumMaxTrackCountFlow.first()
                val lightSourceName = settingsRepository.spectrumDefaultLightSourceFlow.first()
                _spectrumTrackCount.value = defaultTracks
                _spectrumMaxTrackCount.value = maxTracks
                _spectrumLightSource.value = runCatching { SpectrumLightSource.valueOf(lightSourceName) }
                    .getOrDefault(SpectrumLightSource.LED_WHITE)
            } catch (_: Exception) {
                _spectrumTrackCount.value = SettingsRepository.DEFAULT_SPECTRUM_DEFAULT_TRACK_COUNT
                _spectrumMaxTrackCount.value = SettingsRepository.DEFAULT_SPECTRUM_MAX_TRACK_COUNT
                _spectrumLightSource.value = SpectrumLightSource.LED_WHITE
            }
        }
    }

    // 设置分析方法
    fun setAnalysisMethod(method: String) {
        _analysisMethod.value = method
    }

    fun updateDetectionMode(mode: DetectionMode) {
        if (mode != DetectionMode.SPECTRUM) {
            // 非光谱模式时重置光谱映射，避免串数据
            _spectrumColumnMapping.value = emptyMap()
        } else {
            // 进入光谱模式时确保通道数在合法范围
            val clamped = _spectrumTrackCount.value.coerceIn(1, _spectrumMaxTrackCount.value)
            _spectrumTrackCount.value = clamped
        }
    }

    fun updateSpectrumTrackCount(count: Int) {
        val clamped = count.coerceIn(1, _spectrumMaxTrackCount.value)
        _spectrumTrackCount.value = clamped
        // 移除超出范围的绑定
        val filtered = _spectrumColumnMapping.value.filterKeys { it <= clamped }
        _spectrumColumnMapping.value = filtered
    }

    fun bindAnalyteToTrack(trackIndex: Int, analyte: Analyte) {
        val newMap = _spectrumColumnMapping.value.toMutableMap()
        newMap[trackIndex] = analyte
        _spectrumColumnMapping.value = newMap
    }

    fun updateSpectrumLightSource(lightSource: SpectrumLightSource) {
        _spectrumLightSource.value = lightSource
    }

    // 当用户在多选对话框中确定分析物列表后调用
    fun onAnalyteSelectionChanged(selectedAnalytes: List<Analyte>) {
        // 转换为AnalyteConfig对象列表
        val configs = selectedAnalytes.map { analyte ->
            // 查找是否已存在该分析物的配置
            val existingConfig = _selectedAnalyteConfigs.value.find { it.analyte.id == analyte.id }

            // 如果存在，保留其现有配置；否则创建新配置
            existingConfig ?: AnalyteConfig(
                analyte = analyte,
                maxConcentration = "100.0", // 默认最大浓度
                concentrationUnit = "ng/ml" // 默认单位
            )
        }

        _selectedAnalyteConfigs.value = configs
    }

    // 更新特定分析物的配置
    fun updateAnalyteConfig(analyteId: String, newConcentration: String, newUnit: String) {
        val currentConfigs = _selectedAnalyteConfigs.value.toMutableList()
        val index = currentConfigs.indexOfFirst { it.analyte.id == analyteId }

        if (index != -1) {
            val config = currentConfigs[index]
            currentConfigs[index] = config.copy(
                maxConcentration = newConcentration,
                concentrationUnit = newUnit
            )
            _selectedAnalyteConfigs.value = currentConfigs
        }
    }

    // 删除特定分析物的配置
    fun removeAnalyteConfig(analyteId: String) {
        val currentConfigs = _selectedAnalyteConfigs.value.toMutableList()
        currentConfigs.removeAll { it.analyte.id == analyteId }
        _selectedAnalyteConfigs.value = currentConfigs
    }

    // 创建新项目
    suspend fun createProject(
        name: String,
        detectionMode: DetectionMode,
        analysisMethod: AnalysisMethod,
        imageUri: String,
        userId: String? = null,
        rows: Int? = null,
        columns: Int? = null
    ): String? {
        return try {
            // 从设置中获取默认行列值
            val defaultRows = settingsRepository.defaultRowsFlow.first()
            val defaultColumns = settingsRepository.defaultColumnsFlow.first()

            val projectId = UUID.randomUUID().toString()

            val isSpectrum = detectionMode == DetectionMode.SPECTRUM
            val spectrumTrackCount = _spectrumTrackCount.value.coerceAtLeast(1)

            if (isSpectrum) {
                if (imageUri.isBlank()) return null
                // 确保每个通道都已绑定分析物
                for (index in 1..spectrumTrackCount) {
                    if (_spectrumColumnMapping.value[index] == null) return null
                }
            }

            // 创建Project对象，不再包含maxConcentration和concentrationUnit字段
            val project = Project(
                id = projectId,
                name = name,
                detectionMode = detectionMode.name,
                recognitionType = "AUTO", // 保留兼容性，后续可移除
                imageUri = imageUri,
                rows = if (isSpectrum) 1 else rows ?: defaultRows, // 光谱模式固定单通道区域
                columns = if (isSpectrum) 1 else columns ?: defaultColumns,
                createTime = Date(),
                userId = userId ?: "guest",
                lastRunTimestamp = null, // 新项目还没有运行记录
                analysisMethod = analysisMethod.name,
                lightSource = if (isSpectrum) _spectrumLightSource.value.name else null,
                spectrumColumnCount = if (isSpectrum) spectrumTrackCount else 1,
                spectrumColumnMappingJson = if (isSpectrum) {
                    Gson().toJson(_spectrumColumnMapping.value.mapValues { it.value.id })
                } else null
            )

            // 保存项目到数据库
            projectRepository.createProject(project)

            if (!isSpectrum) {
                // 为每个选中的分析物创建关联（标准模式）
                _selectedAnalyteConfigs.value.forEach { config ->
                    val analyteJoin = ProjectAnalyteJoin(
                        projectId = projectId,
                        analyteId = config.analyte.id,
                        maxConcentration = config.maxConcentration.toDoubleOrNull(),
                        concentrationUnit = config.concentrationUnit,
                        fkTemplateId = null // 这将在后续步骤中设置（如果是曲线拟合模式）
                    )

                    // 插入关联记录
                    projectAnalyteJoinRepository.addProjectAnalyteJoin(analyteJoin)
                }
            }

            // 检查是否是单孔(1x1)项目
            if (project.rows == 1 && project.columns == 1) {
                // 触发模板选择对话框状态（如果是CURVE_FIT模式）
                if (project.analysisMethod == "CURVE_FIT") {
                    _showTemplateSelectionDialog.value = true
                }
                // 注意：对于DL_MODEL模式的单孔处理逻辑将在UI层实现
            }

            // 刷新项目列表
            loadProjects()

            // 返回项目ID
            projectId
        } catch (e: Exception) {
            null
        }
    }

    // 用于关闭模板选择对话框
    fun dismissTemplateSelectionDialog() {
        _showTemplateSelectionDialog.value = false
    }

    // 当用户在模板选择对话框中选择模板后调用
    suspend fun assignTemplateToProject(projectId: String, analyteId: String, templateId: String): Boolean {
        return try {
            // 获取当前关联
            val project = projectRepository.getProjectById(projectId) ?: return false

            // 更新关联记录，设置模板ID
            val analyteJoin = ProjectAnalyteJoin(
                projectId = projectId,
                analyteId = analyteId,
                maxConcentration = null, // 曲线拟合模式不使用这些字段
                concentrationUnit = null,
                fkTemplateId = templateId
            )

            // 更新关联
            projectAnalyteJoinRepository.addProjectAnalyteJoin(analyteJoin)

            // 关闭对话框
            dismissTemplateSelectionDialog()

            true
        } catch (e: Exception) {
            false
        }
    }

    // 获取单个项目详情
    fun getProject(projectId: String) {
        viewModelScope.launch {
            try {
                val project = projectRepository.getProjectById(projectId)
                _selectedProject.value = project
            } catch (e: Exception) {
                // 处理错误
            }
        }
    }

    // 删除项目
    suspend fun deleteProject(projectId: String): Boolean {
        return try {
            projectRepository.deleteProject(projectId)

            // 刷新项目列表
            loadProjects()

            true
        } catch (e: Exception) {
            false
        }
    }
}

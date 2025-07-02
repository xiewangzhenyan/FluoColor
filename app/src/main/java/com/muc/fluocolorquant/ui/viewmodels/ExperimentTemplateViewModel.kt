package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.data.repository.AnalyteRepository
import com.muc.fluocolorquant.data.repository.CurveModelRepository
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepository
import com.muc.fluocolorquant.data.repository.ReagentRepository
import com.muc.fluocolorquant.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Date
import java.util.UUID
import javax.inject.Inject

/**
 * 实验模板创建状态
 * 用于驱动向导式创建页面
 */
data class TemplateCreationState(
    val templateName: String = "",
    val selectedAnalyte: Analyte? = null,
    val selectedAntigen: Reagent? = null,
    val selectedAntibody: Reagent? = null,
    val selectedCurveModel: CurveModel? = null,
    val reliableRangeMin: String = "",
    val reliableRangeMax: String = "",
    val concentrationUnit: String = "ng/ml",
    val defaultLayout: Map<Int, String> = emptyMap(), // Key: wellIndex, Value: role
    val isLoadingDependencies: Boolean = false, // 是否正在加载关联数据
    val enableDefaultLayout: Boolean = false, // 是否启用默认孔板布局
    val plateRows: Int = 8,
    val plateColumns: Int = 12,
    val selectedWellRole: String? = null,
    val templateId: String = UUID.randomUUID().toString()
)

/**
 * 实验模板详细信息，包含关联数据
 */
data class TemplateWithDetails(
    val template: ExperimentTemplate,
    val analyte: Analyte? = null,
    val antigen: Reagent? = null,
    val antibody: Reagent? = null,
    val curveModel: CurveModel? = null
)

/**
 * 实验模板ViewModel
 * 管理实验模板的创建、编辑和列表展示
 */
@HiltViewModel
class ExperimentTemplateViewModel @Inject constructor(
    private val experimentTemplateRepository: ExperimentTemplateRepository,
    private val analyteRepository: AnalyteRepository,
    private val reagentRepository: ReagentRepository,
    private val curveModelRepository: CurveModelRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // 所有实验模板
    private val _templates = MutableStateFlow<List<ExperimentTemplate>>(emptyList())
    val templates: StateFlow<List<ExperimentTemplate>> = _templates.asStateFlow()

    // 带详细信息的模板列表
    val templatesWithDetails: StateFlow<List<TemplateWithDetails>> = templates
        .combine(analyteRepository.getAllAnalytes()) { templates, analytes ->
            templates
        }
        .combine(reagentRepository.getAllReagents()) { templates, reagents ->
            templates.map { template ->
                TemplateWithDetails(
                    template = template,
                    analyte = null, // 暂时设为null，后续步骤会设置
                    antigen = template.reagentAntigenId?.let { antigenId ->
                        reagents.find { it.id == antigenId }
                    },
                    antibody = template.reagentAntibodyId?.let { antibodyId ->
                        reagents.find { it.id == antibodyId }
                    },
                    curveModel = null // 暂时设为null，后续步骤会设置
                )
            }
        }
        .combine(analyteRepository.getAllAnalytes()) { templatesWithPartialDetails, analytes ->
            templatesWithPartialDetails.map { template ->
                template.copy(
                    analyte = analytes.find { it.id == template.template.analyteId }
                )
            }
        }
        .combine(curveModelRepository.getAllCurveModels()) { templatesWithPartialDetails, curveModels ->
            templatesWithPartialDetails.map { template ->
                template.copy(
                    curveModel = curveModels.find { it.id == template.template.fkCurveModelId }
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 创建状态
    private val _creationState = MutableStateFlow(TemplateCreationState())
    val creationState: StateFlow<TemplateCreationState> = _creationState.asStateFlow()

    // 所有分析物
    val allAnalytes = analyteRepository.getAllAnalytes()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 可用的试剂，根据选中的分析物动态加载
    private val _availableReagents = MutableStateFlow<List<Reagent>>(emptyList())
    val availableReagents: StateFlow<List<Reagent>> = _availableReagents.asStateFlow()

    // 可用的曲线模型，根据选中的分析物动态加载
    private val _availableCurveModels = MutableStateFlow<List<CurveModel>>(emptyList())
    val availableCurveModels: StateFlow<List<CurveModel>> = _availableCurveModels.asStateFlow()

    // 可用的浓度单位
    val concentrationUnits = settingsRepository.concentrationUnitsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    // 错误消息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 是否正在加载
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 是否保存成功
    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess.asStateFlow()

    init {
        loadTemplates()

        // 初始化时就加载所有曲线模型，而不是等待用户选择分析物
        viewModelScope.launch {
            try {
                curveModelRepository.getAllCurveModels()
                    .collect { models ->
                        _availableCurveModels.value = models
                    }
            } catch (e: Exception) {
                _errorMessage.value = "加载曲线模型失败: ${e.message}"
            }
        }
    }

    /**
     * 加载所有模板
     */
    private fun loadTemplates() {
        viewModelScope.launch {
            experimentTemplateRepository.getAllTemplates()
                .collect { templates ->
                    _templates.value = templates
                }
        }
    }

    /**
     * 当用户在创建流程中选择分析物时触发
     * 这是关键的触发器，会加载关联的试剂和曲线模型
     */
    fun onAnalyteSelectedInCreation(analyte: Analyte) {
        viewModelScope.launch {
            // 更新状态，清空之前选择的试剂和曲线模型
            _creationState.value = _creationState.value.copy(
                selectedAnalyte = analyte,
                selectedAntigen = null,
                selectedAntibody = null,
                selectedCurveModel = null,
                isLoadingDependencies = true
            )

            // 加载与该分析物关联的试剂
            try {
                // 加载试剂
                reagentRepository.getReagentsByAnalyteId(analyte.id)
                    .collect { reagents ->
                        _availableReagents.value = reagents
                    }

                // 注意：不再加载曲线模型，因为我们在初始化时就加载了所有曲线模型
            } catch (e: Exception) {
                _errorMessage.value = "加载关联数据失败: ${e.message}"
            } finally {
                _creationState.value = _creationState.value.copy(isLoadingDependencies = false)
            }
        }
    }

    /**
     * 更新模板名称
     */
    fun updateTemplateName(name: String) {
        _creationState.value = _creationState.value.copy(templateName = name)
    }

    /**
     * 选择抗原
     */
    fun selectAntigen(reagent: Reagent?) {
        _creationState.value = _creationState.value.copy(selectedAntigen = reagent)
    }

    /**
     * 选择抗体
     */
    fun selectAntibody(reagent: Reagent?) {
        _creationState.value = _creationState.value.copy(selectedAntibody = reagent)
    }

    /**
     * 选择曲线模型
     */
    fun selectCurveModel(curveModel: CurveModel?) {
        _creationState.value = _creationState.value.copy(selectedCurveModel = curveModel)
    }

    /**
     * 更新可靠范围下限
     */
    fun updateRangeMin(value: String) {
        _creationState.value = _creationState.value.copy(reliableRangeMin = value)
    }

    /**
     * 更新可靠范围上限
     */
    fun updateRangeMax(value: String) {
        _creationState.value = _creationState.value.copy(reliableRangeMax = value)
    }

    /**
     * 更新浓度单位
     */
    fun updateConcentrationUnit(unit: String) {
        _creationState.value = _creationState.value.copy(concentrationUnit = unit)
    }

    /**
     * 切换是否启用默认孔板布局
     */
    fun toggleDefaultLayout(enabled: Boolean) {
        _creationState.value = _creationState.value.copy(enableDefaultLayout = enabled)
    }

    /**
     * 更新默认孔板布局
     */
    fun updateDefaultLayout(layout: Map<Int, String>) {
        _creationState.value = _creationState.value.copy(defaultLayout = layout)
    }

    /**
     * 保存模板
     */
    fun saveTemplate() {
        viewModelScope.launch {
            _isLoading.value = true
            _saveSuccess.value = false

            try {
                // 数据校验
                val state = _creationState.value

                if (state.templateName.isBlank()) {
                    _errorMessage.value = "请输入模板名称"
                    _isLoading.value = false
                    return@launch
                }

                val analyte = state.selectedAnalyte
                if (analyte == null) {
                    _errorMessage.value = "请选择分析物"
                    _isLoading.value = false
                    return@launch
                }

                val curveModel = state.selectedCurveModel
                if (curveModel == null) {
                    _errorMessage.value = "请选择曲线模型"
                    _isLoading.value = false
                    return@launch
                }

                // 解析范围值
                val rangeMin = state.reliableRangeMin.toDoubleOrNull()
                if (rangeMin == null) {
                    _errorMessage.value = "请输入有效的范围下限"
                    _isLoading.value = false
                    return@launch
                }

                val rangeMax = state.reliableRangeMax.toDoubleOrNull()
                if (rangeMax == null) {
                    _errorMessage.value = "请输入有效的范围上限"
                    _isLoading.value = false
                    return@launch
                }

                if (rangeMin >= rangeMax) {
                    _errorMessage.value = "范围下限必须小于上限"
                    _isLoading.value = false
                    return@launch
                }

                // 将布局Map转换为JSON字符串
                val layoutJson = if (state.enableDefaultLayout && state.defaultLayout.isNotEmpty()) {
                    JSONObject().apply {
                        state.defaultLayout.forEach { (wellIndex, role) ->
                            put(wellIndex.toString(), role)
                        }
                    }.toString()
                } else {
                    null
                }

                // 查找当前模板的ID（如果是编辑模式）
                val templateId = _templates.value.find {
                    it.id == state.templateId
                }?.id ?: UUID.randomUUID().toString()

                // 创建模板对象，如果是编辑模式则保留原来的ID
                val template = ExperimentTemplate(
                    id = templateId,
                    templateName = state.templateName,
                    analyteId = analyte.id,
                    reagentAntigenId = state.selectedAntigen?.id,
                    reagentAntibodyId = state.selectedAntibody?.id,
                    fkCurveModelId = curveModel.id,
                    reliableRangeMin = rangeMin,
                    reliableRangeMax = rangeMax,
                    concentrationUnit = state.concentrationUnit,
                    defaultLayoutJson = layoutJson,
                    createdAt = if (templateId == UUID.randomUUID().toString()) Date() else Date(), // 新建时设置创建时间
                    updatedAt = Date() // 无论是新建还是编辑，都更新最后修改时间
                )

                // 使用合适的方法保存模板（更新或插入）
                if (_templates.value.any { it.id == templateId }) {
                    // 更新现有模板
                    experimentTemplateRepository.updateTemplate(template)
                } else {
                    // 保存新模板
                    experimentTemplateRepository.saveTemplate(template)
                }

                // [已移除] 不再在这里重置状态，以防止UI闪烁
                // _creationState.value = TemplateCreationState()

                // 标记保存成功
                _saveSuccess.value = true

            } catch (e: Exception) {
                _errorMessage.value = "保存模板失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 删除模板
     */
    fun deleteTemplate(template: ExperimentTemplate) {
        viewModelScope.launch {
            try {
                experimentTemplateRepository.deleteTemplate(template)
            } catch (e: Exception) {
                _errorMessage.value = "删除模板失败: ${e.message}"
            }
        }
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 重置保存成功状态
     */
    fun resetSaveSuccess() {
        _saveSuccess.value = false
    }

    /**
     * 加载模板
     */
    fun loadTemplate(templateId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                println("开始加载模板ID: $templateId")
                val template = experimentTemplateRepository.getTemplateById(templateId)
                if (template != null) {
                    println("成功获取模板: ${template.templateName}, 分析物ID: ${template.analyteId}, " +
                            "抗原ID: ${template.reagentAntigenId}, 抗体ID: ${template.reagentAntibodyId}, " +
                            "曲线模型ID: ${template.fkCurveModelId}")

                    // 加载分析物
                    val analyte = analyteRepository.getAnalyteById(template.analyteId)
                    println("加载分析物: ${analyte?.name ?: "未找到"}")

                    // 加载试剂和曲线模型
                    val antigen = template.reagentAntigenId?.let {
                        val reagent = reagentRepository.getReagentById(it)
                        println("加载抗原: ${reagent?.reagentName ?: "未找到"}")
                        reagent
                    }

                    val antibody = template.reagentAntibodyId?.let {
                        val reagent = reagentRepository.getReagentById(it)
                        println("加载抗体: ${reagent?.reagentName ?: "未找到"}")
                        reagent
                    }

                    val curveModel = curveModelRepository.getCurveModelById(template.fkCurveModelId)
                    println("加载曲线模型: ${curveModel?.name ?: "未找到"}")

                    // 解析默认布局JSON
                    val layoutMap = mutableMapOf<Int, String>()
                    template.defaultLayoutJson?.let { json ->
                        val jsonObject = JSONObject(json)
                        jsonObject.keys().forEach { key ->
                            val wellIndex = key.toIntOrNull()
                            val role = jsonObject.getString(key)
                            if (wellIndex != null) {
                                layoutMap[wellIndex] = role
                            }
                        }
                    }

                    // 更新状态
                    _creationState.value = TemplateCreationState(
                        templateName = template.templateName,
                        selectedAnalyte = analyte,
                        selectedAntigen = antigen,
                        selectedAntibody = antibody,
                        selectedCurveModel = curveModel,
                        reliableRangeMin = template.reliableRangeMin.toString(),
                        reliableRangeMax = template.reliableRangeMax.toString(),
                        concentrationUnit = template.concentrationUnit,
                        defaultLayout = layoutMap,
                        enableDefaultLayout = template.defaultLayoutJson != null,
                        plateRows = 8, // 使用默认值
                        plateColumns = 12, // 使用默认值
                        templateId = template.id // 设置模板ID，以便保存时进行更新而非创建
                    )

                    println("模板状态已更新: 分析物=${_creationState.value.selectedAnalyte?.name}, " +
                            "抗原=${_creationState.value.selectedAntigen?.reagentName}, " +
                            "抗体=${_creationState.value.selectedAntibody?.reagentName}, " +
                            "曲线模型=${_creationState.value.selectedCurveModel?.name}")

                    // 加载所有相关的试剂数据
                    analyte?.let {
                        try {
                            // 加载与该分析物关联的所有试剂
                            viewModelScope.launch {
                                reagentRepository.getReagentsByAnalyteId(it.id).collect { reagents ->
                                    _availableReagents.value = reagents
                                    println("已加载${reagents.size}个试剂")

                                    // 如果之前没有成功加载抗原或抗体，现在再次尝试从加载的试剂中查找
                                    if (_creationState.value.selectedAntigen == null && template.reagentAntigenId != null) {
                                        val foundAntigen = reagents.find { it.id == template.reagentAntigenId }
                                        if (foundAntigen != null) {
                                            println("从试剂列表中找到抗原: ${foundAntigen.reagentName}")
                                            selectAntigen(foundAntigen)
                                        }
                                    }

                                    if (_creationState.value.selectedAntibody == null && template.reagentAntibodyId != null) {
                                        val foundAntibody = reagents.find { it.id == template.reagentAntibodyId }
                                        if (foundAntibody != null) {
                                            println("从试剂列表中找到抗体: ${foundAntibody.reagentName}")
                                            selectAntibody(foundAntibody)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            println("加载试剂失败: ${e.message}")
                        }
                    }

                    // 加载所有曲线模型
                    try {
                        viewModelScope.launch {
                            curveModelRepository.getAllCurveModels().collect { models ->
                                _availableCurveModels.value = models
                                println("已加载${models.size}个曲线模型")

                                // 如果之前没有成功加载曲线模型，现在再次尝试
                                if (_creationState.value.selectedCurveModel == null) {
                                    val foundModel = models.find { it.id == template.fkCurveModelId }
                                    if (foundModel != null) {
                                        println("从曲线模型列表中找到模型: ${foundModel.name}")
                                        selectCurveModel(foundModel)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("加载曲线模型失败: ${e.message}")
                    }
                } else {
                    println("未找到模板ID: $templateId")
                    _errorMessage.value = "未找到模板"
                }
            } catch (e: Exception) {
                println("加载模板失败: ${e.message}")
                _errorMessage.value = "加载模板失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 重置模板状态
     */
    fun resetTemplateState() {
        _creationState.value = TemplateCreationState()
    }

    /**
     * 使用回调保存模板
     */
    fun saveTemplate(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 调用无回调的保存方法
                saveTemplate()
                // 检查保存成功状态
                if (_saveSuccess.value) {
                    onSuccess()
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 获取当前选中的抗原ID
     */
    fun getSelectedAntigenId(): String? {
        // 如果已有选中的抗原，直接返回其ID
        _creationState.value.selectedAntigen?.let { return it.id }

        // 否则尝试从加载的模板中获取
        val templates = _templates.value
        val templateId = _creationState.value.templateName // 使用模板名称作为唯一标识

        return templates.find { it.templateName == templateId }?.reagentAntigenId
    }

    /**
     * 获取当前选中的抗体ID
     */
    fun getSelectedAntibodyId(): String? {
        // 如果已有选中的抗体，直接返回其ID
        _creationState.value.selectedAntibody?.let { return it.id }

        // 否则尝试从加载的模板中获取
        val templates = _templates.value
        val templateId = _creationState.value.templateName // 使用模板名称作为唯一标识

        return templates.find { it.templateName == templateId }?.reagentAntibodyId
    }

    /**
     * 获取当前选中的曲线模型ID
     */
    fun getSelectedCurveModelId(): String? {
        // 如果已有选中的曲线模型，直接返回其ID
        _creationState.value.selectedCurveModel?.let { return it.id }

        // 否则尝试从加载的模板中获取
        val templates = _templates.value
        val templateId = _creationState.value.templateName // 使用模板名称作为唯一标识

        return templates.find { it.templateName == templateId }?.fkCurveModelId
    }

    /**
     * 更新孔板行数
     */
    fun updatePlateRows(rows: Int) {
        _creationState.value = _creationState.value.copy(plateRows = rows)
    }

    /**
     * 更新孔板列数
     */
    fun updatePlateColumns(columns: Int) {
        _creationState.value = _creationState.value.copy(plateColumns = columns)
    }

    /**
     * 更新孔位角色
     */
    fun updateWellRole(wellIndex: Int) {
        val currentLayout = _creationState.value.defaultLayout.toMutableMap()
        val selectedRole = _creationState.value.selectedWellRole

        if (selectedRole != null) {
            currentLayout[wellIndex] = selectedRole
        } else {
            currentLayout.remove(wellIndex)
        }

        _creationState.value = _creationState.value.copy(defaultLayout = currentLayout)
    }

    /**
     * 选择孔位角色
     */
    fun selectWellRole(role: String) {
        _creationState.value = _creationState.value.copy(selectedWellRole = role)
    }

    /**
     * 解析默认布局JSON字符串，返回孔位索引到角色的映射
     * * @param layoutJson 布局JSON字符串
     * @return 孔位索引到角色的映射，如果解析失败则返回空映射
     */
    fun parseDefaultLayout(layoutJson: String?): Map<Int, String> {
        if (layoutJson.isNullOrBlank()) {
            return emptyMap()
        }

        val wellRoleMap = mutableMapOf<Int, String>()

        try {
            val jsonObject = JSONObject(layoutJson)
            val keys = jsonObject.keys()

            while (keys.hasNext()) {
                val key = keys.next()
                val wellIndex = key.toIntOrNull()
                val role = jsonObject.optString(key)

                if (wellIndex != null && role.isNotBlank()) {
                    wellRoleMap[wellIndex] = role
                }
            }
        } catch (e: Exception) {
            // 解析失败，返回空映射
            return emptyMap()
        }

        return wellRoleMap
    }
}
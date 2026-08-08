package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.domain.project.ProjectDetectionDestination
import com.muc.fluocolorquant.domain.project.ResolvedTemplateProjectConfiguration
import com.muc.fluocolorquant.domain.project.TemplatePreflightIssue
import com.muc.fluocolorquant.domain.project.TemplateProjectCoordinator
import com.muc.fluocolorquant.domain.project.TemplateProjectCreateRequest
import com.muc.fluocolorquant.domain.project.TemplateProjectCreationOutcome
import com.muc.fluocolorquant.domain.project.TemplateResolution
import com.muc.fluocolorquant.domain.project.TemplateSiteKey
import com.muc.fluocolorquant.ui.screens.project.TemplateProjectFormState
import com.muc.fluocolorquant.ui.screens.project.TemplateSampleSiteField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 模板优先新建项目页面的完整 UI 状态。
 *
 * 已发布模板列表、当前解析配置和表单分开保存：列表更新不会自动改变用户选择；模板解析
 * 失败时仍可保留列表并展示明确的预检问题。
 */
data class ProjectUiState(
    val publishedTemplates: List<ExperimentTemplate> = emptyList(),
    val form: TemplateProjectFormState = TemplateProjectFormState(),
    val resolvedConfiguration: ResolvedTemplateProjectConfiguration? = null,
    val isLoadingTemplates: Boolean = true,
    val isResolvingTemplate: Boolean = false
)

/** 页面一次性事件；导航和 Toast 不存入可重放 StateFlow。 */
sealed interface ProjectEvent {
    data class Created(
        val projectId: String,
        val imageUri: String,
        val destination: ProjectDetectionDestination
    ) : ProjectEvent

    data class ValidationBlocked(val issues: List<TemplatePreflightIssue>) : ProjectEvent
    data object FormIncomplete : ProjectEvent
    data object UnexpectedFailure : ProjectEvent
}

/**
 * 模板优先项目创建状态机。
 *
 * 普通用户只选择一个已发布模板并填写项目/样本批次、样本编号和图片来源。检测模态、载体
 * 行列、采集设备、分析物和模型均来自协调器解析的只读配置，ViewModel 不再读取全局默认
 * 孔板行列，也不保留“检测后再套模板”的状态。
 */
@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val templateProjectCoordinator: TemplateProjectCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectUiState())
    val uiState: StateFlow<ProjectUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ProjectEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ProjectEvent> = _events.asSharedFlow()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _selectedProject = MutableStateFlow<Project?>(null)
    val selectedProject: StateFlow<Project?> = _selectedProject.asStateFlow()

    init {
        observePublishedTemplates()
        loadProjects()
    }

    /** 持续观察已发布模板，但永远不自动选中第一项。 */
    private fun observePublishedTemplates() {
        viewModelScope.launch {
            templateProjectCoordinator.observePublishedTemplates().collect { templates ->
                _uiState.update { state ->
                    state.copy(
                        publishedTemplates = templates,
                        isLoadingTemplates = false
                    )
                }
            }
        }
    }

    /** 用户明确选择模板后解析完整科学配置和默认样本映射。 */
    fun selectTemplate(templateId: String) {
        _uiState.update { state ->
            state.copy(
                form = state.form.copy(
                    selectedTemplateId = templateId,
                    templateReady = false,
                    requiredSampleSites = emptyList(),
                    sampleSlotMapping = emptyMap(),
                    preflightIssues = emptyList()
                ),
                resolvedConfiguration = null,
                isResolvingTemplate = true
            )
        }
        viewModelScope.launch {
            when (val resolution = templateProjectCoordinator.resolveTemplate(templateId)) {
                is TemplateResolution.Ready -> applyResolvedTemplate(resolution.configuration)
                is TemplateResolution.Blocked -> {
                    _uiState.update { state ->
                        state.copy(
                            form = state.form.copy(
                                templateReady = false,
                                preflightIssues = resolution.issues
                            ),
                            resolvedConfiguration = null,
                            isResolvingTemplate = false
                        )
                    }
                }
            }
        }
    }

    /** 将样本角色位点转换为页面字段，并使用模板默认槽位进行无负担预填。 */
    private fun applyResolvedTemplate(configuration: ResolvedTemplateProjectConfiguration) {
        val snapshot = configuration.snapshot
        val analyteNames = snapshot.analytes.associate { it.analyte.id to it.analyte.name }
        val requiredSites = snapshot.siteAssignments
            .asSequence()
            .filter { assignment ->
                assignment.enabled &&
                    assignment.roleType == com.muc.fluocolorquant.data.enums.TemplateSiteRole.SAMPLE.code
            }
            .map { assignment ->
                TemplateSampleSiteField(
                    siteKey = TemplateSiteKey.format(assignment.rowIndex, assignment.columnIndex),
                    analyteName = analyteNames[assignment.analyteId].orEmpty(),
                    suggestedSampleSlot = assignment.defaultSampleSlot?.trim().orEmpty()
                )
            }
            .sortedBy(TemplateSampleSiteField::siteKey)
            .toList()
        val defaultMapping = requiredSites
            .filter { it.suggestedSampleSlot.isNotBlank() }
            .associate { it.siteKey to it.suggestedSampleSlot }

        _uiState.update { state ->
            state.copy(
                form = state.form.copy(
                    templateReady = true,
                    requiredSampleSites = requiredSites,
                    sampleSlotMapping = defaultMapping,
                    preflightIssues = emptyList()
                ),
                resolvedConfiguration = configuration,
                isResolvingTemplate = false
            )
        }
    }

    fun updateProjectName(value: String) = updateForm { copy(projectName = value) }
    fun updateProjectBatch(value: String) = updateForm { copy(projectBatch = value) }
    fun updateSampleBatch(value: String) = updateForm { copy(sampleBatch = value) }
    fun updateImageUri(value: String?) = updateForm { copy(imageUri = value) }

    /** 更新单个位点的样本编号；空白值会在表单门控中被识别为缺失。 */
    fun updateSampleSlot(siteKey: String, sampleSlot: String) {
        updateForm {
            copy(sampleSlotMapping = sampleSlotMapping.toMutableMap().apply {
                this[siteKey] = sampleSlot
            })
        }
    }

    /** 批量把同一样本编号应用到一组位点，减少微流控芯片逐格录入。 */
    fun applySampleSlotToSites(siteKeys: Collection<String>, sampleSlot: String) {
        updateForm {
            copy(sampleSlotMapping = sampleSlotMapping.toMutableMap().apply {
                siteKeys.forEach { siteKey -> this[siteKey] = sampleSlot }
            })
        }
    }

    /** 创建前由协调器重新预检并在 Room 单事务中保存项目和全部分析物关联。 */
    fun createProject(userId: String) {
        val current = _uiState.value
        if (!current.form.canSubmit) {
            _events.tryEmit(ProjectEvent.FormIncomplete)
            return
        }
        val templateId = current.form.selectedTemplateId ?: run {
            _events.tryEmit(ProjectEvent.FormIncomplete)
            return
        }
        _uiState.update { state ->
            state.copy(form = state.form.copy(isSubmitting = true))
        }
        viewModelScope.launch {
            try {
                when (val outcome = templateProjectCoordinator.createProject(
                    TemplateProjectCreateRequest(
                        name = current.form.projectName,
                        templateId = templateId,
                        projectBatch = current.form.projectBatch,
                        sampleBatch = current.form.sampleBatch,
                        sampleSlotMapping = current.form.sampleSlotMapping,
                        imageUri = current.form.imageUri.orEmpty(),
                        userId = userId
                    )
                )) {
                    is TemplateProjectCreationOutcome.Created -> {
                        loadProjects()
                        _uiState.update { state ->
                            state.copy(form = state.form.copy(isSubmitting = false))
                        }
                        _events.emit(
                            ProjectEvent.Created(
                                projectId = outcome.project.id,
                                imageUri = outcome.project.imageUri,
                                destination = outcome.destination
                            )
                        )
                    }

                    is TemplateProjectCreationOutcome.Blocked -> {
                        _uiState.update { state ->
                            state.copy(
                                form = state.form.copy(
                                    isSubmitting = false,
                                    preflightIssues = outcome.issues,
                                    templateReady = false
                                )
                            )
                        }
                        _events.emit(ProjectEvent.ValidationBlocked(outcome.issues))
                    }
                }
            } catch (_: Exception) {
                _uiState.update { state ->
                    state.copy(form = state.form.copy(isSubmitting = false))
                }
                _events.emit(ProjectEvent.UnexpectedFailure)
            }
        }
    }

    private inline fun updateForm(
        transform: TemplateProjectFormState.() -> TemplateProjectFormState
    ) {
        _uiState.update { state -> state.copy(form = state.form.transform()) }
    }

    private fun loadProjects() {
        viewModelScope.launch {
            runCatching { projectRepository.getAllProjects() }
                .onSuccess { _projects.value = it }
        }
    }

    fun getProject(projectId: String) {
        viewModelScope.launch {
            _selectedProject.value = runCatching {
                projectRepository.getProjectById(projectId)
            }.getOrNull()
        }
    }

    suspend fun deleteProject(projectId: String): Boolean {
        return runCatching {
            projectRepository.deleteProject(projectId)
            loadProjects()
            true
        }.getOrDefault(false)
    }

}

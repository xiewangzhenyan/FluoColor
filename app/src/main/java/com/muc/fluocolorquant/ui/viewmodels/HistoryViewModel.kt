package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.SessionManager
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.repository.WellResultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

/**
 * 历史记录视图模型
 * 用于管理历史项目列表、筛选、排序和搜索
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val sessionManager: SessionManager,
    private val wellResultRepository: WellResultRepository
) : ViewModel() {

    // 原始项目列表
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    
    // 筛选后的项目列表（用于UI显示）
    private val _filteredProjects = MutableStateFlow<List<Project>>(emptyList())
    val filteredProjects: StateFlow<List<Project>> = _filteredProjects.asStateFlow()
    
    // 加载状态
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Loading)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()
    
    // 删除操作状态
    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()
    
    // 当前搜索关键词
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    // 当前筛选设置
    private val _filterSettings = MutableStateFlow(FilterSettings())
    val filterSettings: StateFlow<FilterSettings> = _filterSettings.asStateFlow()
    
    // 当前排序设置
    private val _sortSettings = MutableStateFlow(SortSettings())
    val sortSettings: StateFlow<SortSettings> = _sortSettings.asStateFlow()
    
    init {
        // 初始加载用户项目
        loadUserProjects()
    }
    
    /**
     * 加载当前用户的项目列表
     */
    fun loadUserProjects() {
        viewModelScope.launch {
            _loadingState.value = LoadingState.Loading
            
            try {
                // 获取当前用户ID
                val userId = sessionManager.userIdFlow.first() ?: return@launch
                
                // 获取当前用户的所有项目
                val userProjects = projectRepository.getAllProjects().filter { 
                    it.userId == userId.toString() 
                }
                
                _projects.value = userProjects
                
                // 应用当前筛选和排序
                applyFiltersAndSort()
                
                _loadingState.value = if (userProjects.isEmpty()) {
                    LoadingState.Empty
                } else {
                    LoadingState.Success
                }
            } catch (e: Exception) {
                _loadingState.value = LoadingState.Error(e.message ?: "加载项目失败")
            }
        }
    }
    
    /**
     * 设置搜索关键词
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFiltersAndSort()
    }
    
    /**
     * 设置时间筛选范围
     */
    fun setTimeFilter(timeRange: TimeRange) {
        _filterSettings.update { it.copy(timeRange = timeRange) }
        applyFiltersAndSort()
    }
    
    /**
     * 设置检测模式筛选
     */
    fun setDetectionModeFilter(modes: Set<String>) {
        _filterSettings.update { it.copy(detectionModes = modes) }
        applyFiltersAndSort()
    }
    
    /**
     * 设置识别类型筛选
     */
    fun setRecognitionTypeFilter(types: Set<String>) {
        _filterSettings.update { it.copy(recognitionTypes = types) }
        applyFiltersAndSort()
    }
    
    /**
     * 设置排序方式
     */
    fun setSortOrder(field: SortField, direction: SortDirection) {
        _sortSettings.update { it.copy(field = field, direction = direction) }
        applyFiltersAndSort()
    }
    
    /**
     * 删除项目
     */
    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            _deleteState.value = DeleteState.Loading
            
            try {
                projectRepository.deleteProject(projectId)
                
                // 从本地列表移除
                _projects.update { projects ->
                    projects.filter { it.id != projectId }
                }
                
                // 应用筛选和排序
                applyFiltersAndSort()
                
                _deleteState.value = DeleteState.Success
                
                // 重置状态
                viewModelScope.launch {
                    _deleteState.value = DeleteState.Idle
                }
            } catch (e: Exception) {
                _deleteState.value = DeleteState.Error(e.message ?: "删除项目失败")
            }
        }
    }
    
    /**
     * 批量删除项目
     */
    fun deleteProjects(projectIds: List<String>) {
        viewModelScope.launch {
            _deleteState.value = DeleteState.Loading
            
            try {
                // 逐个删除项目
                projectIds.forEach { projectId ->
                    projectRepository.deleteProject(projectId)
                }
                
                // 从本地列表移除
                _projects.update { projects ->
                    projects.filter { it.id !in projectIds }
                }
                
                // 应用筛选和排序
                applyFiltersAndSort()
                
                _deleteState.value = DeleteState.Success
                
                // 重置状态
                viewModelScope.launch {
                    _deleteState.value = DeleteState.Idle
                }
            } catch (e: Exception) {
                _deleteState.value = DeleteState.Error(e.message ?: "批量删除项目失败")
            }
        }
    }
    
    /**
     * 应用所有筛选和排序
     */
    private fun applyFiltersAndSort() {
        viewModelScope.launch {
            val filtered = _projects.value
                .filter { project -> applySearchFilter(project) }
                .filter { project -> applyTimeFilter(project) }
                .filter { project -> applyDetectionModeFilter(project) }
                .filter { project -> applyRecognitionTypeFilter(project) }
                .sortedWith(createComparator())
            
            _filteredProjects.value = filtered
            
            // 更新加载状态
            _loadingState.value = if (_projects.value.isEmpty()) {
                LoadingState.Empty
            } else if (filtered.isEmpty()) {
                LoadingState.FilteredEmpty
            } else {
                LoadingState.Success
            }
        }
    }
    
    /**
     * 应用搜索筛选
     */
    private fun applySearchFilter(project: Project): Boolean {
        val query = _searchQuery.value
        if (query.isBlank()) return true
        
        return project.name.contains(query, ignoreCase = true)
    }
    
    /**
     * 应用时间筛选
     */
    private fun applyTimeFilter(project: Project): Boolean {
        return when (val timeRange = _filterSettings.value.timeRange) {
            TimeRange.ALL -> true
            TimeRange.TODAY -> isToday(project.createTime)
            TimeRange.LAST_WEEK -> isWithinDays(project.createTime, 7)
            TimeRange.LAST_MONTH -> isWithinDays(project.createTime, 30)
            is TimeRange.CUSTOM -> isWithinDateRange(project.createTime, timeRange.startDate, timeRange.endDate)
        }
    }
    
    /**
     * 应用检测模式筛选
     */
    private fun applyDetectionModeFilter(project: Project): Boolean {
        val modes = _filterSettings.value.detectionModes
        if (modes.isEmpty()) return true
        
        return project.detectionMode in modes
    }
    
    /**
     * 应用识别类型筛选
     */
    private fun applyRecognitionTypeFilter(project: Project): Boolean {
        val types = _filterSettings.value.recognitionTypes
        if (types.isEmpty()) return true
        
        return project.recognitionType in types
    }
    
    /**
     * 创建排序比较器
     */
    private fun createComparator(): Comparator<Project> {
        val settings = _sortSettings.value
        val baseComparator = when (settings.field) {
            SortField.NAME -> compareBy<Project> { it.name }
            SortField.CREATE_TIME -> compareBy<Project> { it.createTime }
            SortField.LAST_RUN -> compareBy<Project> { it.lastRunTimestamp ?: Date(0) }
        }
        
        return if (settings.direction == SortDirection.ASCENDING) {
            baseComparator
        } else {
            baseComparator.reversed()
        }
    }
    
    // 工具函数：检查日期是否为今天
    private fun isToday(date: Date): Boolean {
        val today = Calendar.getInstance()
        val calendar = Calendar.getInstance().apply { time = date }
        
        return today.get(Calendar.YEAR) == calendar.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == calendar.get(Calendar.DAY_OF_YEAR)
    }
    
    // 工具函数：检查日期是否在指定天数内
    private fun isWithinDays(date: Date, days: Int): Boolean {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        val startDate = calendar.time
        
        return date.after(startDate)
    }
    
    // 工具函数：检查日期是否在指定范围内
    private fun isWithinDateRange(date: Date, startDate: Date, endDate: Date): Boolean {
        return date.after(startDate) && date.before(endDate)
    }
    
    /**
     * 获取项目对应的最新运行ID
     * @param projectId 项目ID
     * @return 最新的运行ID，如果没有则返回null
     */
    suspend fun getLatestRunIdForProject(projectId: String): String? {
        return try {
            wellResultRepository.getLatestRunIdForProject(projectId)
        } catch (e: Exception) {
            android.util.Log.e("HistoryViewModel", "获取项目运行ID失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 加载状态
     */
    sealed class LoadingState {
        object Loading : LoadingState()
        object Success : LoadingState()
        object Empty : LoadingState()
        object FilteredEmpty : LoadingState()
        data class Error(val message: String) : LoadingState()
    }
    
    /**
     * 删除状态
     */
    sealed class DeleteState {
        object Idle : DeleteState()
        object Loading : DeleteState()
        object Success : DeleteState()
        data class Error(val message: String) : DeleteState()
    }
    
    /**
     * 筛选设置
     */
    data class FilterSettings(
        val timeRange: TimeRange = TimeRange.ALL,
        val detectionModes: Set<String> = emptySet(),
        val recognitionTypes: Set<String> = emptySet()
    )
    
    /**
     * 时间范围
     */
    sealed class TimeRange {
        object ALL : TimeRange()
        object TODAY : TimeRange()
        object LAST_WEEK : TimeRange()
        object LAST_MONTH : TimeRange()
        data class CUSTOM(val startDate: Date, val endDate: Date) : TimeRange()
    }
    
    /**
     * 排序设置
     */
    data class SortSettings(
        val field: SortField = SortField.CREATE_TIME,
        val direction: SortDirection = SortDirection.DESCENDING
    )
    
    /**
     * 排序字段
     */
    enum class SortField {
        NAME, CREATE_TIME, LAST_RUN
    }
    
    /**
     * 排序方向
     */
    enum class SortDirection {
        ASCENDING, DESCENDING
    }
} 
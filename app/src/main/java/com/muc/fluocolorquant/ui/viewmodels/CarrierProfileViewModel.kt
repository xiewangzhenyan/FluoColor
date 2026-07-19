package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.repository.CarrierProfileRepository
import com.muc.fluocolorquant.ui.screens.settings.resources.CarrierPreset
import com.muc.fluocolorquant.ui.screens.settings.resources.CarrierProfileDraft
import com.muc.fluocolorquant.ui.screens.settings.resources.ResourceProfileEvent
import com.muc.fluocolorquant.ui.screens.settings.resources.ResourceStatusFilter
import com.muc.fluocolorquant.ui.screens.settings.resources.matchesResourceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

/** 载体与布局库页面的不可变状态。 */
data class CarrierProfileUiState(
    val profiles: List<CarrierProfile> = emptyList(),
    val filter: ResourceStatusFilter = ResourceStatusFilter.ALL,
    val isEditorVisible: Boolean = false,
    val draft: CarrierProfileDraft = CarrierProfileDraft(),
    val editingSourceId: String? = null,
    val pendingArchive: CarrierProfile? = null,
    val isSaving: Boolean = false
) {
    val visibleProfiles: List<CarrierProfile>
        get() = profiles.filter { profile -> matchesResourceStatus(profile.status, filter) }

    val activeCount: Int
        get() = profiles.count { it.status == ResourceStatus.ACTIVE.code }

    val microfluidicCount: Int
        get() = profiles.count {
            it.carrierType == com.muc.fluocolorquant.data.enums.CarrierType.MICROFLUIDIC_CHIP.code
        }
}

/**
 * 载体档案业务编排。
 *
 * 页面编辑已存在资源时不会调用 Room `update()` 覆盖原记录，而是创建新 ID、新版本并
 * 归档旧版本，从而保证历史项目中的载体引用始终稳定。
 */
@HiltViewModel
class CarrierProfileViewModel @Inject constructor(
    private val repository: CarrierProfileRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(CarrierProfileUiState())
    val uiState: StateFlow<CarrierProfileUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ResourceProfileEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ResourceProfileEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { profiles ->
                _uiState.value = _uiState.value.copy(profiles = profiles)
            }
        }
    }

    fun selectFilter(filter: ResourceStatusFilter) {
        _uiState.value = _uiState.value.copy(filter = filter)
    }

    fun openCreateEditor(preset: CarrierPreset, suggestedName: String) {
        _uiState.value = _uiState.value.copy(
            isEditorVisible = true,
            draft = preset.createDraft(suggestedName),
            editingSourceId = null
        )
    }

    fun openNewVersionEditor(profile: CarrierProfile) {
        _uiState.value = _uiState.value.copy(
            isEditorVisible = true,
            draft = CarrierProfileDraft(
                name = profile.name,
                carrierType = com.muc.fluocolorquant.data.enums.CarrierType.fromCode(profile.carrierType)
                    ?: com.muc.fluocolorquant.data.enums.CarrierType.CUSTOM,
                rowsInput = profile.rows.toString(),
                columnsInput = profile.columns.toString(),
                siteShape = com.muc.fluocolorquant.data.enums.SiteShape.fromCode(profile.siteShape)
                    ?: com.muc.fluocolorquant.data.enums.SiteShape.CUSTOM
            ),
            editingSourceId = profile.id
        )
    }

    fun updateDraft(draft: CarrierProfileDraft) {
        _uiState.value = _uiState.value.copy(draft = draft)
    }

    fun dismissEditor() {
        if (_uiState.value.isSaving) return
        _uiState.value = _uiState.value.copy(isEditorVisible = false, editingSourceId = null)
    }

    fun save() {
        val state = _uiState.value
        val errors = state.draft.validate()
        if (errors.isNotEmpty()) {
            _events.tryEmit(ResourceProfileEvent.ValidationFailed(errors))
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                val rows = state.draft.rowsInput.toInt()
                val columns = state.draft.columnsInput.toInt()
                val source = state.editingSourceId?.let { repository.getById(it) }
                val now = Date()
                val candidate = CarrierProfile(
                    name = source?.name ?: state.draft.name.trim(),
                    carrierType = state.draft.carrierType.code,
                    rows = rows,
                    columns = columns,
                    siteShape = state.draft.siteShape.code,
                    orientationMarkerJson = source?.orientationMarkerJson,
                    roiConfigJson = source?.roiConfigJson,
                    locatorConfigJson = source?.locatorConfigJson,
                    createdAt = now,
                    updatedAt = now
                )
                if (source != null) {
                    repository.createNextVersion(source.id, candidate)
                } else {
                    repository.create(candidate)
                }
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isEditorVisible = false,
                    editingSourceId = null
                )
                _events.emit(ResourceProfileEvent.SaveSucceeded(createdNewVersion = source != null))
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false)
                _events.emit(ResourceProfileEvent.SaveFailed)
            }
        }
    }

    fun requestArchive(profile: CarrierProfile) {
        _uiState.value = _uiState.value.copy(pendingArchive = profile)
    }

    fun cancelArchive() {
        _uiState.value = _uiState.value.copy(pendingArchive = null)
    }

    fun confirmArchive() {
        val target = _uiState.value.pendingArchive ?: return
        viewModelScope.launch {
            try {
                repository.archive(target.id)
                _uiState.value = _uiState.value.copy(pendingArchive = null)
                _events.emit(ResourceProfileEvent.ArchiveSucceeded)
            } catch (_: Exception) {
                _events.emit(ResourceProfileEvent.ArchiveFailed)
            }
        }
    }
}

package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepository
import com.muc.fluocolorquant.ui.screens.settings.resources.AcquisitionProfileDraft
import com.muc.fluocolorquant.ui.screens.settings.resources.CameraControlStrategy
import com.muc.fluocolorquant.ui.screens.settings.resources.DetectionModality
import com.muc.fluocolorquant.ui.screens.settings.resources.ResourceProfileEvent
import com.muc.fluocolorquant.ui.screens.settings.resources.ResourceProfileJsonCodec
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

/** 采集设备档案页面状态。 */
data class AcquisitionProfileUiState(
    val profiles: List<AcquisitionProfile> = emptyList(),
    val filter: ResourceStatusFilter = ResourceStatusFilter.ALL,
    val isEditorVisible: Boolean = false,
    val draft: AcquisitionProfileDraft = AcquisitionProfileDraft(),
    val editingSourceId: String? = null,
    val pendingArchive: AcquisitionProfile? = null,
    val isSaving: Boolean = false
) {
    val visibleProfiles: List<AcquisitionProfile>
        get() = profiles.filter { profile -> matchesResourceStatus(profile.status, filter) }

    val activeCount: Int
        get() = profiles.count { it.status == ResourceStatus.ACTIVE.code }

    val fixedFixtureCount: Int
        get() = profiles.count { !it.fixtureId.isNullOrBlank() }
}

/** 采集设备档案业务编排，普通页面不直接接触 JSON 字段。 */
@HiltViewModel
class AcquisitionProfileViewModel @Inject constructor(
    private val repository: AcquisitionProfileRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AcquisitionProfileUiState())
    val uiState: StateFlow<AcquisitionProfileUiState> = _uiState.asStateFlow()

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

    fun openCreateEditor() {
        _uiState.value = _uiState.value.copy(
            isEditorVisible = true,
            draft = AcquisitionProfileDraft(),
            editingSourceId = null
        )
    }

    fun openNewVersionEditor(profile: AcquisitionProfile) {
        _uiState.value = _uiState.value.copy(
            isEditorVisible = true,
            draft = AcquisitionProfileDraft(
                name = profile.name,
                supportedModes = ResourceProfileJsonCodec.decodeCodes(
                    profile.supportedModesJson,
                    DetectionModality.entries.mapTo(mutableSetOf()) { it.code }
                ),
                compatibleCarrierTypes = ResourceProfileJsonCodec.decodeCodes(
                    profile.compatibleCarrierTypesJson,
                    CarrierType.entries.mapTo(mutableSetOf()) { it.code }
                ),
                deviceMatcherNote = ResourceProfileJsonCodec.decodeNoteObject(profile.deviceMatcherJson),
                opticalModuleName = profile.opticalModuleName.orEmpty(),
                fixtureId = profile.fixtureId.orEmpty(),
                cameraControlStrategy = CameraControlStrategy.entries.find {
                    it.code == profile.cameraControlStrategy
                } ?: CameraControlStrategy.AUTO_AND_LOCK
            ),
            editingSourceId = profile.id
        )
    }

    fun updateDraft(draft: AcquisitionProfileDraft) {
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
                val source = state.editingSourceId?.let { repository.getById(it) }
                val now = Date()
                val candidate = AcquisitionProfile(
                    name = source?.name ?: state.draft.name.trim(),
                    supportedModesJson = ResourceProfileJsonCodec.encodeCodes(state.draft.supportedModes),
                    compatibleCarrierTypesJson = ResourceProfileJsonCodec.encodeCodes(
                        state.draft.compatibleCarrierTypes
                    ),
                    deviceMatcherJson = ResourceProfileJsonCodec.encodeNoteObject(
                        state.draft.deviceMatcherNote
                    ),
                    opticalModuleName = state.draft.opticalModuleName.trim().ifEmpty { null },
                    fixtureId = state.draft.fixtureId.trim().ifEmpty { null },
                    cameraControlStrategy = state.draft.cameraControlStrategy.code,
                    cameraConstraintsJson = source?.cameraConstraintsJson,
                    imageQcProfileJson = source?.imageQcProfileJson,
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

    fun requestArchive(profile: AcquisitionProfile) {
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

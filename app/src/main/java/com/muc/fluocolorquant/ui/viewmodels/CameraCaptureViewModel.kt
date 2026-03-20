package com.muc.fluocolorquant.ui.viewmodels

import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.utils.UiText
import com.muc.fluocolorquant.utils.camera.CameraCaptureCapabilitiesSnapshot
import com.muc.fluocolorquant.utils.camera.CameraCaptureControlState
import com.muc.fluocolorquant.utils.camera.CameraCaptureDefaults
import com.muc.fluocolorquant.utils.camera.CameraEngine
import com.muc.fluocolorquant.utils.camera.CameraZoomSnapshot
import com.muc.fluocolorquant.utils.camera.FixedCameraCaptureRequest
import com.muc.fluocolorquant.utils.camera.toFixedCameraCaptureRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class CameraCaptureUiState(
    val outputPath: String? = null,
    val controlState: CameraCaptureControlState = CameraCaptureControlState(),
    val appliedRequest: FixedCameraCaptureRequest? = null,
    val capabilities: CameraCaptureCapabilitiesSnapshot? = null,
    val zoomSnapshot: CameraZoomSnapshot = CameraZoomSnapshot(),
    val isBinding: Boolean = false,
    val isSaving: Boolean = false,
    val bindError: UiText? = null,
    val showInfoDialog: Boolean = false,
    val showAdvancedSheet: Boolean = false
)

sealed interface CameraCaptureEffect {
    data class ShowToast(
        val message: UiText,
        val type: ToastType
    ) : CameraCaptureEffect

    data class NavigateToCrop(val imageUri: String) : CameraCaptureEffect

    data object NavigateBack : CameraCaptureEffect
}

@HiltViewModel
class CameraCaptureViewModel @Inject constructor(
    private val cameraEngine: CameraEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraCaptureUiState())
    val uiState: StateFlow<CameraCaptureUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<CameraCaptureEffect>()
    val effects: SharedFlow<CameraCaptureEffect> = _effects.asSharedFlow()

    private var bindJob: Job? = null
    private var applySettingsJob: Job? = null
    private var zoomJob: Job? = null

    fun initialize(outputPath: String?) {
        if (outputPath.isNullOrBlank()) {
            viewModelScope.launch {
                _effects.emit(
                    CameraCaptureEffect.ShowToast(
                        message = UiText.StringResource(R.string.camera_capture_invalid_output),
                        type = ToastType.ERROR
                    )
                )
                _effects.emit(CameraCaptureEffect.NavigateBack)
            }
            return
        }

        if (_uiState.value.outputPath == outputPath) return

        _uiState.update {
            it.copy(
                outputPath = outputPath,
                bindError = null
            )
        }
    }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val outputPath = _uiState.value.outputPath
        if (outputPath.isNullOrBlank()) return

        val request = _uiState.value.controlState.toFixedCameraCaptureRequest()
        bindJob?.cancel()
        bindJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBinding = true,
                    bindError = null
                )
            }

            runCatching {
                cameraEngine.bindCamera(
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    requestedSettings = request
                )
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        controlState = it.controlState.copy(
                            iso = result.appliedRequest.requestedIso,
                            exposureTimeNs = result.appliedRequest.requestedExposureTimeNs,
                            exposureCompensationIndex = result.appliedRequest.requestedExposureCompensationIndex,
                            awbLock = result.appliedRequest.requestAwbLock,
                            zoomRatio = result.zoomSnapshot.zoomRatio
                        ),
                        isBinding = false,
                        appliedRequest = result.appliedRequest,
                        capabilities = result.capabilities,
                        zoomSnapshot = result.zoomSnapshot,
                        bindError = null
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable

                val errorText = throwable.message
                    ?.takeIf { it.isNotBlank() }
                    ?.let(UiText::DynamicString)
                    ?: UiText.StringResource(R.string.camera_capture_bind_failed)

                _uiState.update {
                    it.copy(
                        isBinding = false,
                        bindError = errorText
                    )
                }
                _effects.emit(
                    CameraCaptureEffect.ShowToast(
                        message = errorText,
                        type = ToastType.ERROR
                    )
                )
            }
        }
    }

    fun captureImage() {
        val outputPath = _uiState.value.outputPath
        when {
            outputPath.isNullOrBlank() -> {
                viewModelScope.launch {
                    _effects.emit(
                        CameraCaptureEffect.ShowToast(
                            message = UiText.StringResource(R.string.camera_capture_invalid_output),
                            type = ToastType.ERROR
                        )
                    )
                }
            }

            !cameraEngine.isCameraReady() -> {
                viewModelScope.launch {
                    _effects.emit(
                        CameraCaptureEffect.ShowToast(
                            message = UiText.StringResource(R.string.camera_capture_ready),
                            type = ToastType.INFO
                        )
                    )
                }
            }

            _uiState.value.isSaving -> Unit

            else -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(isSaving = true) }

                    runCatching {
                        cameraEngine.captureImage(File(outputPath))
                    }.onSuccess { imageUri ->
                        _uiState.update { it.copy(isSaving = false) }
                        _effects.emit(
                            CameraCaptureEffect.NavigateToCrop(imageUri.toString())
                        )
                    }.onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable

                        val errorText = when {
                            throwable is ImageCaptureException && !throwable.message.isNullOrBlank() ->
                                UiText.DynamicString(throwable.message!!)
                            throwable.message == "camera_not_ready" ->
                                UiText.StringResource(R.string.camera_capture_ready)
                            else ->
                                UiText.StringResource(R.string.camera_capture_save_failed)
                        }

                        _uiState.update { it.copy(isSaving = false) }
                        _effects.emit(
                            CameraCaptureEffect.ShowToast(
                                message = errorText,
                                type = ToastType.ERROR
                            )
                        )
                    }
                }
            }
        }
    }

    fun updateIso(value: Int) {
        updateControlState { copy(iso = value) }
    }

    fun updateExposureTime(value: Long) {
        updateControlState { copy(exposureTimeNs = value) }
    }

    fun updateExposureCompensation(value: Int) {
        updateControlState { copy(exposureCompensationIndex = value) }
    }

    fun updateAwbLock(enabled: Boolean) {
        updateControlState { copy(awbLock = enabled) }
    }

    fun updateZoomRatio(value: Float) {
        val snapshot = _uiState.value.zoomSnapshot
        val clamped = value.coerceIn(snapshot.minZoomRatio, snapshot.maxZoomRatio)
        _uiState.update {
            it.copy(
                controlState = it.controlState.copy(zoomRatio = clamped),
                zoomSnapshot = snapshot.copy(zoomRatio = clamped)
            )
        }
        applyZoomRatio(clamped)
    }

    fun adjustZoomBy(scaleFactor: Float) {
        val currentZoom = _uiState.value.zoomSnapshot.zoomRatio
        updateZoomRatio(currentZoom * scaleFactor)
    }

    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        if (!cameraEngine.isCameraReady()) return

        viewModelScope.launch {
            runCatching {
                cameraEngine.startFocusAndMetering(previewView, x, y)
            }
        }
    }

    fun resetDefaults() {
        _uiState.update {
            it.copy(
                controlState = CameraCaptureControlState(
                    iso = CameraCaptureDefaults.ISO,
                    exposureTimeNs = CameraCaptureDefaults.EXPOSURE_TIME_NS,
                    exposureCompensationIndex = CameraCaptureDefaults.EXPOSURE_COMPENSATION_INDEX,
                    awbLock = CameraCaptureDefaults.AWB_LOCK,
                    zoomRatio = CameraCaptureDefaults.ZOOM_RATIO
                ),
                appliedRequest = null,
                bindError = null
            )
        }
        if (cameraEngine.isCameraReady()) {
            applySettings()
            applyZoomRatio(CameraCaptureDefaults.ZOOM_RATIO)
        }
    }

    fun setInfoDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showInfoDialog = visible) }
    }

    fun setAdvancedSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(showAdvancedSheet = visible) }
    }

    private fun updateControlState(transform: CameraCaptureControlState.() -> CameraCaptureControlState) {
        _uiState.update {
            it.copy(
                controlState = it.controlState.transform(),
                appliedRequest = null,
                bindError = null
            )
        }
        applySettings()
    }

    private fun applySettings() {
        if (!cameraEngine.isCameraReady()) return

        applySettingsJob?.cancel()
        applySettingsJob = viewModelScope.launch {
            val request = _uiState.value.controlState.toFixedCameraCaptureRequest()
            runCatching {
                cameraEngine.updateCaptureRequest(request)
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        controlState = it.controlState.copy(
                            iso = result.appliedRequest.requestedIso,
                            exposureTimeNs = result.appliedRequest.requestedExposureTimeNs,
                            exposureCompensationIndex = result.appliedRequest.requestedExposureCompensationIndex,
                            awbLock = result.appliedRequest.requestAwbLock,
                            zoomRatio = result.zoomSnapshot.zoomRatio
                        ),
                        appliedRequest = result.appliedRequest,
                        capabilities = result.capabilities,
                        zoomSnapshot = result.zoomSnapshot,
                        bindError = null
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
            }
        }
    }

    private fun applyZoomRatio(zoomRatio: Float) {
        if (!cameraEngine.isCameraReady()) return

        zoomJob?.cancel()
        zoomJob = viewModelScope.launch {
            runCatching {
                cameraEngine.setZoomRatio(zoomRatio)
            }.onSuccess { snapshot ->
                _uiState.update {
                    it.copy(
                        controlState = it.controlState.copy(zoomRatio = snapshot.zoomRatio),
                        zoomSnapshot = snapshot
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
            }
        }
    }

    override fun onCleared() {
        bindJob?.cancel()
        applySettingsJob?.cancel()
        zoomJob?.cancel()
        cameraEngine.clear()
        super.onCleared()
    }
}

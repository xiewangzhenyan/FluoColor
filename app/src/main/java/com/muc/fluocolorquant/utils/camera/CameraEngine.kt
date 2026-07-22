package com.muc.fluocolorquant.utils.camera

import android.content.Context
import android.os.Build
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 相机硬件控制抽象，屏蔽 CameraX / Camera2Interop 细节。
 */
interface CameraEngine {
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        requestedSettings: FixedCameraCaptureRequest
    ): CameraBindingResult

    suspend fun updateCaptureRequest(
        requestedSettings: FixedCameraCaptureRequest
    ): CameraBindingResult

    suspend fun setZoomRatio(zoomRatio: Float): CameraZoomSnapshot

    suspend fun startFocusAndMetering(
        previewView: PreviewView,
        x: Float,
        y: Float
    ): Boolean

    suspend fun captureImage(outputFile: File): Uri

    fun isCameraReady(): Boolean

    fun clear()
}

private data class PreparedCameraUseCases(
    val preview: CameraPreview,
    val imageCapture: ImageCapture,
    val request: FixedCameraCaptureRequest,
    val capabilities: CameraCaptureCapabilitiesSnapshot
)

/**
 * 基于 CameraX + Camera2Interop 的默认相机引擎实现。
 */
@ExperimentalCamera2Interop
class CameraXCameraEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : CameraEngine {

    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var camera2CameraControl: Camera2CameraControl? = null
    private var imageCapture: ImageCapture? = null
    private var currentCapabilities: CameraCaptureCapabilitiesSnapshot? = null
    private var currentRequest: FixedCameraCaptureRequest? = null

    override suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        requestedSettings: FixedCameraCaptureRequest
    ): CameraBindingResult = withContext(Dispatchers.Main.immediate) {
        val provider = awaitCameraProvider(context)
        val prepared = prepareCameraUseCases(context, requestedSettings)

        provider.unbindAll()
        prepared.preview.setSurfaceProvider(previewView.surfaceProvider)
        val boundCamera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            prepared.preview,
            prepared.imageCapture
        )

        cameraProvider = provider
        camera = boundCamera
        camera2CameraControl = Camera2CameraControl.from(boundCamera.cameraControl)
        imageCapture = prepared.imageCapture
        currentCapabilities = prepared.capabilities
        currentRequest = prepared.request

        CameraBindingResult(
            appliedRequest = prepared.request,
            capabilities = prepared.capabilities,
            zoomSnapshot = extractZoomSnapshot(boundCamera)
        )
    }

    override suspend fun updateCaptureRequest(
        requestedSettings: FixedCameraCaptureRequest
    ): CameraBindingResult = withContext(Dispatchers.Main.immediate) {
        val capabilities = currentCapabilities ?: throw IllegalStateException("camera_not_ready")
        val boundCamera = camera ?: throw IllegalStateException("camera_not_ready")
        val camera2Control = camera2CameraControl ?: Camera2CameraControl.from(boundCamera.cameraControl)

        val appliedRequest = normalizeRequest(
            requestedSettings = requestedSettings,
            capabilities = capabilities
        )
        val appliedCapabilities = capabilities.withAppliedRequest(appliedRequest)
        val options = buildCaptureRequestOptions(
            request = appliedRequest,
            capabilities = appliedCapabilities
        )

        awaitListenableFuture(camera2Control.setCaptureRequestOptions(options), context)

        currentRequest = appliedRequest
        currentCapabilities = appliedCapabilities
        camera2CameraControl = camera2Control

        CameraBindingResult(
            appliedRequest = appliedRequest,
            capabilities = appliedCapabilities,
            zoomSnapshot = extractZoomSnapshot(boundCamera)
        )
    }

    override suspend fun setZoomRatio(zoomRatio: Float): CameraZoomSnapshot =
        withContext(Dispatchers.Main.immediate) {
            val boundCamera = camera ?: throw IllegalStateException("camera_not_ready")
            val currentZoom = extractZoomSnapshot(boundCamera)
            val clampedZoom = zoomRatio.coerceIn(currentZoom.minZoomRatio, currentZoom.maxZoomRatio)
            awaitListenableFuture(boundCamera.cameraControl.setZoomRatio(clampedZoom), context)
            extractZoomSnapshot(boundCamera, clampedZoom)
        }

    override suspend fun startFocusAndMetering(
        previewView: PreviewView,
        x: Float,
        y: Float
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        val boundCamera = camera ?: throw IllegalStateException("camera_not_ready")
        val meteringPoint = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(meteringPoint)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        awaitListenableFuture(boundCamera.cameraControl.startFocusAndMetering(action), context)
            .isFocusSuccessful
    }

    override suspend fun captureImage(outputFile: File): Uri =
        suspendCancellableCoroutine { continuation ->
            val captureUseCase = imageCapture
            val capabilitiesSnapshot = currentCapabilities
            val requestSnapshot = currentRequest

            if (captureUseCase == null || capabilitiesSnapshot == null || requestSnapshot == null) {
                continuation.resumeWithException(IllegalStateException("camera_not_ready"))
                return@suspendCancellableCoroutine
            }

            outputFile.parentFile?.mkdirs()
            val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()

            captureUseCase.takePicture(
                options,
                captureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        runCatching {
                            CameraCaptureMetadataStore.writeCaptureMetadata(
                                imageFile = outputFile,
                                request = requestSnapshot,
                                capabilities = capabilitiesSnapshot,
                                zoomSnapshot = camera?.let { boundCamera ->
                                    extractZoomSnapshot(boundCamera)
                                }
                            )
                        }.onFailure { error ->
                            Log.w("CameraEngine", "保存相机元数据失败", error)
                        }

                        continuation.resume(Uri.fromFile(outputFile))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                }
            )
        }

    override fun isCameraReady(): Boolean {
        return camera != null &&
            imageCapture != null &&
            currentCapabilities != null &&
            currentRequest != null
    }

    override fun clear() {
        camera = null
        camera2CameraControl = null
        imageCapture = null
        currentCapabilities = null
        currentRequest = null

        runCatching {
            cameraProvider?.unbindAll()
        }

        if (!captureExecutor.isShutdown) {
            captureExecutor.shutdown()
        }
    }
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { provider -> continuation.resume(provider) }
                    .onFailure { error -> continuation.resumeWithException(error) }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

@ExperimentalCamera2Interop
private fun prepareCameraUseCases(
    context: Context,
    requestedSettings: FixedCameraCaptureRequest
): PreparedCameraUseCases {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraId = findBackCameraId(cameraManager) ?: cameraManager.cameraIdList.first()
    val characteristics = cameraManager.getCameraCharacteristics(cameraId)

    val baseCapabilities = extractCapabilities(
        cameraId = cameraId,
        characteristics = characteristics
    )
    val appliedRequest = normalizeRequest(
        requestedSettings = requestedSettings,
        capabilities = baseCapabilities
    )
    val capabilities = baseCapabilities.withAppliedRequest(appliedRequest)

    val previewBuilder = CameraPreview.Builder()
    val imageCaptureBuilder = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setFlashMode(ImageCapture.FLASH_MODE_OFF)

    applyFixedCaptureOptions(
        previewBuilder = previewBuilder,
        imageCaptureBuilder = imageCaptureBuilder,
        request = appliedRequest,
        capabilities = capabilities
    )

    return PreparedCameraUseCases(
        preview = previewBuilder.build(),
        imageCapture = imageCaptureBuilder.build(),
        request = appliedRequest,
        capabilities = capabilities
    )
}

private fun extractCapabilities(
    cameraId: String,
    characteristics: CameraCharacteristics
): CameraCaptureCapabilitiesSnapshot {
    val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
    val availableCapabilities = characteristics.get(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
    ) ?: intArrayOf()
    val supportsManualSensor = availableCapabilities.contains(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
    )
    val supportsAeLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
    } else {
        false
    }
    val supportsAwbLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
    } else {
        false
    }

    val compensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val exposureCompensationRange = compensationRange?.lower?.let { lower ->
        compensationRange.upper.let { upper -> lower..upper }
    } ?: (0..0)
    val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.toIntRange()
    val exposureTimeRange = characteristics.get(
        CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
    )?.toLongRange()
    val lensFacingLabel = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }
    val focalLengths = characteristics.get(
        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
    )?.toList().orEmpty()
    val apertures = characteristics.get(
        CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES
    )?.toList().orEmpty()
    val minimumFocusDistance = characteristics.get(
        CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
    )
    val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)

    return CameraCaptureCapabilitiesSnapshot(
        cameraId = cameraId,
        hardwareLevelLabel = mapHardwareLevel(hardwareLevel),
        supportsManualSensor = supportsManualSensor,
        supportsAeLock = supportsAeLock,
        supportsAwbLock = supportsAwbLock,
        appliedManualSensor = supportsManualSensor && isoRange != null && exposureTimeRange != null,
        appliedAeLock = false,
        appliedAwbLock = false,
        appliedExposureCompensationIndex = CameraCaptureDefaults.EXPOSURE_COMPENSATION_INDEX,
        exposureCompensationRangeLabel = "${exposureCompensationRange.first}..${exposureCompensationRange.last}",
        sensorIsoRangeLabel = isoRange?.let { "${it.first}..${it.last}" },
        sensorExposureTimeRangeLabel = exposureTimeRange?.let { "${it.first}..${it.last}" },
        exposureCompensationRange = exposureCompensationRange,
        sensorIsoRange = isoRange,
        sensorExposureTimeRangeNs = exposureTimeRange,
        lensFacingLabel = lensFacingLabel,
        availableFocalLengthsMm = focalLengths,
        availableApertures = apertures,
        minimumFocusDistanceDiopters = minimumFocusDistance,
        sensorPixelArraySizeLabel = pixelArraySize?.let { "${it.width}x${it.height}" }
    )
}

private fun normalizeRequest(
    requestedSettings: FixedCameraCaptureRequest,
    capabilities: CameraCaptureCapabilitiesSnapshot
): FixedCameraCaptureRequest {
    val appliedIso = requestedSettings.requestedIso.coerceInRange(capabilities.sensorIsoRange)
    val appliedExposureTime = requestedSettings.requestedExposureTimeNs
        .coerceInRange(capabilities.sensorExposureTimeRangeNs)
    val exposureRange = capabilities.exposureCompensationRange ?: (0..0)
    val appliedExposureCompensation = requestedSettings.requestedExposureCompensationIndex
        .coerceIn(exposureRange.first, exposureRange.last)
    val appliedManualSensor = capabilities.appliedManualSensor
    val appliedAeLock = !appliedManualSensor &&
        capabilities.supportsAeLock &&
        requestedSettings.requestAeLock
    val appliedAwbLock = capabilities.supportsAwbLock && requestedSettings.requestAwbLock

    return FixedCameraCaptureRequest(
        requestedIso = appliedIso,
        requestedExposureTimeNs = appliedExposureTime,
        requestedExposureCompensationIndex = appliedExposureCompensation,
        requestAeLock = appliedAeLock,
        requestAwbLock = appliedAwbLock
    )
}

@ExperimentalCamera2Interop
private fun buildCaptureRequestOptions(
    request: FixedCameraCaptureRequest,
    capabilities: CameraCaptureCapabilitiesSnapshot
): CaptureRequestOptions {
    val builder = CaptureRequestOptions.Builder()
        .setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        .setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO
        )

    if (capabilities.appliedManualSensor) {
        builder
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                request.requestedIso
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                request.requestedExposureTimeNs
            )
    } else {
        builder
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                request.requestedExposureCompensationIndex
            )
        if (capabilities.supportsAeLock) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_LOCK,
                request.requestAeLock
            )
        }
    }

    if (capabilities.supportsAwbLock) {
        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_LOCK,
            request.requestAwbLock
        )
    }

    return builder.build()
}

@ExperimentalCamera2Interop
private fun applyFixedCaptureOptions(
    previewBuilder: CameraPreview.Builder,
    imageCaptureBuilder: ImageCapture.Builder,
    request: FixedCameraCaptureRequest,
    capabilities: CameraCaptureCapabilitiesSnapshot
) {
    applyRequestOptionsToExtender(
        extender = Camera2Interop.Extender(previewBuilder),
        request = request,
        capabilities = capabilities
    )
    applyRequestOptionsToExtender(
        extender = Camera2Interop.Extender(imageCaptureBuilder),
        request = request,
        capabilities = capabilities
    )
}

@ExperimentalCamera2Interop
private fun <T> applyRequestOptionsToExtender(
    extender: Camera2Interop.Extender<T>,
    request: FixedCameraCaptureRequest,
    capabilities: CameraCaptureCapabilitiesSnapshot
) {
    extender.setCaptureRequestOption(
        CaptureRequest.CONTROL_AF_MODE,
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    )
    extender.setCaptureRequestOption(
        CaptureRequest.CONTROL_AWB_MODE,
        CaptureRequest.CONTROL_AWB_MODE_AUTO
    )

    if (capabilities.appliedManualSensor) {
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_OFF
        )
        extender.setCaptureRequestOption(
            CaptureRequest.SENSOR_SENSITIVITY,
            request.requestedIso
        )
        extender.setCaptureRequestOption(
            CaptureRequest.SENSOR_EXPOSURE_TIME,
            request.requestedExposureTimeNs
        )
    } else {
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_ON
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            request.requestedExposureCompensationIndex
        )
        if (capabilities.supportsAeLock) {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_LOCK,
                request.requestAeLock
            )
        }
    }

    if (capabilities.supportsAwbLock) {
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_LOCK,
            request.requestAwbLock
        )
    }
}

private suspend fun <T> awaitListenableFuture(
    future: ListenableFuture<T>,
    context: Context
): T =
    suspendCancellableCoroutine { continuation ->
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { result -> continuation.resume(result) }
                    .onFailure { error -> continuation.resumeWithException(error) }
            },
            ContextCompat.getMainExecutor(context)
        )
        continuation.invokeOnCancellation {
            future.cancel(false)
        }
    }

private fun extractZoomSnapshot(
    camera: Camera,
    fallbackZoomRatio: Float? = null
): CameraZoomSnapshot {
    val zoomState = camera.cameraInfo.zoomState.value
    return CameraZoomSnapshot(
        zoomRatio = zoomState?.zoomRatio ?: fallbackZoomRatio ?: CameraCaptureDefaults.ZOOM_RATIO,
        minZoomRatio = zoomState?.minZoomRatio ?: CameraCaptureDefaults.ZOOM_RATIO,
        maxZoomRatio = zoomState?.maxZoomRatio ?: CameraCaptureDefaults.ZOOM_RATIO
    )
}

private fun findBackCameraId(cameraManager: CameraManager): String? {
    return cameraManager.cameraIdList.firstOrNull { cameraId ->
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK
    }
}

private fun mapHardwareLevel(level: Int?): String {
    return when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        else -> "UNKNOWN"
    }
}

private fun Int.coerceInRange(range: IntRange?): Int {
    if (range == null) return this
    return coerceIn(range.first, range.last)
}

private fun Long.coerceInRange(range: LongRange?): Long {
    if (range == null) return this
    return coerceIn(range.first, range.last)
}

private fun Range<Int>.toIntRange(): IntRange = lower..upper

private fun Range<Long>.toLongRange(): LongRange = lower..upper

private fun CameraCaptureCapabilitiesSnapshot.withAppliedRequest(
    request: FixedCameraCaptureRequest
): CameraCaptureCapabilitiesSnapshot {
    return copy(
        appliedAeLock = request.requestAeLock,
        appliedAwbLock = request.requestAwbLock,
        appliedExposureCompensationIndex = request.requestedExposureCompensationIndex
    )
}

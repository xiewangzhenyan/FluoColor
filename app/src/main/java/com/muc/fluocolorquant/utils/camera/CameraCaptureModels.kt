package com.muc.fluocolorquant.utils.camera

/**
 * 相机固定拍摄参数默认值。
 */
object CameraCaptureDefaults {
    const val ISO = 200
    const val EXPOSURE_TIME_NS = 10_000_000L
    const val EXPOSURE_COMPENSATION_INDEX = 0
    const val AWB_LOCK = true
    const val ZOOM_RATIO = 1f
}

/**
 * 相机控制台的当前参数状态。
 */
data class CameraCaptureControlState(
    val iso: Int = CameraCaptureDefaults.ISO,
    val exposureTimeNs: Long = CameraCaptureDefaults.EXPOSURE_TIME_NS,
    val exposureCompensationIndex: Int = CameraCaptureDefaults.EXPOSURE_COMPENSATION_INDEX,
    val awbLock: Boolean = CameraCaptureDefaults.AWB_LOCK,
    val zoomRatio: Float = CameraCaptureDefaults.ZOOM_RATIO
)

/**
 * 固定拍摄请求配置。
 */
data class FixedCameraCaptureRequest(
    val requestedIso: Int,
    val requestedExposureTimeNs: Long,
    val requestedExposureCompensationIndex: Int,
    val requestAeLock: Boolean,
    val requestAwbLock: Boolean
)

/**
 * 相机能力与实际应用策略快照。
 */
data class CameraCaptureCapabilitiesSnapshot(
    val cameraId: String,
    val hardwareLevelLabel: String,
    val supportsManualSensor: Boolean,
    val supportsAeLock: Boolean,
    val supportsAwbLock: Boolean,
    val appliedManualSensor: Boolean,
    val appliedAeLock: Boolean,
    val appliedAwbLock: Boolean,
    val appliedExposureCompensationIndex: Int,
    val exposureCompensationRangeLabel: String,
    val sensorIsoRangeLabel: String?,
    val sensorExposureTimeRangeLabel: String?,
    val exposureCompensationRange: IntRange? = null,
    val sensorIsoRange: IntRange? = null,
    val sensorExposureTimeRangeNs: LongRange? = null,
    // 下列镜头字段均来自 CameraCharacteristics，自动记录而不要求用户建立设备档案。
    val lensFacingLabel: String? = null,
    val availableFocalLengthsMm: List<Float> = emptyList(),
    val availableApertures: List<Float> = emptyList(),
    val minimumFocusDistanceDiopters: Float? = null,
    val sensorPixelArraySizeLabel: String? = null
)

/**
 * 绑定 CameraX 后返回的有效会话快照。
 */
data class CameraBindingResult(
    val appliedRequest: FixedCameraCaptureRequest,
    val capabilities: CameraCaptureCapabilitiesSnapshot,
    val zoomSnapshot: CameraZoomSnapshot
)

/**
 * 当前缩放能力与状态快照。
 */
data class CameraZoomSnapshot(
    val zoomRatio: Float = CameraCaptureDefaults.ZOOM_RATIO,
    val minZoomRatio: Float = CameraCaptureDefaults.ZOOM_RATIO,
    val maxZoomRatio: Float = CameraCaptureDefaults.ZOOM_RATIO
)

/**
 * 将 UI 状态转换为底层相机请求。
 */
fun CameraCaptureControlState.toFixedCameraCaptureRequest(
    requestAeLock: Boolean = true
): FixedCameraCaptureRequest {
    return FixedCameraCaptureRequest(
        requestedIso = iso,
        requestedExposureTimeNs = exposureTimeNs,
        requestedExposureCompensationIndex = exposureCompensationIndex,
        requestAeLock = requestAeLock,
        requestAwbLock = awbLock
    )
}

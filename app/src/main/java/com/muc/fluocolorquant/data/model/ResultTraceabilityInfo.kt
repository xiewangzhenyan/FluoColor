package com.muc.fluocolorquant.data.model

/**
 * 检测结果可追溯信息。
 * 用于在结果页和导出报告中回看采集、推理与处理参数。
 */
data class ResultTraceabilityInfo(
    val detectionModelName: String?,
    val concentrationModelName: String?,
    val recognitionType: String,
    val captureMetadata: CaptureMetadataSummary?,
    val confidenceThreshold: Float?,
    val iouThreshold: Float?,
    val templateName: String?,
    val curveModelName: String?,
    val pixelFeatureName: String?,
    val runTimestampLabel: String?
)

/**
 * 相机拍摄元数据摘要。
 */
data class CaptureMetadataSummary(
    val capturedAtLabel: String?,
    val iso: Int?,
    val exposureTimeMs: Double?,
    val exposureCompensationIndex: Int?,
    val awbModeLabel: String?,
    val metadataFilePath: String?
)

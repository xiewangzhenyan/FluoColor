package com.muc.fluocolorquant.utils

import android.net.Uri
import com.muc.fluocolorquant.data.model.CaptureMetadataSummary
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ResultTraceabilityInfo
import com.muc.fluocolorquant.utils.camera.CameraCaptureMetadataStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 构建结果页与导出链路共用的可追溯信息。
 */
object ResultTraceabilityUtils {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun buildTraceabilityInfo(
        project: Project,
        detectionRun: DetectionRun?,
        template: ExperimentTemplate?,
        curveModel: CurveModel?
    ): ResultTraceabilityInfo {
        val captureMetadata = readCaptureMetadata(project.imageUri)
        val runTimestampLabel = detectionRun?.timestamp?.let(dateFormat::format)
            ?: project.lastRunTimestamp?.let(dateFormat::format)

        return ResultTraceabilityInfo(
            detectionModelName = detectionRun?.detectionModelUsed?.substringAfterLast('/'),
            concentrationModelName = detectionRun?.concentrationModelUsed?.substringAfterLast('/'),
            recognitionType = project.recognitionType,
            captureMetadata = captureMetadata,
            confidenceThreshold = detectionRun?.confThreshold,
            iouThreshold = detectionRun?.iouThreshold,
            templateName = template?.templateName,
            curveModelName = curveModel?.name,
            pixelFeatureName = curveModel?.pixelType?.displayName,
            runTimestampLabel = runTimestampLabel
        )
    }

    fun readCaptureMetadata(imageUri: String): CaptureMetadataSummary? {
        val imageFile = resolveImageFile(imageUri) ?: return null
        val metadataFile = CameraCaptureMetadataStore.buildMetadataFile(imageFile)
        if (!metadataFile.exists()) return null

        return runCatching {
            val root = JSONObject(metadataFile.readText())
            val request = root.optJSONObject("requestedSettings")
            CaptureMetadataSummary(
                capturedAtLabel = root.optString("capturedAt").takeIf { it.isNotBlank() },
                iso = request?.takeIf { it.has("iso") }?.optInt("iso"),
                exposureTimeMs = request
                    ?.takeIf { it.has("exposureTimeNs") }
                    ?.optLong("exposureTimeNs")
                    ?.takeIf { it > 0L }
                    ?.let { it / 1_000_000.0 },
                exposureCompensationIndex = request
                    ?.takeIf { it.has("exposureCompensationIndex") }
                    ?.optInt("exposureCompensationIndex"),
                awbModeLabel = request?.optString("awbMode")?.takeIf { it.isNotBlank() },
                metadataFilePath = metadataFile.absolutePath
            )
        }.getOrNull()
    }

    private fun resolveImageFile(imageUri: String): File? {
        if (imageUri.isBlank()) return null
        return when {
            imageUri.startsWith("file://") -> Uri.parse(imageUri).path?.let(::File)
            imageUri.startsWith("content://") -> null
            else -> File(imageUri)
        }?.takeIf { it.exists() }
    }
}

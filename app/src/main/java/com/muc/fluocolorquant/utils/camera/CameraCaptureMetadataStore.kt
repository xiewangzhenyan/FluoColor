package com.muc.fluocolorquant.utils.camera

import android.os.Build
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 负责将拍摄元数据写入图片旁路文件，便于后续误差分析。
 */
object CameraCaptureMetadataStore {

    private const val METADATA_SUFFIX = "_camera_metadata.json"

    private val exifTags = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_WHITE_BALANCE
    )

    fun buildMetadataFile(imageFile: File): File {
        val parent = imageFile.parentFile ?: File(".")
        return File(parent, "${imageFile.nameWithoutExtension}$METADATA_SUFFIX")
    }

    fun sanitizeMetadataToken(rawValue: String): String {
        return rawValue
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "capture" }
    }

    fun writeCaptureMetadata(
        imageFile: File,
        request: FixedCameraCaptureRequest,
        capabilities: CameraCaptureCapabilitiesSnapshot,
        metadataVersion: String = "1.0"
    ): File {
        val exifData = readExifMetadata(imageFile)
        val metadataFile = buildMetadataFile(imageFile)

        val root = JSONObject().apply {
            put("metadataVersion", metadataVersion)
            put("capturedAt", currentTimestamp())
            put("imageFileName", imageFile.name)
            put("imageAbsolutePath", imageFile.absolutePath)
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER.orEmpty())
                put("brand", Build.BRAND.orEmpty())
                put("model", Build.MODEL.orEmpty())
                put("sdkInt", Build.VERSION.SDK_INT)
            })
            put("requestedSettings", JSONObject().apply {
                put("iso", request.requestedIso)
                put("exposureTimeNs", request.requestedExposureTimeNs)
                put("exposureCompensationIndex", request.requestedExposureCompensationIndex)
                put("aeLockRequested", request.requestAeLock)
                put("awbLockRequested", request.requestAwbLock)
                put("awbMode", if (request.requestAwbLock) "AUTO_LOCK" else "AUTO")
            })
            put("cameraCapabilities", JSONObject().apply {
                put("cameraId", capabilities.cameraId)
                put("hardwareLevel", capabilities.hardwareLevelLabel)
                put("supportsManualSensor", capabilities.supportsManualSensor)
                put("supportsAeLock", capabilities.supportsAeLock)
                put("supportsAwbLock", capabilities.supportsAwbLock)
                put("appliedManualSensor", capabilities.appliedManualSensor)
                put("appliedAeLock", capabilities.appliedAeLock)
                put("appliedAwbLock", capabilities.appliedAwbLock)
                put("appliedExposureCompensationIndex", capabilities.appliedExposureCompensationIndex)
                put("exposureCompensationRange", capabilities.exposureCompensationRangeLabel)
                put("sensorIsoRange", capabilities.sensorIsoRangeLabel ?: "")
                put("sensorExposureTimeRangeNs", capabilities.sensorExposureTimeRangeLabel ?: "")
            })
            put("exif", JSONObject(exifData))
        }

        metadataFile.writeText(root.toString(2))
        return metadataFile
    }

    private fun readExifMetadata(imageFile: File): Map<String, String> {
        if (!imageFile.exists()) return emptyMap()

        return runCatching {
            val exif = ExifInterface(imageFile)
            buildMap {
                exifTags.forEach { tag ->
                    val value = exif.getAttribute(tag)
                    if (!value.isNullOrBlank()) {
                        put(tag, value)
                    }
                }
            }
        }.getOrElse { error ->
            mapOf("readError" to (error.message ?: "unknown"))
        }
    }

    private fun currentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
    }
}

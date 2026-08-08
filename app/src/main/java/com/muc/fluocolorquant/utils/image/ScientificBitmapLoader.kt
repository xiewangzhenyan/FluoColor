package com.muc.fluocolorquant.utils.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 解码后的科研图片及已经应用的EXIF方向信息。 */
data class LoadedScientificBitmap(
    val bitmap: Bitmap,
    val exifRotationDegrees: Int,
    val exifFlipped: Boolean
)

/**
 * content/file/绝对路径共用的图片加载器。
 *
 * 算法看到图片前必须先应用EXIF旋转与翻转，否则90°方向错误会被误认为孔板横竖方向。
 * 这里只改变内存工作图，不修改用户原文件，也不重新编码图片。
 */
@Singleton
class ScientificBitmapLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun load(rawUri: String): LoadedScientificBitmap? = withContext(Dispatchers.IO) {
        val decodedUri = runCatching { URLDecoder.decode(rawUri, Charsets.UTF_8.name()) }
            .getOrDefault(rawUri)
        val uri = Uri.parse(decodedUri)
        val orientation = readOrientation(uri, decodedUri)
        val decoded = decodeBitmap(uri, decodedUri) ?: return@withContext null
        val normalized = applyExif(decoded, orientation.rotationDegrees, orientation.flipped)
        if (normalized !== decoded && !decoded.isRecycled) decoded.recycle()
        LoadedScientificBitmap(
            bitmap = normalized,
            exifRotationDegrees = orientation.rotationDegrees,
            exifFlipped = orientation.flipped
        )
    }

    private fun decodeBitmap(uri: Uri, decodedUri: String): Bitmap? {
        return when (uri.scheme?.lowercase()) {
            "content", "file", "android.resource" -> {
                context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }
            null, "" -> BitmapFactory.decodeFile(File(decodedUri).absolutePath)
            else -> null
        }
    }

    private fun readOrientation(uri: Uri, decodedUri: String): ExifOrientation {
        return runCatching {
            val exif = when (uri.scheme?.lowercase()) {
                "content", "android.resource" -> {
                    context.contentResolver.openInputStream(uri)?.use(::ExifInterface)
                }
                "file" -> uri.path?.let(::ExifInterface)
                null, "" -> ExifInterface(File(decodedUri))
                else -> null
            } ?: return@runCatching ExifOrientation.NONE
            ExifOrientation(
                rotationDegrees = exif.rotationDegrees,
                flipped = exif.isFlipped
            )
        }.getOrDefault(ExifOrientation.NONE)
    }

    /** 暴露为模块内可测函数，确保EXIF的旋转/镜像顺序不会在后续重构中悄然改变。 */
    internal fun applyExif(source: Bitmap, rotationDegrees: Int, flipped: Boolean): Bitmap {
        if (rotationDegrees % 360 == 0 && !flipped) return source
        val matrix = Matrix().apply {
            if (flipped) postScale(-1f, 1f)
            if (rotationDegrees % 360 != 0) postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
    }

    private data class ExifOrientation(val rotationDegrees: Int, val flipped: Boolean) {
        companion object {
            val NONE: ExifOrientation = ExifOrientation(0, false)
        }
    }
}

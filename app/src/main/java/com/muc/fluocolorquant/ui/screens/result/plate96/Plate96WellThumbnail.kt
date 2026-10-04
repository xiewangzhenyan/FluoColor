package com.muc.fluocolorquant.ui.screens.result.plate96

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBitmapCropper
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.domain.result.plate96.Plate96WellResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val PLATE96_WELL_THUMBNAIL_TAG_PREFIX: String = "plate96_well_thumbnail_"

/**
 * 结果、分析、验证和单孔详情共用的真实圆孔缩略图。
 *
 * 首屏只显示已经冻结的裁切证据；如果旧历史没有裁切文件或现代运行缺少几何，明确显示
 * 占位图，不重新定位、不使用虚拟生成图片。
 */
@Composable
fun Plate96WellThumbnail(
    snapshot: Plate96ResultSnapshot,
    well: Plate96WellResult,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        snapshot.runId,
        well.wellIndex,
        snapshot.visualEvidence
    ) {
        value = withContext(Dispatchers.IO) {
            Plate96WellThumbnailLoader.load(context, snapshot, well)
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .testTag("$PLATE96_WELL_THUMBNAIL_TAG_PREFIX${well.wellIndex}")
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.44f)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = stringResource(
                    R.string.plate96_well_thumbnail_description,
                    well.wellLabel
                ),
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 以运行ID和孔位索引缓存真实圆孔裁切图，避免列表滚动或 PDF 导出反复解码整张原图。
 *
 * 该加载器只消费结果快照中已经冻结的图像路径与裁切边界，不重新执行定位。开放为模块内
 * 能力后，页面缩略图与 PDF 逐孔附录会读取同一份图像证据，避免再次维护两套裁切逻辑。
 */
internal object Plate96WellThumbnailLoader {
    private const val TARGET_SIZE_PX = 128
    private val sourceCache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val cropCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun load(
        context: Context,
        snapshot: Plate96ResultSnapshot,
        well: Plate96WellResult
    ): Bitmap? {
        val cacheKey = "${snapshot.runId}:${well.wellIndex}"
        cropCache.get(cacheKey)?.let { return it }
        val evidence = snapshot.visualEvidence
        val bounds = evidence.cropBounds[well.wellIndex]

        val modernCrop = cropFromEvidence(
            context = context,
            path = evidence.normalizedImagePath,
            bounds = bounds?.normalized,
            well = well
        ) ?: cropFromEvidence(
            context = context,
            path = evidence.sourceImagePath,
            bounds = bounds?.source,
            well = well
        )
        val resolved = modernCrop ?: evidence.legacyCropPaths[well.wellIndex]
            ?.let { path -> decodeBitmap(context, path) }
            ?.let { bitmap -> Bitmap.createScaledBitmap(bitmap, TARGET_SIZE_PX, TARGET_SIZE_PX, true) }
        resolved?.let { cropCache.put(cacheKey, it) }
        return resolved
    }

    private fun cropFromEvidence(
        context: Context,
        path: String?,
        bounds: com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBounds?,
        well: Plate96WellResult
    ): Bitmap? {
        if (path.isNullOrBlank() || bounds == null) return null
        val source = sourceCache.get(path) ?: decodeBitmap(context, path)?.also { decoded ->
            sourceCache.put(path, decoded)
        } ?: return null
        return runCatching {
            val region = ArrayUnitBitmapCropper.detectedGeometryRegion(
                siteIndex = well.wellIndex,
                rowIndex = well.rowIndex,
                columnIndex = well.columnIndex,
                shape = ArrayUnitShape.CIRCLE,
                bounds = bounds
            )
            ArrayUnitBitmapCropper.crop(
                source = source,
                region = region,
                targetWidth = TARGET_SIZE_PX,
                targetHeight = TARGET_SIZE_PX,
                transparentOutsideMask = true
            )
        }.getOrNull()
    }

    private fun decodeBitmap(context: Context, path: String): Bitmap? {
        return runCatching {
            val uri = Uri.parse(path)
            when (uri.scheme?.lowercase()) {
                "content", "android.resource" -> context.contentResolver.openInputStream(uri)
                    ?.use(BitmapFactory::decodeStream)
                "file" -> BitmapFactory.decodeFile(uri.path)
                else -> BitmapFactory.decodeFile(File(path).absolutePath)
            }
        }.getOrNull()
    }
}

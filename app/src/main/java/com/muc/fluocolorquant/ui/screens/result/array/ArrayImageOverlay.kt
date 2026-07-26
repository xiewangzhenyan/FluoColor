package com.muc.fluocolorquant.ui.screens.result.array

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.result.ArrayCaptureEvidence
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLDecoder
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.muc.fluocolorquant.ui.theme.FluoRadius

const val ARRAY_IMAGE_OVERLAY_TAG: String = "array_image_overlay"
const val ARRAY_IMAGE_LEGEND_CANDIDATE_TAG: String = "array_image_legend_candidate"
const val ARRAY_IMAGE_LEGEND_IMPUTED_TAG: String = "array_image_legend_imputed"
const val ARRAY_IMAGE_LEGEND_UNADJUSTED_TAG: String = "array_image_legend_unadjusted"
const val ARRAY_IMAGE_LEGEND_FAILURE_TAG: String = "array_image_legend_failure"

/** 原图在固定显示区域内按 ContentScale.Fit 显示时的坐标变换。 */
data class ArrayImageFitTransform(
    val scale: Float,
    val left: Float,
    val top: Float
) {
    fun map(x: Double, y: Double): Offset = Offset(
        x = left + x.toFloat() * scale,
        y = top + y.toFloat() * scale
    )
}

/** 采样后的显示位图与原始像素尺寸分开保存，冻结坐标始终按原图尺寸映射。 */
data class ArrayOverlayImage(
    val bitmap: ImageBitmap,
    val originalWidth: Int,
    val originalHeight: Int
)

private sealed interface ArrayOverlayImageLoadState {
    data object Loading : ArrayOverlayImageLoadState
    data class Success(val image: ArrayOverlayImage) : ArrayOverlayImageLoadState
    data object Error : ArrayOverlayImageLoadState
}

/** 纯数学变换供绘制和点击命中共同使用，避免两套坐标公式发生漂移。 */
fun calculateArrayImageFitTransform(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Float,
    imageHeight: Float
): ArrayImageFitTransform? {
    if (containerWidth <= 0f || containerHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
        return null
    }
    val scale = min(containerWidth / imageWidth, containerHeight / imageHeight)
    return ArrayImageFitTransform(
        scale = scale,
        left = (containerWidth - imageWidth * scale) / 2f,
        top = (containerHeight - imageHeight * scale) / 2f
    )
}

/**
 * 原图定位叠加。
 *
 * 坐标只使用冻结的 original 点位，不重新执行定位；“显示增强”仅通过 Compose 色彩矩阵
 * 改变屏幕显示，既不创建派生定量图，也不会回写 SiteMeasurement。
 */
@Composable
fun ArrayImageOverlay(
    artifact: ArrayCaptureEvidence,
    sites: List<ArrayPhysicalSiteResult>,
    onSiteClick: (ArrayPhysicalSiteResult) -> Unit,
    modifier: Modifier = Modifier,
    imageOverride: ArrayOverlayImage? = null
) {
    val context = LocalContext.current
    val largeArray = sites.maxOfOrNull { maxOf(it.rowIndex, it.columnIndex) }?.let { it >= 14 }
        ?: false
    var showLabels by remember(artifact.artifactId) { mutableStateOf(!largeArray) }
    var displayEnhanced by remember(artifact.artifactId) { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val imageState by produceState<ArrayOverlayImageLoadState>(
        initialValue = imageOverride?.let(ArrayOverlayImageLoadState::Success)
            ?: ArrayOverlayImageLoadState.Loading,
        key1 = artifact.originalPath,
        key2 = imageOverride
    ) {
        if (imageOverride == null) {
            value = withContext(Dispatchers.IO) {
                decodeArrayOverlayImage(context, artifact.originalPath)
                    ?.let(ArrayOverlayImageLoadState::Success)
                    ?: ArrayOverlayImageLoadState.Error
            }
        }
    }
    val loadedImage = (imageState as? ArrayOverlayImageLoadState.Success)?.image
    val imageWidth = loadedImage?.originalWidth?.toFloat()
    val imageHeight = loadedImage?.originalHeight?.toFloat()
    val contrastMatrix = remember {
        // 以 128 为中心提高 22% 对比度，只影响显示层。
        val contrast = 1.22f
        val translation = 128f * (1f - contrast)
        ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translation,
                0f, contrast, 0f, 0f, translation,
                0f, 0f, contrast, 0f, translation,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ARRAY_IMAGE_OVERLAY_TAG),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.array_image_overlay_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.array_image_overlay_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = showLabels,
                    onClick = { showLabels = !showLabels },
                    label = { Text(stringResource(R.string.array_image_toggle_labels)) }
                )
                FilterChip(
                    selected = displayEnhanced,
                    onClick = { displayEnhanced = !displayEnhanced },
                    label = { Text(stringResource(R.string.array_image_toggle_enhancement)) }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .background(Color.Black, RoundedCornerShape(FluoRadius.control))
                    .onSizeChanged { viewportSize = it },
                contentAlignment = Alignment.Center
            ) {
                when (imageState) {
                    ArrayOverlayImageLoadState.Loading -> CircularProgressIndicator()
                    ArrayOverlayImageLoadState.Error -> Text(
                        text = stringResource(R.string.array_image_load_failed),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    is ArrayOverlayImageLoadState.Success -> Image(
                        bitmap = loadedImage!!.bitmap,
                        contentDescription = stringResource(R.string.array_image_original_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        colorFilter = if (displayEnhanced) {
                            ColorFilter.colorMatrix(contrastMatrix)
                        } else {
                            null
                        }
                    )
                }
                if (imageWidth != null && imageHeight != null) {
                    val transform = calculateArrayImageFitTransform(
                        containerWidth = viewportSize.width.toFloat(),
                        containerHeight = viewportSize.height.toFloat(),
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInputForArrayOverlay(
                                sites = sites,
                                transform = transform,
                                onSiteClick = onSiteClick
                            )
                    ) {
                        val pointRadius = if (maxOf(sites.maxOfOrNull { it.rowIndex } ?: 0,
                                sites.maxOfOrNull { it.columnIndex } ?: 0) >= 14
                        ) {
                            4.dp.toPx()
                        } else {
                            6.dp.toPx()
                        }
                        val labelPaint = android.graphics.Paint(
                            android.graphics.Paint.ANTI_ALIAS_FLAG
                        ).apply {
                            color = Color.White.toArgb()
                            textSize = 10.dp.toPx()
                            setShadowLayer(3.dp.toPx(), 0f, 0f, android.graphics.Color.BLACK)
                        }
                        sites.forEach { site ->
                            val center = transform?.map(site.geometry.original.x, site.geometry.original.y)
                                ?: return@forEach
                            val style = overlayStyle(site)
                            drawCircle(
                                color = style.color.copy(alpha = 0.18f),
                                radius = pointRadius * 1.65f,
                                center = center
                            )
                            drawCircle(
                                color = style.color,
                                radius = pointRadius,
                                center = center,
                                style = Stroke(width = 2.dp.toPx())
                            )
                            if (showLabels) {
                                drawContext.canvas.nativeCanvas.drawText(
                                    site.siteKey,
                                    center.x + pointRadius + 2.dp.toPx(),
                                    center.y - pointRadius,
                                    labelPaint
                                )
                            }
                        }
                    }
                }
            }
            ArrayImageOverlayLegend()
            Text(
                text = stringResource(R.string.array_image_enhancement_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.array_result_artifact_path, artifact.originalPath),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 叠加样式优先显示质量失败，其次显示定位来源。 */
private data class ArrayOverlayStyle(val color: Color)

private fun overlayStyle(site: ArrayPhysicalSiteResult): ArrayOverlayStyle {
    if (site.measurements.any { !it.qualityReliable }) {
        return ArrayOverlayStyle(Color(0xFFD32F2F))
    }
    return when (site.geometry.source) {
        GridPointSource.CANDIDATE_REFINED -> ArrayOverlayStyle(Color(0xFF2E7D32))
        GridPointSource.MODEL_IMPUTED -> ArrayOverlayStyle(Color(0xFFF9A825))
        GridPointSource.UNADJUSTED -> ArrayOverlayStyle(Color(0xFF546E7A))
    }
}

private fun Modifier.pointerInputForArrayOverlay(
    sites: List<ArrayPhysicalSiteResult>,
    transform: ArrayImageFitTransform?,
    onSiteClick: (ArrayPhysicalSiteResult) -> Unit
): Modifier {
    return this.then(
        Modifier.pointerInput(sites, transform) {
            detectTapGestures { tap ->
                val activeTransform = transform ?: return@detectTapGestures
                val threshold = 28.dp.toPx()
                val nearest = sites.minByOrNull { site ->
                    val mapped = activeTransform.map(site.geometry.original.x, site.geometry.original.y)
                    hypot((mapped.x - tap.x).toDouble(), (mapped.y - tap.y).toDouble())
                } ?: return@detectTapGestures
                val mapped = activeTransform.map(nearest.geometry.original.x, nearest.geometry.original.y)
                val distance = hypot((mapped.x - tap.x).toDouble(), (mapped.y - tap.y).toDouble())
                if (distance <= threshold) onSiteClick(nearest)
            }
        }
    )
}

@Composable
private fun ArrayImageOverlayLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        OverlayLegendItem(
            color = Color(0xFF2E7D32),
            label = stringResource(R.string.array_image_legend_candidate),
            tag = ARRAY_IMAGE_LEGEND_CANDIDATE_TAG
        )
        OverlayLegendItem(
            color = Color(0xFFF9A825),
            label = stringResource(R.string.array_image_legend_imputed),
            tag = ARRAY_IMAGE_LEGEND_IMPUTED_TAG
        )
        OverlayLegendItem(
            color = Color(0xFF546E7A),
            label = stringResource(R.string.array_image_legend_unadjusted),
            tag = ARRAY_IMAGE_LEGEND_UNADJUSTED_TAG
        )
        OverlayLegendItem(
            color = Color(0xFFD32F2F),
            label = stringResource(R.string.array_image_legend_failure),
            tag = ARRAY_IMAGE_LEGEND_FAILURE_TAG
        )
    }
}

@Composable
private fun OverlayLegendItem(color: Color, label: String, tag: String) {
    Row(
        modifier = Modifier.testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(11.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 使用与检测入口一致的 BitmapFactory 解码，不应用 EXIF 自动旋转。
 * 显示位图最长边限制为 2048 像素，但坐标映射继续使用 inJustDecodeBounds 得到的原图尺寸。
 */
private fun decodeArrayOverlayImage(context: Context, rawPath: String): ArrayOverlayImage? {
    return runCatching {
        val decodedPath = runCatching { URLDecoder.decode(rawPath, Charsets.UTF_8.name()) }
            .getOrDefault(rawPath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openArrayImageStream(context, decodedPath)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAXIMUM_DISPLAY_DIMENSION_PX) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = openArrayImageStream(context, decodedPath)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return@runCatching null
        ArrayOverlayImage(
            bitmap = bitmap.asImageBitmap(),
            originalWidth = bounds.outWidth,
            originalHeight = bounds.outHeight
        )
    }.getOrNull()
}

private fun openArrayImageStream(context: Context, path: String): InputStream? {
    val uri = Uri.parse(path)
    return when (uri.scheme?.lowercase()) {
        "content", "file", "android.resource" -> context.contentResolver.openInputStream(uri)
        null, "" -> FileInputStream(File(path))
        else -> context.contentResolver.openInputStream(uri)
    }
}

private const val MAXIMUM_DISPLAY_DIMENSION_PX: Int = 2048

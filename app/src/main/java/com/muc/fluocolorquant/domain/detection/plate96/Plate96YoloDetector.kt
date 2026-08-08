package com.muc.fluocolorquant.domain.detection.plate96

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import kotlin.math.min

/**
 * 从旧96孔板ViewModel抽出的YOLOv8 Lite候选检测器。
 *
 * 该类只负责模型加载、letterbox、输出解码、NMS和原图坐标反投影；方向、圆形精定位、
 * 晶格补位和UI状态全部由后续领域服务处理，避免算法继续绑死在页面生命周期中。
 */
@Singleton
class Plate96YoloDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : Plate96ObjectDetector {
    private val modelLock = Any()

    @Volatile
    private var model: Module? = null

    override suspend fun detect(
        sourceBitmap: Bitmap,
        confidenceThreshold: Float,
        iouThreshold: Float
    ): List<Plate96ObjectCandidate> = withContext(Dispatchers.Default) {
        require(sourceBitmap.width > 0 && sourceBitmap.height > 0) { "96孔板原图尺寸无效" }
        require(confidenceThreshold in 0f..1f) { "置信度阈值必须位于0到1" }
        require(iouThreshold in 0f..1f) { "IoU阈值必须位于0到1" }

        val prepared = prepareLetterbox(sourceBitmap)
        try {
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                prepared.bitmap,
                floatArrayOf(0f, 0f, 0f),
                floatArrayOf(1f, 1f, 1f)
            )
            val output = synchronized(modelLock) {
                val loadedModel = model ?: loadModel().also { model = it }
                outputTensor(loadedModel.forward(IValue.from(inputTensor)))
            }
            val decoded = decode(output, confidenceThreshold)
            nonMaximumSuppression(decoded, iouThreshold)
                .take(MAXIMUM_RETURNED_CANDIDATES)
                .mapIndexedNotNull { index, raw ->
                    val bounds = scaleToSource(raw, prepared.info, sourceBitmap.width, sourceBitmap.height)
                        ?: return@mapIndexedNotNull null
                    Plate96ObjectCandidate(
                        candidateIndex = index,
                        bounds = bounds,
                        confidence = raw.confidence.toDouble().coerceIn(0.0, 1.0)
                    )
                }
        } finally {
            if (prepared.bitmap !== sourceBitmap && !prepared.bitmap.isRecycled) prepared.bitmap.recycle()
        }
    }

    private fun loadModel(): Module {
        val modelFile = File(context.filesDir, MODEL_ASSET_PATH)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            modelFile.parentFile?.mkdirs()
            context.assets.open(MODEL_ASSET_PATH).use { input ->
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
        }
        require(modelFile.length() > 0L) { "96孔板目标检测模型为空" }
        Log.d(TAG, "加载96孔板定位模型：${modelFile.absolutePath}")
        return LiteModuleLoader.load(modelFile.absolutePath)
    }

    private fun prepareLetterbox(source: Bitmap): PreparedBitmap {
        val scale = min(MODEL_INPUT_SIZE / source.width.toFloat(), MODEL_INPUT_SIZE / source.height.toFloat())
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val paddingX = (MODEL_INPUT_SIZE - scaledWidth) / 2f
        val paddingY = (MODEL_INPUT_SIZE - scaledHeight) / 2f
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val output = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.BLACK)
            drawBitmap(scaled, paddingX, paddingY, null)
        }
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        return PreparedBitmap(
            bitmap = output,
            info = LetterboxInfo(scale = scale, paddingX = paddingX, paddingY = paddingY)
        )
    }

    private fun outputTensor(output: IValue): Tensor {
        return runCatching { output.toTensor() }.getOrElse {
            val tuple = output.toTuple()
            require(tuple.isNotEmpty()) { "96孔板模型返回了空元组" }
            tuple.first().toTensor()
        }
    }

    /** 同时兼容常见的[1,N,6]与[1,6,N]输出布局。 */
    private fun decode(tensor: Tensor, confidenceThreshold: Float): List<RawDetection> {
        val shape = tensor.shape().map(Long::toInt)
        require(shape.size >= 2) { "96孔板模型输出维度不足：$shape" }
        val values = tensor.dataAsFloatArray
        val last = shape.last()
        val secondLast = shape[shape.lastIndex - 1]
        val channelsLast = last >= MINIMUM_DETECTION_ATTRIBUTES && last <= MAXIMUM_DETECTION_ATTRIBUTES
        val attributeCount = if (channelsLast) last else secondLast
        val detectionCount = if (channelsLast) secondLast else last
        require(attributeCount >= MINIMUM_DETECTION_ATTRIBUTES) { "96孔板模型输出缺少检测属性：$shape" }

        fun value(detection: Int, attribute: Int): Float {
            val offset = if (channelsLast) {
                detection * attributeCount + attribute
            } else {
                attribute * detectionCount + detection
            }
            return values[offset]
        }

        return buildList {
            repeat(detectionCount) { detection ->
                val confidence = value(detection, 4)
                if (!confidence.isFinite() || confidence < confidenceThreshold) return@repeat
                val centerX = value(detection, 0)
                val centerY = value(detection, 1)
                val width = value(detection, 2)
                val height = value(detection, 3)
                if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return@repeat
                add(
                    RawDetection(
                        left = centerX - width / 2f,
                        top = centerY - height / 2f,
                        right = centerX + width / 2f,
                        bottom = centerY + height / 2f,
                        confidence = confidence
                    )
                )
            }
        }
    }

    private fun nonMaximumSuppression(
        detections: List<RawDetection>,
        iouThreshold: Float
    ): List<RawDetection> {
        val remaining = detections.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<RawDetection>()
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            selected += best
            remaining.removeAll { candidate -> intersectionOverUnion(best, candidate) > iouThreshold }
        }
        return selected
    }

    private fun intersectionOverUnion(first: RawDetection, second: RawDetection): Float {
        val intersectionWidth = (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0f)
        val intersectionHeight = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val firstArea = (first.right - first.left).coerceAtLeast(0f) * (first.bottom - first.top).coerceAtLeast(0f)
        val secondArea = (second.right - second.left).coerceAtLeast(0f) * (second.bottom - second.top).coerceAtLeast(0f)
        val union = firstArea + secondArea - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun scaleToSource(
        raw: RawDetection,
        info: LetterboxInfo,
        sourceWidth: Int,
        sourceHeight: Int
    ): ArrayImageBounds? {
        val left = ((raw.left - info.paddingX) / info.scale).coerceIn(0f, sourceWidth.toFloat()).toDouble()
        val top = ((raw.top - info.paddingY) / info.scale).coerceIn(0f, sourceHeight.toFloat()).toDouble()
        val right = ((raw.right - info.paddingX) / info.scale).coerceIn(0f, sourceWidth.toFloat()).toDouble()
        val bottom = ((raw.bottom - info.paddingY) / info.scale).coerceIn(0f, sourceHeight.toFloat()).toDouble()
        if (right - left < MINIMUM_BOX_SIZE_PX || bottom - top < MINIMUM_BOX_SIZE_PX) return null
        return ArrayImageBounds(left, top, right, bottom).requireValid()
    }

    private data class LetterboxInfo(val scale: Float, val paddingX: Float, val paddingY: Float)
    private data class PreparedBitmap(val bitmap: Bitmap, val info: LetterboxInfo)
    private data class RawDetection(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float
    )

    private companion object {
        const val TAG: String = "Plate96YoloDetector"
        const val MODEL_ASSET_PATH: String = "models/best_lite.ptl"
        const val MODEL_INPUT_SIZE: Int = 1280
        const val MINIMUM_DETECTION_ATTRIBUTES: Int = 6
        const val MAXIMUM_DETECTION_ATTRIBUTES: Int = 16
        const val MAXIMUM_RETURNED_CANDIDATES: Int = 192
        const val MINIMUM_BOX_SIZE_PX: Double = 1.0
    }
}

package com.muc.fluocolorquant.domain.detection.quantification

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.domain.detection.grid.PgGridImageRectifier
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBitmapCropper
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitSegmentationResult
import com.muc.fluocolorquant.utils.DetectionModeSupport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.torchvision.TensorImageUtils

/** 微流控逐孔深度学习推理实现的稳定版本，写入每个位点的不可变模型快照。 */
const val GRID_DEEP_LEARNING_EXECUTOR_VERSION: String = "grid-pytorch-lite-v1"

/** 深度学习模型整批不可执行时的稳定机器原因。 */
enum class GridDeepLearningFailureReason {
    MODEL_TYPE_MISMATCH,
    MISSING_DEFINITION,
    INVALID_INPUT_SIZE,
    INVALID_NORMALIZATION,
    MODEL_FILE_NOT_FOUND,
    CHECKSUM_MISMATCH,
    MODEL_LOAD_FAILED,
    SEGMENTATION_MISSING,
    SITE_REGION_MISSING,
    INFERENCE_FAILED,
    NON_FINITE_OUTPUT
}

/** 单个位点已经完成可靠范围裁决的深度学习预测。 */
data class GridDeepLearningPrediction(
    val siteIndex: Int,
    val concentration: Double?,
    val rangeStatus: ReliableRangeStatus,
    val modelSnapshotJson: String
)

/** 深度学习整批执行结果；失败类型不允许携带任何部分浓度。 */
sealed interface GridDeepLearningBatchResult {
    data class Success(
        val predictions: Map<Int, GridDeepLearningPrediction>
    ) : GridDeepLearningBatchResult

    data class Failure(
        val reason: GridDeepLearningFailureReason
    ) : GridDeepLearningBatchResult
}

/**
 * 微流控阵列深度学习执行器边界。
 *
 * 协调器只依赖该接口，因此 JVM 测试可以注入确定性的假执行器，不需要加载 Android
 * PyTorch 原生库；生产实现则读取真实紧致单元裁切并执行 Lite PTL。
 */
interface GridDeepLearningExecutor {
    fun execute(
        sourceBitmap: Bitmap,
        grid: PgGridResult,
        segmentation: ArrayUnitSegmentationResult?,
        measurements: List<SiteMeasurement>,
        modelBundle: AnalysisModelBundle
    ): GridDeepLearningBatchResult
}

/** 单元测试默认使用的安全实现：明确返回模型不可用，不生成任何浓度。 */
object UnavailableGridDeepLearningExecutor : GridDeepLearningExecutor {
    override fun execute(
        sourceBitmap: Bitmap,
        grid: PgGridResult,
        segmentation: ArrayUnitSegmentationResult?,
        measurements: List<SiteMeasurement>,
        modelBundle: AnalysisModelBundle
    ): GridDeepLearningBatchResult = GridDeepLearningBatchResult.Failure(
        GridDeepLearningFailureReason.MODEL_LOAD_FAILED
    )
}

/**
 * Android 端真实 PyTorch Lite 执行器。
 *
 * 处理顺序固定为：校验冻结定义 → 解析归一化 → 校验模型 SHA-256 → 透视矫正 → 按真实
 * 单元分割框裁切 → 缩放到模型输入尺寸 → RGB/ImageNet 等冻结规则归一化 → 逐孔推理。
 * 任意一个位点失败都会返回整批失败，协调器随后从原始测量重建“仅信号”结果。
 */
@Singleton
class AndroidGridDeepLearningExecutor @Inject constructor(
    @ApplicationContext private val context: Context
) : GridDeepLearningExecutor {

    private val gson = Gson()

    override fun execute(
        sourceBitmap: Bitmap,
        grid: PgGridResult,
        segmentation: ArrayUnitSegmentationResult?,
        measurements: List<SiteMeasurement>,
        modelBundle: AnalysisModelBundle
    ): GridDeepLearningBatchResult {
        if (AnalysisModelType.fromCode(modelBundle.model.modelType) != AnalysisModelType.DEEP_LEARNING) {
            return failure(GridDeepLearningFailureReason.MODEL_TYPE_MISMATCH)
        }
        val definition = modelBundle.deepLearning
            ?: return failure(GridDeepLearningFailureReason.MISSING_DEFINITION)
        if (definition.inputWidth !in MINIMUM_INPUT_SIZE..MAXIMUM_INPUT_SIZE ||
            definition.inputHeight !in MINIMUM_INPUT_SIZE..MAXIMUM_INPUT_SIZE
        ) {
            return failure(GridDeepLearningFailureReason.INVALID_INPUT_SIZE)
        }
        val normalization = parseNormalization(
            json = definition.normalizationJson,
            modelFileName = definition.modelFileName
        ) ?: return failure(GridDeepLearningFailureReason.INVALID_NORMALIZATION)
        val outputTransform = parseOutputTransform(
            json = definition.metadataJson,
            modelFileName = definition.modelFileName
        ) ?: return failure(GridDeepLearningFailureReason.INVALID_NORMALIZATION)
        val validSegmentation = segmentation?.runCatching {
            requireValid()
        }?.getOrNull() ?: return failure(GridDeepLearningFailureReason.SEGMENTATION_MISSING)
        if (
            validSegmentation.rows != grid.rows ||
            validSegmentation.columns != grid.columns ||
            validSegmentation.regions.size != grid.sites.size
        ) {
            return failure(GridDeepLearningFailureReason.SEGMENTATION_MISSING)
        }

        val modelFile = when (val resolved = resolveVerifiedModelFile(
            definition.modelFileName,
            definition.checksumSha256
        )) {
            is VerifiedModelFileResult.Ready -> resolved.file
            is VerifiedModelFileResult.Failure -> return failure(resolved.reason)
        }
        val module = runCatching { LiteModuleLoader.load(modelFile.absolutePath) }
            .getOrElse { return failure(GridDeepLearningFailureReason.MODEL_LOAD_FAILED) }
        val snapshotJson = buildModelSnapshot(modelBundle)
            ?: return failure(GridDeepLearningFailureReason.INVALID_NORMALIZATION)

        val rectified = runCatching { PgGridImageRectifier.rectify(sourceBitmap, grid) }
            .getOrElse { return failure(GridDeepLearningFailureReason.INFERENCE_FAILED) }
        return try {
            val predictions = linkedMapOf<Int, GridDeepLearningPrediction>()
            for (measurement in measurements) {
                val region = validSegmentation.regions.getOrNull(measurement.siteIndex)
                    ?: return failure(GridDeepLearningFailureReason.SITE_REGION_MISSING)
                val inputBitmap = runCatching {
                    ArrayUnitBitmapCropper.crop(
                        source = rectified,
                        region = region,
                        targetWidth = definition.inputWidth,
                        targetHeight = definition.inputHeight,
                        // 模型训练使用矩形 RGB 输入；圆形/自定义掩膜只服务科学像素统计，
                        // 不能擅自用透明像素改变既有模型的输入分布。
                        transparentOutsideMask = false
                    )
                }.getOrElse {
                    return failure(GridDeepLearningFailureReason.SITE_REGION_MISSING)
                }
                val rawOutput = try {
                    val tensor = TensorImageUtils.bitmapToFloat32Tensor(
                        inputBitmap,
                        normalization.mean,
                        normalization.std
                    )
                    val values = module.forward(IValue.from(tensor)).toTensor().dataAsFloatArray
                    values.firstOrNull()?.toDouble()
                } catch (_: RuntimeException) {
                    null
                } catch (_: LinkageError) {
                    null
                } finally {
                    if (!inputBitmap.isRecycled) inputBitmap.recycle()
                } ?: return failure(GridDeepLearningFailureReason.INFERENCE_FAILED)

                if (!rawOutput.isFinite()) {
                    return failure(GridDeepLearningFailureReason.NON_FINITE_OUTPUT)
                }
                val concentration = outputTransform.toConcentration(
                    rawOutput = rawOutput,
                    reliableMinimum = modelBundle.model.reliableRangeMin,
                    reliableMaximum = modelBundle.model.reliableRangeMax
                )
                if (!concentration.isFinite()) {
                    return failure(GridDeepLearningFailureReason.NON_FINITE_OUTPUT)
                }
                val rangeStatus = when {
                    concentration < modelBundle.model.reliableRangeMin ->
                        ReliableRangeStatus.BELOW_RANGE
                    concentration > modelBundle.model.reliableRangeMax ->
                        ReliableRangeStatus.ABOVE_RANGE
                    else -> ReliableRangeStatus.WITHIN_RANGE
                }
                predictions[measurement.siteIndex] = GridDeepLearningPrediction(
                    siteIndex = measurement.siteIndex,
                    concentration = concentration.takeIf {
                        rangeStatus == ReliableRangeStatus.WITHIN_RANGE
                    },
                    rangeStatus = rangeStatus,
                    modelSnapshotJson = snapshotJson
                )
            }
            GridDeepLearningBatchResult.Success(predictions)
        } finally {
            if (!rectified.isRecycled) rectified.recycle()
        }
    }

    /** 模型快照冻结主档、文件校验、输入尺寸、归一化和执行器版本。 */
    private fun buildModelSnapshot(bundle: AnalysisModelBundle): String? = runCatching {
        gson.toJson(
            linkedMapOf(
                "schemaVersion" to "deep-learning-model-snapshot-v1",
                "executorVersion" to GRID_DEEP_LEARNING_EXECUTOR_VERSION,
                "model" to bundle.model,
                "deepLearning" to bundle.deepLearning,
                "checksumVerified" to true
            )
        )
    }.getOrNull()

    /**
     * 支持 assets、应用私有文件、绝对路径与 SAF content URI。
     * 相对路径只允许落在应用私有目录或 APK assets，禁止通过 `..` 越出受控目录。
     */
    private fun resolveVerifiedModelFile(
        rawLocation: String,
        expectedChecksum: String
    ): VerifiedModelFileResult {
        val location = rawLocation.trim()
        if (location.isEmpty()) return modelFileFailure(GridDeepLearningFailureReason.MODEL_FILE_NOT_FOUND)
        val normalizedChecksum = expectedChecksum.trim().lowercase()
        if (!SHA_256_REGEX.matches(normalizedChecksum)) {
            return modelFileFailure(GridDeepLearningFailureReason.CHECKSUM_MISMATCH)
        }

        val assetCandidates = linkedSetOf(location).apply {
            if (!location.contains('/') && !location.contains('\\')) add("models/$location")
        }
        assetCandidates.forEach { assetPath ->
            val assetReady = runCatching {
                context.assets.open(assetPath).use { input ->
                    copyAndVerify(
                        input = input,
                        destination = cachedModelFile(normalizedChecksum),
                        expectedChecksum = normalizedChecksum
                    )
                }
            }.getOrNull()
            if (assetReady != null) return assetReady
        }

        val uri = runCatching { Uri.parse(location) }.getOrNull()
        if (uri?.scheme.equals("content", ignoreCase = true)) {
            val contentReady = runCatching {
                context.contentResolver.openInputStream(uri!!)?.use { input ->
                    copyAndVerify(
                        input = input,
                        destination = cachedModelFile(normalizedChecksum),
                        expectedChecksum = normalizedChecksum
                    )
                }
            }.getOrNull()
            return contentReady
                ?: modelFileFailure(GridDeepLearningFailureReason.MODEL_FILE_NOT_FOUND)
        }

        val fileCandidates = buildList {
            if (uri?.scheme.equals("file", ignoreCase = true)) {
                uri?.path?.let { add(File(it)) }
            } else {
                val direct = File(location)
                if (direct.isAbsolute) add(direct)
                addSafeChild(context.filesDir, location)?.let(::add)
                addSafeChild(File(context.filesDir, "models"), location)?.let(::add)
                addSafeChild(File(context.filesDir, "model_uploads"), location)?.let(::add)
            }
        }
        val existing = fileCandidates.firstOrNull { file -> file.isFile }
            ?: return modelFileFailure(GridDeepLearningFailureReason.MODEL_FILE_NOT_FOUND)
        return if (sha256(existing).equals(normalizedChecksum, ignoreCase = true)) {
            VerifiedModelFileResult.Ready(existing)
        } else {
            modelFileFailure(GridDeepLearningFailureReason.CHECKSUM_MISMATCH)
        }
    }

    private fun copyAndVerify(
        input: InputStream,
        destination: File,
        expectedChecksum: String
    ): VerifiedModelFileResult {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        temporary.outputStream().buffered().use { output ->
            copyWithDigest(input, output, digest) { copied ->
                byteCount += copied
                require(byteCount <= MAXIMUM_MODEL_BYTES) { "模型文件超过允许大小" }
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            temporary.delete()
            return modelFileFailure(GridDeepLearningFailureReason.CHECKSUM_MISMATCH)
        }
        if (destination.exists()) destination.delete()
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        return VerifiedModelFileResult.Ready(destination)
    }

    private fun copyWithDigest(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        onBytesCopied: (Long) -> Unit
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
            onBytesCopied(count.toLong())
        }
    }

    private fun cachedModelFile(checksum: String): File = File(
        File(context.cacheDir, "verified_models"),
        "$checksum.ptl"
    )

    private fun addSafeChild(parent: File, child: String): File? = runCatching {
        val canonicalParent = parent.canonicalFile
        val candidate = File(canonicalParent, child).canonicalFile
        candidate.takeIf { file ->
            file.path == canonicalParent.path ||
                file.path.startsWith(canonicalParent.path + File.separator)
        }
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** 归一化规则接受 mean/std 三通道数组，标准共享模型缺省时仍固定为 ImageNet。 */
    private fun parseNormalization(json: String, modelFileName: String): NormalizationSpec? {
        val root = parseObject(json)
        val mean = root?.floatArray("mean") ?: root?.floatArray("channelMean")
        val std = root?.floatArray("std") ?: root?.floatArray("channelStd")
        if (mean != null && std != null && mean.size == 3 && std.size == 3 &&
            mean.all(Float::isFinite) && std.all { value -> value.isFinite() && value > 0f }
        ) {
            return NormalizationSpec(mean, std)
        }
        return if (isSharedBuiltInModel(modelFileName)) {
            NormalizationSpec(IMAGENET_MEAN.copyOf(), IMAGENET_STD.copyOf())
        } else {
            null
        }
    }

    /** 输出变换默认按真实浓度解释；共享过渡模型沿用旧96孔板的百分比×最大浓度语义。 */
    private fun parseOutputTransform(json: String?, modelFileName: String): OutputTransform? {
        val root = json?.takeIf(String::isNotBlank)?.let(::parseObject)
        if (!json.isNullOrBlank() && root == null) return null
        val defaultMode = if (isSharedBuiltInModel(modelFileName)) {
            DeepLearningOutputMode.PERCENT_OF_RELIABLE_MAX
        } else {
            DeepLearningOutputMode.RAW_CONCENTRATION
        }
        val modeCode = root?.string("outputMode") ?: root?.string("output_mode")
        val mode = modeCode?.let(DeepLearningOutputMode::fromCode) ?: defaultMode
        val scale = root?.double("outputScale") ?: root?.double("output_scale") ?: 1.0
        val offset = root?.double("outputOffset") ?: root?.double("output_offset") ?: 0.0
        return OutputTransform(mode, scale, offset).takeIf {
            scale.isFinite() && offset.isFinite()
        }
    }

    private fun parseObject(json: String): JsonObject? = runCatching {
        @Suppress("DEPRECATION")
        JsonParser().parse(json).takeIf { element -> element.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun JsonObject.floatArray(name: String): FloatArray? = runCatching {
        getAsJsonArray(name)?.map { element -> element.asFloat }?.toFloatArray()
    }.getOrNull()

    private fun JsonObject.string(name: String): String? = runCatching {
        get(name)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun JsonObject.double(name: String): Double? = runCatching {
        get(name)?.takeIf { it.isJsonPrimitive }?.asDouble
    }.getOrNull()

    private fun isSharedBuiltInModel(modelFileName: String): Boolean {
        val normalized = modelFileName.replace('\\', '/').trimStart('/')
        return normalized == DetectionModeSupport.SHARED_CONCENTRATION_MODEL_ASSET ||
            normalized.endsWith("/${DetectionModeSupport.SHARED_CONCENTRATION_MODEL_ASSET.substringAfterLast('/')}") ||
            normalized == DetectionModeSupport.SHARED_CONCENTRATION_MODEL_ASSET.substringAfterLast('/')
    }

    private fun failure(reason: GridDeepLearningFailureReason) =
        GridDeepLearningBatchResult.Failure(reason)

    private fun modelFileFailure(reason: GridDeepLearningFailureReason) =
        VerifiedModelFileResult.Failure(reason)

    private data class NormalizationSpec(
        val mean: FloatArray,
        val std: FloatArray
    )

    private enum class DeepLearningOutputMode {
        RAW_CONCENTRATION,
        PERCENT_OF_RELIABLE_MAX,
        FRACTION_OF_RELIABLE_MAX;

        companion object {
            fun fromCode(code: String): DeepLearningOutputMode? = entries.firstOrNull {
                it.name.equals(code.trim(), ignoreCase = true)
            }
        }
    }

    private data class OutputTransform(
        val mode: DeepLearningOutputMode,
        val scale: Double,
        val offset: Double
    ) {
        fun toConcentration(
            rawOutput: Double,
            reliableMinimum: Double,
            reliableMaximum: Double
        ): Double {
            val transformed = rawOutput * scale + offset
            return when (mode) {
                DeepLearningOutputMode.RAW_CONCENTRATION -> transformed
                DeepLearningOutputMode.PERCENT_OF_RELIABLE_MAX ->
                    reliableMinimum + transformed / 100.0 * (reliableMaximum - reliableMinimum)
                DeepLearningOutputMode.FRACTION_OF_RELIABLE_MAX ->
                    reliableMinimum + transformed * (reliableMaximum - reliableMinimum)
            }
        }
    }

    private sealed interface VerifiedModelFileResult {
        data class Ready(val file: File) : VerifiedModelFileResult
        data class Failure(val reason: GridDeepLearningFailureReason) : VerifiedModelFileResult
    }

    companion object {
        private const val MINIMUM_INPUT_SIZE = 8
        private const val MAXIMUM_INPUT_SIZE = 4096
        private const val MAXIMUM_MODEL_BYTES = 512L * 1024L * 1024L
        private val SHA_256_REGEX = Regex("^[0-9a-f]{64}$")
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

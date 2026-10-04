package com.muc.fluocolorquant.data.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.JsonParser
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Tensor

/** 模型文件导入或运行契约校验失败的稳定原因，由 Compose 层映射为中英文说明。 */
enum class DeepLearningModelFileFailureReason {
    UNSUPPORTED_FILE_TYPE,
    EMPTY_FILE,
    FILE_TOO_LARGE,
    READ_FAILED,
    INVALID_LITE_MODULE,
    CHECKSUM_MISMATCH,
    FILE_MISSING,
    INPUT_SIZE_INVALID,
    NORMALIZATION_INVALID,
    INFERENCE_CONTRACT_INVALID,
    NON_FINITE_OUTPUT
}

/** 已复制进应用私有目录的 PTL 文件身份；数据库只保存相对路径和 SHA-256。 */
data class ImportedDeepLearningModelFile(
    val originalFileName: String,
    val relativePath: String,
    val checksumSha256: String,
    val byteCount: Long
)

sealed interface DeepLearningModelImportResult {
    data class Success(val file: ImportedDeepLearningModelFile) : DeepLearningModelImportResult
    data class Failure(
        val reason: DeepLearningModelFileFailureReason
    ) : DeepLearningModelImportResult
}

sealed interface DeepLearningModelContractResult {
    data class Success(val sampleOutput: Double) : DeepLearningModelContractResult
    data class Failure(
        val reason: DeepLearningModelFileFailureReason
    ) : DeepLearningModelContractResult
}

/**
 * 用户自训练 PyTorch Lite 模型的受控文件入口。
 *
 * 文件选择后立即复制到 `files/model_uploads`，避免项目运行依赖外部文档提供方的临时权限；
 * 同时自动计算 SHA-256 并验证文件能被 Lite 解释器加载。发布前再按用户声明的输入尺寸、
 * 归一化规则执行一次确定性零图试运行，确认生产链能够获得有限标量输出。这里验证的是
 * “文件与运行契约可执行”，不替代训练集、外部验证集和载体适用性的科学验证。
 */
@Singleton
class DeepLearningModelFileManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun importFromUri(uri: Uri): DeepLearningModelImportResult = withContext(Dispatchers.IO) {
        val originalName = queryDisplayName(uri)
            ?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: return@withContext importFailure(DeepLearningModelFileFailureReason.READ_FAILED)
        if (!originalName.endsWith(REQUIRED_EXTENSION, ignoreCase = true)) {
            return@withContext importFailure(
                DeepLearningModelFileFailureReason.UNSUPPORTED_FILE_TYPE
            )
        }

        val targetDirectory = File(context.filesDir, MODEL_DIRECTORY).apply { mkdirs() }
        val temporary = File(targetDirectory, ".import-${UUID.randomUUID()}$REQUIRED_EXTENSION")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext importFailure(DeepLearningModelFileFailureReason.READ_FAILED)
            input.buffered().use { source ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        byteCount += count
                        if (byteCount > MAXIMUM_MODEL_BYTES) {
                            return@withContext importFailure(
                                DeepLearningModelFileFailureReason.FILE_TOO_LARGE
                            )
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (byteCount == 0L) {
                return@withContext importFailure(DeepLearningModelFileFailureReason.EMPTY_FILE)
            }

            // 只验证 Lite 模块格式与算子能否被当前运行库解析；输入输出契约要等用户完成
            // 尺寸和归一化配置后，在发布阶段执行真实前向试运行。
            try {
                LiteModuleLoader.load(temporary.absolutePath)
            } catch (_: RuntimeException) {
                return@withContext importFailure(
                    DeepLearningModelFileFailureReason.INVALID_LITE_MODULE
                )
            } catch (_: LinkageError) {
                return@withContext importFailure(
                    DeepLearningModelFileFailureReason.INVALID_LITE_MODULE
                )
            }

            val checksum = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            val destination = File(targetDirectory, "$checksum$REQUIRED_EXTENSION")
            val destinationAlreadyValid = destination.isFile &&
                sha256(destination).equals(checksum, ignoreCase = true)
            if (!destinationAlreadyValid) {
                // 哈希命名文件理论上不可冲突；若磁盘残留同名损坏文件，必须用本次已经
                // 校验过的临时副本替换，不能把损坏缓存继续登记进数据库。
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                }
            }
            DeepLearningModelImportResult.Success(
                ImportedDeepLearningModelFile(
                    originalFileName = originalName,
                    relativePath = "$MODEL_DIRECTORY/$checksum$REQUIRED_EXTENSION",
                    checksumSha256 = checksum,
                    byteCount = byteCount
                )
            )
        } catch (_: Exception) {
            importFailure(DeepLearningModelFileFailureReason.READ_FAILED)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /**
     * 按生产执行器的 NCHW 单 RGB 输入、标量输出约定执行发布前试运行。
     *
     * 零图只用于确认张量维度、算子与输出形状可执行，不能据此判断模型准确度；模型是否
     * 适用于某载体仍由用户声明的兼容范围、实验验证资料和项目选择时的风险提示共同决定。
     */
    suspend fun validateRuntimeContract(
        definition: DeepLearningModelDefinition
    ): DeepLearningModelContractResult = withContext(Dispatchers.IO) {
        val modelFile = resolvePrivateModelFile(definition.modelFileName)
            ?: return@withContext contractFailure(
                DeepLearningModelFileFailureReason.FILE_MISSING
            )
        if (!sha256(modelFile).equals(definition.checksumSha256, ignoreCase = true)) {
            return@withContext contractFailure(
                DeepLearningModelFileFailureReason.CHECKSUM_MISMATCH
            )
        }
        val width = definition.inputWidth
        val height = definition.inputHeight
        val valueCount = width.toLong() * height.toLong() * RGB_CHANNEL_COUNT
        if (width !in MINIMUM_INPUT_SIZE..MAXIMUM_INPUT_SIZE ||
            height !in MINIMUM_INPUT_SIZE..MAXIMUM_INPUT_SIZE ||
            valueCount > MAXIMUM_INPUT_FLOAT_COUNT
        ) {
            return@withContext contractFailure(
                DeepLearningModelFileFailureReason.INPUT_SIZE_INVALID
            )
        }
        val normalization = parseNormalization(definition.normalizationJson)
            ?: return@withContext contractFailure(
                DeepLearningModelFileFailureReason.NORMALIZATION_INVALID
            )

        try {
            val module = LiteModuleLoader.load(modelFile.absolutePath)
            val planeSize = width * height
            val inputValues = FloatArray(valueCount.toInt())
            repeat(RGB_CHANNEL_COUNT) { channel ->
                val normalizedZero = -normalization.mean[channel] / normalization.std[channel]
                inputValues.fill(
                    element = normalizedZero,
                    fromIndex = channel * planeSize,
                    toIndex = (channel + 1) * planeSize
                )
            }
            val tensor = Tensor.fromBlob(
                inputValues,
                longArrayOf(1L, RGB_CHANNEL_COUNT.toLong(), height.toLong(), width.toLong())
            )
            val output = module.forward(IValue.from(tensor)).toTensor().dataAsFloatArray
            val scalar = output.firstOrNull()?.toDouble()
                ?: return@withContext contractFailure(
                    DeepLearningModelFileFailureReason.INFERENCE_CONTRACT_INVALID
                )
            if (!scalar.isFinite()) {
                return@withContext contractFailure(
                    DeepLearningModelFileFailureReason.NON_FINITE_OUTPUT
                )
            }
            DeepLearningModelContractResult.Success(sampleOutput = scalar)
        } catch (_: RuntimeException) {
            contractFailure(DeepLearningModelFileFailureReason.INFERENCE_CONTRACT_INVALID)
        } catch (_: LinkageError) {
            contractFailure(DeepLearningModelFileFailureReason.INFERENCE_CONTRACT_INVALID)
        }
    }

    private fun resolvePrivateModelFile(relativePath: String): File? = runCatching {
        val root = context.filesDir.canonicalFile
        val candidate = File(root, relativePath).canonicalFile
        candidate.takeIf { file ->
            file.isFile && file.path.startsWith(root.path + File.separator)
        }
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }.getOrNull()

    private fun parseNormalization(json: String): Normalization? = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val mean = root.getAsJsonArray("mean")?.map { it.asFloat }?.toFloatArray()
            ?: return@runCatching null
        val std = root.getAsJsonArray("std")?.map { it.asFloat }?.toFloatArray()
            ?: return@runCatching null
        if (mean.size != RGB_CHANNEL_COUNT || std.size != RGB_CHANNEL_COUNT ||
            mean.any { !it.isFinite() } || std.any { !it.isFinite() || it == 0f }
        ) {
            return@runCatching null
        }
        Normalization(mean = mean, std = std)
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

    private data class Normalization(val mean: FloatArray, val std: FloatArray)

    private fun importFailure(reason: DeepLearningModelFileFailureReason) =
        DeepLearningModelImportResult.Failure(reason)

    private fun contractFailure(reason: DeepLearningModelFileFailureReason) =
        DeepLearningModelContractResult.Failure(reason)

    private companion object {
        const val MODEL_DIRECTORY = "model_uploads"
        const val REQUIRED_EXTENSION = ".ptl"
        const val RGB_CHANNEL_COUNT = 3
        const val MINIMUM_INPUT_SIZE = 8
        const val MAXIMUM_INPUT_SIZE = 4096
        const val MAXIMUM_MODEL_BYTES = 512L * 1024L * 1024L
        // 发布试运行最多分配约 64 MiB FloatArray，防止错误尺寸在设置页触发内存抖动。
        const val MAXIMUM_INPUT_FLOAT_COUNT = 16L * 1024L * 1024L
    }
}

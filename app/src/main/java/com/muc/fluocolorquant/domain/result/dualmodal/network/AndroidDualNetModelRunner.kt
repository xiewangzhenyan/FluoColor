package com.muc.fluocolorquant.domain.result.dualmodal.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor

/**
 * DualNet 的 PyTorch Lite 执行器。
 *
 * 模型随 APK 内置在 assets；首次使用时复制到缓存目录并逐字节核对 SHA-256，与 [DualNetSpec.MODEL_SHA256]
 * 不一致就拒绝加载，避免被替换的文件静默产生读数。导出时已关闭会改变本网络输出的 CONV_BN_FUSION
 * 优化（见第四章 4.8.1 节），APK 中的文件与 W3 导出逐字节相同。PyTorch Module 不保证线程安全，
 * 推理串行执行。
 */
@Singleton
class AndroidDualNetModelRunner @Inject constructor(
    @ApplicationContext private val context: Context
) : DualNetModelRunner {

    private var module: Module? = null

    @Synchronized
    override fun predict(input: FloatArray, count: Int): FloatArray {
        require(count > 0 && input.size == count * DualNetSpec.CHANNELS * DualNetSpec.CROP_SIZE * DualNetSpec.CROP_SIZE) {
            "DualNet 输入维度不符"
        }
        val loaded = module ?: load().also { module = it }
        return try {
            val tensor = Tensor.fromBlob(
                input,
                longArrayOf(count.toLong(), DualNetSpec.CHANNELS.toLong(), DualNetSpec.CROP_SIZE.toLong(), DualNetSpec.CROP_SIZE.toLong())
            )
            loaded.forward(IValue.from(tensor)).toTensor().dataAsFloatArray
        } catch (error: RuntimeException) {
            throw DualNetUnavailableException(DualNetUnavailableReason.INFERENCE_FAILED, "DualNet 推理失败", error)
        } catch (error: LinkageError) {
            throw DualNetUnavailableException(DualNetUnavailableReason.INFERENCE_FAILED, "PyTorch 运行库不可用", error)
        }
    }

    private fun load(): Module {
        val file = verifiedModelFile()
        return try {
            LiteModuleLoader.load(file.absolutePath)
        } catch (error: RuntimeException) {
            throw DualNetUnavailableException(DualNetUnavailableReason.MODEL_UNAVAILABLE, "DualNet 模型无法加载", error)
        } catch (error: LinkageError) {
            throw DualNetUnavailableException(DualNetUnavailableReason.MODEL_UNAVAILABLE, "PyTorch 运行库不可用", error)
        }
    }

    /** 已核对过的缓存文件直接复用；否则从 assets 重新复制并核对。 */
    private fun verifiedModelFile(): File {
        val target = File(File(context.cacheDir, "verified_models"), "${DualNetSpec.MODEL_SHA256}.ptl")
        if (target.isFile && sha256(target.readBytes()) == DualNetSpec.MODEL_SHA256) return target
        val bytes = try {
            context.assets.open(DualNetSpec.MODEL_ASSET_PATH).use { it.readBytes() }
        } catch (error: java.io.IOException) {
            throw DualNetUnavailableException(DualNetUnavailableReason.MODEL_UNAVAILABLE, "APK 中没有 DualNet 模型", error)
        }
        if (sha256(bytes) != DualNetSpec.MODEL_SHA256) {
            throw DualNetUnavailableException(DualNetUnavailableReason.MODEL_UNAVAILABLE, "DualNet 模型摘要不一致")
        }
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return target
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
}

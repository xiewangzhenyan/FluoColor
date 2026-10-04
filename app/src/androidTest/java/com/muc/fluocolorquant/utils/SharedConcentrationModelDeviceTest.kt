package com.muc.fluocolorquant.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.storage.DeepLearningModelContractResult
import com.muc.fluocolorquant.data.storage.DeepLearningModelFileManager
import com.muc.fluocolorquant.data.storage.DeepLearningModelImportResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.torchvision.TensorImageUtils
import java.io.File

/**
 * 共享浓度模型的 Android 设备冒烟测试。
 *
 * JVM 单元测试只能确认比色与荧光返回同一个资源路径，无法证明 APK 中的真实 PTL 可以被
 * PyTorch Mobile Lite 正常加载和执行。本测试直接读取 main/assets 中的模型，按照生产代码的
 * 128×128 RGB 与 ImageNet mean/std 契约完成一次前向推理，用于尽早发现模型漏打包、文件损坏、
 * Lite 运行时不兼容或输出为 NaN/Infinity 等会让真实检测流程整体失效的问题。
 */
@RunWith(AndroidJUnit4::class)
class SharedConcentrationModelDeviceTest {

    @Test
    fun `共享PTL可按生产预处理契约完成有限值推理`() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val modelAssetPath = DetectionModeSupport.SHARED_CONCENTRATION_MODEL_ASSET
        val extractedModel = File(targetContext.cacheDir, "device_test_shared_concentration_model.ptl")

        // LiteModuleLoader 只能接收真实文件路径，因此将 APK assets 中的模型复制到测试缓存目录。
        targetContext.assets.open(modelAssetPath).use { input ->
            extractedModel.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        assertTrue("共享浓度模型没有正确打包进 APK", extractedModel.length() > 0L)

        val bitmap = createDeterministicRgbInput()
        try {
            val model = LiteModuleLoader.load(extractedModel.absolutePath)
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                bitmap,
                floatArrayOf(0.485f, 0.456f, 0.406f),
                floatArrayOf(0.229f, 0.224f, 0.225f)
            )
            val output = model.forward(IValue.from(inputTensor)).toTensor().dataAsFloatArray

            assertTrue("浓度模型输出不能为空", output.isNotEmpty())
            assertTrue(
                "浓度模型输出必须全部为有限值，实际为 ${output.contentToString()}",
                output.all { value -> value.isFinite() }
            )
        } finally {
            // 设备测试可能与其他高分辨率图像回归连续运行，主动回收像素内存并清理临时模型副本。
            bitmap.recycle()
            extractedModel.delete()
        }
    }

    @Test
    fun `用户PTL导入后自动计算摘要并通过发布前运行契约`() = runBlocking {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(targetContext.cacheDir, "user_import_shared_concentration_model.ptl")
        targetContext.assets.open(DetectionModeSupport.SHARED_CONCENTRATION_MODEL_ASSET).use { input ->
            source.outputStream().use(input::copyTo)
        }
        val manager = DeepLearningModelFileManager(targetContext)
        var importedPrivateFile: File? = null
        try {
            val importResult = manager.importFromUri(Uri.fromFile(source))
            assertTrue(importResult is DeepLearningModelImportResult.Success)
            val imported = (importResult as DeepLearningModelImportResult.Success).file
            assertEquals(source.name, imported.originalFileName)
            assertEquals(64, imported.checksumSha256.length)
            importedPrivateFile = File(targetContext.filesDir, imported.relativePath)
            assertTrue(importedPrivateFile.isFile)

            val validation = manager.validateRuntimeContract(
                DeepLearningModelDefinition(
                    analysisModelId = "device-import-test",
                    modelFileName = imported.relativePath,
                    checksumSha256 = imported.checksumSha256,
                    inputWidth = 128,
                    inputHeight = 128,
                    normalizationJson =
                        "{\"mean\":[0.485,0.456,0.406],\"std\":[0.229,0.224,0.225]}",
                    trainingDataVersion = "device-test"
                )
            )
            assertTrue("导入模型应满足生产标量输出契约：$validation", validation is DeepLearningModelContractResult.Success)
            assertTrue((validation as DeepLearningModelContractResult.Success).sampleOutput.isFinite())
        } finally {
            source.delete()
            // 测试导入副本位于明确的应用私有 model_uploads 目录，只清理本用例实际创建的文件。
            importedPrivateFile?.delete()
        }
    }

    /**
     * 构造稳定且包含 RGB 差异的输入，避免纯黑/纯白极端图像掩盖通道顺序或归一化问题。
     * 像素公式完全确定，使不同设备和重复测试都向模型提供相同数据。
     */
    private fun createDeterministicRgbInput(): Bitmap {
        val size = 128
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            Color.rgb(
                (x * 2).coerceAtMost(255),
                (y * 2).coerceAtMost(255),
                ((x + y) * 3 / 2).coerceAtMost(255)
            )
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}

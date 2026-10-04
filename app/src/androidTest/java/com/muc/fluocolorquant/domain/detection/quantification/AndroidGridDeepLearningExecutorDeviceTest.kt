package com.muc.fluocolorquant.domain.detection.quantification

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocator
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocatorConfig
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import com.muc.fluocolorquant.domain.detection.segmentation.OpenCvArrayUnitSegmenter
import com.muc.fluocolorquant.utils.DetectionModeSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * 用户实拍15×15芯片上的共享 PTL 整批推理回归。
 *
 * 单输入冒烟测试只能证明模型可以加载。本测试继续使用生产定位、紧致方块分割、透视矫正、
 * 128×128 RGB/ImageNet 预处理和 [AndroidGridDeepLearningExecutor]，对225个真实单元逐一
 * 推理。该固定语料同时证明旧版路由错误：面向 96 孔裁切训练的内置模型用于方形微流控
 * 单元时，225 个输出全部越过 0～100 声明域。资源选择层必须提前阻断这条组合；执行器
 * 仍保留本测试作为最后一道防线，确保绕过选择层也不会伪造浓度。
 */
@RunWith(AndroidJUnit4::class)
class AndroidGridDeepLearningExecutorDeviceTest {

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
    }

    @Test
    fun `内置96孔PTL面对微流控实拍输入时全部失败闭合`() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val bitmap = instrumentation.context.assets.open(REAL_IMAGE_ASSET).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input))
        }
        try {
            val grid = OpenCvPgGridLocator().locate(
                bitmap = bitmap,
                config = PgGridLocatorConfig(
                    rows = 15,
                    columns = 15,
                    targetPolarity = GridTargetPolarity.DARK
                )
            )
            val segmentation = OpenCvArrayUnitSegmenter().segment(
                sourceBitmap = bitmap,
                grid = grid,
                shape = ArrayUnitShape.SQUARE
            )
            assertEquals(225, grid.sites.size)
            assertEquals(225, segmentation.regions.size)

            val modelBundle = sharedModelBundle()
            val measurements = List(225) { index -> measurement(index) }

            val execution = AndroidGridDeepLearningExecutor(targetContext).execute(
                sourceBitmap = bitmap,
                grid = grid,
                segmentation = segmentation,
                measurements = measurements,
                modelBundle = modelBundle
            )
            assertTrue("模型文件和输入链有效时应形成逐孔结果：$execution", execution is GridDeepLearningBatchResult.Success)
            val success = execution as GridDeepLearningBatchResult.Success
            assertEquals(225, success.predictions.size + success.siteFailures.size)
            assertTrue("未经验证的微流控输入不能产生伪浓度", success.predictions.isEmpty())
            assertEquals(225, success.siteFailures.size)
            val rawOutputRange = success.siteFailures.values
                .map(GridDeepLearningSiteFailure::rawModelOutput)
                .let { outputs -> outputs.minOrNull() to outputs.maxOrNull() }
            assertTrue(
                "固定实拍语料应稳定复现整体输入域失配，原始输出=" +
                    "${rawOutputRange.first}～${rawOutputRange.second}",
                rawOutputRange.first != null && rawOutputRange.first!! > 100.0
            )
            assertTrue(success.predictions.keys.intersect(success.siteFailures.keys).isEmpty())
            success.siteFailures.values.forEach { failure ->
                assertEquals(
                    GridDeepLearningFailureReason.OUTPUT_OUT_OF_DECLARED_RANGE,
                    failure.reason
                )
                assertTrue(failure.rawModelOutput.isFinite())
                assertTrue(
                    failure.transformedModelOutput < failure.declaredOutputMin ||
                        failure.transformedModelOutput > failure.declaredOutputMax
                )
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** 构造与普通页面“共享浓度模型”完全相同的冻结定义。 */
    private fun sharedModelBundle(): AnalysisModelBundle {
        val modelId = "shared-device-model"
        return AnalysisModelBundle(
            model = AnalysisModel(
                id = modelId,
                name = "共享浓度模型设备回归",
                modelType = AnalysisModelType.DEEP_LEARNING.code,
                analyteId = ANALYTE_ID,
                detectionMode = DetectionModality.FLUORESCENCE.code,
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
                primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
                processorName = FLUORESCENCE_PROCESSOR_NAME,
                processorVersion = FLUORESCENCE_PROCESSOR_VERSION,
                compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
                compatibleAcquisitionProfileIdsJson = "[\"$ACQUISITION_ID\"]",
                concentrationUnit = UNIT,
                reliableRangeMin = 0.0,
                reliableRangeMax = 100.0,
                status = AnalysisModelLifecycleStatus.PUBLISHED.code
            ),
            deepLearning = DeepLearningModelDefinition(
                analysisModelId = modelId,
                modelFileName = DetectionModeSupport.SHARED_CONCENTRATION_MODEL_ASSET,
                checksumSha256 = BuiltInSharedConcentrationModel.CHECKSUM_SHA256,
                inputWidth = BuiltInSharedConcentrationModel.INPUT_SIZE,
                inputHeight = BuiltInSharedConcentrationModel.INPUT_SIZE,
                normalizationJson = gson.toJson(
                    mapOf(
                        "mean" to listOf(0.485, 0.456, 0.406),
                        "std" to listOf(0.229, 0.224, 0.225)
                    )
                ),
                trainingDataVersion = "shared-fluorescence-v1",
                metadataJson = gson.toJson(
                    mapOf("outputMode" to "PERCENT_OF_RELIABLE_MAX")
                )
            )
        )
    }

    /** 执行器只依赖位点索引，但测试仍提供完整可持久化测量契约。 */
    private fun measurement(siteIndex: Int): SiteMeasurement = SiteMeasurement(
        runId = "shared-device-run",
        siteIndex = siteIndex,
        analyteId = ANALYTE_ID,
        detectionMode = DetectionModality.FLUORESCENCE.code,
        rawSignalJson = "{\"siteIndex\":$siteIndex}",
        correctedSignalJson = null,
        primaryFeatureName = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
        primaryFeatureValue = siteIndex.toDouble(),
        confidence = 0.95,
        signalDetectable = true,
        qualityReliable = true,
        processorName = FLUORESCENCE_PROCESSOR_NAME,
        processorVersion = FLUORESCENCE_PROCESSOR_VERSION
    )

    private companion object {
        val gson = Gson()
        const val REAL_IMAGE_ASSET = "pg_grid/real_v1/images/real_15x15_01.jpg"
        const val ANALYTE_ID = "shared-device-cea"
        const val ACQUISITION_ID = "shared-device-acquisition"
        const val UNIT = "ng/mL"
    }
}

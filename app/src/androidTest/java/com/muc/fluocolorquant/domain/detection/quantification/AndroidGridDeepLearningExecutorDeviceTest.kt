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
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.GridDetectionPersistenceBundle
import com.muc.fluocolorquant.data.repository.GridDetectionRunRepository
import com.muc.fluocolorquant.domain.detection.AnalysisModelCompatibilityChecker
import com.muc.fluocolorquant.domain.detection.GridDetectionCoordinator
import com.muc.fluocolorquant.domain.detection.ModelCompatibilityRequest
import com.muc.fluocolorquant.domain.detection.ModelCompatibilityResult
import com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocator
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocatorConfig
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_NAME
import com.muc.fluocolorquant.domain.detection.photometry.FLUORESCENCE_PROCESSOR_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import com.muc.fluocolorquant.domain.detection.segmentation.OpenCvArrayUnitSegmenter
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.utils.DetectionModeSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 推理，并验证批次原子映射与每个位点冻结模型快照。
 */
@RunWith(AndroidJUnit4::class)
class AndroidGridDeepLearningExecutorDeviceTest {

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
    }

    @Test
    fun `共享PTL在实拍十五乘十五芯片完成二百二十五孔批量推理`() {
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
            val analyteSnapshot = analyteSnapshot(modelBundle)
            val compatibility = AnalysisModelCompatibilityChecker.check(
                model = modelBundle.model,
                request = ModelCompatibilityRequest(
                    analyteId = ANALYTE_ID,
                    modality = DetectionModality.FLUORESCENCE,
                    inputProtocol = InputProtocol.ENDPOINT_ONLY,
                    primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
                    carrierType = CarrierType.MICROFLUIDIC_CHIP,
                    acquisitionProfileId = ACQUISITION_ID,
                    processorName = FLUORESCENCE_PROCESSOR_NAME,
                    processorVersion = FLUORESCENCE_PROCESSOR_VERSION
                )
            )
            assertTrue(compatibility is ModelCompatibilityResult.Compatible)
            val measurements = List(225) { index -> measurement(index) }

            val execution = AndroidGridDeepLearningExecutor(targetContext).execute(
                sourceBitmap = bitmap,
                grid = grid,
                segmentation = segmentation,
                measurements = measurements,
                modelBundle = modelBundle
            )
            assertTrue(
                "共享模型整批执行失败：$execution",
                execution is GridDeepLearningBatchResult.Success
            )
            val predictions = (execution as GridDeepLearningBatchResult.Success).predictions
            assertEquals((0 until 225).toSet(), predictions.keys)
            assertEquals(225, predictions.size)
            predictions.values.forEach { prediction ->
                assertTrue(prediction.siteIndex in 0 until 225)
                prediction.concentration?.let { concentration ->
                    assertTrue(concentration.isFinite())
                    assertTrue(concentration in 0.0..100.0)
                    assertEquals(ReliableRangeStatus.WITHIN_RANGE, prediction.rangeStatus)
                }
                assertTrue(prediction.modelSnapshotJson.contains(GRID_DEEP_LEARNING_EXECUTOR_VERSION))
                assertTrue(prediction.modelSnapshotJson.contains(BuiltInSharedConcentrationModel.CHECKSUM_SHA256))
                assertTrue(prediction.modelSnapshotJson.contains("\"checksumVerified\":true"))
            }
            // 同一批次使用同一冻结模型定义，225个位点不得生成互相漂移的快照。
            assertEquals(1, predictions.values.map { it.modelSnapshotJson }.distinct().size)

            val coordinator = GridDetectionCoordinator(
                locator = OpenCvPgGridLocator(),
                repository = NoOpGridRunRepository
            )
            val batch = coordinator.applyDeepLearningBatchResult(
                measurements = measurements,
                analyteSnapshot = analyteSnapshot,
                compatibility = compatibility,
                execution = execution
            )
            assertTrue(batch.modelExecutable)
            assertEquals(225, batch.total)
            assertEquals(225, batch.quantifiedCount + batch.outOfRangeCount)
            assertEquals(0, batch.siteSignalOnlyCount)
            assertTrue(batch.measurements.all { measurement ->
                !measurement.modelSnapshotJson.isNullOrBlank() &&
                    measurement.quantificationQcJson?.contains("DEEP_LEARNING") == true
            })
            assertFalse(batch.measurements.any { measurement ->
                measurement.concentrationValue?.isFinite() == false
            })
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

    private fun analyteSnapshot(bundle: AnalysisModelBundle): TemplateProjectAnalyteSnapshot {
        return TemplateProjectAnalyteSnapshot(
            analyte = Analyte(id = ANALYTE_ID, name = "CEA"),
            templateConfig = TemplateAnalyteConfig(
                id = "shared-device-config",
                templateId = "shared-device-template",
                analyteId = ANALYTE_ID,
                analysisModelId = bundle.model.id,
                concentrationUnit = UNIT,
                reliableRangeMin = 0.0,
                reliableRangeMax = 100.0,
                displayConfigJson = ScientificDetectionConfigCodec.encodeFluorescenceDisplay(
                    FluorescenceChannel.GREEN
                )
            ),
            analysisModel = bundle
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

    /** 本测试只调用批次映射纯函数，不需要写入运行数据库。 */
    private object NoOpGridRunRepository : GridDetectionRunRepository {
        override suspend fun save(bundle: GridDetectionPersistenceBundle) = Unit
    }

    private companion object {
        val gson = Gson()
        const val REAL_IMAGE_ASSET = "pg_grid/real_v1/images/real_15x15_01.jpg"
        const val ANALYTE_ID = "shared-device-cea"
        const val ACQUISITION_ID = "shared-device-acquisition"
        const val UNIT = "ng/mL"
    }
}

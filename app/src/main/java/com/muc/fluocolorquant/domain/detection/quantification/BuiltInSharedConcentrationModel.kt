package com.muc.fluocolorquant.domain.detection.quantification

import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.utils.DetectionModeSupport
import java.util.Date

/**
 * 当前比色与荧光共用的内置浓度模型定义。
 *
 * 普通用户只看到“共享浓度模型”，不会接触文件名、SHA、输入尺寸或归一化 JSON；这些
 * 专业参数由代码冻结并在首次选择时写入模型库，使实验模板可以保存真实外键关联。
 */
object BuiltInSharedConcentrationModel {
    const val OPTION_ID_PREFIX: String = "builtin-shared-concentration-option:"
    const val RESOURCE_NAME_PREFIX: String = "builtin-shared-concentration:"
    const val CHECKSUM_SHA256: String =
        "D1095786AB59C30D3CE80C3E16D63EC66A25A3AA10847B2A8B6A032ADC1FF291"
    const val INPUT_SIZE: Int = 128

    private val gson = Gson()

    /** 页面选择器使用的临时 ID；真正应用时会创建或复用持久化发布模型。 */
    fun optionId(analyteId: String, detectionMode: String): String =
        "$OPTION_ID_PREFIX$detectionMode:$analyteId"

    fun isOptionId(id: String): Boolean = id.startsWith(OPTION_ID_PREFIX)

    /** 稳定资源名包含模态和分析物，满足数据库 name+version 唯一约束。 */
    fun resourceName(analyteId: String, detectionMode: String): String =
        "$RESOURCE_NAME_PREFIX$detectionMode:$analyteId"

    fun isBuiltInResourceName(name: String): Boolean = name.startsWith(RESOURCE_NAME_PREFIX)

    /** 为当前项目分析物生成可发布、可冻结、可由模板引用的完整模型包。 */
    fun createBundle(
        snapshot: TemplateProjectSnapshot,
        analyteSnapshot: TemplateProjectAnalyteSnapshot,
        now: Date = Date()
    ): AnalysisModelBundle {
        val modelId = optionId(
            analyteId = analyteSnapshot.analyte.id,
            detectionMode = snapshot.template.detectionMode.orEmpty()
        )
        val model = AnalysisModel(
            id = modelId,
            name = resourceName(
                analyteId = analyteSnapshot.analyte.id,
                detectionMode = snapshot.template.detectionMode.orEmpty()
            ),
            modelType = AnalysisModelType.DEEP_LEARNING.code,
            analyteId = analyteSnapshot.analyte.id,
            detectionMode = snapshot.template.detectionMode.orEmpty(),
            inputProtocol = snapshot.template.inputProtocol,
            // 深度学习读取 RGB 单元图；该字段仍沿用当前模态主科学信号，供兼容性、
            // 结果摘要和模板筛选使用，不表示 PTL 只读取一个标量特征。
            primaryFeature = analyteSnapshot.analysisModel.model.primaryFeature,
            processorName = analyteSnapshot.analysisModel.model.processorName,
            processorVersion = analyteSnapshot.analysisModel.model.processorVersion,
            compatibleCarrierTypesJson = gson.toJson(
                listOf(snapshot.carrierProfile.carrierType)
            ),
            compatibleAcquisitionProfileIdsJson = gson.toJson(
                listOf(snapshot.acquisitionProfile.id)
            ),
            concentrationUnit = analyteSnapshot.templateConfig.concentrationUnit,
            reliableRangeMin = analyteSnapshot.templateConfig.reliableRangeMin ?: 0.0,
            reliableRangeMax = analyteSnapshot.templateConfig.reliableRangeMax
                ?: analyteSnapshot.analysisModel.model.reliableRangeMax,
            validationMetricsJson = gson.toJson(
                mapOf("source" to "bundled-shared-concentration-model")
            ),
            status = AnalysisModelLifecycleStatus.PUBLISHED.code,
            version = 1,
            createdAt = now,
            updatedAt = now
        )
        return AnalysisModelBundle(
            model = model,
            deepLearning = DeepLearningModelDefinition(
                analysisModelId = modelId,
                modelFileName = DetectionModeSupport.SHARED_CONCENTRATION_MODEL_ASSET,
                checksumSha256 = CHECKSUM_SHA256,
                inputWidth = INPUT_SIZE,
                inputHeight = INPUT_SIZE,
                normalizationJson = gson.toJson(
                    mapOf(
                        "mean" to listOf(0.485, 0.456, 0.406),
                        "std" to listOf(0.229, 0.224, 0.225)
                    )
                ),
                trainingDataVersion = "shared-fluorescence-v1",
                metadataJson = gson.toJson(
                    mapOf(
                        "builtInShared" to true,
                        // 与旧96孔板手动模型流程保持一致：模型输出 0～100 百分比，
                        // 再映射到当前分析物冻结的可靠浓度范围。
                        "outputMode" to "PERCENT_OF_RELIABLE_MAX"
                    )
                )
            )
        )
    }
}

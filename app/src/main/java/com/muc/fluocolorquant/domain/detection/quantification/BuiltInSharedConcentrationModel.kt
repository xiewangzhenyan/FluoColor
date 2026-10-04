package com.muc.fluocolorquant.domain.detection.quantification

import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.utils.DetectionModeSupport
import java.util.Date

/**
 * 当前 96 孔板比色与荧光共用的内置浓度模型定义。
 *
 * 普通用户只看到“共享浓度模型”，不会接触文件名、SHA、输入尺寸或归一化 JSON；这些
 * 专业参数由代码冻结并在首次选择时写入模型库，使实验模板可以保存真实外键关联。
 * 该 PTL 的原始训练与旧版生产链都面向 96 孔圆形裁切，不能仅因输入同为 128×128 RGB
 * 就宣称已经验证微流控方形单元。真实 15×15 芯片回归的 225 个输出全部落在
 * 134.74～172.87，明确越过模型声明的 0～100 域。应用允许科研人员在明确确认风险后
 * 做实验性试用，但结果仍执行严格输出域校验，不能截断或伪造浓度。
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

    /**
     * 当前 Android 推理链能否技术性执行该模型。
     *
     * “能执行”不等于“已经通过科学验证”。规则阵列终点图可以进入同一 RGB/PTL 推理链，
     * 因而不再被产品层硬禁用；真正的验证范围由 [isValidatedFor] 单独表达并在 UI 中要求
     * 用户确认，输出端继续用声明域保护真实结果。
     */
    fun canExecute(snapshot: TemplateProjectSnapshot): Boolean {
        val modality = DetectionModality.fromCode(snapshot.template.detectionMode)
        val carrierType = CarrierType.fromCode(snapshot.carrierProfile.carrierType)
        return carrierType in setOf(CarrierType.PLATE, CarrierType.MICROFLUIDIC_CHIP) &&
            InputProtocol.fromCode(snapshot.template.inputProtocol) == InputProtocol.ENDPOINT_ONLY &&
            modality in setOf(DetectionModality.COLORIMETRIC, DetectionModality.FLUORESCENCE)
    }

    /** 模型已有训练与回归证据覆盖的严格适用范围。 */
    fun isValidatedFor(snapshot: TemplateProjectSnapshot): Boolean {
        return canExecute(snapshot) &&
            CarrierType.fromCode(snapshot.carrierProfile.carrierType) == CarrierType.PLATE &&
            SiteShape.fromCode(snapshot.carrierProfile.siteShape) == SiteShape.CIRCLE &&
            snapshot.carrierProfile.rows == 8 &&
            snapshot.carrierProfile.columns == 12
    }

    /** 旧内置资源的兼容 JSON 可能只保存了 PLATE；运行检查使用此函数恢复技术兼容边界。 */
    fun canExecute(
        carrierType: CarrierType,
        modality: DetectionModality,
        inputProtocol: InputProtocol
    ): Boolean = carrierType in setOf(CarrierType.PLATE, CarrierType.MICROFLUIDIC_CHIP) &&
        inputProtocol == InputProtocol.ENDPOINT_ONLY &&
        modality in setOf(DetectionModality.COLORIMETRIC, DetectionModality.FLUORESCENCE)

    /** 为当前项目分析物生成可发布、可冻结、可由模板引用的完整模型包。 */
    fun createBundle(
        snapshot: TemplateProjectSnapshot,
        analyteSnapshot: TemplateProjectAnalyteSnapshot,
        now: Date = Date()
    ): AnalysisModelBundle {
        require(canExecute(snapshot)) {
            "当前模态或输入协议不能执行内置共享浓度模型"
        }
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
                // 这里声明 Android 推理链可以接受的技术载体范围；模型真正完成验证的
                // 8×12 圆孔板条件另外写入 metadata，不能混淆“可运行”和“已验证”。
                listOf(CarrierType.PLATE.code, CarrierType.MICROFLUIDIC_CHIP.code)
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
                trainingDataVersion = "legacy-plate96-concentration-v1",
                metadataJson = gson.toJson(
                    mapOf(
                        "builtInShared" to true,
                        "validationStatus" to "VALIDATED_FOR_PLATE96_CIRCLE_ONLY",
                        "validatedCarrierType" to CarrierType.PLATE.code,
                        "validatedSiteShape" to SiteShape.CIRCLE.code,
                        "validatedRows" to 8,
                        "validatedColumns" to 12,
                        "requiresExplicitConfirmationOutsideValidatedScope" to true,
                        // 训练标签语义是 0～100 百分比，再映射到当前分析物冻结的可靠浓度
                        // 范围。PTL 末层没有硬边界，因此执行器仍必须拒绝小于0或大于100
                        // 的输出，不能通过 clamp 伪造端点浓度。
                        "outputMode" to "PERCENT_OF_RELIABLE_MAX"
                    )
                )
            )
        )
    }
}

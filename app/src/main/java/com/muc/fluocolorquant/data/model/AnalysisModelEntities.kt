package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.muc.fluocolorquant.data.enums.ResourceStatus
import java.util.Date
import java.util.UUID

/**
 * 面向全部检测模态的通用分析模型元数据。
 *
 * 旧 [CurveModel] 继续服务历史项目；新项目通过本实体明确声明检测模态、输入协议、
 * 主特征和设备兼容范围，避免把比色、荧光或 LSPR 模型静默混用。
 */
@Entity(
    tableName = "analysis_models",
    foreignKeys = [
        ForeignKey(
            entity = Analyte::class,
            parentColumns = ["id"],
            childColumns = ["analyteId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index("analyteId"),
        Index(value = ["name", "version"], unique = true),
        Index(value = ["detectionMode", "inputProtocol", "primaryFeature"])
    ]
)
data class AnalysisModel(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val modelType: String,
    val analyteId: String,
    val detectionMode: String,
    val inputProtocol: String,
    val primaryFeature: String,
    val processorName: String,
    val processorVersion: String,
    val compatibleCarrierTypesJson: String? = null,
    val compatibleAcquisitionProfileIdsJson: String? = null,
    val concentrationUnit: String,
    val reliableRangeMin: Double,
    val reliableRangeMax: Double,
    val validationMetricsJson: String? = null,
    val status: String = ResourceStatus.ACTIVE.code,
    val version: Int = 1,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

/** 标准曲线模型特有的拟合、单调性和反算配置。 */
@Entity(
    tableName = "standard_curve_definitions",
    foreignKeys = [
        ForeignKey(
            entity = AnalysisModel::class,
            parentColumns = ["id"],
            childColumns = ["analysisModelId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class StandardCurveDefinition(
    @PrimaryKey
    val analysisModelId: String,
    val fittingFunction: String,
    val parametersJson: String,
    val monotonicDirection: String,
    val inverseRuleJson: String? = null,
    val lod: Double? = null,
    val loq: Double? = null
)

/**
 * 标准曲线的原始重复点。
 *
 * 保存重复编号、批次和排除理由可以重建统计过程，禁止只保留均值和最终参数。
 */
@Entity(
    tableName = "calibration_points",
    foreignKeys = [
        ForeignKey(
            entity = AnalysisModel::class,
            parentColumns = ["id"],
            childColumns = ["analysisModelId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("analysisModelId"),
        Index(value = ["analysisModelId", "concentration", "repeatIndex"])
    ]
)
data class CalibrationPoint(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val analysisModelId: String,
    val concentration: Double,
    val signalValue: Double,
    val repeatIndex: Int,
    val runId: String? = null,
    val baselineMeasurementId: Long? = null,
    val endpointMeasurementId: Long? = null,
    val batchId: String? = null,
    val excluded: Boolean = false,
    val exclusionReason: String? = null,
    val createdAt: Date = Date()
)

/** 端侧深度学习模型文件及其可复现输入定义。 */
@Entity(
    tableName = "deep_learning_model_definitions",
    foreignKeys = [
        ForeignKey(
            entity = AnalysisModel::class,
            parentColumns = ["id"],
            childColumns = ["analysisModelId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class DeepLearningModelDefinition(
    @PrimaryKey
    val analysisModelId: String,
    val modelFileName: String,
    val checksumSha256: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val normalizationJson: String,
    val trainingDataVersion: String,
    val metadataJson: String? = null
)

package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 实验模板中的单个分析物配置。
 *
 * 通过子表替代旧模板“一条记录只能关联一个分析物和一条曲线”的限制；草稿阶段允许
 * 暂未选择分析模型，发布检查阶段再强制补齐。
 */
@Entity(
    tableName = "template_analyte_configs",
    foreignKeys = [
        ForeignKey(
            entity = ExperimentTemplate::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Analyte::class,
            parentColumns = ["id"],
            childColumns = ["analyteId"],
            onDelete = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = Reagent::class,
            parentColumns = ["id"],
            childColumns = ["reagentAntigenId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Reagent::class,
            parentColumns = ["id"],
            childColumns = ["reagentAntibodyId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = AnalysisModel::class,
            parentColumns = ["id"],
            childColumns = ["analysisModelId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("templateId"),
        Index("analyteId"),
        Index("reagentAntigenId"),
        Index("reagentAntibodyId"),
        Index("analysisModelId"),
        Index(value = ["templateId", "analyteId"], unique = true)
    ]
)
data class TemplateAnalyteConfig(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val templateId: String,
    val analyteId: String,
    val reagentAntigenId: String? = null,
    val reagentAntibodyId: String? = null,
    val analysisModelId: String? = null,
    val concentrationUnit: String,
    val reliableRangeMin: Double? = null,
    val reliableRangeMax: Double? = null,
    val displayOrder: Int = 0,
    val displayConfigJson: String? = null
)

/**
 * 模板中的通用阵列位点分配。
 *
 * 使用零基行列坐标持久化，界面可以按载体选择显示 R01C01 或 A1 别名；唯一索引防止
 * 同一模板对同一个物理位点产生两份相互冲突的配置。
 */
@Entity(
    tableName = "template_site_assignments",
    foreignKeys = [
        ForeignKey(
            entity = ExperimentTemplate::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Analyte::class,
            parentColumns = ["id"],
            childColumns = ["analyteId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("templateId"),
        Index("analyteId"),
        Index(value = ["templateId", "rowIndex", "columnIndex"], unique = true)
    ]
)
data class TemplateSiteAssignment(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val templateId: String,
    val rowIndex: Int,
    val columnIndex: Int,
    val analyteId: String? = null,
    val roleType: String,
    val standardConcentration: Double? = null,
    val repeatGroup: String? = null,
    val defaultSampleSlot: String? = null,
    val referenceScope: String? = null,
    val enabled: Boolean = true
)

/**
 * 实验模板中单个分析物的冻结定量绑定。
 *
 * [sourceResourceId] 只用于追溯，可因资源删除被置空；[resourceSnapshotJson] 保存创建模板
 * 当时的完整曲线/模型摘要和定量快照，因此资源库后续编辑或删除不会改变模板科学含义。
 * 深度学习只保存文件校验和、输入协议等元数据，不复制 PTL 二进制文件。
 */
@Entity(
    tableName = "template_quantitation_bindings",
    foreignKeys = [
        ForeignKey(
            entity = ExperimentTemplate::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Analyte::class,
            parentColumns = ["id"],
            childColumns = ["analyteId"],
            onDelete = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = AnalysisModel::class,
            parentColumns = ["id"],
            childColumns = ["sourceResourceId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("templateId"),
        Index("analyteId"),
        Index("sourceResourceId"),
        Index(value = ["templateId", "analyteId"], unique = true)
    ]
)
data class TemplateQuantitationBinding(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val templateId: String,
    val analyteId: String,
    val method: String,
    val sourceResourceId: String? = null,
    val resourceSnapshotJson: String,
    val contentFingerprint: String,
    val processorName: String,
    val processorVersion: String
)

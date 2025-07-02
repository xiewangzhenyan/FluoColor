package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date
import java.util.UUID

/**
 * 实验模板实体类
 * 
 * 用于存储实验方案的完整配置，包括关联的分析物、试剂和曲线模型
 */
@Entity(
    tableName = "experiment_templates",
    foreignKeys = [
        ForeignKey(entity = Analyte::class, parentColumns = ["id"], childColumns = ["analyteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Reagent::class, parentColumns = ["id"], childColumns = ["reagentAntigenId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = Reagent::class, parentColumns = ["id"], childColumns = ["reagentAntibodyId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = CurveModel::class, parentColumns = ["id"], childColumns = ["fkCurveModelId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index(value = ["analyteId"]),
        Index(value = ["reagentAntigenId"]),
        Index(value = ["reagentAntibodyId"]),
        Index(value = ["fkCurveModelId"])
    ]
)
data class ExperimentTemplate(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(), // 使用UUID作为主键
    val templateName: String,       // 模板名称, e.g., "Linc-Bio CEA 试剂盒 - 荧光法"
    val analyteId: String,          // [外键] 关联到 Analyte 表
    val reagentAntigenId: String?,  // [外键] 关联到 Reagents 表 (抗原)
    val reagentAntibodyId: String?, // [外键] 关联到 Reagents 表 (抗体)
    val fkCurveModelId: String,     // [外键] 关联到 CurveModels 表
    val reliableRangeMin: Double,   // 可靠浓度范围下限
    val reliableRangeMax: Double,   // 可靠浓度范围上限
    val concentrationUnit: String,  // 该实验方案的浓度单位
    val defaultLayoutJson: String?, // [新增] 用于存储可选的、建议性的孔板布局JSON
    val createdAt: Date = Date(),   // 创建时间
    val updatedAt: Date = Date()    // 最后更新时间
) 
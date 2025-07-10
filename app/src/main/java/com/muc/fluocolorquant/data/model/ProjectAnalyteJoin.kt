package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 项目与分析物的多对多关联实体
 * 用于建立项目与分析物之间的多对多关系
 * 同时存储每个分析物在项目中的具体配置
 */
@Entity(
    tableName = "project_analytes_join",
    primaryKeys = ["projectId", "analyteId"],
    foreignKeys = [
        ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Analyte::class, parentColumns = ["id"], childColumns = ["analyteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ExperimentTemplate::class, parentColumns = ["id"], childColumns = ["fkTemplateId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [
        Index("projectId"),
        Index("analyteId"),
        Index("fkTemplateId")
    ]
)
data class ProjectAnalyteJoin(
    val projectId: String,
    val analyteId: String,
    
    // --- 新增字段，取代原projects表中的字段 ---
    // 用于DL模型，当用户选择该方法时，这些字段必须有值
    val maxConcentration: Double?,      
    val concentrationUnit: String?,     

    // --- 新增字段，用于曲线拟合模型 ---
    // 用于记录用户在下一步中为该分析物选择的模板ID
    val fkTemplateId: String?,
    
    // --- 新增字段，用于深度学习模型预测 ---
    // 记录用户为该分析物选择的深度学习模型名称
    val dlModelName: String? = null
) 
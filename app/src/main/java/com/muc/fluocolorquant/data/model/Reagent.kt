package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 试剂库实体类
 * 管理与特定分析物关联的试剂信息，包括分子量用于单位换算
 */
@Entity(
    tableName = "reagents",
    foreignKeys = [
        ForeignKey(
            entity = Analyte::class,
            parentColumns = ["id"],
            childColumns = ["analyteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("analyteId")] // 为外键列添加索引，优化性能
)
data class Reagent(
    @PrimaryKey
    val id: String,                // 唯一ID, e.g., "reagent_cea_antibody_lincbio"
    val analyteId: String,         // 外键: 关联到 Analytes 表
    val reagentName: String,       // 试剂名称, e.g., "Anti-CEA Antibody"
    val reagentType: String,       // 类型: "antigen" 或 "antibody"
    val manufacturer: String?,     // 制造商
    val molecularWeight: Double?,  // 分子量 (kDa), 用于摩尔浓度换算
    val unit: String?              // 浓度单位, e.g., "ng/ml", "ug/ml"
) 
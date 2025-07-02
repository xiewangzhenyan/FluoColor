package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分析物实体类
 * 作为所有数据的顶级索引，管理核心的检测目标
 */
@Entity(
    tableName = "analytes",
    indices = [Index(value = ["name"], unique = true)] // 确保分析物名称唯一
)
data class Analyte(
    @PrimaryKey
    val id: String,    // 唯一ID, e.g., "analyte_cea"
    val name: String   // 分析物名称, e.g., "CEA", "NSE"
) 
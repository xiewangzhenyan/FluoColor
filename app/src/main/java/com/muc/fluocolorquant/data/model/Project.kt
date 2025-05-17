package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

/**
 * 项目数据模型
 * 用于存储检测项目的基本信息
 */
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey
    val id: String,                // 项目ID
    val name: String,              // 项目名称
    val detectionMode: String,     // 检测模式: FLUORESCENCE(荧光检测) 或 COLORIMETRIC(比色检测)
    val recognitionType: String,   // 识别类型: AUTO(自动识别) 或 MANUAL(手动裁剪)
    val imageUri: String,          // 项目图片URI
    val maxConcentration: Double?, // 最大浓度值(ng/ml)，可为null
    val createTime: Date,          // 创建时间
    val userId: String,            // 创建用户ID
    val lastRunTimestamp: Date?    // 最后一次运行时间，方便排序
) 
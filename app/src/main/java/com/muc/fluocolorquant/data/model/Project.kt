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
    val rows: Int,                 // 孔阵行数
    val columns: Int,              // 孔阵列数
    val createTime: Date,          // 创建时间
    val userId: String,            // 创建用户ID
    val lastRunTimestamp: Date?,   // 最后一次运行时间，方便排序
    
    // 新增字段 - 2.0.0版本
    val analysisMethod: String     // 分析方法: "DL_MODEL"(深度学习模型) 或 "CURVE_FIT"(曲线拟合)
    // 以下字段已移除，移至project_analytes_join表
    // val fkCurveModelId: String?,   // 外键，关联到curve_models表
    // val finalCurveModelJson: String? // 最终曲线模型的JSON存储，用于结果溯源
) 
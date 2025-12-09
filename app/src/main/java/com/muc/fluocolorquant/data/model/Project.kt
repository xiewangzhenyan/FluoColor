package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

/**
 * Project entity for Room.
 * Holds detection mode and layout info; includes spectrum-specific metadata.
 */
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey
    val id: String,                // 项目ID
    val name: String,              // 项目名称
    val detectionMode: String,     // FLUORESCENCE / COLORIMETRIC / SPECTRUM
    val recognitionType: String,   // AUTO / MANUAL
    val imageUri: String,          // 项目图像URI
    val rows: Int,                 // 孔板行数
    val columns: Int,              // 孔板列数
    val lightSource: String? = null,           // 光源信息，如“汞灯”/“太阳光”
    val spectrumColumnCount: Int = 1,          // 光谱列数，默认1
    val spectrumColumnMappingJson: String? = null, // JSON(Map<Int, Long>)：列 -> 分析物ID
    val createTime: Date,          // 创建时间
    val userId: String,            // 创建用户ID
    val lastRunTimestamp: Date?,   // 最近一次运行时间
    val analysisMethod: String     // DL_MODEL / CURVE_FIT
)

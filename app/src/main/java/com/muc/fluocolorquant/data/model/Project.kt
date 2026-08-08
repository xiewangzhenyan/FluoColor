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
    val spectrumColumnMappingJson: String? = null, // JSON：{"1":"分析物ID", ...}，键为 1 基通道号字符串
    val createTime: Date,          // 创建时间
    val userId: String,            // 创建用户ID
    val lastRunTimestamp: Date?,   // 最近一次运行时间
    val analysisMethod: String,    // DL_MODEL / CURVE_FIT

    // 以下字段用于模板优先的新项目流程；全部提供默认值以兼容旧页面的构造调用。
    val templateId: String? = null,             // 创建项目时选择的模板ID
    val templateVersion: Int? = null,           // 创建时冻结的模板版本
    val templateSnapshotJson: String? = null,   // 不可变模板快照，历史结果只读取该字段
    val overrideJson: String? = null,            // 项目相对模板的显式覆盖和原因
    val projectBatch: String? = null,            // 实验项目批次
    val sampleBatch: String? = null              // 样本批次
)

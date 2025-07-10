package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单个孔位的结果
 * 包含孔位的检测和分析数据
 */
@Entity(
    tableName = "well_results",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DetectionRun::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Analyte::class,
            parentColumns = ["id"],
            childColumns = ["fkAnalyteId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("projectId"), 
        Index("runId"),
        Index("fkAnalyteId")
    ]
)
data class WellResult(
    @PrimaryKey(autoGenerate = true)
    val resultId: Long = 0,               // 结果ID
    val runId: String?,                   // 关联的运行ID
    val projectId: String,                // 关联的项目ID
    val wellIndex: Int,                   // 孔位索引 (0-95)
    val predictedConcentration: Double?,  // 预测的浓度值
    val trueConcentration: Double?,       // 用户输入的真实浓度值
    val isStandard: Boolean = false,      // 是否为标准品 (已废弃，请使用roleType字段)
    
    // 检测框字段
    val detectedRectLeft: Float?,         // 检测框左坐标
    val detectedRectTop: Float?,          // 检测框上坐标
    val detectedRectRight: Float?,        // 检测框右坐标
    val detectedRectBottom: Float?,       // 检测框下坐标
    val detectionConfidence: Float?,      // 检测置信度
    
    // 图像标识字段
    val croppedImageIdentifier: String?,  // 裁剪图像标识符
    
    // 2.0.0版本字段
    val pixelValueJson: String? = null,    // 存储从图片中提取的原始像素特征值的JSON
    val fkAnalyteId: String? = null,       // 外键，关联到analytes表，指明孔位结果属于哪个分析物
    val isOutOfRange: Boolean = false,     // 标志位，标记计算结果是否超出曲线的可靠浓度范围
    
    // 3.0.0版本字段
    val roleType: String? = null,          // 孔位角色，例如 "STANDARD", "SAMPLE", "BLANK", "QC"
    
    // 新增字段 - 4.0.0版本
    val virtualRow: Int? = null,           // 虚拟布局中的行索引
    val virtualCol: Int? = null            // 虚拟布局中的列索引
) 
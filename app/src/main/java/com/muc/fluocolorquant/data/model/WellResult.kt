package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单个孔位的结果
 * 包含自动识别和手动裁剪两种模式下的数据
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
        )
    ],
    indices = [
        Index("projectId"), 
        Index("runId"),
        Index("fkAnalyteId") // 为新增的外键列添加索引
    ]
)
data class WellResult(
    @PrimaryKey(autoGenerate = true)
    val resultId: Long = 0,               // 结果ID
    val runId: String?,                   // 关联的运行ID (AUTO模式)
    val projectId: String,                // 关联的项目ID
    val wellIndex: Int,                   // 孔位索引 (0-95用于AUTO模式, -1用于MANUAL模式)
    val predictedConcentration: Double?,  // 预测的浓度值
    val trueConcentration: Double?,       // 用户输入的真实浓度值
    val isStandard: Boolean = false,      // 是否为标准品
    
    // AUTO模式特有字段
    val detectedRectLeft: Float?,         // 检测框左坐标
    val detectedRectTop: Float?,          // 检测框上坐标
    val detectedRectRight: Float?,        // 检测框右坐标
    val detectedRectBottom: Float?,       // 检测框下坐标
    val detectionConfidence: Float?,      // 检测置信度
    
    // MANUAL模式特有字段
    val croppedImageIdentifier: String?,  // 裁剪图像标识符
    val manualCropRectLeft: Float?,       // 手动裁剪框左坐标
    val manualCropRectTop: Float?,        // 手动裁剪框上坐标
    val manualCropRectRight: Float?,      // 手动裁剪框右坐标
    val manualCropRectBottom: Float?,     // 手动裁剪框下坐标
    
    // 新增字段 - 2.0.0版本
    val pixelValueJson: String? = null,    // 存储从图片中提取的原始像素特征值的JSON
    val fkAnalyteId: String? = null,       // 外键，关联到analytes表，指明孔位结果属于哪个分析物
    val isOutOfRange: Boolean = false      // 标志位，标记计算结果是否超出曲线的可靠浓度范围
) 
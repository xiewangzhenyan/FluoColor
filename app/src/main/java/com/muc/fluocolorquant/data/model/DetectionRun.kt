package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * 自动识别运行记录
 * 主要用于AUTO模式，记录单次识别运行的信息
 */
@Entity(
    tableName = "detection_runs",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class DetectionRun(
    @PrimaryKey
    val runId: String,                    // 运行ID
    val projectId: String,                // 关联的项目ID
    val timestamp: Date,                  // 运行时间
    val detectionModelUsed: String?,      // 使用的检测模型（如YOLOv5_best_lite.ptl）
    val concentrationModelUsed: String?,  // 使用的浓度预测模型
    val status: String,                   // 状态（如Completed, Failed, Processing）
    val errorMessage: String?,            // 错误信息（如果失败）
    val confThreshold: Float?,            // 置信度阈值
    val iouThreshold: Float?,             // IoU阈值
    val wellsDetected: Int?               // 检测到的孔位数量
) 
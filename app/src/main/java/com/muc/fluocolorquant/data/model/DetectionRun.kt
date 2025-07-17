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
    val detectionModelUsed: String?,      // 使用的孔位检测模型（如YOLOv5_best_lite.ptl）
    
    /**
     * 浓度预测模型使用信息，格式为JSON
     * 
     * 曲线拟合方式记录格式示例:
     * {
     *   "analyteId1": {"type": "curve_fit", "modelId": "curve_model_id1"},
     *   "analyteId2": {"type": "curve_fit", "modelId": "curve_model_id2"}
     * }
     * 
     * 深度学习方式记录格式示例:
     * {
     *   "analyteId1": {"type": "dl_model", "modelName": "concentration.ptl"},
     *   "analyteId2": {"type": "dl_model", "modelName": "improved_concentration_model.ptl"}
     * }
     */
    val concentrationModelUsed: String?,  
    
    /**
     * 运行状态
     * - Processing: 检测运行创建初期，或孔位处理开始时
     * - Completed: 所有分析物的浓度成功计算完毕后
     * - Failed: 流程中任一步骤发生不可恢复错误时
     */
    val status: String,                   
    val errorMessage: String?,            // 错误信息（如果失败）
    val confThreshold: Float?,            // 置信度阈值
    val iouThreshold: Float?,             // IoU阈值
    val wellsDetected: Int?               // 检测到的孔位数量
) 
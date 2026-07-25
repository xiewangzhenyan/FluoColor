package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 检测运行之后追加的预测精度验证记录。
 *
 * 记录采用修订式保存，用户修改参考浓度不会覆盖旧验证，也不会修改冻结的检测浓度。
 * 点位和指标保存为版本化JSON，因为页面总是按一次完整验证读取，不需要逐点跨运行查询。
 */
@Entity(
    tableName = "result_validation_records",
    foreignKeys = [
        ForeignKey(
            entity = DetectionRun::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("runId"),
        Index(value = ["runId", "analyteId", "revision"], unique = true)
    ]
)
data class ResultValidationRecord(
    @PrimaryKey val validationId: String,
    val runId: String,
    val analyteId: String,
    val revision: Int,
    val concentrationUnit: String,
    val validationPointsJson: String,
    val regressionResultJson: String,
    val blandAltmanResultJson: String,
    val processorVersion: String,
    val inputFingerprint: String,
    val createdAt: Long
)

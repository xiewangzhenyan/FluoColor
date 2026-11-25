package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 光谱模式的单通道历史结果。
 *
 * [processingConfigJson]、[processorVersion] 与 [lightSourceSnapshot] 是生成该历史结果时
 * 冻结的解释上下文。处理配置决定结果重建方式；光源只记录采集条件，绝不参与自动校正。
 * 这些字段可空仅用于兼容旧数据库，读取旧结果时不能从当前设置或项目字段反向补写。
 */
@Entity(
    tableName = "spectrum_results",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Analyte::class,
            parentColumns = ["id"],
            childColumns = ["analyteId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("projectId"),
        Index("analyteId"),
        Index(value = ["projectId", "columnIndex"])
    ]
)
data class SpectrumResult(
    @PrimaryKey(autoGenerate = true)
    val resultId: Long = 0,
    val projectId: String,
    val columnIndex: Int,
    val analyteId: String?,
    val imagePath: String,
    val wavelengths: String,   // JSON-encoded List<Float>
    val intensities: String,   // JSON-encoded List<Float>
    val peakWavelength: Float?,
    val processingConfigJson: String? = null,
    val processorVersion: String? = null,
    val lightSourceSnapshot: String? = null
)

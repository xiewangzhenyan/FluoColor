package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.muc.fluocolorquant.data.enums.SpectrumCalibrationType

/**
 * Stores geometry and calibration parameters for each spectrum column.
 *
 * AUTO_IMAGE 模式下，coefficients 表示“归一化纵坐标 -> 波长”的拟合系数；
 * MANUAL_POINT 模式下，coefficients 表示“原图绝对像素 y -> 波长”的拟合系数。
 */
@Entity(
    tableName = "spectrum_calibrations",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("projectId"),
        Index(value = ["projectId", "columnIndex"], unique = true)
    ]
)
data class SpectrumCalibration(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectId: String,
    val columnIndex: Int,
    val roiRect: String,
    val calibrationType: SpectrumCalibrationType,
    val coefficients: String,
    val referencePoints: String? = null
)

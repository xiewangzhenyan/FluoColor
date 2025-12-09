package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Spectrum analysis result replacing well-level results for spectrum mode.
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
    val peakWavelength: Float?
)

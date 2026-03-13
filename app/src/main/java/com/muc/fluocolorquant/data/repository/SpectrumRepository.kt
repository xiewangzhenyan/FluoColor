package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.model.SpectrumCalibration
import com.muc.fluocolorquant.data.model.SpectrumResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for spectrum calibration and results.
 */
interface SpectrumRepository {
    suspend fun insertCalibration(calibration: SpectrumCalibration): Long

    fun getCalibration(projectId: String, columnIndex: Int): Flow<SpectrumCalibration?>

    suspend fun insertResult(result: SpectrumResult): Long

    suspend fun updateResult(result: SpectrumResult)

    fun getResultsByProject(projectId: String): Flow<List<SpectrumResult>>
    
    suspend fun getResultById(resultId: Long): SpectrumResult?

    suspend fun deleteResult(resultId: Long)
}

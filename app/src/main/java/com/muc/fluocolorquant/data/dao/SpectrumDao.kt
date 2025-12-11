package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import com.muc.fluocolorquant.data.model.SpectrumCalibration
import com.muc.fluocolorquant.data.model.SpectrumResult

/**
 * DAO for spectrum calibration data and spectrum analysis results.
 */
@Dao
interface SpectrumDao {
    // Spectrum calibration CRUD
    @Query("SELECT * FROM spectrum_calibrations WHERE projectId = :projectId ORDER BY columnIndex")
    fun getCalibrationsByProject(projectId: String): Flow<List<SpectrumCalibration>>

    @Query("SELECT * FROM spectrum_calibrations WHERE projectId = :projectId AND columnIndex = :columnIndex LIMIT 1")
    fun getCalibration(projectId: String, columnIndex: Int): Flow<SpectrumCalibration?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalibration(calibration: SpectrumCalibration): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalibrations(calibrations: List<SpectrumCalibration>)

    @Update
    suspend fun updateCalibration(calibration: SpectrumCalibration)

    @Query("DELETE FROM spectrum_calibrations WHERE projectId = :projectId")
    suspend fun deleteCalibrationsByProject(projectId: String)

    // Spectrum results CRUD
    @Query("SELECT * FROM spectrum_results WHERE projectId = :projectId ORDER BY columnIndex, resultId")
    fun getResultsByProject(projectId: String): Flow<List<SpectrumResult>>

    @Query("SELECT * FROM spectrum_results WHERE projectId = :projectId AND columnIndex = :columnIndex ORDER BY resultId")
    fun getResultsByColumn(projectId: String, columnIndex: Int): Flow<List<SpectrumResult>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResult(result: SpectrumResult): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResults(results: List<SpectrumResult>)

    @Update
    suspend fun updateResult(result: SpectrumResult)

    @Query("DELETE FROM spectrum_results WHERE projectId = :projectId")
    suspend fun deleteResultsByProject(projectId: String)
    
    @Query("SELECT * FROM spectrum_results WHERE resultId = :resultId LIMIT 1")
    suspend fun getResultById(resultId: Long): SpectrumResult?

    @Query("DELETE FROM spectrum_results WHERE resultId = :resultId")
    suspend fun deleteResult(resultId: Long)
}

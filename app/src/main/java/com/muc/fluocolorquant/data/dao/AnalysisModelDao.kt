package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import kotlinx.coroutines.flow.Flow

/** 通用分析模型及其专用定义的数据访问接口。 */
@Dao
interface AnalysisModelDao {
    @Query("SELECT * FROM analysis_models ORDER BY status, updatedAt DESC")
    fun observeAll(): Flow<List<AnalysisModel>>

    @Query("SELECT * FROM analysis_models WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AnalysisModel?

    @Query(
        """
        SELECT * FROM analysis_models
        WHERE analyteId = :analyteId
          AND detectionMode = :detectionMode
          AND inputProtocol = :inputProtocol
          AND status = 'ACTIVE'
        ORDER BY version DESC
        """
    )
    suspend fun findCompatibleCandidates(
        analyteId: String,
        detectionMode: String,
        inputProtocol: String
    ): List<AnalysisModel>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(model: AnalysisModel)

    @Update
    suspend fun update(model: AnalysisModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStandardCurve(definition: StandardCurveDefinition)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeepLearningDefinition(definition: DeepLearningModelDefinition)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCalibrationPoints(points: List<CalibrationPoint>)

    @Query("SELECT * FROM calibration_points WHERE analysisModelId = :analysisModelId ORDER BY concentration, repeatIndex")
    suspend fun getCalibrationPoints(analysisModelId: String): List<CalibrationPoint>
}

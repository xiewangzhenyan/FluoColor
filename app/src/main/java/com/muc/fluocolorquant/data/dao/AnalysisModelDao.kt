package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import kotlinx.coroutines.flow.Flow
import java.util.Date

/** 通用分析模型及其专用定义的数据访问接口。 */
@Dao
interface AnalysisModelDao {
    @Query("SELECT * FROM analysis_models ORDER BY status, updatedAt DESC")
    fun observeAll(): Flow<List<AnalysisModel>>

    @Query("SELECT * FROM analysis_models WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AnalysisModel?

    /** 幂等保存只复用仍可使用的发布/草稿资源；删除或归档后允许用户重新创建。 */
    @Query(
        """
        SELECT * FROM analysis_models
        WHERE contentFingerprint = :fingerprint
          AND status IN ('PUBLISHED', 'DRAFT')
        ORDER BY CASE status WHEN 'PUBLISHED' THEN 0 ELSE 1 END, updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun getReusableByContentFingerprint(fingerprint: String): AnalysisModel?

    @Query(
        """
        SELECT * FROM analysis_models
        WHERE analyteId = :analyteId
          AND detectionMode = :detectionMode
          AND inputProtocol = :inputProtocol
          AND status = 'PUBLISHED'
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

    @Query("SELECT * FROM standard_curve_definitions WHERE analysisModelId = :analysisModelId LIMIT 1")
    suspend fun getStandardCurveDefinition(analysisModelId: String): StandardCurveDefinition?

    @Query("SELECT * FROM deep_learning_model_definitions WHERE analysisModelId = :analysisModelId LIMIT 1")
    suspend fun getDeepLearningDefinition(analysisModelId: String): DeepLearningModelDefinition?

    @Query("DELETE FROM standard_curve_definitions WHERE analysisModelId = :analysisModelId")
    suspend fun deleteStandardCurveDefinition(analysisModelId: String)

    @Query("DELETE FROM deep_learning_model_definitions WHERE analysisModelId = :analysisModelId")
    suspend fun deleteDeepLearningDefinition(analysisModelId: String)

    @Query("DELETE FROM calibration_points WHERE analysisModelId = :analysisModelId")
    suspend fun deleteCalibrationPoints(analysisModelId: String)

    @Query("DELETE FROM analysis_models WHERE id = :id")
    suspend fun deleteModelById(id: String)

    @Query("SELECT MAX(version) FROM analysis_models WHERE name = :name")
    suspend fun getLatestVersionByName(name: String): Int?

    @Query("UPDATE analysis_models SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Date)

    @Query(
        """
        UPDATE analysis_models
        SET status = 'ARCHIVED', updatedAt = :updatedAt
        WHERE name = :name
          AND status = 'PUBLISHED'
          AND id != :exceptId
        """
    )
    suspend fun archivePublishedSiblings(name: String, exceptId: String, updatedAt: Date)

    /**
     * 原子写入模型主档与类型专用定义。
     *
     * 标准曲线与智能模型互斥，Repository 在调用前会统一模型 ID；事务保证不会出现
     * 主档已经保存但专用定义或重复标定点缺失的半成品记录。
     */
    @Transaction
    suspend fun insertBundle(
        model: AnalysisModel,
        standardCurve: StandardCurveDefinition?,
        deepLearning: DeepLearningModelDefinition?,
        calibrationPoints: List<CalibrationPoint>
    ) {
        insert(model)
        standardCurve?.let { upsertStandardCurve(it) }
        deepLearning?.let { upsertDeepLearningDefinition(it) }
        if (calibrationPoints.isNotEmpty()) {
            insertCalibrationPoints(calibrationPoints)
        }
    }

    /** 草稿更新会先清理旧专用定义，再写入当前模型类型对应的数据。 */
    @Transaction
    suspend fun replaceDraftBundle(
        model: AnalysisModel,
        standardCurve: StandardCurveDefinition?,
        deepLearning: DeepLearningModelDefinition?,
        calibrationPoints: List<CalibrationPoint>
    ) {
        update(model)
        deleteStandardCurveDefinition(model.id)
        deleteDeepLearningDefinition(model.id)
        deleteCalibrationPoints(model.id)
        standardCurve?.let { upsertStandardCurve(it) }
        deepLearning?.let { upsertDeepLearningDefinition(it) }
        if (calibrationPoints.isNotEmpty()) {
            insertCalibrationPoints(calibrationPoints)
        }
    }

    /**
     * 普通资源库的“编辑”会直接更新当前曲线，而不是制造一个用户看不懂的新版本。
     *
     * 已经创建的项目保存的是完整冻结快照，因此这里更新资源库主档不会篡改历史项目；
     * 子表仍在同一事务中整体替换，避免拟合参数与标定点处于不同编辑批次。
     */
    @Transaction
    suspend fun replaceBundle(
        model: AnalysisModel,
        standardCurve: StandardCurveDefinition?,
        deepLearning: DeepLearningModelDefinition?,
        calibrationPoints: List<CalibrationPoint>
    ) {
        update(model)
        deleteStandardCurveDefinition(model.id)
        deleteDeepLearningDefinition(model.id)
        deleteCalibrationPoints(model.id)
        standardCurve?.let { upsertStandardCurve(it) }
        deepLearning?.let { upsertDeepLearningDefinition(it) }
        if (calibrationPoints.isNotEmpty()) {
            insertCalibrationPoints(calibrationPoints)
        }
    }

    /** 发布新版本时才归档同名旧发布版本，整个切换过程保持原子性。 */
    @Transaction
    suspend fun publishVersion(id: String, name: String, updatedAt: Date) {
        archivePublishedSiblings(name = name, exceptId = id, updatedAt = updatedAt)
        updateStatus(id = id, status = "PUBLISHED", updatedAt = updatedAt)
    }
}

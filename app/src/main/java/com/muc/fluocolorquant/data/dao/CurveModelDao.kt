package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.CurveModel
import kotlinx.coroutines.flow.Flow

/**
 * 曲线模型数据访问对象
 */
@Dao
interface CurveModelDao {
    
    /**
     * 获取所有曲线模型
     */
    @Query("SELECT * FROM curve_models ORDER BY updatedAt DESC")
    fun getAllCurveModels(): Flow<List<CurveModel>>
    
    /**
     * 获取所有曲线模型(按时间倒序)
     */
    @Query("SELECT * FROM curve_models ORDER BY updatedAt DESC")
    fun getAllCurveModelsByTimeDesc(): Flow<List<CurveModel>>
    
    /**
     * 根据ID获取曲线模型
     */
    @Query("SELECT * FROM curve_models WHERE id = :id")
    suspend fun getCurveModelById(id: String): CurveModel?
    
    /**
     * 根据像素类型获取曲线模型
     */
    @Query("SELECT * FROM curve_models WHERE pixelType = :pixelType")
    fun getCurveModelsByPixelType(pixelType: String): Flow<List<CurveModel>>
    
    /**
     * 插入新的曲线模型
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCurveModel(curveModel: CurveModel)
    
    /**
     * 更新曲线模型
     */
    @Update
    suspend fun updateCurveModel(curveModel: CurveModel)
    
    /**
     * 删除曲线模型
     */
    @Delete
    suspend fun deleteCurveModel(curveModel: CurveModel)
    
    /**
     * 根据ID删除曲线模型
     */
    @Query("DELETE FROM curve_models WHERE id = :id")
    suspend fun deleteCurveModelById(id: String)
} 
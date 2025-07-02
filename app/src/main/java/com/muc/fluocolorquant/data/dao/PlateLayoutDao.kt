package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.PlateLayout
import kotlinx.coroutines.flow.Flow

/**
 * 孔板布局DAO接口
 * 提供对孔板布局表的增删改查操作
 */
@Dao
interface PlateLayoutDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlateLayout(plateLayout: PlateLayout): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlateLayouts(plateLayouts: List<PlateLayout>): List<Long>

    @Update
    suspend fun updatePlateLayout(plateLayout: PlateLayout)

    @Delete
    suspend fun deletePlateLayout(plateLayout: PlateLayout)

    @Query("SELECT * FROM plate_layouts WHERE id = :id")
    suspend fun getPlateLayoutById(id: Long): PlateLayout?

    @Query("SELECT * FROM plate_layouts WHERE projectId = :projectId")
    fun getPlateLayoutsByProjectId(projectId: String): Flow<List<PlateLayout>>

    @Query("SELECT * FROM plate_layouts WHERE projectId = :projectId AND wellIndex = :wellIndex")
    suspend fun getPlateLayoutByWellIndex(projectId: String, wellIndex: Int): PlateLayout?

    @Query("SELECT * FROM plate_layouts WHERE projectId = :projectId AND analyteId = :analyteId")
    fun getPlateLayoutsByAnalyteId(projectId: String, analyteId: String): Flow<List<PlateLayout>>

    @Query("SELECT * FROM plate_layouts WHERE projectId = :projectId AND roleType = :roleType")
    fun getPlateLayoutsByRoleType(projectId: String, roleType: String): Flow<List<PlateLayout>>

    @Query("DELETE FROM plate_layouts WHERE projectId = :projectId")
    suspend fun deleteAllLayoutsForProject(projectId: String)
} 
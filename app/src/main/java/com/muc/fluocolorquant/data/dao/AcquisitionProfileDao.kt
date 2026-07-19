package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import kotlinx.coroutines.flow.Flow

/** 采集设备档案的数据访问接口。 */
@Dao
interface AcquisitionProfileDao {
    @Query("SELECT * FROM acquisition_profiles ORDER BY status, name, version DESC")
    fun observeAll(): Flow<List<AcquisitionProfile>>

    @Query("SELECT * FROM acquisition_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AcquisitionProfile?

    @Query("SELECT * FROM acquisition_profiles WHERE status = 'ACTIVE' ORDER BY name, version DESC")
    fun observeActive(): Flow<List<AcquisitionProfile>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: AcquisitionProfile)

    @Update
    suspend fun update(profile: AcquisitionProfile)
}

package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.CarrierProfile
import kotlinx.coroutines.flow.Flow

/** 载体档案的数据访问接口。 */
@Dao
interface CarrierProfileDao {
    @Query("SELECT * FROM carrier_profiles ORDER BY status, name, version DESC")
    fun observeAll(): Flow<List<CarrierProfile>>

    @Query("SELECT * FROM carrier_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CarrierProfile?

    @Query("SELECT * FROM carrier_profiles WHERE status = 'ACTIVE' ORDER BY name, version DESC")
    fun observeActive(): Flow<List<CarrierProfile>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: CarrierProfile)

    @Update
    suspend fun update(profile: CarrierProfile)
}

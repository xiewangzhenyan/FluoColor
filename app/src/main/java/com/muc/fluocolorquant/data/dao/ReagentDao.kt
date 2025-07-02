package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.Reagent
import kotlinx.coroutines.flow.Flow

/**
 * 试剂DAO接口
 * 提供对试剂表的增删改查操作
 */
@Dao
interface ReagentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReagent(reagent: Reagent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReagents(reagents: List<Reagent>): List<Long>

    @Update
    suspend fun updateReagent(reagent: Reagent)

    @Delete
    suspend fun deleteReagent(reagent: Reagent)

    @Query("SELECT * FROM reagents WHERE id = :id")
    suspend fun getReagentById(id: String): Reagent?

    @Query("SELECT * FROM reagents WHERE analyteId = :analyteId")
    fun getReagentsByAnalyteId(analyteId: String): Flow<List<Reagent>>

    @Query("SELECT * FROM reagents ORDER BY reagentName ASC")
    fun getAllReagents(): Flow<List<Reagent>>
} 
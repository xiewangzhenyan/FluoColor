package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.Analyte
import kotlinx.coroutines.flow.Flow

/**
 * 分析物DAO接口
 * 提供对分析物表的增删改查操作
 */
@Dao
interface AnalyteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalyte(analyte: Analyte): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalytes(analytes: List<Analyte>): List<Long>

    @Update
    suspend fun updateAnalyte(analyte: Analyte)

    @Delete
    suspend fun deleteAnalyte(analyte: Analyte)

    @Query("SELECT * FROM analytes WHERE id = :id")
    suspend fun getAnalyteById(id: String): Analyte?

    @Query("SELECT * FROM analytes WHERE name = :name")
    suspend fun getAnalyteByName(name: String): Analyte?

    @Query("SELECT * FROM analytes ORDER BY name ASC")
    fun getAllAnalytes(): Flow<List<Analyte>>
} 
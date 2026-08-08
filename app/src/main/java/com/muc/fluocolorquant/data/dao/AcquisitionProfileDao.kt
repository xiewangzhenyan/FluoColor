package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import kotlinx.coroutines.flow.Flow
import java.util.Date
import java.util.UUID

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

    /** 查询同名采集设备档案已经使用的最高版本号。 */
    @Query("SELECT MAX(version) FROM acquisition_profiles WHERE name = :name")
    suspend fun getLatestVersionByName(name: String): Int?

    /** 将设备档案归档，历史检测仍可按旧 ID 回读完整配置。 */
    @Query("UPDATE acquisition_profiles SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /** 原子归档旧设备档案并插入具备新 ID 的下一版本。 */
    @Transaction
    suspend fun archiveAndInsertNextVersion(
        previousId: String,
        replacement: AcquisitionProfile
    ): AcquisitionProfile {
        val now = Date()
        val nextVersion = (getLatestVersionByName(replacement.name) ?: 0) + 1
        val nextProfile = replacement.copy(
            id = UUID.randomUUID().toString(),
            status = ResourceStatus.ACTIVE.code,
            version = nextVersion,
            createdAt = now,
            updatedAt = now
        )
        updateStatus(previousId, ResourceStatus.ARCHIVED.code)
        insert(nextProfile)
        return nextProfile
    }
}

package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.model.CarrierProfile
import kotlinx.coroutines.flow.Flow
import java.util.Date
import java.util.UUID

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

    /** 查询同名载体已经使用的最高版本号。 */
    @Query("SELECT MAX(version) FROM carrier_profiles WHERE name = :name")
    suspend fun getLatestVersionByName(name: String): Int?

    /**
     * 仅更新生命周期状态，不提供物理删除。
     *
     * 已被实验模板或历史项目引用的载体必须继续可读，因此归档是唯一删除语义。
     */
    @Query("UPDATE carrier_profiles SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /**
     * 原子执行“归档旧版本 + 插入新版本”。
     *
     * 新版本使用新的主键和创建时间；即使科学参数发生改变，旧项目仍能通过旧 ID
     * 读取当时使用的载体定义，不会被当前编辑操作污染。
     */
    @Transaction
    suspend fun archiveAndInsertNextVersion(
        previousId: String,
        replacement: CarrierProfile
    ): CarrierProfile {
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

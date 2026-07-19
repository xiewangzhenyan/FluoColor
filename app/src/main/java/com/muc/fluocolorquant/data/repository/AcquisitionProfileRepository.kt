package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.model.AcquisitionProfile
import kotlinx.coroutines.flow.Flow

/** 采集设备档案仓库，提供版本化保存而不是原地覆盖。 */
interface AcquisitionProfileRepository {
    fun observeAll(): Flow<List<AcquisitionProfile>>
    suspend fun getById(id: String): AcquisitionProfile?
    suspend fun create(profile: AcquisitionProfile)
    suspend fun createNextVersion(
        previousId: String,
        replacement: AcquisitionProfile
    ): AcquisitionProfile
    suspend fun archive(id: String)
}

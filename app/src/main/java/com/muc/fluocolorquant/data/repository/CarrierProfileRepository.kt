package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.model.CarrierProfile
import kotlinx.coroutines.flow.Flow

/**
 * 载体档案仓库。
 *
 * 对上层隐藏 Room 细节，并通过显式的“创建下一版本”和“归档”方法阻止界面直接
 * 覆盖或删除历史科学资源。
 */
interface CarrierProfileRepository {
    fun observeAll(): Flow<List<CarrierProfile>>
    suspend fun getById(id: String): CarrierProfile?
    suspend fun create(profile: CarrierProfile)
    suspend fun createNextVersion(previousId: String, replacement: CarrierProfile): CarrierProfile
    suspend fun archive(id: String)
}

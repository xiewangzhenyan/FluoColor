package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.CarrierProfileDao
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.model.CarrierProfile
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Room 载体档案仓库实现。 */
@Singleton
class CarrierProfileRepositoryImpl @Inject constructor(
    private val carrierProfileDao: CarrierProfileDao
) : CarrierProfileRepository {
    override fun observeAll(): Flow<List<CarrierProfile>> = carrierProfileDao.observeAll()

    override suspend fun getById(id: String): CarrierProfile? = carrierProfileDao.getById(id)

    override suspend fun create(profile: CarrierProfile) {
        carrierProfileDao.insert(profile)
    }

    override suspend fun createNextVersion(
        previousId: String,
        replacement: CarrierProfile
    ): CarrierProfile {
        return carrierProfileDao.archiveAndInsertNextVersion(previousId, replacement)
    }

    override suspend fun archive(id: String) {
        carrierProfileDao.updateStatus(id, ResourceStatus.ARCHIVED.code)
    }
}

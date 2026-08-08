package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.AcquisitionProfileDao
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Room 采集设备档案仓库实现。 */
@Singleton
class AcquisitionProfileRepositoryImpl @Inject constructor(
    private val acquisitionProfileDao: AcquisitionProfileDao
) : AcquisitionProfileRepository {
    override fun observeAll(): Flow<List<AcquisitionProfile>> = acquisitionProfileDao.observeAll()

    override suspend fun getById(id: String): AcquisitionProfile? = acquisitionProfileDao.getById(id)

    override suspend fun create(profile: AcquisitionProfile) {
        acquisitionProfileDao.insert(profile)
    }

    override suspend fun createNextVersion(
        previousId: String,
        replacement: AcquisitionProfile
    ): AcquisitionProfile {
        return acquisitionProfileDao.archiveAndInsertNextVersion(previousId, replacement)
    }

    override suspend fun archive(id: String) {
        acquisitionProfileDao.updateStatus(id, ResourceStatus.ARCHIVED.code)
    }
}

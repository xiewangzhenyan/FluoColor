package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.SpectrumDao
import com.muc.fluocolorquant.data.model.SpectrumCalibration
import com.muc.fluocolorquant.data.model.SpectrumResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpectrumRepositoryImpl @Inject constructor(
    private val spectrumDao: SpectrumDao
) : SpectrumRepository {

    override suspend fun insertCalibration(calibration: SpectrumCalibration): Long {
        return spectrumDao.insertCalibration(calibration)
    }

    override fun getCalibration(projectId: String, columnIndex: Int): Flow<SpectrumCalibration?> {
        return spectrumDao.getCalibration(projectId, columnIndex)
    }

    override suspend fun insertResult(result: SpectrumResult): Long {
        return spectrumDao.insertResult(result)
    }

    override fun getResultsByProject(projectId: String): Flow<List<SpectrumResult>> {
        return spectrumDao.getResultsByProject(projectId)
    }

    override suspend fun deleteResult(resultId: Long) {
        spectrumDao.deleteResult(resultId)
    }
}

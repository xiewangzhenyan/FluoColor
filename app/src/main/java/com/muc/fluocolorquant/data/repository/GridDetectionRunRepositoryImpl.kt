package com.muc.fluocolorquant.data.repository

import androidx.room.withTransaction
import com.muc.fluocolorquant.data.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用 Room 数据库级事务保存新检测运行。
 *
 * 不能分别调用三个 DAO 后假设它们“基本都会成功”：科研结果必须保证运行主档、原始
 * 终点附件和全部位点信号同时存在。任一外键、主键或磁盘错误都会让整个事务回滚。
 */
@Singleton
class GridDetectionRunRepositoryImpl @Inject constructor(
    private val database: AppDatabase
) : GridDetectionRunRepository {

    override suspend fun save(bundle: GridDetectionPersistenceBundle) {
        bundle.requireValid()
        database.withTransaction {
            database.detectionRunDao().insertDetectionRun(bundle.run)
            database.captureArtifactDao().insert(bundle.endpointArtifact)
            if (bundle.diagnosticArtifacts.isNotEmpty()) {
                database.captureArtifactDao().insertAll(bundle.diagnosticArtifacts)
            }
            if (bundle.measurements.isNotEmpty()) {
                database.siteMeasurementDao().insertAll(bundle.measurements)
            }
        }
    }
}

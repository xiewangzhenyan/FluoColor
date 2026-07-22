package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.AnalysisModelDao
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import kotlinx.coroutines.flow.Flow
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Room 统一分析模型仓库实现。 */
@Singleton
class AnalysisModelRepositoryImpl @Inject constructor(
    private val analysisModelDao: AnalysisModelDao
) : AnalysisModelRepository {

    override fun observeAll(): Flow<List<AnalysisModel>> = analysisModelDao.observeAll()

    override suspend fun getBundle(id: String): AnalysisModelBundle? {
        val model = analysisModelDao.getById(id) ?: return null
        return AnalysisModelBundle(
            model = model,
            standardCurve = analysisModelDao.getStandardCurveDefinition(id),
            deepLearning = analysisModelDao.getDeepLearningDefinition(id),
            calibrationPoints = analysisModelDao.getCalibrationPoints(id)
        )
    }

    override suspend fun createDraft(bundle: AnalysisModelBundle): AnalysisModelBundle {
        val normalizedName = bundle.model.name.trim()
        require(normalizedName.isNotEmpty()) { "分析模型名称不能为空" }

        val now = Date()
        val modelId = UUID.randomUUID().toString()
        val nextVersion = (analysisModelDao.getLatestVersionByName(normalizedName) ?: 0) + 1
        val model = bundle.model.copy(
            id = modelId,
            name = normalizedName,
            status = AnalysisModelLifecycleStatus.DRAFT.code,
            version = nextVersion,
            createdAt = now,
            updatedAt = now
        )
        val normalized = bundle.withModelIdentity(model)

        analysisModelDao.insertBundle(
            model = normalized.model,
            standardCurve = normalized.standardCurve,
            deepLearning = normalized.deepLearning,
            calibrationPoints = normalized.calibrationPoints
        )
        return normalized
    }

    override suspend fun updateDraft(bundle: AnalysisModelBundle) {
        val current = analysisModelDao.getById(bundle.model.id)
            ?: throw IllegalArgumentException("分析模型不存在")
        check(current.status == AnalysisModelLifecycleStatus.DRAFT.code) {
            "只有草稿模型允许原地更新"
        }

        val now = Date()
        val model = bundle.model.copy(
            id = current.id,
            status = current.status,
            version = current.version,
            createdAt = current.createdAt,
            updatedAt = now
        )
        val normalized = bundle.withModelIdentity(model, regeneratePointIds = false)
        analysisModelDao.replaceDraftBundle(
            model = normalized.model,
            standardCurve = normalized.standardCurve,
            deepLearning = normalized.deepLearning,
            calibrationPoints = normalized.calibrationPoints
        )
    }

    /**
     * 直接编辑现有资源，保留模型 ID、创建时间和当前发布状态。
     *
     * 项目创建时会复制完整 [AnalysisModelBundle] 到项目快照，所以资源库后续编辑只影响
     * 未来项目；历史检测结果仍继续使用当时冻结的拟合函数、参数与原始标定点。
     */
    override suspend fun replace(bundle: AnalysisModelBundle) {
        val current = analysisModelDao.getById(bundle.model.id)
            ?: throw IllegalArgumentException("分析模型不存在")
        val normalizedName = bundle.model.name.trim()
        require(normalizedName.isNotEmpty()) { "分析模型名称不能为空" }

        val model = bundle.model.copy(
            id = current.id,
            name = normalizedName,
            status = current.status,
            version = current.version,
            createdAt = current.createdAt,
            updatedAt = Date()
        )
        val normalized = bundle.withModelIdentity(model)
        analysisModelDao.replaceBundle(
            model = normalized.model,
            standardCurve = normalized.standardCurve,
            deepLearning = normalized.deepLearning,
            calibrationPoints = normalized.calibrationPoints
        )
    }

    /** 删除资源库中的模型；Room 外键会同步清理子定义并把模板引用安全置空。 */
    override suspend fun delete(id: String) {
        val current = analysisModelDao.getById(id)
            ?: throw IllegalArgumentException("分析模型不存在")
        analysisModelDao.deleteModelById(current.id)
    }

    override suspend fun createNextDraft(previousId: String): AnalysisModelBundle {
        val previous = getBundle(previousId)
            ?: throw IllegalArgumentException("源分析模型不存在")
        return createDraft(previous)
    }

    override suspend fun publish(id: String) {
        val model = analysisModelDao.getById(id)
            ?: throw IllegalArgumentException("分析模型不存在")
        if (model.status == AnalysisModelLifecycleStatus.PUBLISHED.code) return
        check(model.status == AnalysisModelLifecycleStatus.DRAFT.code) {
            "只有草稿模型可以发布"
        }
        analysisModelDao.publishVersion(id = model.id, name = model.name, updatedAt = Date())
    }

    override suspend fun archive(id: String) {
        val model = analysisModelDao.getById(id)
            ?: throw IllegalArgumentException("分析模型不存在")
        if (model.status == AnalysisModelLifecycleStatus.ARCHIVED.code) return
        analysisModelDao.updateStatus(
            id = id,
            status = AnalysisModelLifecycleStatus.ARCHIVED.code,
            updatedAt = Date()
        )
    }

    /**
     * 将子表外键统一到新的模型 ID。
     *
     * 创建下一版本时重复点也必须生成新主键，否则 Room 会因复用历史点 ID 而拒绝插入；
     * 更新同一草稿时则保留点 ID，便于后续审计同一草稿的编辑过程。
     */
    private fun AnalysisModelBundle.withModelIdentity(
        model: AnalysisModel,
        regeneratePointIds: Boolean = true
    ): AnalysisModelBundle {
        return AnalysisModelBundle(
            model = model,
            standardCurve = standardCurve?.copy(analysisModelId = model.id),
            deepLearning = deepLearning?.copy(analysisModelId = model.id),
            calibrationPoints = calibrationPoints.map { point ->
                point.copy(
                    id = if (regeneratePointIds) UUID.randomUUID().toString() else point.id,
                    analysisModelId = model.id
                )
            }
        )
    }
}

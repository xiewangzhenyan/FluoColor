package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import kotlinx.coroutines.flow.Flow

/** 分析模型主档及类型专用定义的原子数据包。 */
data class AnalysisModelBundle(
    val model: AnalysisModel,
    val standardCurve: StandardCurveDefinition? = null,
    val deepLearning: DeepLearningModelDefinition? = null,
    val calibrationPoints: List<CalibrationPoint> = emptyList()
)

/**
 * 统一分析模型仓库。
 *
 * 仓库明确区分“更新草稿”和“创建下一版本”，从接口层阻止页面覆盖已发布的科学模型。
 */
interface AnalysisModelRepository {
    fun observeAll(): Flow<List<AnalysisModel>>
    suspend fun getBundle(id: String): AnalysisModelBundle?
    suspend fun getReusableBundleByContentFingerprint(fingerprint: String): AnalysisModelBundle?
    suspend fun createDraft(bundle: AnalysisModelBundle): AnalysisModelBundle
    suspend fun updateDraft(bundle: AnalysisModelBundle)
    suspend fun replace(bundle: AnalysisModelBundle)
    suspend fun delete(id: String)
    suspend fun createNextDraft(previousId: String): AnalysisModelBundle
    suspend fun publish(id: String)
    suspend fun archive(id: String)
}

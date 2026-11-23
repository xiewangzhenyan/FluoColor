package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.AnalyteDao
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.TumorBiomarkerPresets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分析物仓库接口
 * 定义分析物相关的数据操作
 */
interface AnalyteRepository {
    /**
     * 获取所有分析物
     * @return 分析物列表流
     */
    fun getAllAnalytes(): Flow<List<Analyte>>
    
    /**
     * 根据ID获取分析物
     * @param id 分析物ID
     * @return 分析物实体，如果不存在则返回null
     */
    suspend fun getAnalyteById(id: String): Analyte?
    
    /**
     * 根据名称获取分析物
     * @param name 分析物名称
     * @return 分析物实体，如果不存在则返回null
     */
    suspend fun getAnalyteByName(name: String): Analyte?
    
    /**
     * 添加分析物
     * @param name 分析物名称
     * @return 如果添加成功返回true，否则返回false
     */
    suspend fun addAnalyte(name: String): Boolean

    /** 新增完整标志物预设档案；默认转发兼容测试桩。 */
    suspend fun addAnalyteProfile(
        name: String,
        defaultUnit: String? = null,
        defaultMaxConcentration: Double? = null,
        defaultFluorescenceChannel: String? = null,
        referenceCutoff: Double? = null
    ): Boolean = addAnalyte(name)

    /**
     * 更新分析物
     * @param id 分析物ID
     * @param name 新的分析物名称
     * @return 如果更新成功返回true，否则返回false
     */
    suspend fun updateAnalyte(id: String, name: String): Boolean

    /** 更新分析物名称及预设参数；默认转发兼容测试桩。 */
    suspend fun updateAnalyteProfile(
        id: String,
        name: String,
        defaultUnit: String? = null,
        defaultMaxConcentration: Double? = null,
        defaultFluorescenceChannel: String? = null,
        referenceCutoff: Double? = null
    ): Boolean = updateAnalyte(id, name)

    /** 为尚未配置预设的内置常见肿瘤标志物一键补齐推荐单位、量程与参考阈值，返回更新条数。 */
    suspend fun applyStandardPresetsToUnconfigured(): Int = 0
    
    /**
     * 删除分析物
     * @param analyte 要删除的分析物实体
     */
    suspend fun deleteAnalyte(analyte: Analyte)
    
    /**
     * 删除分析物
     * @param id 要删除的分析物ID
     */
    suspend fun deleteAnalyte(id: String)
}

/**
 * 分析物仓库实现类
 * @param analyteDao 分析物数据访问对象
 */
@Singleton
class AnalyteRepositoryImpl @Inject constructor(
    private val analyteDao: AnalyteDao
) : AnalyteRepository {
    
    override fun getAllAnalytes(): Flow<List<Analyte>> {
        return analyteDao.getAllAnalytes()
    }
    
    override suspend fun getAnalyteById(id: String): Analyte? {
        return analyteDao.getAnalyteById(id)
    }
    
    override suspend fun getAnalyteByName(name: String): Analyte? {
        return analyteDao.getAnalyteByName(name)
    }
    
    override suspend fun addAnalyte(name: String): Boolean {
        val preset = TumorBiomarkerPresets.findByName(name)
        return addAnalyteProfile(
            name = name,
            defaultUnit = preset?.unit,
            defaultMaxConcentration = preset?.maxConcentration,
            defaultFluorescenceChannel = preset?.fluorescenceChannel ?: "GREEN",
            referenceCutoff = preset?.referenceCutoff
        )
    }

    override suspend fun addAnalyteProfile(
        name: String,
        defaultUnit: String?,
        defaultMaxConcentration: Double?,
        defaultFluorescenceChannel: String?,
        referenceCutoff: Double?
    ): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return false
        if (getAnalyteByName(trimmedName) != null) return false

        val analyte = Analyte(
            id = "analyte_" + UUID.randomUUID().toString(),
            name = trimmedName,
            defaultUnit = defaultUnit?.trim()?.takeIf(String::isNotEmpty),
            defaultMaxConcentration = defaultMaxConcentration?.takeIf { it.isFinite() && it > 0.0 },
            defaultFluorescenceChannel = defaultFluorescenceChannel?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
            referenceCutoff = referenceCutoff?.takeIf { it.isFinite() && it > 0.0 }
        )
        analyteDao.insertAnalyte(analyte)
        return true
    }

    override suspend fun updateAnalyte(id: String, name: String): Boolean {
        val existing = getAnalyteById(id) ?: return false
        return updateAnalyteProfile(
            id = id,
            name = name,
            defaultUnit = existing.defaultUnit,
            defaultMaxConcentration = existing.defaultMaxConcentration,
            defaultFluorescenceChannel = existing.defaultFluorescenceChannel,
            referenceCutoff = existing.referenceCutoff
        )
    }

    override suspend fun updateAnalyteProfile(
        id: String,
        name: String,
        defaultUnit: String?,
        defaultMaxConcentration: Double?,
        defaultFluorescenceChannel: String?,
        referenceCutoff: Double?
    ): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return false
        val existingByName = getAnalyteByName(trimmedName)
        if (existingByName != null && existingByName.id != id) return false
        val analyte = getAnalyteById(id) ?: return false

        val updatedAnalyte = analyte.copy(
            name = trimmedName,
            defaultUnit = defaultUnit?.trim()?.takeIf(String::isNotEmpty),
            defaultMaxConcentration = defaultMaxConcentration?.takeIf { it.isFinite() && it > 0.0 },
            defaultFluorescenceChannel = defaultFluorescenceChannel?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
            referenceCutoff = referenceCutoff?.takeIf { it.isFinite() && it > 0.0 }
        )
        analyteDao.updateAnalyte(updatedAnalyte)
        return true
    }

    override suspend fun applyStandardPresetsToUnconfigured(): Int {
        var updatedCount = 0
        val current = analyteDao.getAllAnalytes().first()
        current.forEach { analyte ->
            val hasAnyPreset = !analyte.defaultUnit.isNullOrBlank() ||
                analyte.defaultMaxConcentration != null ||
                analyte.referenceCutoff != null
            if (!hasAnyPreset) {
                val preset = TumorBiomarkerPresets.findByName(analyte.name)
                if (preset != null) {
                    analyteDao.updateAnalyte(
                        analyte.copy(
                            defaultUnit = preset.unit,
                            defaultMaxConcentration = preset.maxConcentration,
                            defaultFluorescenceChannel = analyte.defaultFluorescenceChannel ?: preset.fluorescenceChannel,
                            referenceCutoff = preset.referenceCutoff
                        )
                    )
                    updatedCount++
                }
            }
        }
        return updatedCount
    }
    
    override suspend fun deleteAnalyte(analyte: Analyte) {
        analyteDao.deleteAnalyte(analyte)
    }
    
    override suspend fun deleteAnalyte(id: String) {
        val analyte = getAnalyteById(id) ?: return
        deleteAnalyte(analyte)
    }
} 
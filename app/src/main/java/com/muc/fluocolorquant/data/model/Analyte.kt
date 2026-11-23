package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分析物实体类。
 *
 * 作为检测目标的主索引，除唯一名称外，可选保存该标志物的常用浓度单位、常用实验量程上限、
 * 默认荧光发射通道和阳性参考截断值（Cut-off）。新建项目选择分析物时仅读取这些字段做初始预填，
 * 项目创建后立即冻结进运行快照；后续修改本表不会回溯篡改任何历史检测结果。
 */
@Entity(
    tableName = "analytes",
    indices = [Index(value = ["name"], unique = true)] // 确保分析物名称唯一
)
data class Analyte(
    @PrimaryKey
    val id: String,    // 唯一ID, e.g., "analyte_cea"
    val name: String,  // 分析物名称, e.g., "CEA", "NSE"
    val defaultUnit: String? = null,
    val defaultMaxConcentration: Double? = null,
    val defaultFluorescenceChannel: String? = null,
    val referenceCutoff: Double? = null
)

/** 常见肿瘤标志物的实验室与临床参考预设，供新建分析物或一键补齐预设使用。 */
data class TumorBiomarkerPreset(
    val canonicalName: String,
    val unit: String,
    val maxConcentration: Double,
    val fluorescenceChannel: String = "GREEN",
    val referenceCutoff: Double? = null
)

object TumorBiomarkerPresets {
    val all: List<TumorBiomarkerPreset> = listOf(
        TumorBiomarkerPreset("CEA", "ng/mL", 100.0, "GREEN", 5.0),
        TumorBiomarkerPreset("AFP", "ng/mL", 400.0, "GREEN", 20.0),
        TumorBiomarkerPreset("CYFRA21-1", "ng/mL", 50.0, "GREEN", 3.3),
        TumorBiomarkerPreset("NSE", "ng/mL", 100.0, "GREEN", 16.3),
        TumorBiomarkerPreset("CA125", "U/mL", 500.0, "GREEN", 35.0),
        TumorBiomarkerPreset("CA19-9", "U/mL", 500.0, "GREEN", 37.0),
        TumorBiomarkerPreset("ProGRP", "pg/mL", 500.0, "GREEN", 65.0),
        TumorBiomarkerPreset("SCCA", "ng/mL", 50.0, "GREEN", 1.5),
        TumorBiomarkerPreset("PSA", "ng/mL", 50.0, "GREEN", 4.0),
        TumorBiomarkerPreset("TSGF", "U/mL", 200.0, "GREEN", 64.0),
        TumorBiomarkerPreset("p53", "U/mL", 50.0, "GREEN", 1.3)
    )

    fun findByName(name: String): TumorBiomarkerPreset? {
        val normalized = name.trim().replace(" ", "").replace("-", "").uppercase()
        if (normalized.isEmpty()) return null
        return all.firstOrNull { preset ->
            preset.canonicalName.replace(" ", "").replace("-", "").uppercase() == normalized
        }
    }
}

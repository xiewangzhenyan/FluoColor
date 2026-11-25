package com.muc.fluocolorquant.domain.detection.array

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.domain.project.TemplateProjectOverrideCodec
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.domain.project.TemplateSiteKey

/** 每孔一票。信号和浓度各有参与数；仅保存派生摘要，不覆盖单孔原值或标定输入。 */
data class MultiHoleValue(val value: Double?, val unit: String?, val usedWells: Int)

data class MultiHoleGroup(
    val analyteId: String,
    val sampleId: String,
    val repeatGroup: String?,
    val siteIndices: List<Int>,
    val primaryFeature: String?,
    val signal: MultiHoleValue,
    val concentration: MultiHoleValue
)

data class MultiHoleSnapshot(val mode: MultiHoleStatistic, val groups: List<MultiHoleGroup>)

/** 只按实际样本编号及分析物/重复组统计未知样本。绝不按同一行、同一列或等浓度猜测分组。 */
object ArrayMultiHoleStatistics {
    private val gson = Gson()
    const val SNAPSHOT_KEY = "multiHoleStatisticsV1"

    fun freeze(mode: MultiHoleStatistic, snapshot: TemplateProjectSnapshot, overridesJson: String?, measurements: List<SiteMeasurement>): MultiHoleSnapshot? {
        if (mode == MultiHoleStatistic.INDIVIDUAL) return null
        val overrides = overridesJson?.let { runCatching { TemplateProjectOverrideCodec.decode(it) }.getOrNull() }
        val bySite = measurements.associateBy { it.siteIndex to it.analyteId }
        val sites = snapshot.siteAssignments.mapNotNull { assignment ->
            val analyteId = assignment.analyteId ?: return@mapNotNull null
            if (!assignment.enabled || assignment.roleType != TemplateSiteRole.SAMPLE.code) return@mapNotNull null
            val sampleId = (overrides?.sampleSlotMapping?.get(TemplateSiteKey.format(assignment.rowIndex, assignment.columnIndex))
                ?: assignment.defaultSampleSlot)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val siteIndex = assignment.rowIndex * snapshot.carrierProfile.columns + assignment.columnIndex
            val key = GroupKey(analyteId, sampleId, assignment.repeatGroup?.trim()?.takeIf { it.isNotEmpty() })
            Triple(key, siteIndex, bySite[siteIndex to analyteId])
        }.groupBy { it.first }
        val groups = sites.mapNotNull { (key, members) ->
            if (members.size < 2) return@mapNotNull null
            val usable = members.mapNotNull { it.third }.filter(SiteMeasurement::qualityReliable)
            val features = usable.map { it.primaryFeatureName to it.processorVersion }.distinct()
            val signals = if (features.size == 1) usable.mapNotNull { it.primaryFeatureValue?.takeIf(Double::isFinite) } else emptyList()
            // 单侧界限、外推估计、离域和 QC 失败不能伪装为精确浓度，也不能当零参与均值。
            val exact = usable.filter { it.quantificationState == "QUANTIFIED" && it.concentrationValue?.isFinite() == true }
            val unit = exact.map(SiteMeasurement::concentrationUnit).distinct().singleOrNull()?.takeIf { it.isNotBlank() }
            val concentrations = if (unit != null) exact.mapNotNull(SiteMeasurement::concentrationValue) else emptyList()
            MultiHoleGroup(key.analyteId, key.sampleId, key.repeatGroup, members.map { it.second }.sorted(),
                features.singleOrNull()?.first, summarize(signals, null, mode), summarize(concentrations, unit, mode))
        }.sortedWith(compareBy(MultiHoleGroup::analyteId, MultiHoleGroup::sampleId, MultiHoleGroup::repeatGroup))
        return MultiHoleSnapshot(mode, groups)
    }

    internal fun summarize(values: List<Double>, unit: String?, mode: MultiHoleStatistic): MultiHoleValue {
        val sorted = values.filter(Double::isFinite).sorted()
        val middle = sorted.size / 2
        val value = if (sorted.size < 2) null else when (mode) {
            MultiHoleStatistic.INDIVIDUAL -> null
            MultiHoleStatistic.MEAN -> sorted.sumOf { it / sorted.size }.takeIf(Double::isFinite)
            MultiHoleStatistic.MEDIAN -> if (sorted.size % 2 == 1) sorted[middle] else sorted[middle-1] / 2 + sorted[middle] / 2
        }
        return MultiHoleValue(value, unit, sorted.size)
    }

    /** 老运行没有该字段就不展示；坏的附加摘要不能让已有逐孔结果打不开。 */
    fun fromSiteQc(json: String?): MultiHoleSnapshot? = runCatching {
        val summary = gson.fromJson(json, JsonObject::class.java)?.get(SNAPSHOT_KEY) ?: return null
        gson.fromJson(summary, MultiHoleSnapshot::class.java).takeIf { snapshot ->
            snapshot.mode in listOf(MultiHoleStatistic.MEAN, MultiHoleStatistic.MEDIAN) && snapshot.groups.all { group ->
                group.sampleId.isNotBlank() && group.analyteId.isNotBlank() && group.siteIndices.size >= 2 &&
                    group.siteIndices.distinct().size == group.siteIndices.size && group.siteIndices.all { it >= 0 } &&
                    listOf(group.signal, group.concentration).all { metric ->
                        metric.usedWells in 0..group.siteIndices.size && (metric.value == null || metric.value.isFinite() && metric.usedWells >= 2)
                    }
            }
        }
    }.getOrNull()

    private data class GroupKey(val analyteId: String, val sampleId: String, val repeatGroup: String?)
}

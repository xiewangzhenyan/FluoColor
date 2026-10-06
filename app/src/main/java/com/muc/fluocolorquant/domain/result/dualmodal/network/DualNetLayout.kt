package com.muc.fluocolorquant.domain.result.dualmodal.network

import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalAdjudicationEngine
import kotlin.math.abs

/** 一个位点及其同行三只参照孔的物理位点号；网络输入按这四个位置依次裁切。 */
data class DualNetSiteRefs(
    val site: Int,
    val negative: Int,
    val qcMid: Int,
    val qcHigh: Int
) {
    fun all(): List<Int> = listOf(site, negative, qcMid, qcHigh)
}

/** 一组重复位点：测试板上的一个样本，或标定板上的一个标准水平。 */
data class DualNetSiteGroup(
    val key: String,
    /** 标准水平的名义浓度（ng/mL）；样本组为空。 */
    val level: Double?,
    val sites: List<DualNetSiteRefs>
)

/**
 * 把阵列版面映射到 DualNet 的训练版面。
 *
 * 训练数据（W2）每行第 0 列为阴性参照、第 13、14 列为 25 与 250 ng/mL 两只阳控，网络把这三只
 * 参照孔与位点本身一起读，用来抵消同行的照明和试剂批差异。系统版面更自由，这里按"同一行"
 * 而不是按固定列号查找：空白或阴性对照作阴性参照，名义浓度为 25、250 ng/mL 的阳控作两只阳控；
 * 同一角色有多只时取列号最小的一只，保证结果确定。缺任何一只参照的行不送入网络，宁可少读
 * 也不让网络在训练分布之外静默工作。
 */
object DualNetLayout {

    /** 两侧配对已保证逐位点版面一致，所以只需按任一侧解析。 */
    fun sampleGroups(snapshot: ArrayResultSnapshot, analyteId: String): List<DualNetSiteGroup> {
        if (!gridSupported(snapshot)) return emptyList()
        val refs = rowReferences(snapshot, analyteId)
        return snapshot.sites
            .filter { site -> site.enabled && site.roleCode == TemplateSiteRole.SAMPLE.code && site.analyteId == analyteId }
            .groupBy(DualModalAdjudicationEngine::sampleKeyOf)
            .mapNotNull { (key, sites) ->
                val usable = sites.sortedBy(ArrayPhysicalSiteResult::siteIndex).mapNotNull { site -> refs[site.rowIndex]?.withSite(site.siteIndex) }
                usable.takeIf { it.isNotEmpty() }?.let { DualNetSiteGroup(key = key, level = null, sites = it) }
            }
    }

    /** 标定板上 1.4–219 ng/mL 范围内的标准水平，按浓度升序。 */
    fun standardLevels(snapshot: ArrayResultSnapshot, analyteId: String): List<DualNetSiteGroup> {
        if (!gridSupported(snapshot)) return emptyList()
        val refs = rowReferences(snapshot, analyteId)
        return snapshot.sites
            .filter { site ->
                val level = site.standardConcentration
                site.enabled && site.roleCode == TemplateSiteRole.STANDARD.code && site.analyteId == analyteId &&
                    level != null && level.isFinite() &&
                    level >= DualNetSpec.RECALIBRATION_MIN_LEVEL && level <= DualNetSpec.RECALIBRATION_MAX_LEVEL
            }
            .groupBy { site -> site.standardConcentration!! }
            .toSortedMap()
            .mapNotNull { (level, sites) ->
                val usable = sites.sortedBy(ArrayPhysicalSiteResult::siteIndex).mapNotNull { site -> refs[site.rowIndex]?.withSite(site.siteIndex) }
                usable.takeIf { it.isNotEmpty() }?.let { DualNetSiteGroup(key = level.toString(), level = level, sites = it) }
            }
    }

    fun gridSupported(snapshot: ArrayResultSnapshot): Boolean =
        snapshot.rows == DualNetSpec.GRID_SIZE && snapshot.columns == DualNetSpec.GRID_SIZE

    private data class RowRefs(val negative: Int, val qcMid: Int, val qcHigh: Int) {
        fun withSite(site: Int) = DualNetSiteRefs(site = site, negative = negative, qcMid = qcMid, qcHigh = qcHigh)
    }

    private fun rowReferences(snapshot: ArrayResultSnapshot, analyteId: String): Map<Int, RowRefs> {
        return snapshot.sites.filter(ArrayPhysicalSiteResult::enabled).groupBy(ArrayPhysicalSiteResult::rowIndex)
            .mapNotNull { (row, sites) ->
                val ordered = sites.sortedBy(ArrayPhysicalSiteResult::columnIndex)
                // 比色运行的空白位是不属于任何分析物的参考证据（analyteId 为空），也接受。
                val negative = ordered.firstOrNull { site ->
                    (site.roleCode == TemplateSiteRole.BLANK.code || site.roleCode == TemplateSiteRole.NEGATIVE_CONTROL.code) &&
                        (site.analyteId == null || site.analyteId == analyteId)
                } ?: return@mapNotNull null
                val mid = ordered.firstOrNull { it.isQc(analyteId, DualNetSpec.QC_MID_NOMINAL) } ?: return@mapNotNull null
                val high = ordered.firstOrNull { it.isQc(analyteId, DualNetSpec.QC_HIGH_NOMINAL) } ?: return@mapNotNull null
                row to RowRefs(negative = negative.siteIndex, qcMid = mid.siteIndex, qcHigh = high.siteIndex)
            }
            .toMap()
    }

    private fun ArrayPhysicalSiteResult.isQc(analyteId: String, nominal: Double): Boolean {
        val value = standardConcentration ?: return false
        return roleCode == TemplateSiteRole.POSITIVE_CONTROL.code && this.analyteId == analyteId &&
            value.isFinite() && abs(value - nominal) <= nominal * DualNetSpec.NOMINAL_RELATIVE_TOLERANCE
    }
}

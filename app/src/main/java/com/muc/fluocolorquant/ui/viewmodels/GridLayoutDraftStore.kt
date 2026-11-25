package com.muc.fluocolorquant.ui.viewmodels

import com.muc.fluocolorquant.data.enums.TemplateSiteRole

/**
 * 与 Compose 生命周期无关的布局草稿仓库。
 *
 * 同一项目重新定位且行列未变化时保留用户草稿；规格变化时使用新的冻结布局，避免旧坐标
 * 落入错误物理位点。该对象只管理草稿一致性，不依赖 ViewModel 或 Android 状态。
 */
internal class GridLayoutDraftStore {
    private var rows: Int? = null
    private var columns: Int? = null
    private var drafts: List<GridLayoutAssignmentDraft> = emptyList()

    fun clear() {
        rows = null
        columns = null
        drafts = emptyList()
    }

    fun beginSession(
        rows: Int,
        columns: Int,
        frozenAssignments: List<GridLayoutAssignmentDraft>
    ): List<GridLayoutAssignmentDraft> {
        if (this.rows != rows || this.columns != columns) {
            this.rows = rows
            this.columns = columns
            drafts = normalize(rows, columns, frozenAssignments)
        }
        return drafts
    }

    fun update(
        rows: Int,
        columns: Int,
        drafts: List<GridLayoutAssignmentDraft>
    ): List<GridLayoutAssignmentDraft> {
        this.rows = rows
        this.columns = columns
        this.drafts = normalize(rows, columns, drafts)
        return this.drafts
    }

    fun current(): List<GridLayoutAssignmentDraft> = drafts

    /** 去除越界、重复坐标并固定行优先顺序，保证快照与测试可复现。 */
    private fun normalize(
        rows: Int,
        columns: Int,
        drafts: List<GridLayoutAssignmentDraft>
    ): List<GridLayoutAssignmentDraft> = drafts
        .filter { it.rowIndex in 0 until rows && it.columnIndex in 0 until columns }
        .distinctBy { it.rowIndex to it.columnIndex }
        .sortedWith(compareBy(GridLayoutAssignmentDraft::rowIndex, GridLayoutAssignmentDraft::columnIndex))
}

/**
 * 将本次画笔经过的位点增量合并进已有布局。
 *
 * 空位点可以写入；完全相同的重复划过幂等；已有分配默认受保护，只有清除画笔能删除，
 * 删除后才允许重新分配，避免一次拖动无意覆盖科研布局。
 */
internal fun mergePaintedAssignments(
    existing: Map<Int, GridLayoutAssignmentDraft>,
    paintedSiteIndices: Set<Int>,
    rows: Int,
    columns: Int,
    analyteId: String?,
    role: TemplateSiteRole,
    standardConcentration: Double?,
    sampleId: String?,
    clearMode: Boolean
): GridPaintMergeResult {
    if (rows <= 0 || columns <= 0 || paintedSiteIndices.isEmpty()) {
        return GridPaintMergeResult(existing, protectedSiteCount = 0)
    }
    val siteCount = rows * columns
    val validIndices = paintedSiteIndices.filterTo(linkedSetOf()) { it in 0 until siteCount }
    val updated = existing.toMutableMap()
    if (clearMode) {
        validIndices.forEach(updated::remove)
        return GridPaintMergeResult(updated.toSortedMap(), protectedSiteCount = 0)
    }
    if (analyteId == null && role != TemplateSiteRole.DISABLED) {
        return GridPaintMergeResult(existing, protectedSiteCount = 0)
    }

    var protectedSiteCount = 0
    validIndices.forEach { index ->
        val desired = GridLayoutAssignmentDraft(
            rowIndex = index / columns,
            columnIndex = index % columns,
            analyteId = analyteId.takeUnless { role == TemplateSiteRole.DISABLED },
            role = role,
            // 标准品与阳控都携带浓度：前者是标定浓度，后者是名义浓度（双模态判定的阳控证据）。
            standardConcentration = standardConcentration.takeIf {
                role == TemplateSiteRole.STANDARD || role == TemplateSiteRole.POSITIVE_CONTROL
            },
            sampleId = sampleId
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                .takeIf { role == TemplateSiteRole.SAMPLE }
        )
        val current = updated[index]
        when {
            current == null -> updated[index] = desired
            current == desired -> Unit
            else -> protectedSiteCount += 1
        }
    }
    return GridPaintMergeResult(updated.toSortedMap(), protectedSiteCount)
}

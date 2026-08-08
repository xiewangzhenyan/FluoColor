package com.muc.fluocolorquant.ui.screens.detection

import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutAssignmentDraft
import com.muc.fluocolorquant.ui.viewmodels.GridPaintMergeResult
import com.muc.fluocolorquant.ui.viewmodels.mergePaintedAssignments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 孔位画笔增量合并回归测试。
 *
 * 这里专门覆盖“第二笔清空第一笔”和“第二分析物覆盖第一分析物”两类真实用户问题，
 * 保证布局编辑器无论重组速度如何，都只增量修改本次经过的孔位。
 */
class GridLayoutAssignmentMergeTest {

    @Test
    fun `连续两笔绘制会保留第一笔并累计十个孔位`() {
        val first = paint(emptyMap(), 0 until 5, analyteId = "cea")
        val second = paint(first.assignments, 15 until 20, analyteId = "cea")

        assertEquals(10, second.assignments.size)
        assertTrue((0 until 5).all(second.assignments::containsKey))
        assertTrue((15 until 20).all(second.assignments::containsKey))
    }

    @Test
    fun `任意三笔连续绘制均按增量方式保留`() {
        val first = paint(emptyMap(), 0 until 3, analyteId = "cea")
        val second = paint(first.assignments, 15 until 18, analyteId = "cea")
        val third = paint(second.assignments, 30 until 33, analyteId = "cea")

        assertEquals(9, third.assignments.size)
        assertEquals((0 until 3).toSet() + (15 until 18) + (30 until 33), third.assignments.keys)
    }

    @Test
    fun `切换分析物后可以在尚未分配的孔位继续绘制`() {
        val first = paint(emptyMap(), listOf(0, 1), analyteId = "cea")
        val second = paint(first.assignments, listOf(2, 3), analyteId = "ca125")

        assertEquals("cea", second.assignments.getValue(0).analyteId)
        assertEquals("ca125", second.assignments.getValue(2).analyteId)
        assertEquals(0, second.protectedSiteCount)
    }

    @Test
    fun `第二分析物划过已分配孔位时不会覆盖第一分析物`() {
        val first = paint(emptyMap(), listOf(0, 1), analyteId = "cea")
        val second = paint(first.assignments, listOf(1, 2), analyteId = "ca125")

        assertEquals("cea", second.assignments.getValue(1).analyteId)
        assertEquals("ca125", second.assignments.getValue(2).analyteId)
        assertEquals(1, second.protectedSiteCount)
    }

    @Test
    fun `清除画笔只删除本次经过的已有孔位`() {
        val first = paint(emptyMap(), 0 until 5, analyteId = "cea")
        val cleared = mergePaintedAssignments(
            existing = first.assignments,
            paintedSiteIndices = setOf(1, 3),
            rows = 15,
            columns = 15,
            analyteId = null,
            role = TemplateSiteRole.SAMPLE,
            standardConcentration = null,
            sampleId = null,
            clearMode = true
        )

        assertEquals(setOf(0, 2, 4), cleared.assignments.keys)
        assertEquals(0, cleared.protectedSiteCount)
    }

    @Test
    fun `清除后允许第二分析物重新分配该孔位`() {
        val first = paint(emptyMap(), listOf(0), analyteId = "cea")
        val cleared = mergePaintedAssignments(
            existing = first.assignments,
            paintedSiteIndices = setOf(0),
            rows = 15,
            columns = 15,
            analyteId = null,
            role = TemplateSiteRole.SAMPLE,
            standardConcentration = null,
            sampleId = null,
            clearMode = true
        )
        val reassigned = paint(cleared.assignments, listOf(0), analyteId = "ca125")

        assertEquals("ca125", reassigned.assignments.getValue(0).analyteId)
    }

    @Test
    fun `同一分析物同一角色重复划过保持幂等且不提示冲突`() {
        val first = paint(emptyMap(), listOf(0, 1), analyteId = "cea")
        val repeated = paint(first.assignments, listOf(0, 1), analyteId = "cea")

        assertEquals(first.assignments, repeated.assignments)
        assertEquals(0, repeated.protectedSiteCount)
    }

    @Test
    fun `跨行位点仍按行优先编号生成正确坐标`() {
        val result = paint(emptyMap(), listOf(13, 14, 15, 16), analyteId = "cea")

        assertEquals(0, result.assignments.getValue(13).rowIndex)
        assertEquals(13, result.assignments.getValue(13).columnIndex)
        assertEquals(0, result.assignments.getValue(14).rowIndex)
        assertEquals(14, result.assignments.getValue(14).columnIndex)
        assertEquals(1, result.assignments.getValue(15).rowIndex)
        assertEquals(0, result.assignments.getValue(15).columnIndex)
        assertEquals(1, result.assignments.getValue(16).rowIndex)
        assertEquals(1, result.assignments.getValue(16).columnIndex)
    }

    @Test
    fun `快速拖动线段会补齐两个触点之间经过的孔位`() {
        val indices = gridSiteIndicesAlongSegment(
            startX = 5f,
            startY = 5f,
            endX = 173f,
            endY = 5f,
            rows = 15,
            columns = 15,
            cellSizePx = 10f,
            gapPx = 2f
        )

        assertEquals((0 until 15).toSet(), indices)
        assertFalse(indices.contains(15))
    }

    private fun paint(
        existing: Map<Int, GridLayoutAssignmentDraft>,
        indices: Iterable<Int>,
        analyteId: String
    ): GridPaintMergeResult {
        return mergePaintedAssignments(
            existing = existing,
            paintedSiteIndices = indices.toSet(),
            rows = 15,
            columns = 15,
            analyteId = analyteId,
            role = TemplateSiteRole.SAMPLE,
            standardConcentration = null,
            sampleId = null,
            clearMode = false
        )
    }
}

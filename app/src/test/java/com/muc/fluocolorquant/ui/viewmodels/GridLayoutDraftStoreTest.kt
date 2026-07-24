package com.muc.fluocolorquant.ui.viewmodels

import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 布局草稿恢复测试，防止科学门控失败或重新定位后丢失整板配置。 */
class GridLayoutDraftStoreTest {

    @Test
    fun `同规格重新定位保留分析物角色浓度与样本编号`() {
        val store = GridLayoutDraftStore()
        val configured = listOf(
            GridLayoutAssignmentDraft(
                rowIndex = 0,
                columnIndex = 0,
                analyteId = "ca125",
                role = TemplateSiteRole.STANDARD,
                standardConcentration = 10.0
            ),
            GridLayoutAssignmentDraft(
                rowIndex = 14,
                columnIndex = 14,
                analyteId = "cea",
                role = TemplateSiteRole.SAMPLE,
                sampleId = "S-225"
            )
        )

        store.beginSession(rows = 15, columns = 15, frozenAssignments = emptyList())
        store.update(rows = 15, columns = 15, drafts = configured)
        val restored = store.beginSession(
            rows = 15,
            columns = 15,
            frozenAssignments = emptyList()
        )

        assertEquals(2, restored.size)
        assertEquals("ca125", restored[0].analyteId)
        assertEquals(10.0, restored[0].standardConcentration)
        assertEquals(TemplateSiteRole.SAMPLE, restored[1].role)
        assertEquals("S-225", restored[1].sampleId)
    }

    @Test
    fun `规格变化时丢弃旧坐标并恢复新会话冻结布局`() {
        val store = GridLayoutDraftStore()
        store.beginSession(rows = 15, columns = 15, frozenAssignments = emptyList())
        store.update(
            rows = 15,
            columns = 15,
            drafts = listOf(
                GridLayoutAssignmentDraft(
                    rowIndex = 14,
                    columnIndex = 14,
                    analyteId = "ca125",
                    role = TemplateSiteRole.SAMPLE,
                    sampleId = "OLD"
                )
            )
        )
        val frozenBlank = GridLayoutAssignmentDraft(
            rowIndex = 0,
            columnIndex = 1,
            analyteId = null,
            role = TemplateSiteRole.BLANK
        )

        val restored = store.beginSession(
            rows = 10,
            columns = 10,
            frozenAssignments = listOf(frozenBlank)
        )

        assertEquals(listOf(frozenBlank), restored)
        assertNull(restored.single().analyteId)
    }

    @Test
    fun `更新草稿时过滤越界和重复孔位并保持行优先顺序`() {
        val store = GridLayoutDraftStore()
        val duplicateFirst = GridLayoutAssignmentDraft(
            rowIndex = 1,
            columnIndex = 1,
            analyteId = "first",
            role = TemplateSiteRole.SAMPLE
        )
        val normalized = store.update(
            rows = 2,
            columns = 2,
            drafts = listOf(
                duplicateFirst,
                duplicateFirst.copy(analyteId = "duplicate"),
                duplicateFirst.copy(rowIndex = -1),
                GridLayoutAssignmentDraft(
                    rowIndex = 0,
                    columnIndex = 1,
                    analyteId = null,
                    role = TemplateSiteRole.REFERENCE
                )
            )
        )

        assertEquals(2, normalized.size)
        assertEquals(0, normalized[0].rowIndex)
        assertEquals(1, normalized[1].rowIndex)
        assertEquals("first", normalized[1].analyteId)
    }
}

package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.model.Project
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双模态配对候选的项目层面条件。
 *
 * 回归：旧条件要求两个项目模板相同，但模板固定检测模态，比色与荧光项目必然来自两个模板，
 * 直接新建的项目又各有独立的隐式模板，候选因此永远为空。
 */
class DualModalPairingCandidateFilterTest {

    @Test
    fun `不同模板的比色与荧光项目可以互为候选`() {
        val col = project("col", "COLORIMETRIC", templateId = "tpl-col", templateVersion = 1)
        val flu = project("flu", "FLUORESCENCE", templateId = "tpl-flu", templateVersion = 3)

        assertTrue(isDualModalCounterpartProject(col, flu))
        assertTrue(isDualModalCounterpartProject(flu, col))
    }

    @Test
    fun `直接新建的两个项目各有隐式模板，仍可互为候选`() {
        val col = project("col", "COLORIMETRIC", templateId = "direct-template-col")
        val flu = project("flu", "FLUORESCENCE", templateId = "direct-template-flu")

        assertTrue(isDualModalCounterpartProject(col, flu))
    }

    @Test
    fun `同一模态、网格不同、用户不同或同一项目都不是候选`() {
        val col = project("col", "COLORIMETRIC")

        assertFalse(isDualModalCounterpartProject(col, project("col2", "colorimetric")))
        assertFalse(isDualModalCounterpartProject(col, project("flu", "FLUORESCENCE", rows = 8, columns = 12)))
        assertFalse(isDualModalCounterpartProject(col, project("flu", "FLUORESCENCE", userId = "other-user")))
        assertFalse(isDualModalCounterpartProject(col, col.copy(detectionMode = "FLUORESCENCE")))
    }

    @Test
    fun `只有比色与荧光互为另一模态`() {
        assertEquals("FLUORESCENCE", dualModalOppositeMode("colorimetric"))
        assertEquals("COLORIMETRIC", dualModalOppositeMode("FLUORESCENCE"))
        assertNull(dualModalOppositeMode("SPECTRUM"))
        assertFalse(isDualModalCounterpartProject(project("spec", "SPECTRUM"), project("col", "COLORIMETRIC")))
    }

    private fun project(
        id: String,
        mode: String,
        rows: Int = 15,
        columns: Int = 15,
        userId: String = "user-1",
        templateId: String? = "tpl-$id",
        templateVersion: Int? = 1
    ) = Project(
        id = id,
        name = id,
        detectionMode = mode,
        recognitionType = "AUTO",
        imageUri = "",
        rows = rows,
        columns = columns,
        createTime = Date(0),
        userId = userId,
        lastRunTimestamp = null,
        analysisMethod = "TEMPLATE_MANAGED",
        templateId = templateId,
        templateVersion = templateVersion
    )
}

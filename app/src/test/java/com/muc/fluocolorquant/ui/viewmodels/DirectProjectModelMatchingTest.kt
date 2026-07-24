package com.muc.fluocolorquant.ui.viewmodels

import com.muc.fluocolorquant.ui.screens.project.DirectAnalyteSelection
import com.muc.fluocolorquant.ui.screens.project.DirectProjectFormState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 新建页已取消模型匹配，本测试固定多分析物单位不会被全局字段覆盖。 */
class DirectProjectModelMatchingTest {

    @Test
    fun `表单为每个分析物独立保存浓度单位`() {
        val state = DirectProjectUiState(
            concentrationUnits = listOf("ng/mL", "pg/mL"),
            form = DirectProjectFormState(
                selectedAnalytes = listOf(
                    DirectAnalyteSelection("cea", "ng/mL"),
                    DirectAnalyteSelection("afp", "pg/mL")
                )
            )
        )

        assertEquals("ng/mL", state.form.selectedAnalytes[0].concentrationUnit)
        assertEquals("pg/mL", state.form.selectedAnalytes[1].concentrationUnit)
    }
}

package com.muc.fluocolorquant.ui.screens.settings.template

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.Analyte
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 微流控阵列编辑器的关键交互验收测试。
 *
 * 测试只依赖稳定语义标签和业务回调，不锁定颜色、尺寸等视觉实现细节，确保后续可以在
 * 不破坏科研工作流的前提下继续优化页面样式。
 */
class TemplateArrayLayoutEditorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 十乘十阵列提供一百个可选择位点() {
        composeRule.setContent {
            MaterialTheme {
                var layout by remember {
                    mutableStateOf(TemplateArrayLayoutDraft(rows = 10, columns = 10))
                }
                TemplateArrayLayoutEditor(
                    layout = layout,
                    analytes = emptyList(),
                    batchDraft = TemplateSiteDraft(),
                    rectangleSelectionEnabled = false,
                    onRectangleSelectionEnabledChange = {},
                    onToggleSite = { layout = layout.toggleSelection(it) },
                    onSelectRectanglePoint = {},
                    onSelectRow = { layout = layout.selectRow(it) },
                    onSelectColumn = { layout = layout.selectColumn(it) },
                    onSelectAll = { layout = layout.selectAll() },
                    onClearSelection = { layout = layout.clearSelection() },
                    onBatchDraftChange = {},
                    onApplyToSelection = {}
                )
            }
        }

        // 逐一确认 100 个中性坐标节点，避免 Lazy 容器只创建可见位点而遗漏阵列语义。
        repeat(10) { row ->
            repeat(10) { column ->
                val coordinate = TemplateSiteCoordinate(row, column)
                composeRule.onNodeWithTag(TemplateArrayLayoutTestTags.site(coordinate))
                    .assertExists()
            }
        }

        composeRule.onNodeWithTag(
            TemplateArrayLayoutTestTags.site(TemplateSiteCoordinate(0, 0))
        ).performClick().assertIsSelected()
    }

    @Test
    fun 行列头和批量应用会修改整个选区() {
        var appliedLayout = TemplateArrayLayoutDraft(rows = 10, columns = 10)

        composeRule.setContent {
            MaterialTheme {
                var layout by remember { mutableStateOf(appliedLayout) }
                var batchDraft by remember {
                    mutableStateOf(
                        TemplateSiteDraft(
                            analyteId = "cea",
                            role = TemplateSiteRole.SAMPLE
                        )
                    )
                }
                TemplateArrayLayoutEditor(
                    layout = layout,
                    analytes = listOf(Analyte(id = "cea", name = "CEA")),
                    batchDraft = batchDraft,
                    rectangleSelectionEnabled = false,
                    onRectangleSelectionEnabledChange = {},
                    onToggleSite = { layout = layout.toggleSelection(it) },
                    onSelectRectanglePoint = {},
                    onSelectRow = { layout = layout.selectRow(it) },
                    onSelectColumn = { layout = layout.selectColumn(it) },
                    onSelectAll = { layout = layout.selectAll() },
                    onClearSelection = { layout = layout.clearSelection() },
                    onBatchDraftChange = { batchDraft = it },
                    onApplyToSelection = {
                        layout = layout.applyToSelection(batchDraft)
                        appliedLayout = layout
                    }
                )
            }
        }

        composeRule.onNodeWithTag(TemplateArrayLayoutTestTags.rowHeader(0)).performClick()
        composeRule.onNodeWithTag(
            TemplateArrayLayoutTestTags.site(TemplateSiteCoordinate(0, 9))
        ).assertIsSelected()
        composeRule.onNodeWithTag(TemplateArrayLayoutTestTags.APPLY_BUTTON).performClick()

        composeRule.runOnIdle {
            assertEquals(10, appliedLayout.assignments.size)
            assertEquals(
                setOf(TemplateSiteRole.SAMPLE),
                appliedLayout.assignments.values.mapTo(linkedSetOf()) { it.role }
            )
        }
    }

    @Test
    fun 十五乘十五阵列暴露双向滚动视口() {
        composeRule.setContent {
            MaterialTheme {
                TemplateArrayLayoutEditor(
                    layout = TemplateArrayLayoutDraft(rows = 15, columns = 15),
                    analytes = emptyList(),
                    batchDraft = TemplateSiteDraft(),
                    rectangleSelectionEnabled = false,
                    onRectangleSelectionEnabledChange = {},
                    onToggleSite = {},
                    onSelectRectanglePoint = {},
                    onSelectRow = {},
                    onSelectColumn = {},
                    onSelectAll = {},
                    onClearSelection = {},
                    onBatchDraftChange = {},
                    onApplyToSelection = {}
                )
            }
        }

        composeRule.onNodeWithTag(TemplateArrayLayoutTestTags.HORIZONTAL_VIEWPORT)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(TemplateArrayLayoutTestTags.VERTICAL_VIEWPORT)
            .assertIsDisplayed()
    }
}

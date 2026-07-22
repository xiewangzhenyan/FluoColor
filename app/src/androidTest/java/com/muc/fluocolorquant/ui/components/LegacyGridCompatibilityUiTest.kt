package com.muc.fluocolorquant.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import java.util.Date
import org.junit.Rule
import org.junit.Test

/**
 * 工作包 6 的旧孔板与通用阵列 UI 兼容测试。
 *
 * 测试直接覆盖真实位点预览，确保历史 12×8 存储方向仍显示成 8×12，同时新模板
 * 15×15 不会被旧 96 位点画布截断。
 */
class LegacyGridCompatibilityUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun legacyTwelveByEightProjectStillDisplaysEightByTwelvePlate() {
        val project = createProject(rows = 12, columns = 8, templateBacked = false)
        composeRule.setContent {
            FluoColorTheme {
                RealWellPreviewGrid(
                    wellResults = createWellResults(project.id, count = 96),
                    project = project
                )
            }
        }

        composeRule.onNodeWithText("A1").assertExists()
        composeRule.onNodeWithText("H12").assertExists()
    }

    @Test
    fun templateBackedFifteenByFifteenProjectDisplaysLastSite() {
        val project = createProject(rows = 15, columns = 15, templateBacked = true)
        composeRule.setContent {
            FluoColorTheme {
                RealWellPreviewGrid(
                    wellResults = createWellResults(project.id, count = 225),
                    project = project
                )
            }
        }

        composeRule.onNodeWithText("A1").assertExists()
        composeRule.onNodeWithText("O15").assertExists()
    }

    private fun createProject(rows: Int, columns: Int, templateBacked: Boolean): Project {
        return Project(
            id = "project-$rows-$columns-$templateBacked",
            name = "Grid compatibility",
            detectionMode = "COLORIMETRIC",
            recognitionType = "AUTO",
            imageUri = "content://grid/test",
            rows = rows,
            columns = columns,
            createTime = Date(0),
            userId = "user",
            lastRunTimestamp = null,
            analysisMethod = "CURVE_FIT",
            templateId = if (templateBacked) "template-grid" else null,
            templateSnapshotJson = if (templateBacked) "{}" else null
        )
    }

    private fun createWellResults(projectId: String, count: Int): List<WellResult> {
        return List(count) { index ->
            WellResult(
                resultId = index.toLong() + 1L,
                runId = "run-grid",
                projectId = projectId,
                wellIndex = index,
                predictedConcentration = index.toDouble(),
                trueConcentration = null,
                detectedRectLeft = null,
                detectedRectTop = null,
                detectedRectRight = null,
                detectedRectBottom = null,
                detectionConfidence = null,
                croppedImageIdentifier = null
            )
        }
    }
}

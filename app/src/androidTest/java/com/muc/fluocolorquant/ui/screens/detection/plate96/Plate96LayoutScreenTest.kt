package com.muc.fluocolorquant.ui.screens.detection.plate96

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationDraft
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import com.muc.fluocolorquant.domain.detection.plate96.Plate96CircleCandidate
import com.muc.fluocolorquant.domain.detection.plate96.Plate96CircleObservation
import com.muc.fluocolorquant.domain.detection.plate96.Plate96CircleSource
import com.muc.fluocolorquant.domain.detection.plate96.Plate96GridAssembler
import com.muc.fluocolorquant.domain.detection.plate96.Plate96OrientationResolver
import com.muc.fluocolorquant.ui.screens.detection.ArrayLayoutEditorTestTags
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutAssignmentDraft
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationAnalyte
import com.muc.fluocolorquant.ui.viewmodels.mergePaintedAssignments
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 96孔板圆形布局页设备UI回归。 */
@RunWith(AndroidJUnit4::class)
class Plate96LayoutScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `布局页显示96张圆孔裁切完整双位数标签虚拟板和定量工作台`() {
        setPlateLayoutContent()

        composeRule.onNodeWithTag(Plate96LayoutTestTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(ArrayLayoutEditorTestTags.REAL_GRID)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("A10", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithText("A12", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithText("H12", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onNodeWithTag(ArrayLayoutEditorTestTags.VIRTUAL_GRID)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ArrayLayoutEditorTestTags.QUANTITATION)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `点击真实圆孔可以打开圆形放大预览`() {
        setPlateLayoutContent()

        composeRule.onNodeWithTag("${ArrayLayoutEditorTestTags.CROP_PREFIX}9")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(ArrayLayoutEditorTestTags.CROP_DIALOG).assertIsDisplayed()
    }

    /** 仅在显式参数开启时保留页面，供ADB截图检查360dp圆孔密度和A10～A12。 */
    @Test
    fun visualPlate96Layout_holdsForAdbReview() {
        val shouldHold = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_HOLD_SCREENSHOT)
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue("未请求96孔布局ADB视觉自审，跳过保留页面", shouldHold)
        setPlateLayoutContent()
        composeRule.waitForIdle()
        Thread.sleep(SCREENSHOT_HOLD_MILLIS)
    }

    /** 滚动到圆形虚拟板，单独检查坐标、角色缩写和双位数列号是否完整。 */
    @Test
    fun visualPlate96VirtualLayout_holdsForAdbReview() {
        val shouldHold = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_HOLD_VIRTUAL_SCREENSHOT)
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue("未请求96孔虚拟布局ADB视觉自审，跳过保留页面", shouldHold)
        setPlateLayoutContent()
        composeRule.onNodeWithTag(ArrayLayoutEditorTestTags.VIRTUAL_GRID)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.waitForIdle()
        Thread.sleep(SCREENSHOT_HOLD_MILLIS)
    }

    private fun setPlateLayoutContent() {
        val bitmap = createPlateBitmap()
        val circles = circles()
        val localization = Plate96GridAssembler().assemble(
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            circles = circles,
            resolution = Plate96OrientationResolver().resolve(circles.toObservations())
        )
        val analytes = listOf(
            GridLocalizationAnalyte("cea", "CEA", "ng/mL", 100.0),
            GridLocalizationAnalyte("afp", "AFP", "ng/mL", 120.0)
        )
        val preview = localization.toPlate96LayoutPreview(
            runId = "plate96-layout-ui",
            originalImageUri = "content://plate96/layout",
            detectionMode = "colorimetric",
            analytes = analytes,
            quantitationDrafts = analytes.map { analyte ->
                GridAnalyteQuantitationDraft(
                    analyteId = analyte.id,
                    mode = GridAnalyteQuantitationMode.SIGNAL_ONLY
                )
            }
        )
        val initialAssignments = buildMap {
            repeat(18) { siteIndex ->
                put(
                    siteIndex,
                    GridLayoutAssignmentDraft(
                        rowIndex = siteIndex / 12,
                        columnIndex = siteIndex % 12,
                        analyteId = if (siteIndex < 9) "cea" else "afp",
                        role = if (siteIndex % 6 == 0) TemplateSiteRole.STANDARD
                        else TemplateSiteRole.SAMPLE,
                        sampleId = "S${siteIndex + 1}"
                    )
                )
            }
        }

        composeRule.setContent {
            var assignments by remember { mutableStateOf(initialAssignments) }
            FluoColorTheme {
                Plate96LayoutScreen(
                    normalizedBitmap = bitmap,
                    preview = preview.copy(initialAssignments = assignments.values.toList()),
                    assignments = assignments,
                    onAssignmentsChange = { assignments = it },
                    onPaintAssignments = { intent ->
                        mergePaintedAssignments(
                            existing = assignments,
                            paintedSiteIndices = intent.paintedSiteIndices,
                            rows = 8,
                            columns = 12,
                            analyteId = intent.analyteId,
                            role = intent.role,
                            standardConcentration = intent.standardConcentration,
                            sampleId = intent.sampleId,
                            clearMode = intent.clearMode
                        ).also { assignments = it.assignments }
                    },
                    onBack = {},
                    onReviewLocalization = {},
                    onFinalize = {}
                )
            }
        }
    }

    /** 构造具有明暗层次的圆孔图，使透明圆形裁切和布局填色可直接目检。 */
    private fun createPlateBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(238, 240, 236))
        canvas.drawRoundRect(
            18f,
            18f,
            702f,
            462f,
            28f,
            28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 68, 70) }
        )
        circles().forEachIndexed { index, circle ->
            val intensity = 220 - (index % 12) * 4 - (index / 12) * 3
            canvas.drawCircle(
                circle.centerX.toFloat(),
                circle.centerY.toFloat(),
                circle.radius.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(intensity, (intensity + 8).coerceAtMost(255), intensity)
                }
            )
        }
        return bitmap
    }

    private fun circles(): List<Plate96CircleCandidate> = buildList {
        var index = 0
        repeat(8) { row ->
            repeat(12) { column ->
                val centerX = 52.0 + column * 56.0
                val centerY = 48.0 + row * 55.0
                add(
                    Plate96CircleCandidate(
                        candidateIndex = index++,
                        centerX = centerX,
                        centerY = centerY,
                        radius = 20.0,
                        confidence = 0.96,
                        source = Plate96CircleSource.HOUGH,
                        objectBounds = ArrayImageBounds(
                            centerX - 23.0,
                            centerY - 23.0,
                            centerX + 23.0,
                            centerY + 23.0
                        )
                    )
                )
            }
        }
    }

    private fun List<Plate96CircleCandidate>.toObservations(): List<Plate96CircleObservation> {
        return mapIndexed { observationIndex, circle ->
            Plate96CircleObservation(
                x = circle.centerX,
                y = circle.centerY,
                radius = circle.radius,
                confidence = circle.confidence,
                observationIndex = observationIndex
            )
        }
    }

    private companion object {
        const val ARGUMENT_HOLD_SCREENSHOT: String = "holdPlate96LayoutScreenshot"
        const val ARGUMENT_HOLD_VIRTUAL_SCREENSHOT: String = "holdPlate96VirtualLayoutScreenshot"
        const val SCREENSHOT_HOLD_MILLIS: Long = 30_000L
    }
}

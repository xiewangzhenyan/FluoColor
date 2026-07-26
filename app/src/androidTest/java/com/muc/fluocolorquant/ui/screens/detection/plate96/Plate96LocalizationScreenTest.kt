package com.muc.fluocolorquant.ui.screens.detection.plate96

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.plate96.Plate96BitmapNormalizer
import com.muc.fluocolorquant.domain.detection.plate96.Plate96CircleCandidate
import com.muc.fluocolorquant.domain.detection.plate96.Plate96CircleObservation
import com.muc.fluocolorquant.domain.detection.plate96.Plate96CircleSource
import com.muc.fluocolorquant.domain.detection.plate96.Plate96GridAssembler
import com.muc.fluocolorquant.domain.detection.plate96.Plate96Locator
import com.muc.fluocolorquant.domain.detection.plate96.Plate96OrientationResolver
import com.muc.fluocolorquant.domain.detection.plate96.Plate96YoloDetector
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.viewmodels.Plate96ImageViewMode
import com.muc.fluocolorquant.ui.viewmodels.Plate96LocalizationUiState
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File
import kotlinx.coroutines.runBlocking

/** 96孔板定位页的设备UI回归，重点固定窄屏布局和手动微调入口。 */
@RunWith(AndroidJUnit4::class)
class Plate96LocalizationScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `定位页显示方向预览算法摘要和继续入口`() {
        val fixture = fixture(selectedSiteIndex = null)
        composeRule.setContent {
            FluoColorTheme {
                Plate96ReadyContent(
                    state = fixture.state,
                    onImageViewChange = {},
                    onShowOutlinesChange = {},
                    onShowLabelsChange = {},
                    onSelectSite = {},
                    onLocatorModeChange = {},
                    onConfirmCurrentOrientation = {},
                    onOriginCornerChange = {},
                    onRestoreAutomaticOrientation = {},
                    onNudgeSelectedSite = { _, _ -> },
                    onSetSelectedSiteRadius = {},
                    onRestoreSelectedSite = {},
                    onContinue = {}
                )
            }
        }

        composeRule.onNodeWithTag(Plate96LocalizationTestTags.READY_CONTENT).assertIsDisplayed()
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.ORIENTATION_CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.PREVIEW)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.ALGORITHM_SELECTOR)
            .performScrollTo()
            .assertIsDisplayed()
        // LazyColumn只组合当前视口附近的节点。算法选择器前移后，必须先滚到摘要索引，
        // 不能对尚未组合的底部节点直接调用performScrollTo()。
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.CONTENT_LIST)
            .performScrollToIndex(6)
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.SUMMARY)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.CONTENT_LIST)
            .performScrollToIndex(7)
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.CONTINUE_BUTTON)
            .assertIsDisplayed()

    }

    @Test
    fun `选中孔位后可以打开微调并回传移动恢复和完成动作`() {
        val fixture = fixture(selectedSiteIndex = 0)
        var horizontalSteps = 0
        var verticalSteps = 0
        var restoreCount = 0
        composeRule.setContent {
            FluoColorTheme {
                Plate96ReadyContent(
                    state = fixture.state,
                    onImageViewChange = {},
                    onShowOutlinesChange = {},
                    onShowLabelsChange = {},
                    onSelectSite = {},
                    onLocatorModeChange = {},
                    onConfirmCurrentOrientation = {},
                    onOriginCornerChange = {},
                    onRestoreAutomaticOrientation = {},
                    onNudgeSelectedSite = { horizontal, vertical ->
                        horizontalSteps += horizontal
                        verticalSteps += vertical
                    },
                    onSetSelectedSiteRadius = {},
                    onRestoreSelectedSite = { restoreCount++ },
                    onContinue = {}
                )
            }
        }

        composeRule.onNodeWithTag(Plate96LocalizationTestTags.MANUAL_ADJUST_BUTTON)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.ADJUSTMENT_SHEET).assertIsDisplayed()
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.CROP_PREVIEW).assertIsDisplayed()
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.NUDGE_RIGHT).performClick()
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.NUDGE_UP).performClick()
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.RESTORE_AUTOMATIC).performClick()
        composeRule.runOnIdle {
            assertEquals(1, horizontalSteps)
            assertEquals(-1, verticalSteps)
            assertEquals(1, restoreCount)
        }
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.DONE).performClick()

    }

    /**
     * 仅在显式传入参数时保留页面30秒，供ADB截取真实设备画面。
     * 普通回归会跳过，避免固定等待拖慢测试套件。
     */
    @Test
    fun visualPreview_holdsForAdbReview() {
        val shouldHold = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_HOLD_SCREENSHOT)
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue("未请求ADB视觉自审，跳过保留页面", shouldHold)
        val fixture = fixture(selectedSiteIndex = 27, showLabels = true)
        composeRule.setContent {
            FluoColorTheme {
                Plate96ReadyContent(
                    state = fixture.state,
                    onImageViewChange = {},
                    onShowOutlinesChange = {},
                    onShowLabelsChange = {},
                    onSelectSite = {},
                    onLocatorModeChange = {},
                    onConfirmCurrentOrientation = {},
                    onOriginCornerChange = {},
                    onRestoreAutomaticOrientation = {},
                    onNudgeSelectedSite = { _, _ -> },
                    onSetSelectedSiteRadius = {},
                    onRestoreSelectedSite = {},
                    onContinue = {}
                )
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(SCREENSHOT_HOLD_MILLIS)
    }

    /** 显式打开微调面板并保留页面，供ADB检查底部面板在360dp下是否拥挤。 */
    @Test
    fun visualAdjustment_holdsForAdbReview() {
        val shouldHold = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_HOLD_ADJUSTMENT_SCREENSHOT)
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue("未请求微调面板ADB视觉自审，跳过保留页面", shouldHold)
        val fixture = fixture(selectedSiteIndex = 27, showLabels = true)
        composeRule.setContent {
            FluoColorTheme {
                Plate96ReadyContent(
                    state = fixture.state,
                    onImageViewChange = {},
                    onShowOutlinesChange = {},
                    onShowLabelsChange = {},
                    onSelectSite = {},
                    onLocatorModeChange = {},
                    onConfirmCurrentOrientation = {},
                    onOriginCornerChange = {},
                    onRestoreAutomaticOrientation = {},
                    onNudgeSelectedSite = { _, _ -> },
                    onSetSelectedSiteRadius = {},
                    onRestoreSelectedSite = {},
                    onContinue = {}
                )
            }
        }
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.MANUAL_ADJUST_BUTTON)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(Plate96LocalizationTestTags.ADJUSTMENT_SHEET).assertIsDisplayed()
        Thread.sleep(SCREENSHOT_HOLD_MILLIS)
    }

    /**
     * 可选实拍视觉探针：直接使用设备Download中的用户图片运行完整YOLO+圆孔定位后展示页面。
     * 没有显式路径时自动跳过，避免把个人实验图片写入Git或固定CI资产。
     */
    @Test
    fun visualRealPhoto_holdsForAdbReview() {
        val arguments = InstrumentationRegistry.getArguments()
        val shouldHold = arguments.getString(ARGUMENT_HOLD_REAL_SCREENSHOT)
            ?.toBooleanStrictOrNull()
            ?: false
        val imagePath = arguments.getString(ARGUMENT_REAL_IMAGE_PATH)
        assumeTrue("未请求实拍ADB视觉自审", shouldHold && !imagePath.isNullOrBlank())
        val imageFile = File(requireNotNull(imagePath))
        assumeTrue("实拍图片不存在：$imagePath", imageFile.exists())
        check(OpenCVLoader.initDebug()) { "OpenCV初始化失败" }
        val source = requireNotNull(BitmapFactory.decodeFile(imageFile.absolutePath))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val locator = Plate96Locator(
            objectDetector = Plate96YoloDetector(instrumentation.targetContext),
            circleRefiner = com.muc.fluocolorquant.domain.detection.plate96.Plate96CircleRefiner(),
            orientationResolver = Plate96OrientationResolver(),
            gridAssembler = Plate96GridAssembler()
        )
        val session = runBlocking { locator.localizeSession(source) }
        val normalized = Plate96BitmapNormalizer.normalize(source, session.result.orientation)
        val state = Plate96LocalizationUiState.Ready(
            sourceBitmap = source,
            normalizedBitmap = normalized,
            session = session,
            imageViewMode = Plate96ImageViewMode.NORMALIZED,
            locatorMode = ArrayLocatorMode.AUTO,
            showOutlines = true,
            showLabels = false,
            selectedSiteIndex = null,
            orientationConfirmed = !session.orientationResolution.requiresOriginConfirmation,
            exifRotationDegrees = 0,
            exifFlipped = false
        )
        composeRule.setContent {
            FluoColorTheme {
                Plate96ReadyContent(
                    state = state,
                    onImageViewChange = {},
                    onShowOutlinesChange = {},
                    onShowLabelsChange = {},
                    onSelectSite = {},
                    onLocatorModeChange = {},
                    onConfirmCurrentOrientation = {},
                    onOriginCornerChange = {},
                    onRestoreAutomaticOrientation = {},
                    onNudgeSelectedSite = { _, _ -> },
                    onSetSelectedSiteRadius = {},
                    onRestoreSelectedSite = {},
                    onContinue = {}
                )
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(SCREENSHOT_HOLD_MILLIS)
    }

    /** 使用规则圆阵生成不依赖YOLO模型的确定性页面状态。 */
    private fun fixture(
        selectedSiteIndex: Int?,
        showLabels: Boolean = false
    ): LocalizationFixture {
        val source = createPlateBitmap()
        val circles = circles()
        val resolver = Plate96OrientationResolver()
        val resolution = resolver.resolve(
            circles.mapIndexed { index, circle ->
                Plate96CircleObservation(
                    x = circle.centerX,
                    y = circle.centerY,
                    radius = circle.radius,
                    confidence = circle.confidence,
                    observationIndex = index
                )
            }
        )
        val result = Plate96GridAssembler().assemble(
            sourceWidth = source.width,
            sourceHeight = source.height,
            circles = circles,
            resolution = resolution
        )
        val normalized = Plate96BitmapNormalizer.normalize(source, result.orientation)
        val session = Plate96Locator.Session(
            circles = circles,
            orientationResolution = resolution,
            result = result
        )
        return LocalizationFixture(
            state = Plate96LocalizationUiState.Ready(
                sourceBitmap = source,
                normalizedBitmap = normalized,
                session = session,
                imageViewMode = Plate96ImageViewMode.NORMALIZED,
                locatorMode = ArrayLocatorMode.AUTO,
                showOutlines = true,
                showLabels = showLabels,
                selectedSiteIndex = selectedSiteIndex,
                orientationConfirmed = true,
                exifRotationDegrees = 0,
                exifFlipped = false
            ),
            source = source,
            normalized = normalized
        )
    }

    /** 绘制接近真实孔板明暗层次的设备测试图，截图时可以直接检查圆孔标签密度。 */
    private fun createPlateBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(236, 239, 235))
        canvas.drawRoundRect(
            18f,
            18f,
            702f,
            462f,
            28f,
            28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 57, 62) }
        )
        circles().forEachIndexed { index, circle ->
            val row = index / 12
            val column = index % 12
            val intensity = 206 - ((row * 9 + column * 5) % 54)
            canvas.drawCircle(
                circle.centerX.toFloat(),
                circle.centerY.toFloat(),
                circle.radius.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(intensity, intensity + 9, intensity + 3)
                }
            )
            canvas.drawCircle(
                circle.centerX.toFloat(),
                circle.centerY.toFloat(),
                circle.radius.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    color = Color.rgb(117, 132, 136)
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

    private data class LocalizationFixture(
        val state: Plate96LocalizationUiState.Ready,
        val source: Bitmap,
        val normalized: Bitmap
    )

    private companion object {
        const val ARGUMENT_HOLD_SCREENSHOT: String = "holdPlate96Screenshot"
        const val ARGUMENT_HOLD_ADJUSTMENT_SCREENSHOT: String = "holdPlate96AdjustmentScreenshot"
        const val ARGUMENT_HOLD_REAL_SCREENSHOT: String = "holdPlate96RealScreenshot"
        const val ARGUMENT_REAL_IMAGE_PATH: String = "plate96ImagePath"
        const val SCREENSHOT_HOLD_MILLIS: Long = 30_000L
    }
}

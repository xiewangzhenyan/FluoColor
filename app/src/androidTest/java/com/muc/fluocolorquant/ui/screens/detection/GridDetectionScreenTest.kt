package com.muc.fluocolorquant.ui.screens.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsEnabled
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.ManualGridGeometry
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocatorConfig
import androidx.test.platform.app.InstrumentationRegistry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationMethod
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationSnapshot
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationDraft
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.GridAnalysisModelOption
import com.muc.fluocolorquant.domain.detection.GridDetectionStage
import com.muc.fluocolorquant.domain.detection.GridExperimentTemplateOption
import com.muc.fluocolorquant.domain.detection.GridLayoutConfigurationSource
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisPreferences
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisScope
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.array.MultiHoleStatistic
import com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocator
import com.muc.fluocolorquant.domain.detection.grid.compareOnImage
import com.muc.fluocolorquant.domain.detection.photometry.WellReadoutMode
import com.muc.fluocolorquant.ui.screens.settings.ArrayReadoutDefaultsCard
import com.muc.fluocolorquant.ui.screens.settings.DetectionModeSegments
import com.muc.fluocolorquant.ui.viewmodels.ArrayLocatorAttempt
import com.muc.fluocolorquant.ui.viewmodels.ArrayLocatorAttemptStatus
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiState
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutAssignmentDraft
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationAnalyte
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationEvidence
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationPreview
import com.muc.fluocolorquant.ui.viewmodels.GridPaintMergeResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.opencv.android.OpenCVLoader

/** 微流控检测网关 UI 测试，确保阶段进度和完成结果不再复用旧孔位文案。 */
@RunWith(AndroidJUnit4::class)
class GridDetectionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun comparisonInNormalPickerDisplaysOwnGridAndNoAnalysisAction() {
        val device = InstrumentationRegistry.getInstrumentation()
        val input = InstrumentationRegistry.getArguments().getString("locatorChipImage")
        // 常规回归用冻结图；视觉复验允许显式指定缓存里的 15×15 原图，不把用户素材加入测试 APK。
        val bitmap = if (input == null) {
            device.context.assets.open("pg_grid/synthetic_10x10_dark_squares.png")
                .use { requireNotNull(BitmapFactory.decodeStream(it)) }
        } else requireNotNull(BitmapFactory.decodeFile(java.io.File(device.targetContext.cacheDir, input).path))
        val size = if (input == null) 10 else 15
        check(OpenCVLoader.initDebug())
        val grid = runBlocking { requireNotNull(OpenCvPgGridLocator().compareOnImage(bitmap, size, size, ArrayLocatorMode.LOG_SPOTS)) }
        val ready = GridDetectionUiState.LocalizationReady(quantitationPreview())
        composeRule.setContent {
            var state by remember { mutableStateOf<GridDetectionUiState>(ready) }
            com.muc.fluocolorquant.ui.theme.FluoColorTheme {
                GridDetectionGatewayContent(state, {}, {}, {}, {}, {}, { GridPaintMergeResult(emptyMap(), 0) }, {},
                    onChooseLocator = { mode ->
                        // 这里特意注入一次无网格，检查页面不会沿用上一个方法的叠加图或显示定量入口。
                        val status = when (mode) {
                            ArrayLocatorMode.LOG_SPOTS -> ArrayLocatorAttemptStatus.Preview(grid)
                            ArrayLocatorMode.KMEANS -> ArrayLocatorAttemptStatus.Running
                            else -> ArrayLocatorAttemptStatus.NoGrid
                        }
                        state = GridDetectionUiState.LocatingArray(ArrayLocatorAttempt(bitmap, ArrayAnalysisScope.CHIP,
                            mode, status, ArrayLocatorMode.AUTO))
                    }, onRestoreLocalization = { state = ready })
            }
        }
        fun choose(@androidx.annotation.StringRes label: Int, selected: Int) {
            composeRule.onNodeWithText(string(selected)).performScrollTo().performClick()
            composeRule.onNode(hasAnyAncestor(hasTestTag("array_option_sheet")) and hasScrollAction())
                .performScrollToNode(hasText(string(label)))
            composeRule.onNodeWithText(string(label)).performClick()
        }
        val frame = composeRule.onNodeWithTag("array_localization_preview")
        val initialFrame = frame.fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithText(string(R.string.grid_localization_description)).assertDoesNotExist()
        composeRule.onNodeWithTag("array_localization_help").performClick()
        composeRule.onNodeWithText(string(R.string.array_localization_comparison_help)).assertExists()
        composeRule.onNodeWithText(string(R.string.close)).performClick()

        choose(R.string.array_locator_log, R.string.array_locator_pg)
        assertEquals(initialFrame, frame.fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithTag("array_locator_comparison_overlay").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.array_localization_continue)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.array_localization_comparison_help)).assertDoesNotExist()
        val screen = composeRule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(device.targetContext.filesDir, "array-locator-comparison-preview.png").outputStream().use {
            screen.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        choose(R.string.array_locator_dbscan, R.string.array_locator_log)
        assertEquals(initialFrame, frame.fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithTag("array_locator_comparison_overlay").assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.array_localization_no_grid)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("array_locator_restore").performClick()
        composeRule.onNodeWithText(string(R.string.array_localization_continue)).assertIsEnabled()
        choose(R.string.array_locator_kmeans, R.string.array_locator_pg)
        composeRule.onNodeWithTag("array_locator_comparison_overlay").assertDoesNotExist()
        composeRule.onNodeWithTag("array_locator_restore").assertIsNotEnabled()
        composeRule.onNodeWithTag("grid_manual_entry").assertIsNotEnabled()
    }

    @Test
    fun localizationEvidenceShowsPreviewTabsWithoutDuplicateReadoutPanel() {
        val preview = quantitationPreview().copy(evidence = listOf(
            CaptureRole.PROCESS_ORIGINAL_GEOMETRY, CaptureRole.PROCESS_RECTIFIED,
            CaptureRole.PROCESS_ROI_BACKGROUND, CaptureRole.PROCESS_GRID_OVERLAY
        ).map { role -> GridLocalizationEvidence(role, "unused-${role.code}.png") })
        val ready = GridDetectionUiState.LocalizationReady(preview)
        val state = mutableStateOf(ready)
        composeRule.setContent {
            com.muc.fluocolorquant.ui.theme.FluoColorTheme {
                GridDetectionGatewayContent(state.value, {}, {}, {}, {}, {}, { GridPaintMergeResult(emptyMap(), 0) }, {})
            }
        }
        listOf(R.string.grid_evidence_original, R.string.grid_evidence_rectified,
            R.string.array_readout_region_preview, R.string.grid_evidence_overlay).forEach { label ->
            // 英文的 Grid 同时可作规格标签，只核对可点击的预览选项。
            composeRule.onNode(hasText(string(label)) and hasClickAction()).performScrollTo().assertIsDisplayed()
        }
        // 定位确认页不再重复展示「取值设置」折叠面板（统一收口至系统设置 -> 检测默认值）
        composeRule.onNodeWithText(string(R.string.array_detection_current)).assertDoesNotExist()
        composeRule.onNodeWithTag("array_localization_continue").assertIsEnabled()

        composeRule.runOnIdle {
            state.value = ready.copy(readoutStage = GridDetectionStage.PHOTOMETRY)
        }
        composeRule.onNodeWithTag("array_localization_preview").assertExists()
        composeRule.onNodeWithTag("array_localization_continue").assertIsNotEnabled()
    }

    @Test
    fun detectionSettingsUnifiedReadoutCardSupportsCenterMedianAndReset() {
        val writes = mutableListOf<ArrayAnalysisPreferences>()
        composeRule.setContent {
            var mode by remember { mutableStateOf(DetectionModality.FLUORESCENCE) }
            var readoutPrefs by remember { mutableStateOf(ArrayAnalysisPreferences()) }
            com.muc.fluocolorquant.ui.theme.FluoColorTheme {
                androidx.compose.foundation.layout.Column {
                    DetectionModeSegments(
                        title = string(R.string.default_detection_mode),
                        selected = mode,
                        onSelect = { mode = it }
                    )
                    ArrayReadoutDefaultsCard(
                        config = readoutPrefs,
                        onSave = { updated ->
                            writes += updated
                            readoutPrefs = updated
                        }
                    )
                }
            }
        }
        val colorBounds = composeRule.onNode(hasTestTag("detection_mode_COLORIMETRIC") and hasClickAction())
            .fetchSemanticsNode().boundsInRoot
        val fluoBounds = composeRule.onNode(hasTestTag("detection_mode_FLUORESCENCE") and hasClickAction())
            .fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(
            abs(colorBounds.top - fluoBounds.top) <= 1f && abs(colorBounds.bottom - fluoBounds.bottom) <= 1f
        )

        // 默认态不展示恢复推荐配置按钮
        composeRule.onNodeWithTag("array_readout_reset").assertDoesNotExist()

        // 切换「孔内取值」为「中心区域中位数」，显示中心比例滑块与同口径重标定提示
        composeRule.onNode(hasAnyAncestor(hasTestTag("array_readout_picker")) and hasClickAction()).performClick()
        composeRule.onNodeWithText(string(R.string.array_readout_center_median)).performClick()
        composeRule.onNodeWithText(string(R.string.array_readout_center_percent, 80)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.array_readout_recalibrate)).assertIsDisplayed()

        // 切换「多孔统计」为「取平均值」
        composeRule.onNode(hasAnyAncestor(hasTestTag("array_group_picker")) and hasClickAction()).performClick()
        composeRule.onNodeWithText(string(R.string.array_group_mean)).performClick()
        composeRule.runOnIdle {
            assertEquals(WellReadoutMode.CENTER_MEDIAN, writes.last().readout)
            assertEquals(MultiHoleStatistic.MEAN, writes.last().multiHoleStatistic)
        }

        // 点击「恢复推荐配置」一键回到全孔中位数 + 分别显示
        composeRule.onNodeWithTag("array_readout_reset").performClick()
        composeRule.runOnIdle {
            assertEquals(ArrayAnalysisPreferences(), writes.last())
        }
    }

    @Test
    fun manualEntryFromFailureRequiresFourValidCornerCenters() {
        // 宽图在正方形视口有上下留白；这个用例防止把留白误当角孔、误用屏幕像素或少一个点就允许应用。
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) }
        var applied = 0
        var captured = emptyList<GridPoint>()
        composeRule.setContent {
            var state by remember { mutableStateOf<GridDetectionUiState>(GridDetectionUiState.LocatingArray(
                ArrayLocatorAttempt(bitmap, ArrayAnalysisScope.CHIP, ArrayLocatorMode.AUTO, ArrayLocatorAttemptStatus.NoGrid))) }
            fun update(points: List<GridPoint>) {
                val preview = if (points.size == 4) ManualGridGeometry.build(400,200,PgGridLocatorConfig(10,15,GridTargetPolarity.DARK),points)
                    .sites.map { it.original } else emptyList()
                captured = points
                state = GridDetectionUiState.ManualCorners(bitmap,10,15,points,preview)
            }
            MaterialTheme {
                GridDetectionGatewayContent(state, {}, {}, {}, {}, {}, { GridPaintMergeResult(emptyMap(), 0) }, {},
                    onManualCorners = { update(emptyList()) }, onMarkCorner = { update(captured + it) },
                    onUndoCorner = { update(captured.dropLast(1)) }, onClearCorners = { update(emptyList()) }, onApplyCorners = { applied++ })
            }
        }
        composeRule.onNodeWithTag("grid_manual_entry").performClick()
        val canvas = composeRule.onNodeWithTag("grid_manual_canvas")
        fun tap(horizontal: Float, vertical: Float) = canvas.performScrollTo().performTouchInput {
            click(Offset(width * horizontal, height * vertical))
        }
        tap(.3f,.05f)
        composeRule.runOnIdle { assertEquals(0, captured.size) }
        tap(.2f,.32f); tap(.8f,.32f); tap(.8f,.68f)
        composeRule.onNodeWithTag("grid_manual_apply").assertIsNotEnabled()
        tap(.2f,.68f)
        composeRule.onNodeWithTag("grid_manual_apply").assertIsEnabled()
        composeRule.runOnIdle { assertEquals(79.5, captured.first().x, 1.0); assertEquals(27.5, captured.first().y, 1.0) }
        composeRule.onNodeWithTag("grid_manual_undo").performScrollTo().performClick()
        composeRule.onNodeWithTag("grid_manual_apply").assertIsNotEnabled()
        tap(.2f,.68f)
        composeRule.onNodeWithTag("grid_manual_apply").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, applied) }
    }

    @Test
    fun manualGridRealPhotoPreview() {
        // 真机界面自审只使用实拍图；自动金标准坐标仅作已标注 UI 夹具，并非人工精度真值。
        val device = InstrumentationRegistry.getInstrumentation()
        val bitmap = device.context.assets.open("pg_grid/real_v1/images/real_15x15_01.jpg").use { android.graphics.BitmapFactory.decodeStream(it)!! }
        val base = com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocator()
        check(org.opencv.android.OpenCVLoader.initDebug())
        val config = PgGridLocatorConfig(15,15,GridTargetPolarity.DARK)
        val fixture = base.locate(bitmap,config)
        val anchors = listOf(0,14,224,210).map { fixture.sites[it].original }
        val grid = ManualGridGeometry.build(bitmap.width,bitmap.height,config,anchors)
        composeRule.setContent {
            com.muc.fluocolorquant.ui.theme.FluoColorTheme {
                GridDetectionGatewayContent(GridDetectionUiState.ManualCorners(bitmap,15,15,anchors,grid.sites.map { it.original }),
                    {}, {}, {}, {}, {}, { GridPaintMergeResult(emptyMap(), 0) }, {})
            }
        }
        composeRule.onNodeWithTag("grid_manual_apply").assertIsEnabled()
        composeRule.waitForIdle()
        val screen = composeRule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(device.targetContext.filesDir, "manual-grid-preview.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test
    fun spotInspectorFlagBadAndCenterMedianWorkOnRealChipPreview() {
        val device = InstrumentationRegistry.getInstrumentation()
        val context = device.targetContext
        val bitmap = device.context.assets.open("pg_grid/real_v1/images/real_15x15_01.jpg")
            .use { requireNotNull(BitmapFactory.decodeStream(it)) }
        check(OpenCVLoader.initDebug())
        val locator = OpenCvPgGridLocator()
        val grid = locator.locate(bitmap, PgGridLocatorConfig(15, 15, GridTargetPolarity.DARK))
        val segmentation = com.muc.fluocolorquant.domain.detection.segmentation.OpenCvArrayUnitSegmenter()
            .segment(bitmap, grid, com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape.SQUARE)
        val initialQuant = com.muc.fluocolorquant.domain.detection.photometry.PgQuantSampler.sample(
            bitmap = bitmap,
            grid = grid,
            unitSegmentation = segmentation
        )
        val runId = "ui-spot-inspector-review"
        val evidence = com.muc.fluocolorquant.domain.detection.evidence.AndroidGridProcessingEvidenceWriter(context)
            .write(runId, bitmap, grid, initialQuant)
        val rectifiedPath = evidence.firstOrNull { it.role == CaptureRole.PROCESS_RECTIFIED }?.path

        fun buildPreview(
            quant: com.muc.fluocolorquant.domain.detection.photometry.PgQuantResult,
            prefs: ArrayAnalysisPreferences
        ): GridLocalizationPreview = quantitationPreview().copy(
            runId = runId,
            rows = grid.rows,
            columns = grid.columns,
            siteCount = grid.sites.size,
            observedRatio = grid.geometry.observedRatio,
            meanConfidence = grid.geometry.meanConfidence,
            rectifiedImagePath = rectifiedPath,
            rectifiedWidth = grid.rectifiedWidth,
            rectifiedHeight = grid.rectifiedHeight,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height,
            trustedGeometry = grid.geometry.trusted,
            analysisPreferences = prefs,
            evidence = evidence.map { GridLocalizationEvidence(it.role, it.path) },
            sites = grid.sites.map { site ->
                val sp = quant.sites[site.siteIndex]
                com.muc.fluocolorquant.ui.viewmodels.GridLocalizationSitePreview(
                    siteIndex = site.siteIndex,
                    rowIndex = site.key.rowIndex,
                    columnIndex = site.key.columnIndex,
                    rectifiedX = site.rectified.x,
                    rectifiedY = site.rectified.y,
                    cropRegion = segmentation.regions[site.siteIndex],
                    originalX = site.original.x,
                    originalY = site.original.y,
                    foregroundMedianGray = sp.roiMedianGray,
                    foregroundMeanGray = sp.roiMeanGray,
                    selectedReadoutGray = sp.foregroundGray,
                    backgroundMedianGray = sp.backgroundMedianGray,
                    signalToNoiseRatio = sp.signalToNoiseRatio,
                    pctAboveBgPlus2Sd = sp.pctAboveBgPlus2Sd,
                    qualityReliable = sp.qc.qualityReliable,
                    manuallyFlaggedBad = sp.manuallyFlaggedBad
                )
            }
        )

        var badSites by mutableStateOf(emptySet<Int>())
        var adjustedOffsets by mutableStateOf(emptyMap<Int, Pair<Int, Int>>())
        composeRule.setContent {
            val activeQuant = remember(badSites) { initialQuant.withManualBadSites(badSites) }
            val activePreview = remember(activeQuant, adjustedOffsets) {
                val base = buildPreview(activeQuant, ArrayAnalysisPreferences())
                base.copy(
                    sites = base.sites.map { site ->
                        val offset = adjustedOffsets[site.siteIndex]
                        if (offset == null) site else site.copy(
                            rectifiedX = site.autoRectifiedX + offset.first,
                            rectifiedY = site.autoRectifiedY + offset.second,
                            isUserAdjusted = true
                        )
                    }
                )
            }
            com.muc.fluocolorquant.ui.theme.FluoColorTheme {
                GridDetectionGatewayContent(
                    state = GridDetectionUiState.LocalizationReady(activePreview),
                    onBack = {},
                    onRetry = {},
                    onOpenLayout = {},
                    onReviewLocalization = {},
                    onAssignmentsChange = {},
                    onPaintAssignments = { GridPaintMergeResult(emptyMap(), 0) },
                    onFinalizeLayout = {},
                    onToggleManualBadSite = { idx ->
                        badSites = if (idx in badSites) badSites - idx else badSites + idx
                    },
                    onNudgeRegularSite = { idx, dx, dy ->
                        val cur = adjustedOffsets[idx] ?: (0 to 0)
                        adjustedOffsets = adjustedOffsets + (idx to (cur.first + dx to cur.second + dy))
                    },
                    onRestoreRegularSite = { idx ->
                        adjustedOffsets = adjustedOffsets - idx
                    }
                )
            }
        }
        composeRule.waitForIdle()
        // 点击网格中心孔（H8，约在画布正中）打开单孔检视卡
        composeRule.onNodeWithTag("array_localization_preview").performTouchInput {
            click(Offset(width * 0.50f, height * 0.50f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("grid_spot_adjust_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("grid_spot_nudge_right").performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.grid_spot_inspector_seg_adjusted)).assertIsDisplayed()
        val inspectorScreen = composeRule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.filesDir, "localization-spot-inspector-preview.png").outputStream().use {
            inspectorScreen.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        composeRule.onNodeWithText(string(R.string.grid_spot_adjust_restore)).performClick()
        composeRule.onNodeWithTag("grid_spot_adjust_toggle").performClick()

        composeRule.onNodeWithText(string(R.string.grid_spot_flag_bad)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.grid_spot_unflag_bad)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_localization_excluded_count, 1))
            .performScrollTo()
            .assertIsDisplayed()
        bitmap.recycle()
    }

    @Test
    fun `定位阶段显示微流控专用进度`() {
        composeRule.setContent {
            MaterialTheme {
                GridDetectionGatewayContent(
                    state = GridDetectionUiState.Processing(GridDetectionStage.LOCATING),
                    onBack = {},
                    onRetry = {},
                    onOpenLayout = {},
                    onReviewLocalization = {},
                    onAssignmentsChange = {},
                    onPaintAssignments = { GridPaintMergeResult(emptyMap(), 0) },
                    onFinalizeLayout = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_stage_locating)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_detection_processing_description)).assertIsDisplayed()
    }

    @Test
    fun `完成状态直接显示正在打开结果而不是中转卡`() {
        composeRule.setContent {
            MaterialTheme {
                GridDetectionGatewayContent(
                    state = GridDetectionUiState.Completed(
                        runId = "run-1",
                        measurementCount = 100,
                        signalOnlyAnalyteIds = emptySet(),
                        frameQcIssueCount = 2
                    ),
                    onBack = {},
                    onRetry = {},
                    onOpenLayout = {},
                    onReviewLocalization = {},
                    onAssignmentsChange = {},
                    onPaintAssignments = { GridPaintMergeResult(emptyMap(), 0) },
                    onFinalizeLayout = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_detection_opening_results)).assertIsDisplayed()
    }

    @Test
    fun `孔位角色一级工具只显示高频角色且更多菜单保留科研角色`() {
        composeRule.setContent {
            MaterialTheme {
                GridRolePalette(
                    selectedRole = TemplateSiteRole.SAMPLE,
                    clearMode = false,
                    onRoleSelected = {},
                    onClearSelected = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.template_array_role_sample)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.template_array_role_standard)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.template_array_role_blank)).assertIsDisplayed()
        // 一级工具栏使用短标签以保证英文和360dp窄屏不截断；长文案只用于说明区域。
        composeRule.onNodeWithText(string(R.string.grid_layout_clear_short)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.template_array_role_negative_control))
            .assertCountEquals(0)

        composeRule.onNodeWithText(string(R.string.grid_layout_more_short)).performClick()
        composeRule.onNodeWithText(string(R.string.template_array_role_negative_control))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.template_array_role_positive_control))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.template_array_role_reference))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.template_array_role_disabled))
            .assertIsDisplayed()
    }

    @Test
    fun `孔位布局底部同时提供模板入口和四种逐分析物定量方式`() {
        composeRule.setContent {
            MaterialTheme {
                // 单独为长配置区提供滚动容器，模拟它在真实孔位布局页中的纵向浏览行为。
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    GridExperimentConfigurationSection(
                        preview = quantitationPreview(),
                        assignments = sampleAssignments(),
                        onUseManualConfiguration = {},
                        onApplyTemplate = {},
                        onSetQuantitationMode = { _, _ -> },
                        onSelectAnalysisModel = { _, _ -> },
                        onUpdateOnsiteAdvanced = { _, _, _ -> },
                        onPreviewOnsiteFit = {},
                        onSaveTemplate = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_configuration_use_template))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_configuration_manual))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_quant_mode_onsite))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_quant_mode_curve))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_quant_mode_deep_learning))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            string(R.string.grid_quant_no_compatible_deep_learning_hint)
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_quant_mode_signal_only))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_save_as_experiment_template))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `第二分析物没有已发布曲线时标准曲线入口仍可进入但不能误确认`() {
        var selectedAnalyteId: String? = null
        var selectedMode: GridAnalyteQuantitationMode? = null
        val initialPreview = quantitationPreview().copy(
            analytes = listOf(
                GridLocalizationAnalyte(
                    id = "cea",
                    name = "CEA",
                    concentrationUnit = "ng/mL",
                    maxConcentration = 100.0
                ),
                GridLocalizationAnalyte(
                    id = "cyfra",
                    name = "CYFRA21-1",
                    concentrationUnit = "ng/mL",
                    maxConcentration = 100.0
                )
            ),
            selectedQuantitationAnalyteId = "cyfra",
            // 只有第一个分析物拥有已发布曲线，用于固定“资源不能跨分析物复用”的边界。
            availableModels = listOf(
                GridAnalysisModelOption(
                    id = "cea-curve",
                    name = "CEA curve",
                    version = 1,
                    analyteId = "cea",
                    modelType = AnalysisModelType.STANDARD_CURVE,
                    primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
                    concentrationUnit = "ng/mL"
                )
            ),
            quantitationDrafts = listOf(
                quantitationPreview().quantitationDrafts.single(),
                GridAnalyteQuantitationDraft(
                    analyteId = "cyfra",
                    mode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT
                )
            )
        )

        composeRule.setContent {
            MaterialTheme {
                var preview by remember { mutableStateOf(initialPreview) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    GridExperimentConfigurationSection(
                        preview = preview,
                        assignments = sampleAssignments(),
                        onUseManualConfiguration = {},
                        onApplyTemplate = {},
                        onSetQuantitationMode = { analyteId, mode ->
                            selectedAnalyteId = analyteId
                            selectedMode = mode
                            // 模拟 ViewModel 接收方式选择后的不可变状态更新，以继续核查空资源与确认门控。
                            preview = preview.copy(
                                quantitationDrafts = preview.quantitationDrafts.map { draft ->
                                    if (draft.analyteId == analyteId) {
                                        draft.copy(
                                            mode = mode,
                                            selectedAnalysisModelId = null,
                                            appliedSnapshot = null
                                        )
                                    } else {
                                        draft
                                    }
                                }
                            )
                        },
                        onSelectAnalysisModel = { _, _ -> },
                        onUpdateOnsiteAdvanced = { _, _, _ -> },
                        onPreviewOnsiteFit = {},
                        onSaveTemplate = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_quant_mode_curve))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals("cyfra", selectedAnalyteId)
            assertEquals(GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE, selectedMode)
        }
        composeRule.onNodeWithText(string(R.string.grid_quant_no_models))
            .performScrollTo()
            .assertIsDisplayed()
        // 入口可以进入不代表允许空资源通过；必须选择当前分析物的真实兼容曲线后才能确认。
        composeRule.onNodeWithText(string(R.string.grid_quant_complete_current))
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun `微流控内置模型不显示常驻警告但选择时必须确认`() {
        var selectedModelId: String? = null
        val preview = quantitationPreview().copy(
            availableModels = listOf(
                GridAnalysisModelOption(
                    id = "builtin-shared",
                    name = "stable-machine-name",
                    version = 1,
                    analyteId = "cea",
                    modelType = AnalysisModelType.DEEP_LEARNING,
                    primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
                    concentrationUnit = "ng/mL",
                    reliableRangeMin = 0.0,
                    reliableRangeMax = 100.0,
                    builtInShared = true,
                    requiresExplicitScopeConfirmation = true
                )
            ),
            quantitationDrafts = listOf(
                GridAnalyteQuantitationDraft(
                    analyteId = "cea",
                    mode = GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL
                )
            )
        )
        composeRule.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    GridExperimentConfigurationSection(
                        preview = preview,
                        assignments = sampleAssignments(),
                        onUseManualConfiguration = {},
                        onApplyTemplate = {},
                        onSetQuantitationMode = { _, _ -> },
                        onSelectAnalysisModel = { _, modelId -> selectedModelId = modelId },
                        onUpdateOnsiteAdvanced = { _, _, _ -> },
                        onPreviewOnsiteFit = {},
                        onSaveTemplate = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ArrayLayoutEditorTestTags.MODEL_SELECTOR)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(
            ArrayLayoutEditorTestTags.MODEL_OPTION_PREFIX + "builtin-shared"
        )
            .performClick()
        composeRule.onNodeWithText(string(R.string.grid_quant_model_scope_dialog_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.grid_quant_model_scope_confirm))
            .performClick()
        composeRule.runOnIdle { assertEquals("builtin-shared", selectedModelId) }
    }

    @Test
    fun `实验模板入口展示兼容模板并把选择结果回传`() {
        var selectedTemplateId: String? = null
        composeRule.setContent {
            MaterialTheme {
                GridExperimentConfigurationSection(
                    preview = quantitationPreview(),
                    assignments = sampleAssignments(),
                    onUseManualConfiguration = {},
                    onApplyTemplate = { selectedTemplateId = it },
                    onSetQuantitationMode = { _, _ -> },
                    onSelectAnalysisModel = { _, _ -> },
                    onUpdateOnsiteAdvanced = { _, _, _ -> },
                    onPreviewOnsiteFit = {},
                    onSaveTemplate = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_configuration_use_template)).performClick()
        composeRule.onNodeWithText(string(R.string.grid_template_dialog_title)).assertIsDisplayed()
        composeRule.onNodeWithText("15×15 双分析物模板").performClick()
        composeRule.runOnIdle {
            assertEquals("template-15", selectedTemplateId)
        }
    }

    @Test
    fun `现场标定逐标准孔显示固定单位且不再要求画笔预填浓度`() {
        val standards = (0 until 3).associateWith { column ->
            GridLayoutAssignmentDraft(
                rowIndex = 0,
                columnIndex = column,
                analyteId = "cea",
                role = TemplateSiteRole.STANDARD,
                standardConcentration = null
            )
        }
        composeRule.setContent {
            MaterialTheme {
                GridExperimentConfigurationSection(
                    preview = quantitationPreview(),
                    assignments = standards,
                    onUseManualConfiguration = {},
                    onApplyTemplate = {},
                    onSetQuantitationMode = { _, _ -> },
                    onSelectAnalysisModel = { _, _ -> },
                    onUpdateOnsiteAdvanced = { _, _, _ -> },
                    onPreviewOnsiteFit = {},
                    onSaveTemplate = {}
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.grid_quant_enter_standards)).performClick()
        composeRule.onNodeWithText("A1").assertIsDisplayed()
        composeRule.onNodeWithText("A2").assertIsDisplayed()
        composeRule.onNodeWithText("A3").assertIsDisplayed()
        // 单位是输入框右侧的独立固定列，空值且未聚焦时仍必须全部可见。
        composeRule.onAllNodesWithText("ng/mL").assertCountEquals(3)
    }

    /** 构造与真实流程一致的手动配置摘要，验证模板与定量工具不是二选一模型。 */
    private fun quantitationPreview(): GridLocalizationPreview {
        return GridLocalizationPreview(
            runId = "run-layout",
            originalImageUri = "content://test/real-chip.jpg",
            detectionMode = DetectionModality.FLUORESCENCE.code,
            rows = 15,
            columns = 15,
            siteCount = 225,
            observedRatio = 1.0,
            meanConfidence = 0.95,
            frameQcIssueCount = 0,
            rectifiedImagePath = null,
            cropHalfSizePx = 14.0,
            sites = emptyList(),
            analytes = listOf(
                GridLocalizationAnalyte(
                    id = "cea",
                    name = "CEA",
                    concentrationUnit = "ng/mL",
                    maxConcentration = 100.0
                )
            ),
            evidence = emptyList(),
            configurationSource = GridLayoutConfigurationSource.MANUAL,
            availableTemplates = listOf(
                GridExperimentTemplateOption(
                    id = "template-15",
                    name = "15×15 双分析物模板",
                    version = 1,
                    analyteIds = setOf("cea")
                )
            ),
            quantitationDrafts = listOf(
                GridAnalyteQuantitationDraft(
                    analyteId = "cea",
                    mode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT,
                    selectedFeature = AnalysisPrimaryFeature.FLUORESCENCE_SNR,
                    // 已确认后才展示“保存为实验模板”，与真实页面门控保持一致。
                    appliedSnapshot = AnalyteQuantitationSnapshot(
                        analyteId = "cea",
                        method = AnalyteQuantitationMethod.ONSITE_CALIBRATION,
                        concentrationUnit = "ng/mL",
                        processorVersion = "test",
                        inputFingerprint = "test"
                    )
                )
            )
        )
    }

    /** 至少保留一个有效孔位，使“保存为实验模板”入口处于真实可用状态。 */
    private fun sampleAssignments(): Map<Int, GridLayoutAssignmentDraft> {
        return mapOf(
            0 to GridLayoutAssignmentDraft(
                rowIndex = 0,
                columnIndex = 0,
                analyteId = "cea",
                role = TemplateSiteRole.SAMPLE,
                sampleId = "S-01"
            )
        )
    }

    private fun string(id: Int, vararg arguments: Any): String {
        return ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(id, *arguments)
    }
}

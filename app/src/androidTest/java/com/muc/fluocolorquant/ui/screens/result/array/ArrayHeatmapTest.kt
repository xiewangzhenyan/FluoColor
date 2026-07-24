package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayCarrierResult
import com.muc.fluocolorquant.domain.result.ArrayFrameResult
import com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail
import com.muc.fluocolorquant.domain.result.ArrayMeasurementQc
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteGeometry
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.viewmodels.ArrayResultUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 通用阵列热力图的 Compose 节点、规格与点击索引测试。 */
@RunWith(AndroidJUnit4::class)
class ArrayHeatmapTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `四种阵列规格都渲染完整单元且最后位点点击索引正确`() {
        val modelState = mutableStateOf(model(rows = 4, columns = 4))
        var clickedIndex: Int? = null
        composeRule.setContent {
            FluoColorTheme {
                ArrayHeatmap(
                    model = modelState.value,
                    onSiteClick = { clickedIndex = it.siteIndex }
                )
            }
        }

        listOf(4 to 4, 10 to 10, 15 to 15, 4 to 6).forEach { (rows, columns) ->
            composeRule.runOnIdle {
                clickedIndex = null
                modelState.value = model(rows, columns)
            }
            composeRule.waitForIdle()

            composeRule.onAllNodes(
                SemanticsMatcher("阵列热力图单元") { node ->
                    node.config.getOrElse(SemanticsProperties.TestTag) { "" }
                        .startsWith(ARRAY_HEATMAP_CELL_TAG_PREFIX)
                },
                useUnmergedTree = true
            ).assertCountEquals(rows * columns)

            val lastIndex = rows * columns - 1
            composeRule.onNodeWithTag(
                "$ARRAY_HEATMAP_CELL_TAG_PREFIX$lastIndex",
                useUnmergedTree = true
            ).performClick()
            composeRule.runOnIdle { assertEquals(lastIndex, clickedIndex) }
        }
    }

    @Test
    fun `十五乘十五阵列点击缩放按钮会立即放大并可恢复完整显示`() {
        val model = buildAnalyteHeatmapModel(
            rows = 15,
            columns = 15,
            analyte = analyte(),
            inputs = emptyList()
        )

        composeRule.setContent {
            FluoColorTheme {
                ArrayHeatmap(model = model, onSiteClick = {})
            }
        }

        composeRule.onNodeWithTag(ARRAY_HEATMAP_TRANSFORM_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ARRAY_HEATMAP_ZOOM_TOGGLE_TAG).assertIsDisplayed()
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.TestTag,
                ARRAY_HEATMAP_ZOOMED_CONTENT_TAG
            ),
            useUnmergedTree = true
        ).assertCountEquals(0)
        composeRule.onNodeWithTag("${ARRAY_HEATMAP_CELL_TAG_PREFIX}224", useUnmergedTree = true)
            .assertIsDisplayed()

        composeRule.onNodeWithTag(ARRAY_HEATMAP_ZOOM_TOGGLE_TAG).performClick()
        composeRule.onNodeWithTag(ARRAY_HEATMAP_ZOOMED_CONTENT_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(ARRAY_HEATMAP_ZOOM_TOGGLE_TAG).performClick()
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.TestTag,
                ARRAY_HEATMAP_ZOOMED_CONTENT_TAG
            ),
            useUnmergedTree = true
        ).assertCountEquals(0)
    }

    @Test
    fun `范围状态使用弱标记且不丢失浓度端点颜色`() {
        val analyte = analyte().copy(
            reliableRangeMax = 100.0,
            projectRangeMax = 100.0
        )
        val model = buildAnalyteHeatmapModel(
            rows = 15,
            columns = 15,
            analyte = analyte,
            inputs = List(15 * 15) { siteIndex ->
                when (siteIndex) {
                    0 -> heatmapInput(
                        siteIndex = siteIndex,
                        concentration = 20.0,
                        primaryFeature = 20.0,
                        rangeStatus = "ABOVE_RANGE",
                        quantificationStatus = "EXTRAPOLATED"
                    )
                    1 -> heatmapInput(
                        siteIndex = siteIndex,
                        concentration = null,
                        primaryFeature = 1.0,
                        rangeStatus = "BELOW_PROJECT_RANGE",
                        quantificationStatus = "OUTSIDE_PROJECT_RANGE"
                    )
                    2 -> heatmapInput(
                        siteIndex = siteIndex,
                        concentration = null,
                        primaryFeature = 200.0,
                        rangeStatus = "ABOVE_PROJECT_RANGE",
                        quantificationStatus = "OUTSIDE_PROJECT_RANGE"
                    )
                    else -> {
                        val concentration = siteIndex.toDouble() / (15 * 15 - 1) * 100.0
                        heatmapInput(
                            siteIndex = siteIndex,
                            concentration = concentration,
                            primaryFeature = concentration,
                            rangeStatus = "WITHIN_RANGE",
                            quantificationStatus = "QUANTIFIED"
                        )
                    }
                }
            }
        )

        assertEquals(0.0f, model.cells[1].normalizedValue)
        assertEquals(1.0f, model.cells[2].normalizedValue)
        assertFalse(model.cells[1].qc.failure)
        assertFalse(model.cells[2].qc.failure)

        composeRule.setContent {
            FluoColorTheme {
                Column {
                    ArrayHeatmap(model = model, onSiteClick = {})
                    // 将真实结果页图例与热力图一起渲染，直接验证三类范围状态的同排布局。
                    ArrayHeatmapQcLegend(model)
                }
            }
        }

        composeRule.onNodeWithTag(
            "$ARRAY_HEATMAP_EXTRAPOLATED_MARKER_TAG_PREFIX${0}",
            useUnmergedTree = true
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(
            "$ARRAY_HEATMAP_BELOW_RANGE_MARKER_TAG_PREFIX${1}",
            useUnmergedTree = true
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(
            "$ARRAY_HEATMAP_ABOVE_RANGE_MARKER_TAG_PREFIX${2}",
            useUnmergedTree = true
        ).assertIsDisplayed()

        // 三类范围状态必须保持在同一行，防止短文案修改后布局再次回退为两行。
        val rangeLegendTopPositions = listOf(
            ArrayHeatmapLegendKindForTest.EXTRAPOLATED,
            ArrayHeatmapLegendKindForTest.BELOW_PROJECT_RANGE,
            ArrayHeatmapLegendKindForTest.ABOVE_PROJECT_RANGE
        ).map { kind ->
            composeRule.onNodeWithTag(
                "$ARRAY_HEATMAP_LEGEND_TAG_PREFIX${kind.name}",
                useUnmergedTree = true
            ).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(rangeLegendTopPositions[0], rangeLegendTopPositions[1], 1f)
        assertEquals(rangeLegendTopPositions[0], rangeLegendTopPositions[2], 1f)
    }

    @Test
    fun `多分析物切换会替换当前热力图而不是混用色带`() {
        val snapshot = multiAnalyteSnapshot()
        composeRule.setContent {
            FluoColorTheme {
                ArrayResultContent(
                    state = ArrayResultUiState.Success(snapshot),
                    onBack = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithTag("${ARRAY_HEATMAP_CARD_TAG_PREFIX}analyte-1").assertIsDisplayed()
        composeRule.onNodeWithTag("array_overview_analyte_chip_analyte-2").performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag("${ARRAY_HEATMAP_CARD_TAG_PREFIX}analyte-2").assertIsDisplayed()
    }

    private fun analyte(): ArrayAnalyteResult {
        return ArrayAnalyteResult(
            analyteId = "analyte-1",
            name = "AFP",
            displayOrder = 0,
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.0,
            reliableRangeMax = 10.0,
            modelId = "model-1",
            modelName = "AFP model",
            modelType = "STANDARD_CURVE",
            modelVersion = 1,
            primaryFeature = "DELTA_E",
            processorName = "pg-color",
            processorVersion = "1"
        )
    }

    /** 与生产图例枚举名称保持一致，测试无需暴露页面内部的私有实现类型。 */
    private enum class ArrayHeatmapLegendKindForTest {
        EXTRAPOLATED,
        BELOW_PROJECT_RANGE,
        ABOVE_PROJECT_RANGE
    }

    private fun model(rows: Int, columns: Int): ArrayHeatmapModel {
        return buildAnalyteHeatmapModel(
            rows = rows,
            columns = columns,
            analyte = analyte(),
            inputs = emptyList()
        )
    }

    /** 构造单个冻结位点输入，专门验证范围状态不会改变浓度归一化结果。 */
    private fun heatmapInput(
        siteIndex: Int,
        concentration: Double?,
        primaryFeature: Double?,
        rangeStatus: String,
        quantificationStatus: String
    ): ArrayHeatmapValueInput {
        val columns = 15
        return ArrayHeatmapValueInput(
            siteIndex = siteIndex,
            rowIndex = siteIndex / columns,
            columnIndex = siteIndex % columns,
            siteKey = "R${(siteIndex / columns + 1).toString().padStart(2, '0')}C${(siteIndex % columns + 1).toString().padStart(2, '0')}",
            enabled = true,
            roleCode = "SAMPLE",
            concentrationValue = concentration,
            primaryFeatureValue = primaryFeature,
            reliableRangeStatus = rangeStatus,
            signalDetectable = true,
            qualityReliable = true,
            geometryFlags = emptySet(),
            photometryFlags = emptySet(),
            quantificationStatus = quantificationStatus
        )
    }

    /** 构造两个单位和可靠范围不同的分析物，验证页面只显示当前选择的独立色带。 */
    private fun multiAnalyteSnapshot(): ArrayResultSnapshot {
        val first = analyte()
        val second = analyte().copy(
            analyteId = "analyte-2",
            name = "CEA",
            concentrationUnit = "pg/mL",
            reliableRangeMin = 100.0,
            reliableRangeMax = 200.0,
            modelId = "model-2",
            modelName = "CEA model"
        )
        return ArrayResultSnapshot(
            runId = "run-multi",
            projectId = "project-multi",
            projectName = "Multi analyte chip",
            runTimestampEpochMillis = 1_000L,
            runStatus = "Completed",
            detectionMode = "COLORIMETRIC",
            carrier = ArrayCarrierResult(
                id = "carrier",
                name = "Microfluidic chip",
                carrierType = "MICROFLUIDIC_CHIP",
                version = 1,
                siteShape = "CIRCLE",
                orientationMarkerJson = null
            ),
            rows = 1,
            columns = 2,
            analytes = listOf(first, second),
            sites = listOf(
                site(siteIndex = 0, analyte = first, concentration = 5.0),
                site(siteIndex = 1, analyte = second, concentration = 150.0)
            ),
            frame = ArrayFrameResult(
                locatorName = "pg-grid",
                locatorVersion = "2.1",
                rectifiedWidth = 200,
                rectifiedHeight = 100,
                chipRegionMethod = "test",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 1.0,
                    trusted = true,
                    observedRatio = 1.0,
                    geometryRmsePx = 0.1,
                    inlierCount = 2,
                    outlierCount = 0,
                    meanConfidence = 0.98
                ),
                qcIssues = emptyList(),
                frameQcJson = "{}"
            ),
            artifacts = emptyList(),
            effectiveConfigSnapshotJson = "{}",
            configurationDeviationJson = null,
            acquisitionMetadataJson = null,
            processingVersionJson = null,
            modelUsageJson = null,
            siteQcSummaryJson = null
        )
    }

    private fun site(
        siteIndex: Int,
        analyte: ArrayAnalyteResult,
        concentration: Double
    ): ArrayPhysicalSiteResult {
        val point = GridPoint(x = siteIndex * 20.0 + 10.0, y = 10.0)
        return ArrayPhysicalSiteResult(
            siteIndex = siteIndex,
            rowIndex = 0,
            columnIndex = siteIndex,
            siteKey = "R01C${(siteIndex + 1).toString().padStart(2, '0')}",
            enabled = true,
            roleCode = "SAMPLE",
            analyteId = analyte.analyteId,
            defaultSampleSlot = "S${siteIndex + 1}",
            sampleSlot = "S${siteIndex + 1}",
            overrideReason = null,
            standardConcentration = null,
            repeatGroup = null,
            referenceScope = null,
            geometry = ArraySiteGeometry(
                rectified = point,
                original = point,
                confidence = 0.98,
                source = GridPointSource.CANDIDATE_REFINED,
                flags = emptySet()
            ),
            measurements = listOf(
                ArraySiteMeasurementResult(
                    measurementId = siteIndex.toLong() + 1L,
                    analyteId = analyte.analyteId,
                    detectionMode = "COLORIMETRIC",
                    primaryFeatureName = analyte.primaryFeature,
                    primaryFeatureValue = concentration,
                    concentrationValue = concentration,
                    concentrationUnit = analyte.concentrationUnit,
                    reliableRangeStatus = "WITHIN_RANGE",
                    backgroundValue = 1.0,
                    signalToNoiseRatio = 8.0,
                    confidence = 0.98,
                    signalDetectable = true,
                    qualityReliable = true,
                    processorName = analyte.processorName,
                    processorVersion = analyte.processorVersion,
                    modelSnapshotJson = "{}",
                    rawSignalJson = "{}",
                    correctedSignalJson = null,
                    qcJson = "{}",
                    quantificationQcJson = "{}",
                    qc = ArrayMeasurementQc(
                        geometrySourceCode = null,
                        geometryFlags = emptySet(),
                        photometryFlags = emptySet(),
                        quantificationStatus = "QUANTIFIED",
                        quantificationScope = null,
                        quantificationReason = null
                    ),
                    detail = ArrayMeasurementDetail.LegacyUnparsed(
                        rawSignalJson = "{}",
                        correctedSignalJson = null
                    )
                )
            )
        )
    }
}

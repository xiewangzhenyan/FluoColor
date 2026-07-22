package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.mutableStateOf
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
    fun `十五乘十五阵列提供缩放平移容器并保持初始完整显示`() {
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
        composeRule.onNodeWithTag("${ARRAY_HEATMAP_CELL_TAG_PREFIX}224", useUnmergedTree = true)
            .assertIsDisplayed()
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

        composeRule.onNodeWithTag(ARRAY_RESULT_ANALYTE_TAB_TAG).performClick()
        composeRule.onNodeWithTag("${ARRAY_HEATMAP_CARD_TAG_PREFIX}analyte-1").assertIsDisplayed()
        composeRule.onNodeWithTag("${ARRAY_ANALYTE_CHIP_TAG_PREFIX}analyte-2").performClick()
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

    private fun model(rows: Int, columns: Int): ArrayHeatmapModel {
        return buildAnalyteHeatmapModel(
            rows = rows,
            columns = columns,
            analyte = analyte(),
            inputs = emptyList()
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

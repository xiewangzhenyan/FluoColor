package com.muc.fluocolorquant.domain.result

import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapScaleMode
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapConcentrationScaleMode
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapValueState
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapValueInput
import com.muc.fluocolorquant.ui.screens.result.array.buildAnalyteHeatmapModel
import com.muc.fluocolorquant.ui.screens.result.array.resolveArraySiteIndex
import com.muc.fluocolorquant.ui.screens.result.array.resolveArrayResultRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用阵列热力图的纯逻辑契约。
 *
 * 测试刻意不依赖 Compose 渲染：先证明色带范围、位点数量和 QC 编码正确，再由仪器
 * 测试验证触摸与视觉节点，避免 UI 改版时把科研含义一并改坏。
 */
class ArrayHeatmapScaleTest {

    @Test
    fun `全分析物只有单侧界限时首屏不得显示已定量或仅信号`() {
        assertEquals(
            "BoundaryOnlyCompleted",
            resolveArrayResultRunStatus(
                storedStatus = "Completed",
                analyteIds = setOf("cea", "cyfra21-1"),
                finiteConcentrationAnalyteIds = emptySet(),
                boundaryAnalyteIds = setOf("cea", "cyfra21-1")
            )
        )
        assertEquals(
            "PartiallyQuantified",
            resolveArrayResultRunStatus(
                storedStatus = "Completed",
                analyteIds = setOf("cea", "cyfra21-1"),
                finiteConcentrationAnalyteIds = setOf("cea"),
                boundaryAnalyteIds = setOf("cyfra21-1")
            )
        )
        assertEquals(
            "SignalOnlyCompleted",
            resolveArrayResultRunStatus(
                storedStatus = "Completed",
                analyteIds = setOf("cea"),
                finiteConcentrationAnalyteIds = emptySet(),
                boundaryAnalyteIds = emptySet()
            )
        )
    }

    @Test
    fun `范围和普通几何标志不再制造测量质量复核`() {
        val analyte = analyte(reliableMin = 0.0, reliableMax = 10.0)
        val inputs = listOf(
            input(siteIndex = 0, concentration = 5.0),
            input(siteIndex = 1, concentration = 5.0, geometryFlags = setOf("IMPUTED_POSITION")),
            input(siteIndex = 2, concentration = 5.0, qualityReliable = false)
        )

        val model = buildAnalyteHeatmapModel(
            rows = 1,
            columns = 3,
            analyte = analyte,
            inputs = inputs
        )

        assertEquals(ArrayHeatmapScaleMode.CONCENTRATION, model.scale.mode)
        assertEquals(0.0, model.scale.minimum, 0.0)
        assertEquals(10.0, model.scale.maximum, 0.0)
        assertEquals(listOf(0.5f, 0.5f, 0.5f), model.cells.map { it.normalizedValue })
        assertFalse(model.cells[0].qc.warning)
        assertFalse(model.cells[1].qc.warning)
        assertTrue(model.cells[2].qc.warning)
        assertFalse(model.cells[2].qc.failure)
    }

    @Test
    fun `本次分布使用稳健分位数展开颜色且不受单个离群值支配`() {
        val analyte = analyte(reliableMin = 0.0, reliableMax = 1000.0).copy(
            projectRangeMin = 0.0,
            projectRangeMax = 1000.0
        )
        // 0～190构成主体分布，1000模拟单个偶发离群值。21个值的P5/P95恰好为10和190。
        val concentrations = (0..19).map { it * 10.0 } + 1000.0
        val model = buildAnalyteHeatmapModel(
            rows = 1,
            columns = concentrations.size,
            analyte = analyte,
            inputs = concentrations.mapIndexed { siteIndex, concentration ->
                input(siteIndex = siteIndex, concentration = concentration)
            },
            concentrationScaleMode = ArrayHeatmapConcentrationScaleMode.RUN_DISTRIBUTION
        )

        assertEquals(ArrayHeatmapScaleMode.CONCENTRATION, model.scale.mode)
        assertEquals(ArrayHeatmapConcentrationScaleMode.RUN_DISTRIBUTION, model.scale.concentrationScaleMode)
        assertEquals(10.0, model.scale.minimum, 1e-9)
        assertEquals(190.0, model.scale.maximum, 1e-9)
        assertEquals(0.5f, model.cells[10].normalizedValue ?: Float.NaN, 1e-6f)
        assertTrue(
            model.scale.availableConcentrationScaleModes.containsAll(
                ArrayHeatmapConcentrationScaleMode.entries
            )
        )
    }

    @Test
    fun `运行963同构分布拆分为范围统计而不是189个建议复核`() {
        val analyte = analyte(reliableMin = 10.0, reliableMax = 40.0).copy(
            projectRangeMin = 0.0,
            projectRangeMax = 100.0
        )
        val statuses = buildList {
            repeat(61) { add("WITHIN_RANGE") }
            repeat(119) { add("ABOVE_RANGE") }
            repeat(8) { add("BELOW_RANGE") }
            repeat(20) { add("BELOW_PROJECT_RANGE") }
            repeat(17) { add("ABOVE_PROJECT_RANGE") }
        }
        val inputs = statuses.mapIndexed { siteIndex, status ->
            val concentration = when (status) {
                "WITHIN_RANGE" -> 25.0
                "ABOVE_RANGE" -> 50.0
                "BELOW_RANGE" -> 5.0
                else -> null
            }
            ArrayHeatmapValueInput(
                siteIndex = siteIndex,
                rowIndex = siteIndex / 15,
                columnIndex = siteIndex % 15,
                siteKey = "R${(siteIndex / 15 + 1).toString().padStart(2, '0')}C${(siteIndex % 15 + 1).toString().padStart(2, '0')}",
                enabled = true,
                roleCode = "SAMPLE",
                concentrationValue = concentration,
                primaryFeatureValue = siteIndex.toDouble(),
                reliableRangeStatus = status,
                signalDetectable = true,
                qualityReliable = true,
                geometryFlags = emptySet(),
                // 复现真实运行中25个范围内位点携带普通光度提示，但可靠性布尔值仍为true。
                photometryFlags = if (siteIndex < 25) setOf("non_uniform") else emptySet(),
                quantificationStatus = when (status) {
                    "WITHIN_RANGE" -> "QUANTIFIED"
                    "ABOVE_RANGE", "BELOW_RANGE" -> "EXTRAPOLATED"
                    else -> "OUTSIDE_PROJECT_RANGE"
                }
            )
        }

        val model = buildAnalyteHeatmapModel(
            rows = 15,
            columns = 15,
            analyte = analyte,
            inputs = inputs
        )

        // 旧运行仍可按历史范围字段恢复三类结果，但“已计算”只能统计真正拥有点浓度的位点。
        // 37 个项目量程外位点只有方向、没有精确浓度，因此必须归入复测，不能继续伪装成已计算。
        assertEquals(225, model.measuredCount)
        assertEquals(188, model.calculatedCount)
        assertEquals(61, model.quantifiedCount)
        assertEquals(127, model.estimatedCount)
        assertEquals(37, model.retestCount)
        assertEquals(model.measuredCount, model.quantifiedCount + model.estimatedCount + model.retestCount)
        assertEquals(61, model.withinCalibrationRangeCount)
        assertEquals(127, model.calibrationExtrapolatedCount)
        assertEquals(37, model.outsideProjectRangeCount)
        assertEquals(225, model.reliableCount)
        assertEquals(0, model.warningCount)
        assertEquals(0, model.failureCount)
    }

    @Test
    fun `Room15强类型状态严格闭合为定量估计复测`() {
        val analyte = analyte(reliableMin = 0.0, reliableMax = 100.0)
        val inputs = listOf(
            input(siteIndex = 0, concentration = 20.0).copy(
                quantificationState = "QUANTIFIED"
            ),
            input(siteIndex = 1, concentration = 118.0).copy(
                quantificationState = "ESTIMATED",
                reliableRangeStatus = "ABOVE_RANGE"
            ),
            input(siteIndex = 2, concentration = null, primaryFeature = 220.0).copy(
                quantificationState = "BOUND_ONLY",
                censoringDirection = "LOWER_BOUND",
                reliableRangeStatus = "ABOVE_TRUSTED_RANGE"
            ),
            input(siteIndex = 3, concentration = null, primaryFeature = null).copy(
                quantificationState = "UNAVAILABLE"
            )
        )

        val model = buildAnalyteHeatmapModel(
            rows = 1,
            columns = 4,
            analyte = analyte,
            inputs = inputs
        )

        assertEquals(4, model.measuredCount)
        assertEquals(2, model.calculatedCount)
        assertEquals(1, model.quantifiedCount)
        assertEquals(1, model.estimatedCount)
        assertEquals(2, model.retestCount)
        assertEquals(model.measuredCount, model.quantifiedCount + model.estimatedCount + model.retestCount)
        assertEquals(ArrayHeatmapValueState.QUANTIFIED, model.cells[0].valueState)
        assertEquals(ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED, model.cells[1].valueState)
        assertEquals(ArrayHeatmapValueState.ABOVE_PROJECT_RANGE, model.cells[2].valueState)
        assertEquals(1.0f, model.cells[2].normalizedValue)
        assertEquals(ArrayHeatmapValueState.UNAVAILABLE, model.cells[3].valueState)
        // 可信边界外属于“需要复测/仅报告界限”，不能被展示层伪装成项目量程外。
        assertEquals(0, model.outsideProjectRangeCount)
    }

    @Test
    fun `超项目量程保留方向状态并投影到色带端点`() {
        val analyte = analyte(reliableMin = 0.0, reliableMax = 100.0)
        val model = buildAnalyteHeatmapModel(
            rows = 1,
            columns = 3,
            analyte = analyte,
            inputs = listOf(
                input(siteIndex = 0, concentration = null, primaryFeature = 20.0).copy(
                    reliableRangeStatus = "BELOW_PROJECT_RANGE",
                    quantificationStatus = "OUTSIDE_PROJECT_RANGE"
                ),
                input(siteIndex = 1, concentration = null, primaryFeature = 80.0).copy(
                    reliableRangeStatus = "ABOVE_PROJECT_RANGE",
                    quantificationStatus = "OUTSIDE_PROJECT_RANGE"
                ),
                input(siteIndex = 2, concentration = 40.0, qualityReliable = false)
            )
        )

        assertEquals(ArrayHeatmapValueState.BELOW_PROJECT_RANGE, model.cells[0].valueState)
        assertEquals(0.0, model.cells[0].displayValue ?: Double.NaN, 0.0)
        assertEquals(0.0f, model.cells[0].normalizedValue)
        assertFalse(model.cells[0].qc.failure)
        assertEquals(ArrayHeatmapValueState.ABOVE_PROJECT_RANGE, model.cells[1].valueState)
        assertEquals(100.0, model.cells[1].displayValue ?: Double.NaN, 0.0)
        assertEquals(1.0f, model.cells[1].normalizedValue)
        assertFalse(model.cells[1].qc.failure)
        assertFalse(model.cells[2].qc.failure)
        assertTrue(model.cells[2].qc.warning)
        assertEquals(40.0, model.cells[2].displayValue ?: Double.NaN, 0.0)
    }

    @Test
    fun `全部位点超项目量程时仍保持浓度色带`() {
        val model = buildAnalyteHeatmapModel(
            rows = 1,
            columns = 2,
            analyte = analyte(reliableMin = 0.0, reliableMax = 100.0),
            inputs = listOf(
                input(siteIndex = 0, concentration = null, primaryFeature = 8.0).copy(
                    reliableRangeStatus = "BELOW_PROJECT_RANGE",
                    quantificationStatus = "OUTSIDE_PROJECT_RANGE"
                ),
                input(siteIndex = 1, concentration = null, primaryFeature = 92.0).copy(
                    reliableRangeStatus = "ABOVE_PROJECT_RANGE",
                    quantificationStatus = "OUTSIDE_PROJECT_RANGE"
                )
            )
        )

        assertEquals(ArrayHeatmapScaleMode.CONCENTRATION, model.scale.mode)
        assertEquals(listOf(0.0f, 1.0f), model.cells.map { it.normalizedValue })
        assertEquals(
            listOf(
                ArrayHeatmapValueState.BELOW_PROJECT_RANGE,
                ArrayHeatmapValueState.ABOVE_PROJECT_RANGE
            ),
            model.cells.map { it.valueState }
        )
    }

    @Test
    fun `没有任何浓度时自动切换为当前分析物主特征色带`() {
        val analyte = analyte(reliableMin = 0.0, reliableMax = 10.0)
        val model = buildAnalyteHeatmapModel(
            rows = 1,
            columns = 3,
            analyte = analyte,
            inputs = listOf(
                input(siteIndex = 0, primaryFeature = 10.0),
                input(siteIndex = 1, primaryFeature = 20.0),
                input(siteIndex = 2, primaryFeature = 30.0)
            )
        )

        assertEquals(ArrayHeatmapScaleMode.PRIMARY_FEATURE, model.scale.mode)
        assertEquals(10.0, model.scale.minimum, 0.0)
        assertEquals(30.0, model.scale.maximum, 0.0)
        assertEquals(listOf(0.0f, 0.5f, 1.0f), model.cells.map { it.normalizedValue })
    }

    @Test
    fun `旧运行只保存部分浓度时统一回退为信号热力图`() {
        val analyte = analyte(reliableMin = 28.0, reliableMax = 34.0)
        val model = buildAnalyteHeatmapModel(
            rows = 1,
            columns = 3,
            analyte = analyte,
            inputs = listOf(
                input(siteIndex = 0, concentration = 30.0, primaryFeature = 10.0),
                input(siteIndex = 1, primaryFeature = 20.0).copy(
                    reliableRangeStatus = "BELOW_RANGE",
                    quantificationStatus = "OUT_OF_RELIABLE_RANGE"
                ),
                input(siteIndex = 2, primaryFeature = 30.0, signalDetectable = false).copy(
                    reliableRangeStatus = "ABOVE_RANGE",
                    quantificationStatus = "OUT_OF_RELIABLE_RANGE"
                )
            )
        )

        assertEquals(ArrayHeatmapScaleMode.PRIMARY_FEATURE, model.scale.mode)
        assertTrue(model.historicalConcentrationIncomplete)
        assertEquals(listOf(10.0, 20.0, 30.0), model.cells.map { it.displayValue })
        assertTrue(model.cells.none { it.qc.failure })
        assertTrue(model.cells.all { it.valueState == ArrayHeatmapValueState.QUANTIFIED })
        assertTrue(model.cells[2].qc.lowSignal)
    }

    @Test
    fun `强类型单侧浓度界限保持浓度色带而不是误判为旧历史缺失`() {
        val analyte = analyte(reliableMin = 0.0, reliableMax = 100.0)
        val model = buildAnalyteHeatmapModel(
            rows = 1,
            columns = 2,
            analyte = analyte,
            inputs = listOf(
                input(siteIndex = 0, concentration = null, primaryFeature = 8.0).copy(
                    reliableRangeStatus = "BELOW_RANGE",
                    quantificationStatus = "OUT_OF_RELIABLE_RANGE",
                    quantificationState = "BOUND_ONLY",
                    censoringDirection = "UPPER_BOUND"
                ),
                input(siteIndex = 1, concentration = null, primaryFeature = 92.0).copy(
                    reliableRangeStatus = "ABOVE_RANGE",
                    quantificationStatus = "OUT_OF_RELIABLE_RANGE",
                    quantificationState = "BOUND_ONLY",
                    censoringDirection = "LOWER_BOUND"
                )
            )
        )

        // 强类型单侧界限已经是完整浓度结论，不能再触发旧版“部分浓度丢失”的信号回退。
        assertEquals(ArrayHeatmapScaleMode.CONCENTRATION, model.scale.mode)
        assertFalse(model.historicalConcentrationIncomplete)
        assertEquals(2, model.retestCount)
        assertEquals(ArrayHeatmapValueState.BELOW_PROJECT_RANGE, model.cells[0].valueState)
        assertEquals(ArrayHeatmapValueState.ABOVE_PROJECT_RANGE, model.cells[1].valueState)
        assertEquals(listOf(0.0f, 1.0f), model.cells.map { it.normalizedValue })
    }

    @Test
    fun `没有有限信号时才进入无法计算状态`() {
        val model = buildAnalyteHeatmapModel(
            rows = 1,
            columns = 1,
            analyte = analyte(reliableMin = 0.0, reliableMax = 100.0),
            inputs = listOf(
                input(siteIndex = 0, primaryFeature = null, signalDetectable = false).copy(
                    reliableRangeStatus = null,
                    quantificationStatus = "NON_FINITE_SIGNAL"
                )
            )
        )

        assertTrue(model.cells.single().qc.failure)
        assertFalse(model.cells.single().qc.lowSignal)
        assertEquals(ArrayHeatmapValueState.UNAVAILABLE, model.cells.single().valueState)
        assertEquals(null, model.cells.single().displayValue)
    }

    @Test
    fun `所有支持规格都固定创建完整物理位点`() {
        listOf(4 to 4, 10 to 10, 15 to 15, 4 to 6).forEach { (rows, columns) ->
            val model = buildAnalyteHeatmapModel(
                rows = rows,
                columns = columns,
                analyte = analyte(),
                inputs = emptyList()
            )

            assertEquals(rows * columns, model.cells.size)
            val last = model.cells.last()
            assertEquals(rows - 1, last.rowIndex)
            assertEquals(columns - 1, last.columnIndex)
            assertEquals(rows * columns - 1, last.siteIndex)
        }
    }

    @Test
    fun `自定义非方阵索引始终使用零基行乘列数加列`() {
        assertEquals(0, resolveArraySiteIndex(rowIndex = 0, columnIndex = 0, columns = 6))
        assertEquals(23, resolveArraySiteIndex(rowIndex = 3, columnIndex = 5, columns = 6))
    }

    @Test
    fun `其他分析物位点保留在物理阵列中但不会计为当前分析物漏检`() {
        val model = buildAnalyteHeatmapModel(
            rows = 1,
            columns = 2,
            analyte = analyte(),
            inputs = listOf(
                input(siteIndex = 0, primaryFeature = 10.0),
                input(siteIndex = 1, primaryFeature = null).copy(
                    applicable = false,
                    hasMeasurement = false,
                    roleCode = "SAMPLE"
                )
            )
        )

        assertEquals(2, model.cells.size)
        assertFalse(model.cells[1].applicable)
        assertEquals(0, model.missingCount)
    }

    private fun analyte(
        reliableMin: Double? = null,
        reliableMax: Double? = null
    ): ArrayAnalyteResult {
        return ArrayAnalyteResult(
            analyteId = "analyte-1",
            name = "AFP",
            displayOrder = 0,
            concentrationUnit = "ng/mL",
            reliableRangeMin = reliableMin,
            reliableRangeMax = reliableMax,
            modelId = "model-1",
            modelName = "AFP model",
            modelType = "STANDARD_CURVE",
            modelVersion = 1,
            primaryFeature = "DELTA_E",
            processorName = "pg-color",
            processorVersion = "1"
        )
    }

    private fun input(
        siteIndex: Int,
        concentration: Double? = null,
        primaryFeature: Double? = null,
        qualityReliable: Boolean = true,
        signalDetectable: Boolean = true,
        geometryFlags: Set<String> = emptySet()
    ): ArrayHeatmapValueInput {
        return ArrayHeatmapValueInput(
            siteIndex = siteIndex,
            rowIndex = 0,
            columnIndex = siteIndex,
            siteKey = "R01C${(siteIndex + 1).toString().padStart(2, '0')}",
            enabled = true,
            roleCode = "SAMPLE",
            concentrationValue = concentration,
            primaryFeatureValue = primaryFeature,
            reliableRangeStatus = if (concentration == null) null else "WITHIN_RANGE",
            signalDetectable = signalDetectable,
            qualityReliable = qualityReliable,
            geometryFlags = geometryFlags,
            photometryFlags = emptySet()
        )
    }
}

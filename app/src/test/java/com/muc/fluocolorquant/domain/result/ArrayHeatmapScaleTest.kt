package com.muc.fluocolorquant.domain.result

import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapScaleMode
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapValueInput
import com.muc.fluocolorquant.ui.screens.result.array.buildAnalyteHeatmapModel
import com.muc.fluocolorquant.ui.screens.result.array.resolveArraySiteIndex
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
    fun `浓度归一化不受警告和质量失败编码影响`() {
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
        assertTrue(model.cells[1].qc.warning)
        assertTrue(model.cells[2].qc.failure)
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

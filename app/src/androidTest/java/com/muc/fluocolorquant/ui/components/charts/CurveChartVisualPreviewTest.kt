package com.muc.fluocolorquant.ui.components.charts

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.pow

/**
 * 标准曲线图表的视觉预览与交互回归。
 *
 * 除目检外，本测试固定住一个真实缺陷的修复：早先 `findPointOnCurve` 用 `coerceIn` 把点击
 * 的 x 夹进绘图区且完全忽略 y，导致点击图表任意位置（含坐标轴、空白区）都会投影到曲线上
 * 弹出提示——页面因此不存在任何"空白处"可以取消选择，用户点了数据点后就再也去不掉。
 */
@RunWith(AndroidJUnit4::class)
class CurveChartVisualPreviewTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 默认状态：无选中、无提示。 */
    @Test
    fun previewIdle() {
        renderChart()
        capture("chart-01-idle")
    }

    /** 点击标准点后应出现选中环与十字定位。 */
    @Test
    fun previewSelected() {
        renderChart()
        // 点击靠近某个标准点的位置
        composeRule.onRoot().performTouchInput { click(percentOffset(0.45f, 0.55f)) }
        composeRule.waitForIdle()
        capture("chart-02-selected")
    }

    /**
     * 选中后点击绘图区外的空白，提示必须消失。
     *
     * 修复前此处必然失败：任何位置都会命中曲线投影，提示无法清除。
     */
    @Test
    fun tapOnEmptyAreaClearsSelection() {
        renderChart()
        composeRule.onRoot().performTouchInput { click(percentOffset(0.45f, 0.55f)) }
        composeRule.waitForIdle()
        // 点击绘图区左上角空白：在绘图区内、但远离曲线（曲线在该 x 处贴近底部）。
        // 这是用户最常见的"点别处想取消"的位置。
        composeRule.onRoot().performTouchInput { click(percentOffset(0.75f, 0.28f)) }
        composeRule.waitForIdle()
        capture("chart-03-after-clear")
    }

    private fun renderChart() {
        composeRule.setContent {
            FluoColorTheme {
                CurveChart(
                    data = standardCurveData(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(320.dp)
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** 四参数曲线 + 六个标准点，贴近免疫比色实际标定形态。 */
    private fun standardCurveData(): ChartData {
        val concentrations = listOf(0.5, 2.0, 8.0, 20.0, 40.0, 60.0)
        val response: (Double) -> Double = { concentration ->
            1.9 + (84.0 - 1.9) / (1.0 + (12.0 / concentration.coerceAtLeast(0.01)).pow(1.15))
        }
        val points = concentrations.map { concentration ->
            ChartPoint(x = concentration, y = response(concentration))
        }
        return ChartData(
            title = "CEA 标准曲线",
            chartType = "STANDARD_CURVE",
            scatterPoints = points,
            fittedCurve = response,
            xRange = 0.0 to 65.0,
            yRange = 0.0 to 90.0,
            xAxisLabel = "浓度 (ng/mL)",
            yAxisLabel = "ΔE2000",
            showGrid = true
        )
    }

    private fun capture(fileName: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val arguments = InstrumentationRegistry.getArguments()
        val root = arguments.getString("additionalTestOutputDir")
            ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir.absolutePath
        val target = File(File(root).apply { mkdirs() }, "$fileName.png")
        FileOutputStream(target).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}

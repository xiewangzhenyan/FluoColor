package com.muc.fluocolorquant.ui.screens.project

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.ui.components.ScientificSelectionField
import com.muc.fluocolorquant.ui.components.ScientificSelectionFieldDensity
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.theme.FluoRadius
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 新建项目页"最大浓度 + 浓度单位"同行控件的对齐回归。
 *
 * 两个字段组由不同组件实现（左侧是“外置标签 + OutlinedTextField”，右侧是
 * ScientificSelectionField 内部维护的“外置标签 + 选择控件”），整体高度与顶部基线极易在
 * 改动中漂移。这里必须比较同一语义层级的完整字段组，不能把左侧输入框本体与右侧字段组
 * 混在一起比较，否则会把标签高度误判为页面错位。
 */
@RunWith(AndroidJUnit4::class)
class AnalyteFieldAlignmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val maxConcentrationTag = "test_max_concentration_field"
    private val unitTag = "test_unit_field"

    @Test
    fun 最大浓度与浓度单位控件高度和顶边一致() {
        renderRow()

        val left = composeRule.onNodeWithTag(maxConcentrationTag).fetchSemanticsNode()
        val right = composeRule.onNodeWithTag(unitTag).fetchSemanticsNode()

        assertEquals(
            "两个输入控件高度必须一致",
            left.size.height,
            right.size.height
        )
        assertEquals(
            "两个输入控件顶边必须对齐",
            left.positionInRoot.y.toInt(),
            right.positionInRoot.y.toInt()
        )
    }

    @Test
    fun previewRow() {
        renderRow()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir.absolutePath
        FileOutputStream(File(File(root).apply { mkdirs() }, "analyte-row.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** 复刻页面里的同行结构，保持与生产代码相同的权重、间距与组件参数。 */
    private fun renderRow() {
        composeRule.setContent {
            FluoColorTheme {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.48f)
                            // 标签必须挂在完整字段组上，与右侧 ScientificSelectionField 的
                            // modifier 语义保持一致，确保断言比较的是同一布局层级。
                            .testTag(maxConcentrationTag),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(
                            text = "最大浓度",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedTextField(
                            value = "60",
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            leadingIcon = {
                                Icon(Icons.Default.Science, contentDescription = null)
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(FluoRadius.control)
                        )
                    }
                    ScientificSelectionField(
                        label = "浓度单位",
                        value = "ng/mL",
                        placeholder = "选择单位",
                        icon = Icons.Default.Straighten,
                        onClick = {},
                        modifier = Modifier
                            .weight(0.52f)
                            .testTag(unitTag),
                        density = ScientificSelectionFieldDensity.COMPACT
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}

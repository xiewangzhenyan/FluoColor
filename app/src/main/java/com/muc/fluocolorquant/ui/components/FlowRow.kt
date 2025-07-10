package com.muc.fluocolorquant.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 流式布局行组件
 * 当子项超出宽度限制时自动换行
 *
 * @param modifier 修饰符
 * @param alignment 对齐方式
 * @param verticalGap 垂直间距
 * @param horizontalGap 水平间距
 * @param content 子项内容
 */
@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start,
    verticalGap: Dp = 0.dp,
    horizontalGap: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        val horizontalGapPx = horizontalGap.roundToPx()
        val verticalGapPx = verticalGap.roundToPx()
        
        val rows = mutableListOf<Row>()
        var currentRow = Row(horizontalGapPx)
        
        measurables.forEach { measurable ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0))
            
            if (currentRow.width + placeable.width > constraints.maxWidth) {
                rows.add(currentRow)
                currentRow = Row(horizontalGapPx)
            }
            
            currentRow.add(placeable)
        }
        
        if (currentRow.placeables.isNotEmpty()) {
            rows.add(currentRow)
        }
        
        val width = constraints.maxWidth
        val height = rows.sumBy { row -> row.height } + max(0, rows.size - 1) * verticalGapPx
        
        layout(width, height) {
            var y = 0
            
            rows.forEach { row ->
                val startX = when (alignment) {
                    Alignment.Start -> 0
                    Alignment.CenterHorizontally -> (width - row.width) / 2
                    Alignment.End -> width - row.width
                    else -> 0
                }
                
                var x = startX
                row.placeables.forEach { placeable ->
                    placeable.placeRelative(x, y)
                    x += placeable.width + horizontalGapPx
                }
                
                y += row.height + verticalGapPx
            }
        }
    }
}

private class Row(private val horizontalGap: Int) {
    val placeables = mutableListOf<Placeable>()
    
    var width = 0
        private set
    
    val height: Int
        get() = placeables.maxOfOrNull { it.height } ?: 0
    
    fun add(placeable: Placeable) {
        placeables.add(placeable)
        width += placeable.width
        if (placeables.size > 1) {
            width += horizontalGap
        }
    }
} 
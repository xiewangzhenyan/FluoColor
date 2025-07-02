package com.muc.fluocolorquant.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agog.mathdisplay.MTMathView

/**
 * 一个用于在Jetpack Compose中显示LaTeX公式的Composable组件。
 * 它内部封装了AndroidMath库的MTMathView。
 *
 * @param latex LaTeX字符串。
 * @param modifier 修改器。
 */
@Composable
fun LatexView(
    latex: String,
    modifier: Modifier = Modifier
) {
    // 从Compose主题中获取颜色
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    // 将Compose的sp单位转换为View系统使用的像素单位
    val fontSizeInPixels = with(density) { 20.sp.toPx() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            // 在factory中创建并初始化View
            MTMathView(context).apply {
                this.latex = latex
                this.textColor = textColor.toArgb()
                this.fontSize = fontSizeInPixels
                // 使用显示模式以获得更好的公式外观
                this.labelMode = MTMathView.MTMathViewMode.KMTMathViewModeDisplay
            }
        },
        update = { view ->
            // 当Composable重组时，更新View的属性
            view.latex = latex
            view.textColor = textColor.toArgb()
            view.fontSize = fontSizeInPixels
        }
    )
}
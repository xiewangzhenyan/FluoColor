package com.muc.fluocolorquant.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathView

/**
 * 一个用于在Jetpack Compose中显示LaTeX公式的Composable组件。
 * 它内部封装了 jlatexmath-android 库的 JLatexMathView。
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
    val textSizeInPixels = with(density) { 20.sp.toPx() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            // 在factory中创建并初始化View
            JLatexMathView(context).apply {
                // jlatexmath-android 使用 drawable 来设置公式
                val drawable = JLatexMathDrawable.builder(latex)
                    .textSize(textSizeInPixels)
                    .color(textColor.toArgb())
                    .align(JLatexMathDrawable.ALIGN_CENTER) // 居中对齐
                    .build()
                this.setLatexDrawable(drawable)
            }
        },
        update = { view ->
            // 当Composable重组时，更新View的属性
            val drawable = JLatexMathDrawable.builder(latex)
                .textSize(textSizeInPixels)
                .color(textColor.toArgb())
                .align(JLatexMathDrawable.ALIGN_CENTER) // 居中对齐
                .build()
            view.setLatexDrawable(drawable)
        }
    )
}
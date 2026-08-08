package com.muc.fluocolorquant.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathView

/**
 * LaTeX 公式在容器中的水平对齐方式。
 *
 * 使用项目自己的枚举隔离第三方库常量，避免业务页面直接依赖 jlatexmath 的实现细节。
 */
enum class LatexAlignment {
    START,
    CENTER,
    END
}

/**
 * 一个用于在Jetpack Compose中显示LaTeX公式的Composable组件。
 * 它内部封装了 jlatexmath-android 库的 JLatexMathView。
 *
 * @param latex LaTeX字符串。
 * @param modifier 修改器。
 * @param textSize 公式字号；列表摘要可使用较小字号，结果详情可使用默认字号。
 * @param textColor 公式颜色，默认跟随 Material 3 次级正文颜色。
 * @param alignment 公式在可用宽度中的水平对齐方式。
 */
@Composable
fun LatexView(
    latex: String,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 20.sp,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    alignment: LatexAlignment = LatexAlignment.CENTER
) {
    val density = LocalDensity.current
    // jlatexmath-android 接收像素字号，因此统一在 Compose 密度环境中转换。
    val textSizeInPixels = with(density) { textSize.toPx() }
    val drawableAlignment = when (alignment) {
        LatexAlignment.START -> JLatexMathDrawable.ALIGN_LEFT
        LatexAlignment.CENTER -> JLatexMathDrawable.ALIGN_CENTER
        LatexAlignment.END -> JLatexMathDrawable.ALIGN_RIGHT
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            // 在 factory 中创建并初始化 View，同时提供公式源码作为无障碍描述。
            JLatexMathView(context).apply {
                contentDescription = latex
                val drawable = JLatexMathDrawable.builder(latex)
                    .textSize(textSizeInPixels)
                    .color(textColor.toArgb())
                    .align(drawableAlignment)
                    .build()
                this.setLatexDrawable(drawable)
            }
        },
        update = { view ->
            // 当 Composable 重组时同步公式、字号、颜色和对齐方式。
            view.contentDescription = latex
            val drawable = JLatexMathDrawable.builder(latex)
                .textSize(textSizeInPixels)
                .color(textColor.toArgb())
                .align(drawableAlignment)
                .build()
            view.setLatexDrawable(drawable)
        }
    )
}

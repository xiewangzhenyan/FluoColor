package com.muc.fluocolorquant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.ui.theme.FluoSpacing

/**
 * 科研数值排版。
 *
 * 默认字体是比例字体：数字 `1` 比 `8` 窄。结果页的指标带、样本表和重复孔统计因此列列
 * 错位，小数点对不齐，扫视一列数值时眼睛需要不断重新定位——这是"看起来不像专业仪器
 * 软件"最直接的来源，比配色和圆角的影响都大。
 *
 * 这里统一启用 OpenType 的 `tnum`（tabular figures）字型特性：同一字体内所有数字宽度
 * 相同，数值天然按位对齐，无需手动填充空格或改用等宽字体牺牲可读性。
 *
 * 只用于数值，不用于正文：中文与说明文字仍使用默认字距，避免整页字重失衡。
 */

/**
 * 表格数字字型特性。
 *
 * `tnum` 让数字等宽；`lnum`（lining figures）强制使用与大写字母等高的数字形态，
 * 避免部分字体在正文中默认使用高低不一的旧式数字。
 */
private const val TABULAR_FIGURES = "tnum, lnum"

/** 给任意文本样式套上等宽数字特性。 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)

/**
 * 等宽数字文本。
 *
 * 用法与 [Text] 一致，只是数字按位对齐。传入的字符串必须已由调用方完成本地化格式化
 * （小数位、千分位、单位），本组件不做任何数值格式化，以免同一数值在不同页面呈现出
 * 不同精度。
 */
@Composable
fun FluoNumericText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.tabularFigures(),
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 数值 + 单位的组合。
 *
 * 数值使用等宽数字并保持视觉重量，单位以较小字号跟随其后并对齐基线下沿。数值必须带
 * 单位（AGENTS.md 7.4），但单位不应与数值抢夺注意力——扫视一列指标时先读到的应该是
 * 量级，而不是重复出现的 `ng/mL`。
 *
 * 单位过长时允许换行显示而不是截断成省略号：`μmol/L`、`copies/mL` 这类单位一旦被截断
 * 就失去了科学含义。
 */
@Composable
fun FluoMeasurement(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    unitColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        FluoNumericText(
            text = value,
            style = valueStyle,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
        unit?.takeIf(String::isNotBlank)?.let { unitText ->
            Text(
                text = unitText,
                style = MaterialTheme.typography.labelMedium,
                color = unitColor,
                // 下移一点让单位与数值的基线对齐而不是与其顶部对齐。
                modifier = Modifier.padding(bottom = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 结果页首屏的科学指标块。
 *
 * 与通用的 [FluoMetricTile] 区别在于：这里数值是主体（headlineSmall + 等宽数字），
 * 标签在上方作为限定语。指标带横排三项时，三个数值的小数点会自然对齐。
 *
 * @param value 已格式化的数值；为 null 时显示占位破折号而不是 0，避免把"没有测到"
 *   呈现成"测得 0"（AGENTS.md 11）。
 */
@Composable
fun FluoScientificMetric(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    supporting: String? = null
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (value == null) {
            Text(
                text = NO_VALUE_PLACEHOLDER,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            FluoMeasurement(
                value = value,
                unit = unit,
                valueColor = valueColor
            )
        }
        supporting?.takeIf(String::isNotBlank)?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 数值缺失占位。
 *
 * 使用长破折号而不是 `0`、`N/A` 或空字符串：`0` 会被误读为测量结果，空字符串让人以为
 * 是渲染缺陷，而破折号在科研表格中是"此项无数据"的通用写法。
 */
const val NO_VALUE_PLACEHOLDER: String = "—"

/** 指标带内各项之间的标准间距，供结果页横排指标复用。 */
val FluoMetricRowSpacing = FluoSpacing.lg

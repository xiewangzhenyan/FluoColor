package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.result.ResultQualityIssue
import com.muc.fluocolorquant.domain.result.ResultQualityLevel
import com.muc.fluocolorquant.domain.result.ResultQualitySummary
import com.muc.fluocolorquant.ui.components.FluoNumericText
import com.muc.fluocolorquant.ui.theme.FluoIconSize
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.theme.FluoTheme

/** 裁决条测试标签，供设备回归定位而不依赖中英文可见文本。 */
const val RESULT_QUALITY_BAR_TAG: String = "result_quality_bar"

/**
 * 运行质量裁决条。
 *
 * 固定在结果页顶栏下方、四个一级页之上：无论用户在看热力图还是翻到"过程"页核对证据，
 * 总能同时看到"这批数据能不能用"的总判定。
 *
 * 结构是"一句话结论 + 支撑数字"：
 * - 第一行是裁决本身，用图标 + 文字 + 语义色三重表达，不单靠颜色（AGENTS.md 10）；
 * - 第二行是支撑该结论的关键数字，让判定可被追问而不是黑箱。
 *
 * 组件不做任何判定逻辑，只负责呈现 [ResultQualitySummary]；判定规则集中在 domain 层
 * 并配有单测，96 孔板与微流控共用同一口径。
 */
@Composable
fun ResultQualityBar(
    summary: ResultQualitySummary,
    modifier: Modifier = Modifier,
    /** 浓度单位；仅信号运行传入信号特征名，为空时不显示单位。 */
    unit: String? = null,
    /** 数值格式化交由调用方，避免同一数值在不同页面出现不同精度。 */
    formatValue: (Double) -> String
) {
    val appearance = summary.level.appearance()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RESULT_QUALITY_BAR_TAG),
        color = appearance.container,
        shape = RoundedCornerShape(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = FluoSpacing.lg,
                vertical = FluoSpacing.md
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FluoSpacing.md)
        ) {
            Icon(
                imageVector = appearance.icon,
                contentDescription = null,
                modifier = Modifier.size(FluoIconSize.large),
                tint = appearance.accent
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(appearance.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = appearance.onContainer,
                    maxLines = 1
                )
                QualityEvidenceRow(
                    summary = summary,
                    contentColor = appearance.onContainer,
                    formatValue = formatValue
                )
                // 降级原因单独一行：用户需要知道"为什么不是良好"，而不是只看到一个结论。
                summary.issues.firstOrNull()?.let { issue ->
                    Text(
                        text = stringResource(issue.labelRes()),
                        style = MaterialTheme.typography.labelSmall,
                        color = appearance.onContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 支撑数字行。
 *
 * 只呈现真实存在的项：仅信号运行没有 R²，没有重复孔就没有 CV。缺失项直接不显示，
 * 而不是补一个占位数字——占位符会让人误以为该指标测过但结果不佳。
 */
@Composable
private fun QualityEvidenceRow(
    summary: ResultQualitySummary,
    contentColor: Color,
    formatValue: (Double) -> String
) {
    val parts = buildList {
        summary.rSquared?.let { value ->
            add(stringResource(R.string.result_quality_metric_r2, formatValue(value)))
        }
        summary.repeatCvPercent?.let { value ->
            add(stringResource(R.string.result_quality_metric_cv, formatValue(value)))
        }
        if (summary.evaluatedCount > 0) {
            add(
                stringResource(
                    R.string.result_quality_metric_in_range,
                    summary.inRangeCount,
                    summary.evaluatedCount
                )
            )
        }
        if (summary.retestCount > 0) {
            add(stringResource(R.string.result_quality_metric_retest, summary.retestCount))
        }
    }
    if (parts.isEmpty()) {
        // 完全没有支撑数字时说明连位点都没有；给出明确说明而不是留一行空白。
        Text(
            text = stringResource(R.string.result_quality_unknown_detail),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        return
    }
    // 支撑数字含 R² 与 CV，使用等宽数字让不同运行之间的同一指标位置稳定。
    FluoNumericText(
        text = parts.joinToString(EVIDENCE_SEPARATOR),
        style = MaterialTheme.typography.labelMedium,
        color = contentColor
    )
}

/** 支撑数字之间的分隔符。中点比逗号更适合并列的短指标，且中英文下都不需要额外空格规则。 */
private const val EVIDENCE_SEPARATOR = " · "

/** 裁决等级对应的视觉与文案。 */
private data class QualityAppearance(
    val icon: ImageVector,
    val accent: Color,
    val container: Color,
    val onContainer: Color,
    val titleRes: Int
)

@Composable
private fun ResultQualityLevel.appearance(): QualityAppearance = when (this) {
    ResultQualityLevel.GOOD -> QualityAppearance(
        icon = Icons.Filled.CheckCircle,
        accent = FluoTheme.semantic.success,
        container = FluoTheme.semantic.successContainer,
        onContainer = FluoTheme.semantic.onSuccessContainer,
        titleRes = R.string.result_quality_good
    )
    ResultQualityLevel.REVIEW -> QualityAppearance(
        icon = Icons.Outlined.WarningAmber,
        accent = FluoTheme.semantic.warning,
        container = FluoTheme.semantic.warningContainer,
        onContainer = FluoTheme.semantic.onWarningContainer,
        titleRes = R.string.result_quality_review
    )
    ResultQualityLevel.UNRELIABLE -> QualityAppearance(
        icon = Icons.Outlined.ErrorOutline,
        accent = MaterialTheme.colorScheme.error,
        container = MaterialTheme.colorScheme.errorContainer,
        onContainer = MaterialTheme.colorScheme.onErrorContainer,
        titleRes = R.string.result_quality_unreliable
    )
    // 仅信号运行不是"出问题"，只是没有浓度可判定，因此用中性提示色而不是警告色：
    // 把正常的仅信号流程标成黄色会让用户以为运行失败。
    ResultQualityLevel.UNKNOWN -> QualityAppearance(
        icon = Icons.Outlined.HelpOutline,
        accent = FluoTheme.semantic.info,
        container = FluoTheme.semantic.infoContainer,
        onContainer = FluoTheme.semantic.onInfoContainer,
        titleRes = R.string.result_quality_unknown
    )
}

private fun ResultQualityIssue.labelRes(): Int = when (this) {
    ResultQualityIssue.LOW_R_SQUARED -> R.string.result_quality_issue_r2
    ResultQualityIssue.HIGH_REPEAT_CV -> R.string.result_quality_issue_cv
    ResultQualityIssue.HIGH_RETEST_RATIO -> R.string.result_quality_issue_retest
    ResultQualityIssue.HIGH_EXTRAPOLATION_RATIO -> R.string.result_quality_issue_extrapolation
}

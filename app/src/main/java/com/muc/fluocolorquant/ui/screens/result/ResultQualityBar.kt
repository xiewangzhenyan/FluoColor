package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.result.ResultRunSummary
import com.muc.fluocolorquant.ui.components.FluoNumericText
import com.muc.fluocolorquant.ui.theme.FluoSpacing

/** 摘要条测试标签，供设备回归定位而不依赖中英文可见文本。 */
const val RESULT_QUALITY_BAR_TAG: String = "result_quality_bar"

/**
 * 运行摘要条。
 *
 * 只陈述事实，不下判语。
 *
 * 早先版本在这里做"良好 / 需复核 / 不建议使用"三态裁决，配警告色与感叹号。该设计有两个
 * 实质错误：把"样本超量程"当成了数据质量问题（超量程是实验设计与样本浓度不匹配的正常
 * 现象，处理办法是稀释重测或补标准点，不代表测量不可信）；阈值又是凭"生化常规"臆断的
 * （超量程占比 5%、R² 0.99），真实体系里 96 孔板几十个孔超量程完全正常，结果每次打开
 * 结果页都在报警——既没帮上忙，也让软件显得在指责用户。
 *
 * 现在只把关键数字如实平铺。数字本身就是结论，好坏由研究者按自己的实验体系判断。
 *
 * 保留为独立组件是因为 96 孔板与微流控结果页共用它，口径必须一致。
 */
@Composable
fun ResultQualityBar(
    summary: ResultRunSummary,
    modifier: Modifier = Modifier
) {
    val parts = buildList {
        summary.rSquared?.let { value ->
            // R² 固定四位小数：0.9987 与 0.9950 在两位小数下都显示成 1.00 / 0.99，
            // 而这正是判断曲线好坏时最需要分辨的位数。
            add(stringResource(R.string.result_quality_metric_r2, "%.4f".format(value)))
        }
        summary.repeatCvPercent?.let { value ->
            add(stringResource(R.string.result_quality_metric_cv, "%.1f".format(value)))
        }
        // 超出项目量程与由曲线外推分别陈述：前者改项目设置即可，后者说明浓度落在标定区间
        // 之外、只能外推得到，处理方式完全不同。合并成一个"需复测"数字会掩盖该差别。
        if (summary.outsideProjectRangeCount > 0) {
            add(
                stringResource(
                    R.string.result_summary_out_of_range,
                    summary.outsideProjectRangeCount
                )
            )
        }
        if (summary.extrapolatedCount > 0) {
            add(stringResource(R.string.result_summary_extrapolated, summary.extrapolatedCount))
        }
        if (summary.rSquared == null && summary.evaluatedCount > 0) {
            add(stringResource(R.string.result_summary_no_curve))
        }
    }

    if (parts.isEmpty()) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RESULT_QUALITY_BAR_TAG),
        // 最低层级表面而不是语义色：这里陈述事实，不表达状态。
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = FluoSpacing.lg,
                vertical = FluoSpacing.sm
            ),
            horizontalArrangement = Arrangement.spacedBy(FluoSpacing.sm)
        ) {
            FluoNumericText(
                text = parts.joinToString(SUMMARY_SEPARATOR),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** 摘要项之间的分隔符。中点比逗号更适合并列的短指标，中英文下都不需要额外空格规则。 */
private const val SUMMARY_SEPARATOR = " · "

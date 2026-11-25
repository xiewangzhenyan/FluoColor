package com.muc.fluocolorquant.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.muc.fluocolorquant.R
import java.util.Locale

/**
 * 将数据库与历史快照中的稳定分析方式编码转换为用户可见文案。
 *
 * 分析方式编码属于持久化契约，不能为了展示而改写；界面也不能把未知编码直接暴露给用户。
 * 因此所有项目摘要与结果详情统一经过这里：已知的新旧编码映射到同一科学语义，未来版本
 * 无法识别的编码则显示稳定的历史兼容文案，同时原始编码仍完整保留在数据库与导出清单中。
 */
@StringRes
fun analysisMethodLabelResource(stableCode: String?): Int = when (
    stableCode?.trim()?.uppercase(Locale.ROOT)
) {
    "DL_MODEL", "DEEP_LEARNING_MODEL" -> R.string.deep_learning_analysis
    "CURVE_FIT", "STANDARD_CURVE", "EXISTING_STANDARD_CURVE" ->
        R.string.curve_fitting_analysis
    "SIGNAL_ONLY" -> R.string.grid_quant_mode_signal_only
    "LSPR_SPECTRUM" -> R.string.lspr_spectrum_analysis
    "TEMPLATE_MANAGED", "TEMPLATE_MODEL" -> R.string.analysis_method_template_managed
    "ONSITE_CALIBRATION", "ONSITE_AUTO_FIT" -> R.string.grid_quant_onsite_dialog_title
    else -> R.string.analysis_method_historical
}

/** 在 Compose 页面中读取当前应用语言对应的分析方式文案。 */
@Composable
fun localizedAnalysisMethodLabel(stableCode: String?): String =
    stringResource(analysisMethodLabelResource(stableCode))

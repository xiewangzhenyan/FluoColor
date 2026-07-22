package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.AnalyteResultDetails
import com.muc.fluocolorquant.data.model.Reagent
import com.muc.fluocolorquant.ui.components.ScientificExpandableSection
import com.muc.fluocolorquant.ui.viewmodels.ReagentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 分析方案卡片
 * 显示分析物的分析方案信息，包括分析方法、使用的模板、试剂信息等
 */
@Composable
fun AnalysisPlanCard(
    analyteDetails: AnalyteResultDetails,
    reagentViewModel: ReagentViewModel = hiltViewModel()
) {
    // 用于存储加载的试剂信息
    var antigen by remember { mutableStateOf<Reagent?>(null) }
    var antibody by remember { mutableStateOf<Reagent?>(null) }
    var expanded by rememberSaveable(analyteDetails.analyte.id) { mutableStateOf(false) }
    val missingValue = stringResource(R.string.result_traceability_not_available)
    val analysisMethodLabel = when (analyteDetails.analysisMethod) {
        "CURVE_FIT" -> stringResource(R.string.curve_fitting_analysis)
        "DL_MODEL" -> stringResource(R.string.deep_learning_analysis)
        else -> analyteDetails.analysisMethod
    }
    val templateName = analyteDetails.usedTemplate?.templateName
        ?: stringResource(R.string.no_template_used)
    val curveModelName = analyteDetails.fittedCurveModel?.name
        ?: analyteDetails.traceabilityInfo?.curveModelName
        ?: stringResource(R.string.no_curve_model)
    val pixelFeatureName = analyteDetails.traceabilityInfo?.pixelFeatureName
        ?: analyteDetails.fittedCurveModel?.pixelType?.name
        ?: missingValue
    
    // 加载试剂信息
    LaunchedEffect(analyteDetails.usedTemplate?.reagentAntigenId, analyteDetails.usedTemplate?.reagentAntibodyId) {
        // 加载抗原信息
        analyteDetails.usedTemplate?.reagentAntigenId?.let { antigenId ->
            val reagent = withContext(Dispatchers.IO) {
                reagentViewModel.getReagentById(antigenId)
            }
            antigen = reagent
        }
        
        // 加载抗体信息
        analyteDetails.usedTemplate?.reagentAntibodyId?.let { antibodyId ->
            val reagent = withContext(Dispatchers.IO) {
                reagentViewModel.getReagentById(antibodyId)
            }
            antibody = reagent
        }
    }

    ScientificExpandableSection(
        title = stringResource(R.string.analysis_plan_for, analyteDetails.analyte.name),
        summary = stringResource(
            R.string.result_analysis_plan_compact_summary,
            analysisMethodLabel,
            curveModelName
        ),
        icon = Icons.Default.Science,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        toggleContentDescription = stringResource(
            if (expanded) R.string.result_details_collapse else R.string.result_details_expand
        )
    ) {
        Column {
            AnalysisPlanInfoLine(
                label = stringResource(R.string.analysis_method_label),
                value = analysisMethodLabel
            )
            Spacer(modifier = Modifier.height(8.dp))
            AnalysisPlanInfoLine(
                label = stringResource(R.string.result_traceability_template),
                value = templateName
            )
            Spacer(modifier = Modifier.height(8.dp))
            AnalysisPlanInfoLine(
                label = stringResource(R.string.result_traceability_curve_model),
                value = curveModelName
            )
            Spacer(modifier = Modifier.height(8.dp))
            AnalysisPlanInfoLine(
                label = stringResource(R.string.result_traceability_pixel_feature),
                value = pixelFeatureName
            )

            Spacer(modifier = Modifier.height(8.dp))
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // 试剂信息（如果有）
            analyteDetails.usedTemplate?.let { template ->
                AnalysisPlanInfoLine(
                    label = stringResource(R.string.analysis_plan_reliable_range_label),
                    value = stringResource(
                        R.string.analysis_plan_reliable_range_value,
                        template.reliableRangeMin.toString(),
                        template.reliableRangeMax.toString(),
                        template.concentrationUnit
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 抗原信息
                if (template.reagentAntigenId != null) {
                    val antigenName = antigen?.reagentName
                        ?: missingValue
                    AnalysisPlanInfoLine(
                        label = stringResource(R.string.analysis_plan_antigen_label),
                        value = antigenName
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // 抗体信息
                if (template.reagentAntibodyId != null) {
                    val antibodyName = antibody?.reagentName
                        ?: missingValue
                    AnalysisPlanInfoLine(
                        label = stringResource(R.string.analysis_plan_antibody_label),
                        value = antibodyName
                    )
                }
            } ?: AnalysisPlanInfoLine(
                label = stringResource(R.string.analysis_plan_reliable_range_label),
                value = missingValue
            )
        }
    }
}

@Composable
private fun AnalysisPlanInfoLine(
    label: String,
    value: String
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

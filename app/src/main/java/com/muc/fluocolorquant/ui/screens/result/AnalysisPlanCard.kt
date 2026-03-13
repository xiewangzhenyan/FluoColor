package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.AnalyteResultDetails
import com.muc.fluocolorquant.data.model.Reagent
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
    val colorScheme = MaterialTheme.colorScheme
    
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 卡片标题
            Text(
                text = stringResource(R.string.analysis_plan_for, analyteDetails.analyte.name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 分析方法
            Text(
                text = stringResource(
                    R.string.analysis_method_res,
                    when (analyteDetails.analysisMethod) {
                        "CURVE_FIT" -> stringResource(R.string.curve_fitting_analysis)
                        "DL_MODEL" -> stringResource(R.string.deep_learning_analysis)
                        else -> analyteDetails.analysisMethod
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 使用的模板
            Text(
                text = stringResource(
                    R.string.template_used_res,
                    analyteDetails.usedTemplate?.templateName ?: stringResource(R.string.no_template_used)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // 试剂信息（如果有）
            analyteDetails.usedTemplate?.let { template ->
                // 抗原信息
                if (template.reagentAntigenId != null) {
                    val antigenName = antigen?.reagentName ?: "Unknown"
                    val manufacturer = antigen?.manufacturer ?: ""
                    Text(
                        text = stringResource(R.string.antigen_manufacturer_placeholder, antigenName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                // 抗体信息
                if (template.reagentAntibodyId != null) {
                    val antibodyName = antibody?.reagentName ?: "Unknown"
                    val manufacturer = antibody?.manufacturer ?: ""
                    Text(
                        text = stringResource(R.string.antibody_manufacturer_placeholder, antibodyName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                // 可靠范围
                if (template.reliableRangeMin != null && template.reliableRangeMax != null) {
                    Text(
                        text = stringResource(
                            R.string.reliable_range_format,
                            template.reliableRangeMin.toString(),
                            template.reliableRangeMax.toString(),
                            template.concentrationUnit ?: analyteDetails.concentrationUnit
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
} 

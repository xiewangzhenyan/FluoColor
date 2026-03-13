package com.muc.fluocolorquant.ui.screens.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatDate(date: Date): String = 
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(date)

/**
 * 项目信息卡片
 * 显示项目的基本信息，包括名称、检测模式、识别类型、创建时间、浓度单位和分析物列表
 */
@Composable
fun ProjectInfoCard(
    project: Project, 
    concentrationUnit: String,
    analytesList: List<Analyte> = emptyList()
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 项目名称
            Text(
                text = project.name, 
                style = MaterialTheme.typography.titleLarge, 
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 检测模式
            Text(
                text = stringResource(
                    R.string.detection_mode_res,
                    when (project.detectionMode) {
                        "FLUORESCENCE" -> stringResource(R.string.fluorescence_detection_mode)
                        "COLORIMETRIC" -> stringResource(R.string.colorimetric_detection_mode)
                        else -> project.detectionMode
                    }
                ),
                color = colorScheme.onSurfaceVariant
            )
            
            // 识别类型
            Text(
                text = stringResource(
                    R.string.analysis_method_res,
                    when (project.analysisMethod) {
                        "DL_MODEL" -> stringResource(R.string.deep_learning_analysis)
                        "CURVE_FIT" -> stringResource(R.string.curve_fitting_analysis)
                        else -> project.analysisMethod
                    }
                ),
                color = colorScheme.onSurfaceVariant
            )
            
            // 分析物列表
            if (analytesList.isNotEmpty()) {
                val analyteNames = analytesList.joinToString(", ") { it.name }
                Text(
                    text = stringResource(R.string.analytes_in_project, analyteNames),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            }
            
            // 创建时间
            Text(
                text = stringResource(R.string.creation_time, formatDate(project.createTime)),
                color = colorScheme.onSurfaceVariant
            )

            // 浓度单位
            Text(
                text = stringResource(R.string.concentration_unit_res, concentrationUnit),
                color = colorScheme.onSurfaceVariant
            )
        }
    }
} 

package com.muc.fluocolorquant.ui.components.tables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R

/**
 * 指标表格组件
 * 用于显示拟合质量指标
 * 
 * @param metrics 指标映射表，键为指标名称，值为格式化的指标值
 * @param modifier 修饰符
 * @param title 表格标题，默认为"拟合质量评估"
 */
@Composable
fun MetricsTable(
    metrics: Map<String, String>,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 表格标题
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
            
            // 表头
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.metrics_name),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.metrics_value),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // 表格内容
            metrics.forEach { (name, value) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = name,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = value,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End
                    )
                }
                Divider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
} 
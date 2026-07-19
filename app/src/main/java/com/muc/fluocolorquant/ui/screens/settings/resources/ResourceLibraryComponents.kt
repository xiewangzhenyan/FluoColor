package com.muc.fluocolorquant.ui.screens.settings.resources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import kotlinx.coroutines.flow.Flow

/** 页面顶部统计项，由调用页面传入已经本地化的标签。 */
data class ResourceSummaryMetric(
    val label: String,
    val value: String,
    val icon: ImageVector
)

/**
 * 资源库统一标题与统计面板。
 *
 * 面板使用水平统计而不是复杂仪表盘，突出实验室资源数量和状态，同时保持小屏设备上
 * 的可扫描性；三个指标在同一行中等权展示。
 */
@Composable
fun ResourceLibraryHeader(
    title: String,
    subtitle: String,
    metrics: List<ResourceSummaryMetric>,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.18f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                metrics.take(3).forEach { metric ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = metric.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = accentColor
                            )
                            Text(
                                text = metric.value,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = metric.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 全部、启用和已归档三种稳定筛选。 */
@Composable
fun ResourceStatusFilterBar(
    selected: ResourceStatusFilter,
    onSelected: (ResourceStatusFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ResourceStatusFilter.entries.forEach { filter ->
            val label = when (filter) {
                ResourceStatusFilter.ALL -> stringResource(R.string.resource_filter_all)
                ResourceStatusFilter.ACTIVE -> stringResource(R.string.resource_filter_active)
                ResourceStatusFilter.ARCHIVED -> stringResource(R.string.resource_filter_archived)
            }
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = { Text(label) }
            )
        }
    }
}

/** 将稳定状态编码显示为明确的本地化标签。 */
@Composable
fun ResourceStatusBadge(status: String) {
    val (label, color) = when (status) {
        ResourceStatus.ACTIVE.code -> stringResource(R.string.resource_status_active) to
            MaterialTheme.colorScheme.primary
        ResourceStatus.ARCHIVED.code -> stringResource(R.string.resource_status_archived) to
            MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(R.string.resource_status_legacy) to MaterialTheme.colorScheme.tertiary
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.10f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 空列表占位，说明下一步操作而不是只显示“无数据”。 */
@Composable
fun ResourceEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(58.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Inventory2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 卡片中用于显示几何、模态等短元数据的胶囊标签。 */
@Composable
fun ResourceMetadataPill(text: String, icon: ImageVector? = null) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 统一消费 ViewModel 的一次性事件并映射为自定义 Toast。
 *
 * 所有字符串先在 Composable 上下文中解析，再在 Flow 回调中使用，严格遵守
 * `stringResource()` 不得在非 Composable 回调直接调用的约束。
 */
@Composable
fun ResourceProfileEventEffect(events: Flow<ResourceProfileEvent>) {
    val toastManager = LocalToastManager.current
    val saveSuccess = stringResource(R.string.resource_save_success)
    val versionSuccess = stringResource(R.string.resource_new_version_success)
    val archiveSuccess = stringResource(R.string.resource_archive_success)
    val saveFailed = stringResource(R.string.resource_save_failed)
    val archiveFailed = stringResource(R.string.resource_archive_failed)
    val validationMessages = mapOf(
        ResourceFormError.NAME_REQUIRED to stringResource(R.string.resource_validation_name),
        ResourceFormError.ROWS_OUT_OF_RANGE to stringResource(R.string.resource_validation_rows),
        ResourceFormError.COLUMNS_OUT_OF_RANGE to stringResource(R.string.resource_validation_columns),
        ResourceFormError.DETECTION_MODE_REQUIRED to stringResource(R.string.resource_validation_mode),
        ResourceFormError.COMPATIBLE_CARRIER_REQUIRED to stringResource(
            R.string.resource_validation_carrier
        )
    )

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is ResourceProfileEvent.ValidationFailed -> {
                    val message = event.errors.firstNotNullOfOrNull(validationMessages::get)
                        ?: saveFailed
                    toastManager.showToast(message, ToastType.WARNING)
                }
                is ResourceProfileEvent.SaveSucceeded -> toastManager.showToast(
                    if (event.createdNewVersion) versionSuccess else saveSuccess,
                    ToastType.SUCCESS
                )
                ResourceProfileEvent.ArchiveSucceeded -> toastManager.showToast(
                    archiveSuccess,
                    ToastType.SUCCESS
                )
                ResourceProfileEvent.SaveFailed -> toastManager.showToast(saveFailed, ToastType.ERROR)
                ResourceProfileEvent.ArchiveFailed -> toastManager.showToast(
                    archiveFailed,
                    ToastType.ERROR
                )
            }
        }
    }
}

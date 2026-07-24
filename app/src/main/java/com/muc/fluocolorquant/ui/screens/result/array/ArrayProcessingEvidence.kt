package com.muc.fluocolorquant.ui.screens.result.array

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.domain.result.ArrayCaptureEvidence
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import java.io.File

const val ARRAY_PROCESSING_TAB_TAG: String = "array_processing_tab"
const val ARRAY_PROCESSING_IMAGE_TAG: String = "array_processing_image"

/**
 * 用户可见的 PG-Grid/PG-Quant 处理证据页。
 *
 * 页面按“原始输入 → 算法处理证据”展示随运行冻结的图像，不在 UI 层重新执行算法。
 * 原始图不再叠加可能产生坐标歧义的临时标签；定位效果直接查看冻结的网格叠加步骤。
 */
@Composable
fun ArrayProcessingEvidenceTab(snapshot: ArrayResultSnapshot) {
    val evidence = remember(snapshot.runId, snapshot.artifacts) {
        snapshot.artifacts.filter { artifact ->
            val role = CaptureRole.fromCode(artifact.captureRole)
            role == CaptureRole.ENDPOINT || role?.isProcessingEvidence == true
        }
    }
    var selectedId by remember(snapshot.runId, evidence) {
        mutableStateOf(evidence.firstOrNull()?.artifactId)
    }
    val selected = evidence.firstOrNull { it.artifactId == selectedId } ?: evidence.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(ARRAY_PROCESSING_TAB_TAG),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ProcessingIntroCard()
        }
        if (evidence.isEmpty()) {
            item {
                ProcessingEmptyCard()
            }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(evidence, key = ArrayCaptureEvidence::artifactId) { artifact ->
                        val role = CaptureRole.fromCode(artifact.captureRole)
                        FilterChip(
                            selected = artifact.artifactId == selected?.artifactId,
                            onClick = { selectedId = artifact.artifactId },
                            label = {
                                Text(
                                    text = processingEvidenceShortTitle(role),
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }
            selected?.let { artifact ->
                item {
                    ProcessingEvidenceCard(
                        artifact = artifact,
                        currentIndex = evidence.indexOfFirst { it.artifactId == artifact.artifactId } + 1,
                        total = evidence.size
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessingIntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.52f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondary
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.array_processing_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.array_processing_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProcessingEvidenceCard(
    artifact: ArrayCaptureEvidence,
    currentIndex: Int,
    total: Int
) {
    val role = CaptureRole.fromCode(artifact.captureRole)
    var loadFailed by remember(artifact.artifactId) { mutableStateOf(false) }
    val imageModel = remember(artifact.originalPath) {
        val uri = Uri.parse(artifact.originalPath)
        if (uri.scheme.isNullOrBlank()) File(artifact.originalPath) else artifact.originalPath
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = processingEvidenceTitle(role),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = processingEvidenceDescription(role),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = stringResource(
                            R.string.array_processing_step_count,
                            currentIndex,
                            total
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
                    // 测试标记属于“证据图显示区域”而不是 Coil 的瞬时加载节点。
                    // 即使文件损坏或历史附件丢失，错误占位仍应保留同一个可访问语义区域。
                    .testTag(ARRAY_PROCESSING_IMAGE_TAG),
                contentAlignment = Alignment.Center
            ) {
                if (loadFailed) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.BrokenImage, contentDescription = null)
                        Text(stringResource(R.string.array_processing_image_failed))
                    }
                } else {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = processingEvidenceTitle(role),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        onError = { loadFailed = true }
                    )
                }
            }

            if (role?.isProcessingEvidence == true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.array_processing_diagnostic_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.array_processing_original_input_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            artifact.checksumSha256?.takeIf(String::isNotBlank)?.let { checksum ->
                Text(
                    text = stringResource(
                        R.string.array_processing_checksum,
                        checksum.take(12)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProcessingEmptyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.array_processing_empty_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.array_processing_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun processingEvidenceShortTitle(role: CaptureRole?): String = when (role) {
    CaptureRole.ENDPOINT -> stringResource(R.string.array_processing_short_input)
    CaptureRole.PROCESS_ORIGINAL_GEOMETRY -> stringResource(R.string.array_processing_short_original)
    CaptureRole.PROCESS_CANDIDATE_RESPONSE -> stringResource(R.string.array_processing_short_candidate)
    CaptureRole.PROCESS_RECTIFIED -> stringResource(R.string.array_processing_short_rectified)
    CaptureRole.PROCESS_GRID_OVERLAY -> stringResource(R.string.array_processing_short_grid)
    CaptureRole.PROCESS_ROI_BACKGROUND -> stringResource(R.string.array_processing_short_roi)
    CaptureRole.PROCESS_BACKGROUND_FIELD -> stringResource(R.string.array_processing_short_background)
    CaptureRole.PROCESS_SIGNAL_HEATMAP -> stringResource(R.string.array_processing_short_signal)
    CaptureRole.PROCESS_SNR_HEATMAP -> stringResource(R.string.array_processing_short_snr)
    CaptureRole.PROCESS_CORRECTED_COLOR -> stringResource(R.string.array_processing_short_color)
    else -> stringResource(R.string.array_capture_role_unknown)
}

@Composable
fun processingEvidenceTitle(role: CaptureRole?): String = when (role) {
    CaptureRole.ENDPOINT -> stringResource(R.string.array_processing_input_title)
    CaptureRole.PROCESS_ORIGINAL_GEOMETRY -> stringResource(R.string.array_processing_original_title)
    CaptureRole.PROCESS_CANDIDATE_RESPONSE -> stringResource(R.string.array_processing_candidate_title)
    CaptureRole.PROCESS_RECTIFIED -> stringResource(R.string.array_processing_rectified_title)
    CaptureRole.PROCESS_GRID_OVERLAY -> stringResource(R.string.array_processing_grid_title)
    CaptureRole.PROCESS_ROI_BACKGROUND -> stringResource(R.string.array_processing_roi_title)
    CaptureRole.PROCESS_BACKGROUND_FIELD -> stringResource(R.string.array_processing_background_title)
    CaptureRole.PROCESS_SIGNAL_HEATMAP -> stringResource(R.string.array_processing_signal_title)
    CaptureRole.PROCESS_SNR_HEATMAP -> stringResource(R.string.array_processing_snr_title)
    CaptureRole.PROCESS_CORRECTED_COLOR -> stringResource(R.string.array_processing_color_title)
    else -> stringResource(R.string.array_capture_role_unknown)
}

@Composable
private fun processingEvidenceDescription(role: CaptureRole?): String = when (role) {
    CaptureRole.ENDPOINT -> stringResource(R.string.array_processing_input_desc)
    CaptureRole.PROCESS_ORIGINAL_GEOMETRY -> stringResource(R.string.array_processing_original_desc)
    CaptureRole.PROCESS_CANDIDATE_RESPONSE -> stringResource(R.string.array_processing_candidate_desc)
    CaptureRole.PROCESS_RECTIFIED -> stringResource(R.string.array_processing_rectified_desc)
    CaptureRole.PROCESS_GRID_OVERLAY -> stringResource(R.string.array_processing_grid_desc)
    CaptureRole.PROCESS_ROI_BACKGROUND -> stringResource(R.string.array_processing_roi_desc)
    CaptureRole.PROCESS_BACKGROUND_FIELD -> stringResource(R.string.array_processing_background_desc)
    CaptureRole.PROCESS_SIGNAL_HEATMAP -> stringResource(R.string.array_processing_signal_desc)
    CaptureRole.PROCESS_SNR_HEATMAP -> stringResource(R.string.array_processing_snr_desc)
    CaptureRole.PROCESS_CORRECTED_COLOR -> stringResource(R.string.array_processing_color_desc)
    else -> stringResource(R.string.array_processing_unknown_desc)
}

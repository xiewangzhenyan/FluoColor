package com.muc.fluocolorquant.ui.screens.result.plate96

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.domain.result.ArrayCaptureEvidence
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSource
import com.muc.fluocolorquant.ui.theme.FluoRadius

const val PLATE96_PROCESSING_TAG: String = "plate96_processing"
const val PLATE96_PROCESSING_IMAGE_TAG_PREFIX: String = "plate96_processing_image_"
const val PLATE96_LEGACY_HISTORY_NOTICE_TAG: String = "plate96_legacy_history_notice"

/** 96孔板过程页按实验顺序展示证据，不把内部JSON或质控长文案堆给普通用户。 */
@Composable
fun Plate96ProcessingContent(snapshot: Plate96ResultSnapshot) {
    val artifactsByRole = snapshot.arraySnapshot.artifacts.groupBy { it.captureRole }
    val legacy = snapshot.source == Plate96ResultSource.LEGACY_WELL_RESULT
    val stages = plate96ProcessingStages(snapshot).filter { stage ->
        !legacy || stage.roles.any { role -> artifactsByRole[role.code].orEmpty().isNotEmpty() }
    }
    LazyColumn(
        modifier = Modifier.testTag(PLATE96_PROCESSING_TAG),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (legacy) {
            item { Plate96LegacyHistoryNotice() }
        } else {
            item {
            Card(
                shape = RoundedCornerShape(FluoRadius.card),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.plate96_process_orientation_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = orientationSummary(snapshot),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            R.string.plate96_process_locator_summary,
                            snapshot.arraySnapshot.frame.locatorName,
                            snapshot.arraySnapshot.frame.locatorVersion,
                            snapshot.wells.count { it.site.geometry.source.name != "GRID_IMPUTED" },
                            snapshot.wells.count { it.site.geometry.source.name == "GRID_IMPUTED" }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        }
        items(stages, key = Plate96ProcessingStage::code) { stage ->
            Plate96ProcessingStageCard(
                stage = stage,
                artifacts = stage.roles.flatMap { role -> artifactsByRole[role.code].orEmpty() }
            )
        }
    }
}

/**
 * 旧运行没有冻结方向矩阵和处理中间图。
 *
 * 页面明确说明兼容边界，并只显示数据库中真实存在的附件，避免用今天的算法补画历史证据。
 */
@Composable
private fun Plate96LegacyHistoryNotice() {
    Card(
        modifier = Modifier.testTag(PLATE96_LEGACY_HISTORY_NOTICE_TAG),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.plate96_process_legacy_notice_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.plate96_process_legacy_notice_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Plate96ProcessingStageCard(
    stage: Plate96ProcessingStage,
    artifacts: List<ArrayCaptureEvidence>
) {
    val complete = artifacts.isNotEmpty() || stage.inferredComplete
    Card(
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (complete) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (complete) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(stage.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(stage.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (complete) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = stringResource(
                            if (complete) R.string.plate96_process_recorded
                            else R.string.plate96_process_not_recorded
                        ),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            artifacts.forEach { artifact ->
                AsyncImage(
                    model = artifact.derivedPath ?: artifact.originalPath,
                    contentDescription = stringResource(stage.titleRes),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.55f)
                        .testTag("$PLATE96_PROCESSING_IMAGE_TAG_PREFIX${artifact.artifactId}"),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

private fun plate96ProcessingStages(snapshot: Plate96ResultSnapshot): List<Plate96ProcessingStage> {
    return listOf(
        Plate96ProcessingStage(
            code = "original",
            titleRes = R.string.plate96_process_original,
            descriptionRes = R.string.plate96_process_original_desc,
            roles = setOf(CaptureRole.ENDPOINT),
            inferredComplete = snapshot.arraySnapshot.artifacts.any { it.captureRole == CaptureRole.ENDPOINT.code }
        ),
        Plate96ProcessingStage(
            code = "orientation",
            titleRes = R.string.plate96_process_orientation,
            descriptionRes = R.string.plate96_process_orientation_desc,
            roles = setOf(CaptureRole.PROCESS_ORIENTATION_NORMALIZED),
            inferredComplete = snapshot.orientation.quarterTurnsClockwise != null
        ),
        Plate96ProcessingStage(
            code = "yolo",
            titleRes = R.string.plate96_process_yolo,
            descriptionRes = R.string.plate96_process_yolo_desc,
            roles = setOf(CaptureRole.PROCESS_YOLO_OVERLAY, CaptureRole.PROCESS_CANDIDATE_RESPONSE),
            inferredComplete = snapshot.arraySnapshot.frame.locatorName.isNotBlank()
        ),
        Plate96ProcessingStage(
            code = "circles",
            titleRes = R.string.plate96_process_circles,
            descriptionRes = R.string.plate96_process_circles_desc,
            roles = setOf(CaptureRole.PROCESS_HOUGH_CIRCLE_OVERLAY),
            inferredComplete = snapshot.wells.any { it.site.geometry.source.name != "GRID_IMPUTED" }
        ),
        Plate96ProcessingStage(
            code = "grid",
            titleRes = R.string.plate96_process_grid,
            descriptionRes = R.string.plate96_process_grid_desc,
            roles = setOf(CaptureRole.PROCESS_GRID_OVERLAY),
            inferredComplete = snapshot.wells.size == 96
        ),
        Plate96ProcessingStage(
            code = "projection",
            titleRes = R.string.plate96_process_projection,
            descriptionRes = R.string.plate96_process_projection_desc,
            roles = setOf(CaptureRole.PROCESS_ORIGINAL_PROJECTION_OVERLAY),
            inferredComplete = false
        ),
        Plate96ProcessingStage(
            code = "crops",
            titleRes = R.string.plate96_process_crops,
            descriptionRes = R.string.plate96_process_crops_desc,
            roles = setOf(CaptureRole.PROCESS_CROP_CONTACT_SHEET),
            inferredComplete = false
        ),
        Plate96ProcessingStage(
            code = "quantitation",
            titleRes = R.string.plate96_process_quantitation,
            descriptionRes = R.string.plate96_process_quantitation_desc,
            roles = setOf(CaptureRole.PROCESS_SIGNAL_HEATMAP),
            inferredComplete = snapshot.wells.any { it.site.measurements.isNotEmpty() }
        )
    )
}

@Composable
private fun orientationSummary(snapshot: Plate96ResultSnapshot): String {
    val orientation = snapshot.orientation
    val sourceLayout = if (orientation.sourceRows != null && orientation.sourceColumns != null) {
        "${orientation.sourceRows}×${orientation.sourceColumns}"
    } else {
        stringResource(R.string.plate96_process_unknown)
    }
    val turns = orientation.quarterTurnsClockwise?.let { it * 90 }
        ?.let { stringResource(R.string.plate96_process_rotation_degrees, it) }
        ?: stringResource(R.string.plate96_process_unknown)
    return stringResource(R.string.plate96_process_orientation_summary, sourceLayout, turns)
}

private data class Plate96ProcessingStage(
    val code: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val roles: Set<CaptureRole>,
    val inferredComplete: Boolean
)

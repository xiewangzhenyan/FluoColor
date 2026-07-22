package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.detection.grid.GridFrameQcCode
import com.muc.fluocolorquant.domain.detection.grid.GridFrameQcIssue
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridQcSeverity
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot

const val ARRAY_QC_PANEL_TAG: String = "array_qc_panel"
const val ARRAY_QC_IMPUTED_TAG_PREFIX: String = "array_qc_imputed_"
const val ARRAY_QC_LOW_SIGNAL_TAG_PREFIX: String = "array_qc_low_signal_"
const val ARRAY_QC_ADVICE_TAG_PREFIX: String = "array_qc_advice_"

/** 位点级 QC 的稳定 UI 原因码，页面文案全部由 strings.xml 映射。 */
enum class ArraySiteQcCode {
    QUALITY_FAILURE,
    LOW_SIGNAL,
    MODEL_IMPUTED,
    UNADJUSTED,
    LOW_LOCAL_EVIDENCE,
    EXTRAPOLATED_POSITION,
    BELOW_RELIABLE_RANGE,
    ABOVE_RELIABLE_RANGE,
    SATURATED,
    UNDER_EXPOSED,
    ROI_OUT_OF_BOUNDS,
    NON_UNIFORM,
    BACKGROUND_ANOMALY,
    HOT_PIXEL,
    SPECULAR_HIGHLIGHT
}

data class ArraySiteQcEntry(
    val site: ArrayPhysicalSiteResult,
    val codes: List<ArraySiteQcCode>,
    val severity: GridQcSeverity
)

/** 把逐位点稳定字段聚合为可展示条目，低信号单独保持 INFO 级别。 */
fun buildArraySiteQcEntries(snapshot: ArrayResultSnapshot): List<ArraySiteQcEntry> {
    return snapshot.sites.mapNotNull { site ->
        val codes = buildSet {
            when (site.geometry.source) {
                GridPointSource.CANDIDATE_REFINED -> Unit
                GridPointSource.MODEL_IMPUTED -> add(ArraySiteQcCode.MODEL_IMPUTED)
                GridPointSource.UNADJUSTED -> add(ArraySiteQcCode.UNADJUSTED)
            }
            site.geometry.flags.forEach { flag ->
                when (flag.name) {
                    "LOW_LOCAL_EVIDENCE" -> add(ArraySiteQcCode.LOW_LOCAL_EVIDENCE)
                    "EXTRAPOLATED_POSITION" -> add(ArraySiteQcCode.EXTRAPOLATED_POSITION)
                    "IMPUTED_POSITION" -> add(ArraySiteQcCode.MODEL_IMPUTED)
                }
            }
            site.measurements.forEach { measurement ->
                if (!measurement.qualityReliable) add(ArraySiteQcCode.QUALITY_FAILURE)
                if (!measurement.signalDetectable) add(ArraySiteQcCode.LOW_SIGNAL)
                when (measurement.reliableRangeStatus?.uppercase()) {
                    "BELOW_RANGE" -> add(ArraySiteQcCode.BELOW_RELIABLE_RANGE)
                    "ABOVE_RANGE" -> add(ArraySiteQcCode.ABOVE_RELIABLE_RANGE)
                }
                measurement.qc.photometryFlags.forEach { flag ->
                    when (flag.uppercase()) {
                        "LOW_SNR", "LOW_SNR_SIGNAL", "LOW-SNR", "LOW_SNR_FLAG" -> {
                            add(ArraySiteQcCode.LOW_SIGNAL)
                        }
                        "SATURATED" -> add(ArraySiteQcCode.SATURATED)
                        "UNDER_EXPOSED" -> add(ArraySiteQcCode.UNDER_EXPOSED)
                        "ROI_OUT_OF_BOUNDS" -> add(ArraySiteQcCode.ROI_OUT_OF_BOUNDS)
                        "NON_UNIFORM" -> add(ArraySiteQcCode.NON_UNIFORM)
                        "BACKGROUND_ANOMALY" -> add(ArraySiteQcCode.BACKGROUND_ANOMALY)
                        "HOT_PIXEL" -> add(ArraySiteQcCode.HOT_PIXEL)
                        "SPECULAR_HIGHLIGHT" -> add(ArraySiteQcCode.SPECULAR_HIGHLIGHT)
                    }
                }
                measurement.qc.geometryFlags.forEach { flag ->
                    when (flag.uppercase()) {
                        "IMPUTED_POSITION" -> add(ArraySiteQcCode.MODEL_IMPUTED)
                        "LOW_LOCAL_EVIDENCE" -> add(ArraySiteQcCode.LOW_LOCAL_EVIDENCE)
                        "EXTRAPOLATED_POSITION" -> add(ArraySiteQcCode.EXTRAPOLATED_POSITION)
                    }
                }
            }
        }.toList()
        if (codes.isEmpty()) return@mapNotNull null
        ArraySiteQcEntry(
            site = site,
            codes = codes.sortedByDescending(::siteQcSeverityRank),
            severity = codes.maxByOrNull(::siteQcSeverityRank)?.let(::siteQcSeverity)
                ?: GridQcSeverity.INFO
        )
    }.sortedWith(
        compareByDescending<ArraySiteQcEntry> { severityRank(it.severity) }
            .thenBy { it.site.siteIndex }
    )
}

/** 帧级、几何级和位点级 QC 的完整页面。 */
@Composable
fun ArrayQcPanel(
    snapshot: ArrayResultSnapshot,
    onSiteClick: (ArrayPhysicalSiteResult) -> Unit
) {
    val siteEntries = remember(snapshot) { buildArraySiteQcEntries(snapshot) }
    val frameIssues = snapshot.frame.qcIssues.sortedByDescending { severityRank(it.severity) }
    val hasFrameFailure = frameIssues.any { it.severity == GridQcSeverity.FAILURE }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(ARRAY_QC_PANEL_TAG),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (hasFrameFailure) {
            item { FrameFailureBanner() }
        }
        item { GeometrySummaryCard(snapshot) }
        item {
            SectionHeading(
                title = stringResource(R.string.array_qc_frame_issues_title),
                count = frameIssues.size
            )
        }
        if (frameIssues.isEmpty()) {
            item { EmptyQcCard(stringResource(R.string.array_qc_no_frame_issues)) }
        } else {
            items(frameIssues, key = { "${it.code.name}-${it.severity.name}" }) { issue ->
                FrameIssueCard(issue)
            }
        }
        item {
            SectionHeading(
                title = stringResource(R.string.array_qc_site_issues_title),
                count = siteEntries.size
            )
        }
        if (siteEntries.isEmpty()) {
            item { EmptyQcCard(stringResource(R.string.array_qc_no_site_issues)) }
        } else {
            items(siteEntries, key = { it.site.siteIndex }) { entry ->
                SiteQcCard(entry, onSiteClick)
            }
        }
        item {
            Text(
                text = stringResource(R.string.array_qc_interpretation_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}

@Composable
private fun FrameFailureBanner() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = stringResource(R.string.array_qc_frame_failure_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.array_qc_frame_failure_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun GeometrySummaryCard(snapshot: ArrayResultSnapshot) {
    val geometry = snapshot.frame.geometry
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.array_result_geometry_qc),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = stringResource(
                    R.string.array_result_geometry_qc_body,
                    geometry.observedRatio * 100.0,
                    geometry.meanConfidence * 100.0,
                    geometry.geometryRmsePx ?: 0.0
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = stringResource(
                    R.string.array_qc_geometry_counts,
                    geometry.inlierCount,
                    geometry.outlierCount,
                    geometry.candidateSupportRatio?.times(100.0) ?: 0.0
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
            )
        }
    }
}

@Composable
private fun FrameIssueCard(issue: GridFrameQcIssue) {
    val colors = severityColors(issue.severity)
    var expanded by rememberSaveable(issue.code.name, issue.severity.name) {
        mutableStateOf(false)
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "frameQcArrow"
    )
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = severityIcon(issue.severity),
                    contentDescription = null,
                    tint = colors.accent
                )
                Text(
                    text = frameIssueTitle(issue.code),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                SeverityBadge(issue.severity)
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.array_qc_collapse_advice
                        else R.string.array_qc_expand_advice
                    ),
                    modifier = Modifier.rotate(arrowRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (issue.measuredValue != null || issue.threshold != null) {
                Text(
                    text = stringResource(
                        R.string.array_qc_measure_threshold,
                        issue.measuredValue?.let(::formatArrayHeatmapValue)
                            ?: stringResource(R.string.array_heatmap_no_value_symbol),
                        issue.threshold?.let(::formatArrayHeatmapValue)
                            ?: stringResource(R.string.array_heatmap_no_value_symbol)
                    ),
                    modifier = Modifier.padding(start = 36.dp, top = 7.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = frameIssueAdvice(issue.code),
                    modifier = Modifier.padding(start = 36.dp, top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SiteQcCard(
    entry: ArraySiteQcEntry,
    onSiteClick: (ArrayPhysicalSiteResult) -> Unit
) {
    val colors = severityColors(entry.severity)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container)
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = entry.site.siteKey,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = entry.site.sampleSlot ?: entry.site.defaultSampleSlot
                            ?: stringResource(R.string.array_site_value_not_recorded),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SeverityBadge(entry.severity)
            }
            entry.codes.forEach { code ->
                SiteQcIssueRow(entry.site.siteIndex, code)
            }
            Text(
                text = stringResource(R.string.array_qc_open_site_detail),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSiteClick(entry.site) }
                    .padding(top = 4.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.accent,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SiteQcIssueRow(siteIndex: Int, code: ArraySiteQcCode) {
    val severity = siteQcSeverity(code)
    var expanded by rememberSaveable(siteIndex, code.name) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "siteQcArrow"
    )
    val tag = when (code) {
        ArraySiteQcCode.MODEL_IMPUTED -> "$ARRAY_QC_IMPUTED_TAG_PREFIX$siteIndex"
        ArraySiteQcCode.LOW_SIGNAL -> "$ARRAY_QC_LOW_SIGNAL_TAG_PREFIX$siteIndex"
        else -> "array_qc_issue_${siteIndex}_${code.name.lowercase()}"
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
                .clickable { expanded = !expanded }
                .padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(severityColors(severity).accent, RoundedCornerShape(50))
            )
            Text(
                text = siteQcTitle(code),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.array_qc_collapse_advice
                    else R.string.array_qc_expand_advice
                ),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(arrowRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                text = siteQcAdvice(code),
                modifier = Modifier
                    .testTag("$ARRAY_QC_ADVICE_TAG_PREFIX${siteIndex}_${code.name.lowercase()}")
                    .padding(start = 15.dp, top = 3.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = count.toString(),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun EmptyQcCard(text: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SeverityBadge(severity: GridQcSeverity) {
    val colors = severityColors(severity)
    Surface(color = colors.accent.copy(alpha = 0.14f), shape = RoundedCornerShape(9.dp)) {
        Text(
            text = severityLabel(severity),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.accent,
            fontWeight = FontWeight.Bold
        )
    }
}

private data class QcColors(val container: Color, val accent: Color)

@Composable
private fun severityColors(severity: GridQcSeverity): QcColors {
    return when (severity) {
        GridQcSeverity.FAILURE -> QcColors(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
            MaterialTheme.colorScheme.error
        )
        GridQcSeverity.WARNING -> QcColors(Color(0xFFFFF3D6), Color(0xFF9A6700))
        GridQcSeverity.INFO -> QcColors(Color(0xFFE4F3FA), Color(0xFF0277A5))
    }
}

private fun severityIcon(severity: GridQcSeverity): ImageVector = when (severity) {
    GridQcSeverity.FAILURE -> Icons.Outlined.ErrorOutline
    GridQcSeverity.WARNING -> Icons.Outlined.WarningAmber
    GridQcSeverity.INFO -> Icons.Outlined.Info
}

@Composable
private fun severityLabel(severity: GridQcSeverity): String = when (severity) {
    GridQcSeverity.FAILURE -> stringResource(R.string.array_qc_severity_failure)
    GridQcSeverity.WARNING -> stringResource(R.string.array_qc_severity_warning)
    GridQcSeverity.INFO -> stringResource(R.string.array_qc_severity_info)
}

@Composable
private fun frameIssueTitle(code: GridFrameQcCode): String = when (code) {
    GridFrameQcCode.CHIP_REGION_FALLBACK -> stringResource(R.string.array_qc_frame_chip_fallback_title)
    GridFrameQcCode.GRID_SUPPORT_LOW -> stringResource(R.string.array_qc_frame_grid_support_title)
    GridFrameQcCode.HIGH_IMPUTED_RATIO -> stringResource(R.string.array_qc_frame_imputed_ratio_title)
    GridFrameQcCode.GEOMETRY_RMSE_HIGH -> stringResource(R.string.array_qc_frame_rmse_title)
    GridFrameQcCode.OVER_EXPOSED -> stringResource(R.string.array_qc_frame_over_exposed_title)
    GridFrameQcCode.UNDER_EXPOSED -> stringResource(R.string.array_qc_frame_under_exposed_title)
    GridFrameQcCode.BLURRED -> stringResource(R.string.array_qc_frame_blurred_title)
    GridFrameQcCode.ILLUMINATION_NON_UNIFORM -> stringResource(
        R.string.array_qc_frame_illumination_title
    )
    GridFrameQcCode.PERSPECTIVE_EXCESSIVE -> stringResource(R.string.array_qc_frame_perspective_title)
}

@Composable
private fun frameIssueAdvice(code: GridFrameQcCode): String = when (code) {
    GridFrameQcCode.CHIP_REGION_FALLBACK -> stringResource(R.string.array_qc_frame_chip_fallback_advice)
    GridFrameQcCode.GRID_SUPPORT_LOW -> stringResource(R.string.array_qc_frame_grid_support_advice)
    GridFrameQcCode.HIGH_IMPUTED_RATIO -> stringResource(R.string.array_qc_frame_imputed_ratio_advice)
    GridFrameQcCode.GEOMETRY_RMSE_HIGH -> stringResource(R.string.array_qc_frame_rmse_advice)
    GridFrameQcCode.OVER_EXPOSED -> stringResource(R.string.array_qc_frame_over_exposed_advice)
    GridFrameQcCode.UNDER_EXPOSED -> stringResource(R.string.array_qc_frame_under_exposed_advice)
    GridFrameQcCode.BLURRED -> stringResource(R.string.array_qc_frame_blurred_advice)
    GridFrameQcCode.ILLUMINATION_NON_UNIFORM -> stringResource(
        R.string.array_qc_frame_illumination_advice
    )
    GridFrameQcCode.PERSPECTIVE_EXCESSIVE -> stringResource(R.string.array_qc_frame_perspective_advice)
}

@Composable
private fun siteQcTitle(code: ArraySiteQcCode): String = when (code) {
    ArraySiteQcCode.QUALITY_FAILURE -> stringResource(R.string.array_qc_site_quality_failure_title)
    ArraySiteQcCode.LOW_SIGNAL -> stringResource(R.string.array_qc_site_low_signal_title)
    ArraySiteQcCode.MODEL_IMPUTED -> stringResource(R.string.array_qc_site_model_imputed_title)
    ArraySiteQcCode.UNADJUSTED -> stringResource(R.string.array_qc_site_unadjusted_title)
    ArraySiteQcCode.LOW_LOCAL_EVIDENCE -> stringResource(R.string.array_qc_site_low_evidence_title)
    ArraySiteQcCode.EXTRAPOLATED_POSITION -> stringResource(R.string.array_qc_site_extrapolated_title)
    ArraySiteQcCode.BELOW_RELIABLE_RANGE -> stringResource(R.string.array_qc_site_below_range_title)
    ArraySiteQcCode.ABOVE_RELIABLE_RANGE -> stringResource(R.string.array_qc_site_above_range_title)
    ArraySiteQcCode.SATURATED -> stringResource(R.string.array_qc_site_saturated_title)
    ArraySiteQcCode.UNDER_EXPOSED -> stringResource(R.string.array_qc_site_under_exposed_title)
    ArraySiteQcCode.ROI_OUT_OF_BOUNDS -> stringResource(R.string.array_qc_site_roi_bounds_title)
    ArraySiteQcCode.NON_UNIFORM -> stringResource(R.string.array_qc_site_non_uniform_title)
    ArraySiteQcCode.BACKGROUND_ANOMALY -> stringResource(R.string.array_qc_site_background_title)
    ArraySiteQcCode.HOT_PIXEL -> stringResource(R.string.array_qc_site_hot_pixel_title)
    ArraySiteQcCode.SPECULAR_HIGHLIGHT -> stringResource(R.string.array_qc_site_specular_title)
}

@Composable
private fun siteQcAdvice(code: ArraySiteQcCode): String = when (code) {
    ArraySiteQcCode.QUALITY_FAILURE -> stringResource(R.string.array_qc_site_quality_failure_advice)
    ArraySiteQcCode.LOW_SIGNAL -> stringResource(R.string.array_qc_site_low_signal_advice)
    ArraySiteQcCode.MODEL_IMPUTED -> stringResource(R.string.array_qc_site_model_imputed_advice)
    ArraySiteQcCode.UNADJUSTED -> stringResource(R.string.array_qc_site_unadjusted_advice)
    ArraySiteQcCode.LOW_LOCAL_EVIDENCE -> stringResource(R.string.array_qc_site_low_evidence_advice)
    ArraySiteQcCode.EXTRAPOLATED_POSITION -> stringResource(R.string.array_qc_site_extrapolated_advice)
    ArraySiteQcCode.BELOW_RELIABLE_RANGE -> stringResource(R.string.array_qc_site_below_range_advice)
    ArraySiteQcCode.ABOVE_RELIABLE_RANGE -> stringResource(R.string.array_qc_site_above_range_advice)
    ArraySiteQcCode.SATURATED -> stringResource(R.string.array_qc_site_saturated_advice)
    ArraySiteQcCode.UNDER_EXPOSED -> stringResource(R.string.array_qc_site_under_exposed_advice)
    ArraySiteQcCode.ROI_OUT_OF_BOUNDS -> stringResource(R.string.array_qc_site_roi_bounds_advice)
    ArraySiteQcCode.NON_UNIFORM -> stringResource(R.string.array_qc_site_non_uniform_advice)
    ArraySiteQcCode.BACKGROUND_ANOMALY -> stringResource(R.string.array_qc_site_background_advice)
    ArraySiteQcCode.HOT_PIXEL -> stringResource(R.string.array_qc_site_hot_pixel_advice)
    ArraySiteQcCode.SPECULAR_HIGHLIGHT -> stringResource(R.string.array_qc_site_specular_advice)
}

private fun siteQcSeverity(code: ArraySiteQcCode): GridQcSeverity = when (code) {
    ArraySiteQcCode.LOW_SIGNAL -> GridQcSeverity.INFO
    ArraySiteQcCode.MODEL_IMPUTED,
    ArraySiteQcCode.UNADJUSTED,
    ArraySiteQcCode.LOW_LOCAL_EVIDENCE,
    ArraySiteQcCode.EXTRAPOLATED_POSITION,
    ArraySiteQcCode.BELOW_RELIABLE_RANGE,
    ArraySiteQcCode.ABOVE_RELIABLE_RANGE -> GridQcSeverity.WARNING
    else -> GridQcSeverity.FAILURE
}

private fun siteQcSeverityRank(code: ArraySiteQcCode): Int = severityRank(siteQcSeverity(code))

private fun severityRank(severity: GridQcSeverity): Int = when (severity) {
    GridQcSeverity.FAILURE -> 3
    GridQcSeverity.WARNING -> 2
    GridQcSeverity.INFO -> 1
}

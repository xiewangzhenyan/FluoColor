package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.photometry.BaseSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.domain.detection.photometry.RgbPhotometry
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail
import com.muc.fluocolorquant.domain.result.ArrayMeasurementQualityLevel
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.domain.result.resolveQualityLevel
import com.muc.fluocolorquant.ui.components.analysisFeatureLabel

const val ARRAY_SITE_DETAIL_SHEET_TAG: String = "array_site_detail_sheet"
const val ARRAY_SITE_DETAIL_LIST_TAG: String = "array_site_detail_list"
const val ARRAY_SITE_DETAIL_COLORIMETRIC_TAG: String = "array_site_detail_colorimetric"
const val ARRAY_SITE_DETAIL_FLUORESCENCE_TAG: String = "array_site_detail_fluorescence"

/** 热力图、原图叠加和 QC 列表共享的位点选择，不把当前分析物选择丢失。 */
data class ArraySiteSelection(
    val siteIndex: Int,
    val analyteId: String? = null
)

/**
 * 位点详情从冻结快照直接读取，不触发重新分析。
 *
 * 比色和荧光使用不同的专用段落；LegacyUnparsed 只提示原始证据仍在，不猜测字段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArraySiteDetailSheet(
    snapshot: ArrayResultSnapshot,
    selection: ArraySiteSelection,
    onDismiss: () -> Unit
) {
    val site = snapshot.sites.firstOrNull { it.siteIndex == selection.siteIndex } ?: return
    val measurement = resolveSelectedMeasurement(site, selection.analyteId)
    val analyte = resolveSelectedAnalyte(snapshot, site, measurement, selection.analyteId)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ARRAY_SITE_DETAIL_SHEET_TAG),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag(ARRAY_SITE_DETAIL_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { SiteDetailHeader(site, measurement) }
            item { CommonSiteInformation(site, measurement, analyte) }
            when (val detail = measurement?.detail) {
                is ArrayMeasurementDetail.Colorimetric -> item {
                    ColorimetricDetailSection(snapshot, detail)
                }
                is ArrayMeasurementDetail.Fluorescence -> item {
                    FluorescenceDetailSection(detail)
                }
                is ArrayMeasurementDetail.ColorimetricReference -> item {
                    ReferenceDetailSection(detail)
                }
                is ArrayMeasurementDetail.LegacyUnparsed -> item {
                    LegacyEvidenceSection()
                }
                null -> item { NoMeasurementSection() }
            }
            item {
                // 为系统手势区保留安全间距，避免最后一行被底部导航条遮挡。
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier.height(22.dp)
                )
            }
        }
    }
}

@Composable
private fun SiteDetailHeader(
    site: ArrayPhysicalSiteResult,
    measurement: ArraySiteMeasurementResult?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stringResource(R.string.array_site_detail_title, site.siteKey),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(
                    R.string.array_site_detail_coordinate,
                    site.rowIndex + 1,
                    site.columnIndex + 1,
                    site.siteIndex
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val qualityLevel = measurement?.resolveQualityLevel(site)
        val statusColor = when (qualityLevel) {
            null -> MaterialTheme.colorScheme.surfaceVariant
            ArrayMeasurementQualityLevel.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer
            ArrayMeasurementQualityLevel.REVIEW -> Color(0xFFFFE8BE)
            ArrayMeasurementQualityLevel.VALID -> MaterialTheme.colorScheme.primaryContainer
        }
        Surface(color = statusColor, shape = RoundedCornerShape(12.dp)) {
            Text(
                text = when (qualityLevel) {
                    null -> stringResource(R.string.array_heatmap_missing)
                    ArrayMeasurementQualityLevel.UNAVAILABLE -> stringResource(R.string.array_heatmap_failure)
                    ArrayMeasurementQualityLevel.REVIEW -> stringResource(R.string.array_heatmap_warning)
                    ArrayMeasurementQualityLevel.VALID -> stringResource(R.string.array_heatmap_reliable)
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CommonSiteInformation(
    site: ArrayPhysicalSiteResult,
    measurement: ArraySiteMeasurementResult?,
    analyte: ArrayAnalyteResult?
) {
    val geometryFlagsText = if (site.geometry.flags.isEmpty()) {
        stringResource(R.string.array_site_none)
    } else {
        val labels = mutableListOf<String>()
        for (flag in site.geometry.flags) {
            labels += arrayGeometryFlagLabel(flag.name)
        }
        labels.joinToString()
    }
    DetailSectionCard(title = stringResource(R.string.array_site_common_section)) {
        DetailRow(
            label = stringResource(R.string.array_site_sample_slot),
            value = site.sampleSlot ?: site.defaultSampleSlot
                ?: stringResource(R.string.array_site_value_not_recorded)
        )
        DetailRow(
            label = stringResource(R.string.array_site_analyte),
            value = analyte?.name ?: stringResource(R.string.array_site_global_reference)
        )
        DetailRow(
            label = stringResource(R.string.array_site_role),
            value = arraySiteRoleLabel(site.roleCode)
        )
        DetailRow(
            label = stringResource(R.string.array_site_result_value),
            value = measurementResultText(measurement, analyte)
        )
        DetailRow(
            label = stringResource(R.string.array_site_reliable_range_status),
            value = reliableRangeLabel(measurement?.reliableRangeStatus)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DetailRow(
            label = stringResource(R.string.array_site_geometry_source),
            value = geometrySourceLabel(site.geometry.source)
        )
        DetailRow(
            label = stringResource(R.string.array_site_geometry_confidence),
            value = stringResource(
                R.string.array_site_percent_value,
                site.geometry.confidence * 100.0
            )
        )
        DetailRow(
            label = stringResource(R.string.array_site_geometry_flags),
            value = geometryFlagsText
        )
        if (measurement != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailRow(
                label = stringResource(R.string.array_site_model),
                value = analyte?.let {
                    stringResource(
                        R.string.array_site_model_value,
                        it.modelName,
                        it.modelVersion
                    )
                } ?: stringResource(R.string.array_site_value_not_recorded)
            )
            DetailRow(
                label = stringResource(R.string.array_site_processor),
                value = stringResource(
                    R.string.array_site_processor_value,
                    measurement.processorName,
                    measurement.processorVersion
                )
            )
        }
    }
}

@Composable
private fun ColorimetricDetailSection(
    snapshot: ArrayResultSnapshot,
    detail: ArrayMeasurementDetail.Colorimetric
) {
    val site = detail.site
    DetailSectionCard(
        title = stringResource(R.string.array_site_colorimetric_section),
        modifier = Modifier.testTag(ARRAY_SITE_DETAIL_COLORIMETRIC_TAG)
    ) {
        DetailRow(
            stringResource(R.string.array_site_primary_feature),
            primaryFeatureLabel(site.primaryFeature.code)
        )
        site.deltaE2000?.let { value ->
            DetailRow(
                stringResource(R.string.array_site_delta_e),
                formatArrayHeatmapValue(value)
            )
        }
        site.opticalDensity?.let { value ->
            DetailRow(
                stringResource(R.string.array_site_optical_density),
                formatArrayHeatmapValue(value)
            )
        }
        DetailRow(
            stringResource(R.string.array_site_white_balanced_rgb),
            rgbText(site.whiteBalancedRgb)
        )
        DetailRow(
            stringResource(R.string.array_site_lab),
            stringResource(
                R.string.array_site_lab_value,
                site.lab.lightness,
                site.lab.a,
                site.lab.b
            )
        )
        if (detail.calibrationContext.referenceIndices.isNotEmpty()) {
            DetailRow(
                stringResource(R.string.array_site_reference_sites),
                detail.calibrationContext.referenceIndices.joinToString { index ->
                    snapshot.sites.getOrNull(index)?.siteKey ?: (index + 1).toString()
                }
            )
        }
        DetailRow(
            stringResource(R.string.array_site_white_balance_gains),
            rgbText(detail.calibrationContext.whiteBalanceGains)
        )
        detail.calibrationContext.referenceRgb?.let { referenceRgb ->
            DetailRow(
                stringResource(R.string.array_site_reference_rgb),
                rgbText(referenceRgb)
            )
        }
        detail.calibrationContext.referenceLab?.let { referenceLab ->
            DetailRow(
                stringResource(R.string.array_site_reference_lab),
                stringResource(
                    R.string.array_site_lab_value,
                    referenceLab.lightness,
                    referenceLab.a,
                    referenceLab.b
                )
            )
        }
        BasePhotometryRows(site.base)
        DetailRow(
            stringResource(R.string.array_site_specular_ratio),
            stringResource(R.string.array_site_percent_value, site.base.saturationRatio * 100.0)
        )
    }
}

@Composable
private fun FluorescenceDetailSection(detail: ArrayMeasurementDetail.Fluorescence) {
    val site = detail.site
    val base = site.base
    DetailSectionCard(
        title = stringResource(R.string.array_site_fluorescence_section),
        modifier = Modifier.testTag(ARRAY_SITE_DETAIL_FLUORESCENCE_TAG)
    ) {
        DetailRow(
            stringResource(R.string.array_site_fluorescence_channel),
            fluorescenceChannelLabel(site.channel)
        )
        DetailRow(
            stringResource(R.string.array_site_primary_feature),
            primaryFeatureLabel(site.primaryFeature.code)
        )
        DetailRow(
            stringResource(R.string.array_site_raw_intensity),
            formatArrayHeatmapValue(channelValue(base.roiMedianRgb, base.roiMedianGray, site.channel))
        )
        DetailRow(
            stringResource(R.string.array_site_corrected_intensity),
            formatArrayHeatmapValue(
                channelValue(base.correctedMedianRgb, base.correctedMedianGray, site.channel)
            )
        )
        DetailRow(
            stringResource(R.string.array_site_background),
            formatArrayHeatmapValue(
                channelValue(base.backgroundMedianRgb, base.backgroundMedianGray, site.channel)
            )
        )
        DetailRow(
            stringResource(R.string.array_site_net_intensity),
            formatArrayHeatmapValue(site.netIntensity)
        )
        DetailRow(
            stringResource(R.string.array_site_integrated_intensity),
            formatArrayHeatmapValue(site.integratedIntensity)
        )
        DetailRow(
            stringResource(R.string.array_site_snr),
            formatArrayHeatmapValue(site.signalToNoiseRatio)
        )
        DetailRow(
            stringResource(R.string.array_site_saturation_ratio),
            stringResource(R.string.array_site_percent_value, base.saturationRatio * 100.0)
        )
        DetailRow(
            stringResource(R.string.array_site_hot_pixel_ratio),
            stringResource(R.string.array_site_percent_value, base.hotPixelRatio * 100.0)
        )
        DetailRow(
            stringResource(R.string.array_site_detectability),
            if (site.qc.signalDetectable) {
                stringResource(R.string.array_site_detectable)
            } else {
                stringResource(R.string.array_site_below_detection)
            }
        )
    }
}

@Composable
private fun ReferenceDetailSection(detail: ArrayMeasurementDetail.ColorimetricReference) {
    DetailSectionCard(title = stringResource(R.string.array_site_reference_section)) {
        DetailRow(
            stringResource(R.string.array_site_role),
            arraySiteRoleLabel(detail.roleCode)
        )
        BasePhotometryRows(detail.site)
        Text(
            text = stringResource(R.string.array_site_reference_evidence_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LegacyEvidenceSection() {
    DetailSectionCard(title = stringResource(R.string.array_site_legacy_section)) {
        Text(
            text = stringResource(R.string.array_site_legacy_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoMeasurementSection() {
    DetailSectionCard(title = stringResource(R.string.array_site_no_measurement_section)) {
        Text(
            text = stringResource(R.string.array_site_no_measurement_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BasePhotometryRows(base: BaseSitePhotometry) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    DetailRow(
        stringResource(R.string.array_site_raw_signal),
        formatArrayHeatmapValue(base.signalGray)
    )
    DetailRow(
        stringResource(R.string.array_site_corrected_signal),
        formatArrayHeatmapValue(base.correctedSignalGray)
    )
    DetailRow(
        stringResource(R.string.array_site_background),
        formatArrayHeatmapValue(base.backgroundMedianGray)
    )
    DetailRow(
        stringResource(R.string.array_site_snr),
        formatArrayHeatmapValue(base.signalToNoiseRatio)
    )
}

@Composable
private fun DetailSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.43f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.57f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun resolveSelectedMeasurement(
    site: ArrayPhysicalSiteResult,
    analyteId: String?
): ArraySiteMeasurementResult? {
    return analyteId?.let { selected ->
        site.measurements.firstOrNull { it.analyteId == selected }
    } ?: site.analyteId?.let { assigned ->
        site.measurements.firstOrNull { it.analyteId == assigned }
    } ?: site.measurements.firstOrNull()
}

private fun resolveSelectedAnalyte(
    snapshot: ArrayResultSnapshot,
    site: ArrayPhysicalSiteResult,
    measurement: ArraySiteMeasurementResult?,
    selectedAnalyteId: String?
): ArrayAnalyteResult? {
    val analyteId = selectedAnalyteId ?: measurement?.analyteId ?: site.analyteId
    return snapshot.analytes.firstOrNull { it.analyteId == analyteId }
}

@Composable
private fun measurementResultText(
    measurement: ArraySiteMeasurementResult?,
    analyte: ArrayAnalyteResult?
): String {
    if (measurement == null) return stringResource(R.string.array_heatmap_no_value)
    val concentration = measurement.concentrationValue
    return if (concentration != null) {
        stringResource(
            R.string.array_site_concentration_value,
            formatArrayHeatmapValue(concentration),
            measurement.concentrationUnit.orEmpty()
        )
    } else if (
        measurement.reliableRangeStatus.equals("BELOW_PROJECT_RANGE", ignoreCase = true) &&
        analyte?.projectRangeMin?.isFinite() == true
    ) {
        stringResource(
            R.string.array_site_concentration_below_boundary,
            formatArrayHeatmapValue(requireNotNull(analyte?.projectRangeMin)),
            measurement.concentrationUnit ?: analyte?.concentrationUnit.orEmpty()
        )
    } else if (
        measurement.reliableRangeStatus.equals("ABOVE_PROJECT_RANGE", ignoreCase = true) &&
        analyte?.projectRangeMax?.isFinite() == true
    ) {
        stringResource(
            R.string.array_site_concentration_above_boundary,
            formatArrayHeatmapValue(requireNotNull(analyte?.projectRangeMax)),
            measurement.concentrationUnit ?: analyte?.concentrationUnit.orEmpty()
        )
    } else {
        stringResource(
            R.string.array_site_signal_value,
            primaryFeatureLabel(measurement.primaryFeatureName),
            measurement.primaryFeatureValue?.let(::formatArrayHeatmapValue)
                ?: stringResource(R.string.array_heatmap_no_value_symbol)
        )
    }
}

@Composable
private fun reliableRangeLabel(status: String?): String {
    return when (status?.uppercase()) {
        "WITHIN_RANGE" -> stringResource(R.string.array_site_range_within)
        "BELOW_RANGE" -> stringResource(R.string.array_site_range_below)
        "ABOVE_RANGE" -> stringResource(R.string.array_site_range_above)
        "BELOW_PROJECT_RANGE" -> stringResource(R.string.array_site_range_below_project)
        "ABOVE_PROJECT_RANGE" -> stringResource(R.string.array_site_range_above_project)
        else -> stringResource(R.string.array_site_range_not_available)
    }
}

@Composable
internal fun arraySiteRoleLabel(code: String?): String {
    return when (TemplateSiteRole.fromCode(code)) {
        TemplateSiteRole.SAMPLE -> stringResource(R.string.template_array_role_sample)
        TemplateSiteRole.STANDARD -> stringResource(R.string.template_array_role_standard)
        TemplateSiteRole.BLANK -> stringResource(R.string.template_array_role_blank)
        TemplateSiteRole.NEGATIVE_CONTROL -> stringResource(R.string.template_array_role_negative_control)
        TemplateSiteRole.POSITIVE_CONTROL -> stringResource(R.string.template_array_role_positive_control)
        TemplateSiteRole.REFERENCE -> stringResource(R.string.template_array_role_reference)
        TemplateSiteRole.DISABLED -> stringResource(R.string.template_array_role_disabled)
        null -> stringResource(R.string.array_site_value_not_recorded)
    }
}

@Composable
internal fun geometrySourceLabel(source: GridPointSource): String {
    return when (source) {
        GridPointSource.CANDIDATE_REFINED -> stringResource(R.string.array_geometry_candidate_refined)
        GridPointSource.MODEL_IMPUTED -> stringResource(R.string.array_geometry_model_imputed)
        GridPointSource.UNADJUSTED -> stringResource(R.string.array_geometry_unadjusted)
    }
}

@Composable
internal fun arrayGeometryFlagLabel(code: String): String {
    return when (code.uppercase()) {
        "IMPUTED_POSITION" -> stringResource(R.string.array_geometry_flag_imputed)
        "LOW_LOCAL_EVIDENCE" -> stringResource(R.string.array_geometry_flag_low_evidence)
        "EXTRAPOLATED_POSITION" -> stringResource(R.string.array_geometry_flag_extrapolated)
        else -> stringResource(R.string.array_qc_unknown_issue)
    }
}

@Composable
internal fun primaryFeatureLabel(code: String): String {
    val feature = AnalysisPrimaryFeature.fromCode(code) ?: return code
    return analysisFeatureLabel(feature)
}

@Composable
private fun fluorescenceChannelLabel(channel: FluorescenceChannel): String {
    return when (channel) {
        FluorescenceChannel.RED -> stringResource(R.string.template_wizard_fluorescence_channel_red)
        FluorescenceChannel.GREEN -> stringResource(R.string.template_wizard_fluorescence_channel_green)
        FluorescenceChannel.BLUE -> stringResource(R.string.template_wizard_fluorescence_channel_blue)
        FluorescenceChannel.GRAY -> stringResource(R.string.template_wizard_fluorescence_channel_gray)
    }
}

@Composable
private fun rgbText(rgb: RgbPhotometry): String {
    return stringResource(R.string.array_site_rgb_value, rgb.red, rgb.green, rgb.blue)
}

private fun channelValue(
    rgb: RgbPhotometry,
    gray: Double,
    channel: FluorescenceChannel
): Double {
    return when (channel) {
        FluorescenceChannel.RED -> rgb.red
        FluorescenceChannel.GREEN -> rgb.green
        FluorescenceChannel.BLUE -> rgb.blue
        FluorescenceChannel.GRAY -> gray
    }
}

package com.muc.fluocolorquant.ui.screens.result.array

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.export.ArrayExportEvidenceReader
import com.muc.fluocolorquant.domain.result.export.ArrayResultExportFormat
import com.muc.fluocolorquant.domain.result.export.ArrayResultExporter
import com.muc.fluocolorquant.domain.result.export.ArrayResultPngExporter
import com.muc.fluocolorquant.domain.result.export.ArrayResultPngLabels
import com.muc.fluocolorquant.domain.result.export.ArrayResultPdfExporter
import com.muc.fluocolorquant.domain.result.export.ArrayResultPdfLabels
import com.muc.fluocolorquant.domain.result.export.ArrayResultValidationPdfLabels
import com.muc.fluocolorquant.domain.result.validation.ResultValidationSnapshot
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import java.io.File
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ARRAY_RESULT_EXPORT_SHEET_TAG: String = "array_result_export_sheet"
const val ARRAY_RESULT_EXPORT_CSV_TAG: String = "array_result_export_csv"
const val ARRAY_RESULT_EXPORT_PNG_TAG: String = "array_result_export_png"
const val ARRAY_RESULT_EXPORT_PDF_TAG: String = "array_result_export_pdf"
const val ARRAY_RESULT_EXPORT_ZIP_TAG: String = "array_result_export_zip"
const val ARRAY_RESULT_EXPORT_PROGRESS_TAG: String = "array_result_export_progress"

/**
 * 系统文件选择器与冻结快照导出的 Compose 协调层。
 *
 * 用户点击格式时先固定当前 snapshot，再打开 CreateDocument；即使文件选择器返回前页面状态
 * 发生变化，也只会导出用户当时选择的运行。
 */
@Composable
fun ArrayResultExportCoordinator(
    snapshot: ArrayResultSnapshot,
    visible: Boolean,
    onDismiss: () -> Unit,
    selectedAnalyteId: String? = null,
    validations: Map<String, ResultValidationSnapshot> = emptyMap(),
    pdfLabelsOverride: ArrayResultPdfLabels? = null
) {
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    val scope = rememberCoroutineScope()
    val defaultPdfLabels = arrayResultPdfLabels()
    val pdfLabels = pdfLabelsOverride ?: defaultPdfLabels
    val validationPdfLabels = arrayResultValidationPdfLabels()
    val pngLabels = ArrayResultPngLabels(
        concentrationTitleFormat = stringResource(R.string.array_png_title_format),
        signalTitleFormat = stringResource(R.string.array_png_signal_title_format),
        concentration = stringResource(R.string.array_png_concentration),
        signal = stringResource(R.string.array_png_signal),
        noValue = stringResource(R.string.array_pdf_no_value)
    )
    val exportSuccess = stringResource(R.string.array_export_success)
    val exportFailure = stringResource(R.string.array_export_failure)
    var pendingSnapshot by remember { mutableStateOf(snapshot) }
    var exporting by remember { mutableStateOf(false) }

    val writeExport: (Uri, ArrayResultExportFormat, ArrayResultSnapshot) -> Unit =
        { destination, format, frozenSnapshot ->
            scope.launch {
                exporting = true
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                            when (format) {
                                ArrayResultExportFormat.CSV -> output.write(
                                    ArrayResultExporter.createMeasurementsCsv(frozenSnapshot)
                                )
                                ArrayResultExportFormat.PNG -> output.write(
                                    ArrayResultPngExporter.createHeatmapPng(
                                        snapshot = frozenSnapshot,
                                        analyteId = selectedAnalyteId,
                                        labels = pngLabels
                                    )
                                )
                                ArrayResultExportFormat.PDF -> output.write(
                                    ArrayResultPdfExporter.createPdf(
                                        context = context,
                                        snapshot = frozenSnapshot,
                                        labels = pdfLabels,
                                        validations = validations,
                                        validationLabels = validationPdfLabels
                                    )
                                )
                                ArrayResultExportFormat.ZIP -> ArrayResultExporter.writeArchive(
                                    snapshot = frozenSnapshot,
                                    evidenceReader = ArrayExportEvidenceReader { sourcePath ->
                                        readEvidenceBytes(context, sourcePath)
                                    },
                                    output = output,
                                    supplementalFiles = buildArchiveSupplementalFiles(
                                        context = context,
                                        snapshot = frozenSnapshot,
                                        pdfLabels = pdfLabels,
                                        validationPdfLabels = validationPdfLabels,
                                        pngLabels = pngLabels,
                                        validations = validations
                                    )
                                )
                            }
                            output.flush()
                        } ?: error("OUTPUT_STREAM_UNAVAILABLE")
                    }.isSuccess
                }
                exporting = false
                toastManager.showToast(
                    message = if (success) exportSuccess else exportFailure,
                    type = if (success) ToastType.SUCCESS else ToastType.ERROR
                )
            }
        }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ArrayResultExportFormat.CSV.mimeType)
    ) { uri ->
        uri?.let { writeExport(it, ArrayResultExportFormat.CSV, pendingSnapshot) }
    }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ArrayResultExportFormat.PDF.mimeType)
    ) { uri ->
        uri?.let { writeExport(it, ArrayResultExportFormat.PDF, pendingSnapshot) }
    }
    val pngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ArrayResultExportFormat.PNG.mimeType)
    ) { uri ->
        uri?.let { writeExport(it, ArrayResultExportFormat.PNG, pendingSnapshot) }
    }
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ArrayResultExportFormat.ZIP.mimeType)
    ) { uri ->
        uri?.let { writeExport(it, ArrayResultExportFormat.ZIP, pendingSnapshot) }
    }

    if (visible) {
        ArrayResultExportSheet(
            onDismiss = onDismiss,
            onCsvExport = {
                pendingSnapshot = snapshot
                onDismiss()
                csvLauncher.launch(exportFileName(snapshot, ArrayResultExportFormat.CSV))
            },
            onPngExport = {
                pendingSnapshot = snapshot
                onDismiss()
                pngLauncher.launch(exportFileName(snapshot, ArrayResultExportFormat.PNG))
            },
            onPdfExport = {
                pendingSnapshot = snapshot
                onDismiss()
                pdfLauncher.launch(exportFileName(snapshot, ArrayResultExportFormat.PDF))
            },
            onZipExport = {
                pendingSnapshot = snapshot
                onDismiss()
                zipLauncher.launch(exportFileName(snapshot, ArrayResultExportFormat.ZIP))
            }
        )
    }
    if (exporting) {
        ArrayExportProgressOverlay()
    }
}

/** 四种格式的职责在说明中明确区分，避免用户把单张图表或CSV误认为完整证据归档。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrayResultExportSheet(
    onDismiss: () -> Unit,
    onCsvExport: () -> Unit,
    onPngExport: () -> Unit,
    onPdfExport: () -> Unit,
    onZipExport: () -> Unit
) {
    // 四种科研导出格式都属于一级动作，打开面板时直接完整展开，避免ZIP被半展开状态遮住。
    // 同时保留纵向滚动，确保更小屏幕或更大系统字体下每个格式仍然可以访问。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ARRAY_RESULT_EXPORT_SHEET_TAG),
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.array_export_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.array_export_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ExportFormatCard(
                modifier = Modifier.testTag(ARRAY_RESULT_EXPORT_CSV_TAG),
                icon = Icons.Outlined.Description,
                iconColor = Color(0xFF15803D),
                title = stringResource(R.string.array_export_csv_title),
                description = stringResource(R.string.array_export_csv_description),
                onClick = onCsvExport
            )
            ExportFormatCard(
                modifier = Modifier.testTag(ARRAY_RESULT_EXPORT_PNG_TAG),
                icon = Icons.Outlined.Image,
                iconColor = Color(0xFF0284C7),
                title = stringResource(R.string.array_export_png_title),
                description = stringResource(R.string.array_export_png_description),
                onClick = onPngExport
            )
            ExportFormatCard(
                modifier = Modifier.testTag(ARRAY_RESULT_EXPORT_PDF_TAG),
                icon = Icons.Outlined.PictureAsPdf,
                iconColor = Color(0xFFDC2626),
                title = stringResource(R.string.array_export_pdf_title),
                description = stringResource(R.string.array_export_pdf_description),
                onClick = onPdfExport
            )
            ExportFormatCard(
                modifier = Modifier.testTag(ARRAY_RESULT_EXPORT_ZIP_TAG),
                icon = Icons.Outlined.Archive,
                iconColor = Color(0xFF7C3AED),
                title = stringResource(R.string.array_export_zip_title),
                description = stringResource(R.string.array_export_zip_description),
                onClick = onZipExport
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(R.string.array_export_frozen_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}

@Composable
private fun ExportFormatCard(
    modifier: Modifier,
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = iconColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = iconColor
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ArrayExportProgressOverlay() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.testTag(ARRAY_RESULT_EXPORT_PROGRESS_TAG),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(
                    text = stringResource(R.string.array_export_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
internal fun arrayResultPdfLabels(): ArrayResultPdfLabels {
    return ArrayResultPdfLabels(
        documentTitle = stringResource(R.string.array_pdf_document_title),
        frozenEvidenceNote = stringResource(R.string.array_pdf_frozen_note),
        project = stringResource(R.string.array_pdf_project),
        runId = stringResource(R.string.array_pdf_run_id),
        runTime = stringResource(R.string.array_pdf_run_time),
        status = stringResource(R.string.array_pdf_status),
        detectionMode = stringResource(R.string.array_pdf_detection_mode),
        carrier = stringResource(R.string.array_pdf_carrier),
        layout = stringResource(R.string.array_pdf_layout),
        physicalSites = stringResource(R.string.array_pdf_physical_sites),
        measurements = stringResource(R.string.array_pdf_measurements),
        validMeasurements = stringResource(R.string.array_pdf_valid_measurements),
        reviewMeasurements = stringResource(R.string.array_pdf_review_measurements),
        unavailableMeasurements = stringResource(R.string.array_pdf_unavailable_measurements),
        overviewHeatmap = stringResource(R.string.array_pdf_overview_heatmap),
        analyteSection = stringResource(R.string.array_pdf_analyte_section),
        model = stringResource(R.string.array_pdf_model),
        primaryFeature = stringResource(R.string.array_pdf_primary_feature),
        projectRange = stringResource(R.string.array_pdf_project_range),
        calibrationRange = stringResource(R.string.array_pdf_calibration_range),
        concentrationHeatmap = stringResource(R.string.array_pdf_concentration_heatmap),
        signalHeatmap = stringResource(R.string.array_pdf_signal_heatmap),
        qualitySummary = stringResource(R.string.array_pdf_quality_summary),
        frameReviewCount = stringResource(R.string.array_pdf_frame_review_count),
        reviewMeasurementCount = stringResource(R.string.array_pdf_review_measurement_count),
        unavailableMeasurementCount = stringResource(R.string.array_pdf_unavailable_measurement_count),
        lowSignalCount = stringResource(R.string.array_pdf_low_signal_count),
        traceability = stringResource(R.string.array_pdf_traceability),
        locator = stringResource(R.string.array_pdf_locator),
        processor = stringResource(R.string.array_pdf_processor),
        frozenSnapshot = stringResource(R.string.array_pdf_frozen_snapshot),
        noValue = stringResource(R.string.array_pdf_no_value),
        pageFormat = stringResource(R.string.array_pdf_page_format)
    )
}

@Composable
private fun arrayResultValidationPdfLabels(): ArrayResultValidationPdfLabels {
    return ArrayResultValidationPdfLabels(
        title = stringResource(R.string.plate_validation_title),
        summaryFormat = stringResource(R.string.plate_validation_pdf_summary),
        regressionTitle = stringResource(R.string.plate_validation_regression_title),
        blandAltmanTitle = stringResource(R.string.plate_validation_bland_title),
        referenceAxis = stringResource(R.string.plate_validation_reference_axis),
        predictedAxis = stringResource(R.string.plate_validation_predicted_axis),
        meanAxis = stringResource(R.string.plate_validation_mean_axis),
        differenceAxis = stringResource(R.string.plate_validation_difference_axis),
        rSquared = stringResource(R.string.plate_validation_metric_r2),
        slope = stringResource(R.string.plate_validation_metric_slope),
        rmse = stringResource(R.string.plate_validation_metric_rmse),
        mae = stringResource(R.string.plate_validation_metric_mae),
        meanBias = stringResource(R.string.plate_validation_metric_bias),
        lowerLimit = stringResource(R.string.plate_validation_metric_lower),
        upperLimit = stringResource(R.string.plate_validation_metric_upper),
        withinLimits = stringResource(R.string.plate_validation_metric_within)
    )
}

private fun exportFileName(
    snapshot: ArrayResultSnapshot,
    format: ArrayResultExportFormat
): String {
    val rawBase = "${snapshot.projectName}_${snapshot.runId}"
    val safeBase = rawBase.map { character ->
        if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
    }.joinToString("").trim('_').take(96).ifBlank { snapshot.runId.take(32) }
    return "$safeBase.${format.fileExtension}"
}

/** 同时兼容 SAF content URI、file URI 和应用内部保存的普通绝对路径。 */
private fun readEvidenceBytes(context: Context, sourcePath: String): ByteArray? {
    if (sourcePath.isBlank()) return null
    val uri = Uri.parse(sourcePath)
    return when (uri.scheme?.lowercase()) {
        "content", "android.resource", "file" ->
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        null -> File(sourcePath).takeIf(File::isFile)?.readBytes()
        else -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }
}

/**
 * 为完整证据归档生成可直接阅读的PDF、逐分析物PNG和预测验证JSON。
 *
 * 所有内容都在IO线程中从冻结快照生成；文件名只使用清洗后的稳定ID，避免分析物名称中的
 * 斜杠或特殊字符改变ZIP目录结构。
 */
private fun buildArchiveSupplementalFiles(
    context: Context,
    snapshot: ArrayResultSnapshot,
    pdfLabels: ArrayResultPdfLabels,
    validationPdfLabels: ArrayResultValidationPdfLabels,
    pngLabels: ArrayResultPngLabels,
    validations: Map<String, ResultValidationSnapshot>
): Map<String, ByteArray> = buildMap {
    put(
        "report/result-summary.pdf",
        ArrayResultPdfExporter.createPdf(
            context = context,
            snapshot = snapshot,
            labels = pdfLabels,
            validations = validations,
            validationLabels = validationPdfLabels
        )
    )
    snapshot.analytes.sortedBy { analyte -> analyte.displayOrder }
        .forEachIndexed { index, analyte ->
            val safeId = exportArchiveSafePart(analyte.analyteId, index)
            put(
                "charts/${index.toString().padStart(2, '0')}_${safeId}_heatmap.png",
                ArrayResultPngExporter.createHeatmapPng(snapshot, analyte.analyteId, pngLabels)
            )
            validations[analyte.analyteId]?.let { validation ->
                val json = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                    .toJson(validation)
                    .toByteArray(Charsets.UTF_8)
                put(
                    "validation/${index.toString().padStart(2, '0')}_${safeId}.json",
                    json
                )
            }
        }
}

private fun exportArchiveSafePart(value: String, fallbackIndex: Int): String = value
    .map { character ->
        if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
    }
    .joinToString("")
    .trim('_')
    .take(64)
    .ifBlank { "analyte_$fallbackIndex" }

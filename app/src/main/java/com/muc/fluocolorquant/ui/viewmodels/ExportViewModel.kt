package com.muc.fluocolorquant.ui.viewmodels

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.export.pdf.PdfPageCanvas
import com.muc.fluocolorquant.domain.spectrum.export.SpectrumChartLabels
import com.muc.fluocolorquant.domain.spectrum.export.SpectrumChartRenderer
import com.muc.fluocolorquant.domain.spectrum.export.SpectrumCsvExporter
import com.muc.fluocolorquant.domain.spectrum.export.SpectrumPdfExporter
import com.muc.fluocolorquant.data.model.AnalyteResultDetails
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.data.model.SpectrumExportData
import com.muc.fluocolorquant.data.model.SpectrumChannelExportModel
import com.muc.fluocolorquant.data.repository.ProjectAnalyteJoinRepository
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import com.muc.fluocolorquant.utils.ResultTraceabilityUtils
import com.muc.fluocolorquant.utils.camera.CameraCaptureMetadataStore
import com.muc.fluocolorquant.utils.math.FittingEngine
import com.muc.fluocolorquant.utils.math.GridLayoutPolicy
import com.muc.fluocolorquant.utils.math.WellMappingUtils
import com.muc.fluocolorquant.utils.pdf.PdfCoverPageContent
import com.muc.fluocolorquant.utils.pdf.PdfCoverPageRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.ui.components.charts.ChartData
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ViewModel负责处理所有导出相关的功能
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectAnalyteJoinRepository: ProjectAnalyteJoinRepository
) : ViewModel() {

    // PDF页面尺寸常量 (A4 at 72 dpi)
    companion object {
        const val PDF_PAGE_WIDTH = 595
        const val PDF_PAGE_HEIGHT = 842
        const val PDF_MARGIN = 40f
        const val PDF_HEADER_HEIGHT = 80f
        const val PDF_FOOTER_HEIGHT = 40f

        // 内容区域常量
        const val PDF_CONTENT_START_Y = PDF_MARGIN + PDF_HEADER_HEIGHT
        const val PDF_CONTENT_HEIGHT = PDF_PAGE_HEIGHT - PDF_CONTENT_START_Y - PDF_MARGIN - PDF_FOOTER_HEIGHT
        const val PDF_CONTENT_WIDTH = PDF_PAGE_WIDTH - 2 * PDF_MARGIN

        // 分节间隔
        const val SECTION_SPACING = 30f
        const val SUBSECTION_SPACING = 15f
        const val TABLE_CELL_HEIGHT = 30f
    }

    /**
     * 导出状态封装类
     */
    sealed class ExportState {
        object Idle : ExportState()
        object InProgress : ExportState()
        data class Success(val message: String, val filePath: String? = null) : ExportState()
        data class Error(val message: String) : ExportState()
    }

    // 当前导出状态
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    /**
     * 导出数据封装类
     */
    data class ReportData(
        val project: Project,
        val analyteDetails: List<AnalyteResultDetails>,
        val detectionRun: DetectionRun? = null
    )

    /**
     * 使用项目真实列数生成导出标签。
     *
     * 历史 `virtualRow/virtualCol` 可能由固定 12 列算法写入，不能继续作为导出的科学
     * 坐标来源；线性 `wellIndex` 与项目兼容尺寸才是稳定依据。
     */
    private fun resolveWellLabel(wellResult: WellResult, project: Project): String {
        val dimensions = GridLayoutPolicy.resolveProject(project)
        return WellMappingUtils.getWellLabelForIndex(
            index = wellResult.wellIndex,
            columns = dimensions.columns
        )
    }

    /**
     * 开始导出PDF报告
     */
    fun startPdfExport(reportData: ReportData) {
        _exportState.value = ExportState.InProgress
        viewModelScope.launch {
            try {
                val filePath = exportFullPdfReport(reportData)
                _exportState.value = ExportState.Success(
                    message = context.getString(R.string.export_pdf_success),
                    filePath = filePath
                )
            } catch (e: Exception) {
                Log.e("ExportViewModel", "PDF export failed", e)
                _exportState.value = ExportState.Error(
                    context.getString(R.string.export_error, e.localizedMessage ?: "Unknown error")
                )
            }
        }
    }

    /**
     * 开始导出CSV数据
     */
    fun startCsvExport(reportData: ReportData) {
        _exportState.value = ExportState.InProgress
        viewModelScope.launch {
            try {
                val filePath = exportDataToCsv(reportData)
                _exportState.value = ExportState.Success(
                    message = context.getString(R.string.export_csv_success),
                    filePath = filePath
                )
            } catch (e: Exception) {
                Log.e("ExportViewModel", "CSV export failed", e)
                _exportState.value = ExportState.Error(
                    context.getString(R.string.export_error, e.localizedMessage ?: "Unknown error")
                )
            }
        }
    }

    /**
     * 开始导出PNG图表
     */
    fun startPngExport(reportData: ReportData) {
        _exportState.value = ExportState.InProgress
        viewModelScope.launch {
            try {
                exportChartsToPng(reportData)
                _exportState.value = ExportState.Success(
                    message = context.getString(R.string.export_png_success)
                )
            } catch (e: Exception) {
                Log.e("ExportViewModel", "PNG export failed", e)
                _exportState.value = ExportState.Error(
                    context.getString(R.string.export_error, e.localizedMessage ?: "Unknown error")
                )
            }
        }
    }

    /**
     * 导出科研归档包。
     * 归档内包含 PDF、CSV、结果页截图、原始图片、裁切图片与可追溯 JSON。
     */
    fun startArchiveExport(reportData: ReportData, resultSnapshot: Bitmap?) {
        _exportState.value = ExportState.InProgress
        viewModelScope.launch {
            try {
                val filePath = exportResearchArchive(reportData, resultSnapshot)
                _exportState.value = ExportState.Success(
                    message = context.getString(R.string.export_archive_success),
                    filePath = filePath
                )
            } catch (e: Exception) {
                Log.e("ExportViewModel", "Archive export failed", e)
                _exportState.value = ExportState.Error(
                    context.getString(R.string.export_error, e.localizedMessage ?: "Unknown error")
                )
            }
        }
    }

    /**
     * 重置导出状态
     */
    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    @Suppress("DEPRECATION")
    private fun getDownloadDirectory(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (publicDownloads != null && (publicDownloads.exists() || publicDownloads.mkdirs())) {
            return publicDownloads
        }
        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
    }

    private fun writeTraceabilityCsvHeader(
        fos: FileOutputStream,
        reportData: ReportData
    ) {
        val traceability = reportData.analyteDetails.firstOrNull()?.traceabilityInfo
        val captureMetadata = traceability?.captureMetadata
        val lines = buildList {
            add("# Project: ${reportData.project.name}")
            add("# Detection Mode: ${reportData.project.detectionMode}")
            add("# Analysis Method: ${reportData.project.analysisMethod}")
            add("# Export Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            reportData.detectionRun?.detectionModelUsed?.let { add("# Detection Model: $it") }
            reportData.detectionRun?.concentrationModelUsed?.let { add("# Concentration Model: $it") }
            captureMetadata?.capturedAtLabel?.let { add("# Capture Time: $it") }
            captureMetadata?.iso?.let { add("# Capture ISO: $it") }
            captureMetadata?.exposureTimeMs?.let {
                add("# Capture Exposure: ${String.format(Locale.US, "%.2f", it)} ms")
            }
            captureMetadata?.awbModeLabel?.let { add("# Capture AWB: $it") }
            if (reportData.analyteDetails.isNotEmpty()) {
                add(
                    "# Analyte Settings: " + reportData.analyteDetails.joinToString(" | ") { detail ->
                        buildString {
                            append(detail.analyte.name)
                            detail.traceabilityInfo?.templateName?.let { append(" template=$it") }
                            detail.traceabilityInfo?.curveModelName?.let { append(" curve=$it") }
                            detail.traceabilityInfo?.pixelFeatureName?.let { append(" pixel=$it") }
                        }
                    }
                )
            }
            add("")
        }
        fos.write(lines.joinToString("\n").toByteArray())
    }

    private suspend fun exportResearchArchive(
        reportData: ReportData,
        resultSnapshot: Bitmap?
    ): String = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val safeProjectName = reportData.project.name.replace(" ", "_")
        val zipFile = File(getDownloadDirectory(), "FluoColor_Archive_${safeProjectName}_$timestamp.zip")
        val workDir = File(context.cacheDir, "archive_${safeProjectName}_$timestamp")

        if (!workDir.exists()) {
            workDir.mkdirs()
        }

        try {
            val pdfFile = File(workDir, "report.pdf")
            exportFullPdfReport(reportData, pdfFile)

            val csvFile = File(workDir, "results.csv")
            exportDataToCsv(reportData, csvFile)

            val manifestFile = File(workDir, "traceability_manifest.json")
            manifestFile.writeText(buildTraceabilityManifest(reportData).toString(2))

            val snapshotFile = resultSnapshot?.let {
                File(workDir, "result_snapshot.png").also { file ->
                    FileOutputStream(file).use { output ->
                        it.compress(Bitmap.CompressFormat.PNG, 100, output)
                    }
                }
            }

            val projectImageFile = resolveExistingFile(reportData.project.imageUri)
            val projectMetadataFile = projectImageFile
                ?.let(CameraCaptureMetadataStore::buildMetadataFile)
                ?.takeIf(File::exists)

            val cropFiles = reportData.analyteDetails
                .flatMap { detail -> detail.wellResults }
                .mapNotNull { it.croppedImageIdentifier }
                .mapNotNull(::resolveExistingFile)
                .distinctBy { it.absolutePath }

            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                addFileToZip(zip, pdfFile, "report/report.pdf")
                addFileToZip(zip, csvFile, "report/results.csv")
                addFileToZip(zip, manifestFile, "manifest/traceability_manifest.json")
                snapshotFile?.let { addFileToZip(zip, it, "report/result_snapshot.png") }
                projectImageFile?.let {
                    addFileToZip(zip, it, "source/${sanitizeArchiveName(it.name)}")
                }
                projectMetadataFile?.let {
                    addFileToZip(zip, it, "source/${sanitizeArchiveName(it.name)}")
                }
                cropFiles.forEachIndexed { index, file ->
                    addFileToZip(zip, file, "well_crops/${index + 1}_${sanitizeArchiveName(file.name)}")
                }
            }

            val fileUri = Uri.fromFile(zipFile)
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))
            return@withContext zipFile.absolutePath
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun buildTraceabilityManifest(reportData: ReportData): JSONObject {
        val captureMetadata = ResultTraceabilityUtils.readCaptureMetadata(reportData.project.imageUri)
        return JSONObject().apply {
            put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            put("project", JSONObject().apply {
                put("id", reportData.project.id)
                put("name", reportData.project.name)
                put("detectionMode", reportData.project.detectionMode)
                put("recognitionType", reportData.project.recognitionType)
                put("analysisMethod", reportData.project.analysisMethod)
                put("imageUri", reportData.project.imageUri)
                put("createdAt", reportData.project.createTime.toString())
            })
            put("detectionRun", JSONObject().apply {
                put("runId", reportData.detectionRun?.runId)
                put("timestamp", reportData.detectionRun?.timestamp?.toString())
                put("detectionModelUsed", reportData.detectionRun?.detectionModelUsed)
                put("concentrationModelUsed", reportData.detectionRun?.concentrationModelUsed)
                put("confThreshold", reportData.detectionRun?.confThreshold)
                put("iouThreshold", reportData.detectionRun?.iouThreshold)
                put("wellsDetected", reportData.detectionRun?.wellsDetected)
            })
            put("captureMetadata", JSONObject().apply {
                put("capturedAt", captureMetadata?.capturedAtLabel)
                put("iso", captureMetadata?.iso)
                put("exposureTimeMs", captureMetadata?.exposureTimeMs)
                put("exposureCompensationIndex", captureMetadata?.exposureCompensationIndex)
                put("awbMode", captureMetadata?.awbModeLabel)
                put("metadataFilePath", captureMetadata?.metadataFilePath)
            })
            put("analytes", JSONArray().apply {
                reportData.analyteDetails.forEach { detail ->
                    put(
                        JSONObject().apply {
                            put("id", detail.analyte.id)
                            put("name", detail.analyte.name)
                            put("analysisMethod", detail.analysisMethod)
                            put("templateName", detail.traceabilityInfo?.templateName)
                            put("curveModelName", detail.traceabilityInfo?.curveModelName)
                            put("pixelFeatureName", detail.traceabilityInfo?.pixelFeatureName)
                            put("wellCount", detail.wellResults.size)
                            put("concentrationUnit", detail.concentrationUnit)
                        }
                    )
                }
            })
        }
    }

    private fun resolveExistingFile(identifier: String): File? {
        if (identifier.isBlank()) return null
        return when {
            identifier.startsWith("file://") -> Uri.parse(identifier).path?.let(::File)
            identifier.startsWith("content://") -> null
            else -> File(identifier)
        }?.takeIf { it.exists() }
    }

    private fun addFileToZip(zip: ZipOutputStream, file: File, entryName: String) {
        if (!file.exists()) return
        FileInputStream(file).use { input ->
            zip.putNextEntry(ZipEntry(entryName))
            input.copyTo(zip)
            zip.closeEntry()
        }
    }

    private fun sanitizeArchiveName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    // ==================== 光谱导出功能 ====================

    /**
     * 开始导出光谱CSV数据
     */
    fun startSpectrumCsvExport(spectrumData: SpectrumExportData) {
        _exportState.value = ExportState.InProgress
        viewModelScope.launch {
            try {
                val filePath = exportSpectrumToCsv(spectrumData)
                _exportState.value = ExportState.Success(
                    message = context.getString(R.string.spectrum_export_csv_success),
                    filePath = filePath
                )
            } catch (e: Exception) {
                Log.e("ExportViewModel", "Spectrum CSV export failed", e)
                _exportState.value = ExportState.Error(
                    context.getString(R.string.export_error, e.localizedMessage ?: "Unknown error")
                )
            }
        }
    }

    /**
     * 开始导出光谱PNG图表
     */
    fun startSpectrumPngExport(spectrumData: SpectrumExportData, isMerged: Boolean = false) {
        _exportState.value = ExportState.InProgress
        viewModelScope.launch {
            try {
                exportSpectrumChartsToPng(spectrumData, isMerged)
                _exportState.value = ExportState.Success(
                    message = context.getString(R.string.spectrum_export_png_success)
                )
            } catch (e: Exception) {
                Log.e("ExportViewModel", "Spectrum PNG export failed", e)
                _exportState.value = ExportState.Error(
                    context.getString(R.string.export_error, e.localizedMessage ?: "Unknown error")
                )
            }
        }
    }

    /**
     * 开始导出光谱PDF报告
     */
    fun startSpectrumPdfExport(spectrumData: SpectrumExportData) {
        _exportState.value = ExportState.InProgress
        viewModelScope.launch {
            try {
                val filePath = exportSpectrumPdfReport(spectrumData)
                _exportState.value = ExportState.Success(
                    message = context.getString(R.string.spectrum_export_pdf_success),
                    filePath = filePath
                )
            } catch (e: Exception) {
                Log.e("ExportViewModel", "Spectrum PDF export failed", e)
                _exportState.value = ExportState.Error(
                    context.getString(R.string.export_error, e.localizedMessage ?: "Unknown error")
                )
            }
        }
    }

    /**
     * 导出数据为CSV文件
     * @return 保存的文件路径
     */
    private suspend fun exportDataToCsv(
        reportData: ReportData,
        targetFile: File? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "FluoColor_${reportData.project.name.replace(" ", "_")}_$timestamp.csv"
            val file = targetFile ?: File(getDownloadDirectory(), fileName)

            FileOutputStream(file).use { fos ->
                writeTraceabilityCsvHeader(fos, reportData)
                val pixelTypeHeaders = PixelType.values().joinToString(",") { it.identifier }
                val titleLine = "Well,WellIndex,AnalyteName,AnalysisMethod,PredictedConcentration,TrueConcentration,Unit,$pixelTypeHeaders\n"
                fos.write(titleLine.toByteArray())

                for (analyteDetail in reportData.analyteDetails) {
                    val analyteName = analyteDetail.analyte.name
                    val analysisMethod = analyteDetail.analysisMethod
                    val unit = analyteDetail.concentrationUnit

                    for (wellResult in analyteDetail.wellResults) {
                        val wellLabel = resolveWellLabel(wellResult, reportData.project)

                        val predictedConcentration = wellResult.predictedConcentration?.let { String.format(Locale.US, "%.4f", it) } ?: "-"
                        val trueConcentration = wellResult.trueConcentration?.let { String.format(Locale.US, "%.4f", it) } ?: "-"

                        val pixelMap = wellResult.pixelValueJson?.let { com.muc.fluocolorquant.utils.PixelExtractionUtils.jsonToMap(it) } ?: emptyMap()

                        val pixelValues = PixelType.values().map { pixelEnum ->
                            pixelMap[pixelEnum.identifier]?.let { String.format(Locale.US, "%.4f", it) } ?: "-"
                        }

                        val lineData = listOf(
                            wellLabel,
                            wellResult.wellIndex.toString(),
                            analyteName,
                            analysisMethod,
                            predictedConcentration,
                            trueConcentration,
                            unit
                        ) + pixelValues

                        val line = lineData.joinToString(",") + "\n"
                        fos.write(line.toByteArray())
                    }
                }
            }

            if (targetFile == null) {
                val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))
            }

            return@withContext file.absolutePath
        } catch (e: IOException) {
            Log.e("ExportViewModel", "Failed to save CSV file", e)
            throw e
        }
    }

    /**
     * 导出光谱数据为CSV文件(长表格式)
     * @return 保存的文件路径
     */
    private suspend fun exportSpectrumToCsv(data: SpectrumExportData): String = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "FluoColor_Spectrum_${data.project.name.replace(" ", "_")}_$timestamp.csv"

            val file = File(getDownloadDirectory(), fileName)

            // CSV 的科学内容由领域组件生成（纯 Kotlin、可 JVM 断言）；这里只负责落盘、
            // 授予 FileProvider 访问权限和触发媒体扫描这些平台职责。
            val csv = SpectrumCsvExporter.buildCsv(
                projectName = data.project.name,
                exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                channels = data.channels
            )
            FileOutputStream(file).use { fos ->
                fos.write(csv.toByteArray(Charsets.UTF_8))
            }

            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))

            return@withContext file.absolutePath
        } catch (e: IOException) {
            Log.e("ExportViewModel", "Failed to save spectrum CSV file", e)
            throw e
        }
    }


    /**
     * 导出图表为PNG文件
     * @return 保存的文件数量
     */
    private suspend fun exportChartsToPng(reportData: ReportData): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        try {
            for (analyteDetail in reportData.analyteDetails) {
                val analyteName = analyteDetail.analyte.name
                val baseName = "FluoColor_${reportData.project.name.replace(" ", "_")}_${analyteName.replace(" ", "_")}"

                val heatmapBitmap = generateHeatmapBitmap(analyteDetail)
                if (heatmapBitmap != null) {
                    val heatmapFileName = "${baseName}_Heatmap_$timestamp.png"
                    if (saveBitmapToFile(heatmapBitmap, "png", heatmapFileName)) {
                        successCount++
                    }
                }

                val trendBitmap = generateTrendChartBitmap(analyteDetail)
                if (trendBitmap != null) {
                    val trendFileName = "${baseName}_Trend_$timestamp.png"
                    if (saveBitmapToFile(trendBitmap, "png", trendFileName)) {
                        successCount++
                    }
                }

                if (analyteDetail.standardCurveChartData != null) {
                    val curveBitmap = generateStandardCurveBitmap(analyteDetail)
                    if (curveBitmap != null) {
                        val curveFileName = "${baseName}_StandardCurve_$timestamp.png"
                        if (saveBitmapToFile(curveBitmap, "png", curveFileName)) {
                            successCount++
                        }
                    }
                }

                analyteDetail.validationData?.let {
                    val regressionBitmap = generateValidationRegressionBitmap(analyteDetail)
                    if (regressionBitmap != null) {
                        val regressionFileName = "${baseName}_Validation_Regression_$timestamp.png"
                        if (saveBitmapToFile(regressionBitmap, "png", regressionFileName)) {
                            successCount++
                        }
                    }

                    val blandAltmanBitmap = generateValidationBlandAltmanBitmap(analyteDetail)
                    if (blandAltmanBitmap != null) {
                        val blandAltmanFileName = "${baseName}_Validation_BlandAltman_$timestamp.png"
                        if (saveBitmapToFile(blandAltmanBitmap, "png", blandAltmanFileName)) {
                            successCount++
                        }
                    }
                }
            }

            return@withContext successCount
        } catch (e: Exception) {
            Log.e("ExportViewModel", "Failed to export charts", e)
            throw e
        }
    }

    /**
     * 导出光谱图表为PNG文件
     * @param isMerged 是否合并所有曲线到一张图
     * @return 保存的文件数量
     */
    private suspend fun exportSpectrumChartsToPng(data: SpectrumExportData, isMerged: Boolean = false): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        try {
            if (isMerged) {
                // 合并模式：生成一张包含所有通道曲线的图片
                val fileName = "${data.project.name.replace(" ", "_")}_AllChannels_Spectrum_$timestamp.png"
                val bitmap = generateMergedSpectrumBitmap(data.channels)
                if (bitmap != null) {
                    if (saveBitmapToFile(bitmap, "png", fileName)) {
                        successCount++
                    }
                }
            } else {
                // 单通道模式：为每个通道生成单独的图片
                for (channel in data.channels) {
                    val channelIndex = channel.channelIndex
                    val analyteName = channel.analyteName.replace(" ", "_")
                    val fileName = "${data.project.name.replace(" ", "_")}_Channel${channelIndex}_${analyteName}_Spectrum_$timestamp.png"

                    val bitmap = generateSpectrumCurveBitmap(channel)
                    if (bitmap != null) {
                        if (saveBitmapToFile(bitmap, "png", fileName)) {
                            successCount++
                        }
                    }
                }
            }

            return@withContext successCount
        } catch (e: Exception) {
            Log.e("ExportViewModel", "Failed to export spectrum charts", e)
            throw e
        }
    }

    /**
     * 生成光谱曲线图位图。
     *
     * 绘制本身已下沉为 [SpectrumChartRenderer]（领域层，不持有 Context）；这里只负责把
     * 界面语言下的标题与轴名解析出来传进去。
     */
    private fun generateSpectrumCurveBitmap(channel: SpectrumChannelExportModel): Bitmap? {
        return runCatching {
            SpectrumChartRenderer.renderChannelCurve(
                channel = channel,
                title = context.getString(
                    R.string.spectrum_chart_title_format,
                    channel.channelIndex,
                    channel.analyteName
                ),
                labels = spectrumChartLabels()
            )
        }.onFailure { error ->
            Log.e("ExportViewModel", "Failed to generate spectrum curve bitmap", error)
        }.getOrNull()
    }

    /** 生成合并光谱图位图（所有通道叠加在一张图上）。 */
    private fun generateMergedSpectrumBitmap(channels: List<SpectrumChannelExportModel>): Bitmap? {
        return runCatching {
            SpectrumChartRenderer.renderMergedCurves(
                channels = channels,
                title = context.getString(R.string.spectrum_merged_chart_title),
                labels = spectrumChartLabels()
            )
        }.onFailure { error ->
            Log.e("ExportViewModel", "Failed to generate merged spectrum bitmap", error)
        }.getOrNull()
    }

    private fun spectrumChartLabels(): SpectrumChartLabels = SpectrumChartLabels(
        wavelengthAxis = context.getString(R.string.spectrum_axis_wavelength),
        intensityAxis = context.getString(R.string.spectrum_axis_intensity)
    )

    /**
     * 【优化】生成热力图位图，确保布局合理
     */
    private fun generateHeatmapBitmap(analyteDetail: AnalyteResultDetails): Bitmap? {
        try {
            val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            val titlePaint = TextPaint().apply {
                color = android.graphics.Color.BLACK
                textSize = 40f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val title = "${analyteDetail.analyte.name} ${context.getString(R.string.concentration_heatmap)}"
            canvas.drawText(title, 600f, 80f, titlePaint)

            val dimensions = GridLayoutPolicy.resolveProject(analyteDetail.project)
            val rows = dimensions.rows
            val cols = dimensions.columns
            val startX = 120f
            val startY = 160f  // 增加上方空间，避免与标题重叠
            // 固定导出画布，通过真实行列动态计算格子大小，15×15 和非方阵不会被裁掉。
            val cellWidth = (1200f - startX - 60f) / cols
            val cellHeight = (900f - startY - 80f) / rows
            val maxConcentration = analyteDetail.wellResults.mapNotNull { it.predictedConcentration }.filter { it.isFinite() }.maxOrNull() ?: 100.0

            val textPaint = TextPaint().apply {
                textSize = 20f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            val labelPaint = TextPaint(textPaint).apply { textSize = 24f }

            // 绘制列标签
            for (col in 0 until cols) {
                canvas.drawText((col + 1).toString(), startX + col * cellWidth + cellWidth / 2, startY - 20f, labelPaint)
            }
            // 绘制行标签
            for (row in 0 until rows) {
                canvas.drawText(WellMappingUtils.getRowLabel(row), startX - 40f, startY + row * cellHeight + cellHeight / 2 + 10f, labelPaint)
            }

            // 绘制热力图
            analyteDetail.wellResults.forEach { wellResult ->
                val (row, col) = WellMappingUtils.mapRealToVirtualCoordinates(
                    realIndex = wellResult.wellIndex,
                    columns = cols
                )
                if (row in 0 until rows && col in 0 until cols) {
                    val left = startX + col * cellWidth
                    val top = startY + row * cellHeight
                    val right = left + cellWidth
                    val bottom = top + cellHeight

                    val concentration = wellResult.predictedConcentration
                    val composeColor = if (concentration != null) HeatmapColorUtil.getColor(concentration, 0.0, maxConcentration) else androidx.compose.ui.graphics.Color.LightGray
                    val color = android.graphics.Color.argb((composeColor.alpha * 255).toInt(), (composeColor.red * 255).toInt(), (composeColor.green * 255).toInt(), (composeColor.blue * 255).toInt())
                    val paint = Paint().apply { this.color = color }
                    canvas.drawRect(left, top, right, bottom, paint)

                    if (concentration != null) {
                        val textColor = if (composeColor.red * 0.299 + composeColor.green * 0.587 + composeColor.blue * 0.114 > 0.6) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                        textPaint.color = textColor
                        canvas.drawText(String.format(Locale.US, "%.1f", concentration), left + cellWidth / 2, top + cellHeight / 2 + 8f, textPaint)
                    }
                }
            }

            // 绘制图例，调整位置确保完全显示
            val legendY = startY + (rows * cellHeight) + 80f  // 增加间距
            val legendWidth = 800f
            val legendHeight = 40f
            val legendX = (canvas.width - legendWidth) / 2

            // 图例渐变色
            val gradientPaint = Paint()
            val colors = IntArray(100) { i ->
                val composeColor = HeatmapColorUtil.getColor(i / 99.0, 0.0, 1.0)
                android.graphics.Color.argb((composeColor.alpha * 255).toInt(), (composeColor.red * 255).toInt(), (composeColor.green * 255).toInt(), (composeColor.blue * 255).toInt())
            }
            for (i in 0 until 100) {
                gradientPaint.color = colors[i]
                canvas.drawRect(legendX + i * (legendWidth / 100), legendY, legendX + (i + 1) * (legendWidth / 100), legendY + legendHeight, gradientPaint)
            }

            // 图例标签
            val legendTextPaint = TextPaint().apply {
                textSize = 24f
                isAntiAlias = true
                color = android.graphics.Color.BLACK
            }
            legendTextPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("0.00", legendX, legendY + legendHeight + 30f, legendTextPaint)
            legendTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.US, "%.2f", maxConcentration), legendX + legendWidth, legendY + legendHeight + 30f, legendTextPaint)

            // 图例标题，居中显示
            legendTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.bitmap_legend_concentration_range_format, String.format(Locale.US, "%.2f", maxConcentration), analyteDetail.concentrationUnit),
                legendX + legendWidth / 2, legendY - 10f, legendTextPaint)

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成热力图位图失败", e)
            return null
        }
    }

    /**
     * 【优化】生成浓度趋势图位图，确保X轴标签完整显示
     */
    private fun generateTrendChartBitmap(analyteDetail: AnalyteResultDetails): Bitmap? {
        try {
            val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)  // 增加图表高度
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            val titlePaint = TextPaint().apply {
                color = android.graphics.Color.BLACK
                textSize = 40f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val title = "${analyteDetail.analyte.name} ${context.getString(R.string.pdf_trend_chart_title)}"
            canvas.drawText(title, 600f, 70f, titlePaint)

            val validResults = analyteDetail.wellResults.filter { it.predictedConcentration != null && it.predictedConcentration!!.isFinite() }.sortedBy { it.wellIndex }
            if (validResults.isEmpty()) return bitmap

            val maxConcentration = validResults.maxOf { it.predictedConcentration!! } * 1.1
            val minConcentration = 0.0

            val leftPadding = 120f
            val rightPadding = 60f
            val topPadding = 120f
            val bottomPadding = 150f  // 增加底部空间，确保X轴标签完全显示
            val graphWidth = canvas.width - leftPadding - rightPadding
            val graphHeight = canvas.height - topPadding - bottomPadding

            val axisPaint = Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = 3f }
            val labelPaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 24f; isAntiAlias = true }

            // 绘制Y轴和标签
            canvas.drawLine(leftPadding, topPadding, leftPadding, topPadding + graphHeight, axisPaint)
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.save()
            canvas.rotate(-90f)
            canvas.drawText(
                context.getString(R.string.bitmap_axis_label_concentration_with_unit, analyteDetail.concentrationUnit),
                -(topPadding + graphHeight / 2),
                leftPadding - 70,
                labelPaint
            )
            canvas.restore()

            // 绘制X轴和标签
            canvas.drawLine(leftPadding, topPadding + graphHeight, leftPadding + graphWidth, topPadding + graphHeight, axisPaint)
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                context.getString(R.string.bitmap_axis_label_well_id_capital),
                leftPadding + graphWidth / 2,
                topPadding + graphHeight + 100,  // 增加Y坐标，确保标签在图表下方足够位置
                labelPaint
            )

            // 绘制Y轴刻度
            val yGridLines = 5
            labelPaint.textAlign = Paint.Align.RIGHT
            for(i in 0..yGridLines) {
                val yValue = minConcentration + (maxConcentration - minConcentration) * i / yGridLines
                val yPos = topPadding + graphHeight - (graphHeight * (yValue - minConcentration) / (maxConcentration - minConcentration)).toFloat()
                canvas.drawLine(leftPadding - 5, yPos, leftPadding + graphWidth, yPos, Paint().apply { color = android.graphics.Color.LTGRAY; strokeWidth = 1f })
                canvas.drawText(String.format(Locale.US, "%.1f", yValue), leftPadding - 10, yPos + 8, labelPaint)
            }

            if (validResults.size > 1) {
                val path = android.graphics.Path()
                val pointPaint = Paint().apply { color = android.graphics.Color.RED; style = Paint.Style.FILL }
                val linePaint = Paint().apply { color = android.graphics.Color.BLUE; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
                val pointLabelPaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 18f; isAntiAlias = true; textAlign = Paint.Align.CENTER }

                validResults.forEachIndexed { index, wellResult ->
                    val x = leftPadding + index * (graphWidth / (validResults.size - 1))
                    val y = topPadding + graphHeight - (graphHeight * (wellResult.predictedConcentration!! - minConcentration) / (maxConcentration - minConcentration)).toFloat()

                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    canvas.drawCircle(x, y, 8f, pointPaint)

                    val wellLabel = resolveWellLabel(wellResult, analyteDetail.project)

                    // 在数据点下方添加孔位标签
                    canvas.drawText(wellLabel, x, y - 15, pointLabelPaint)

                    // 在X轴下方也添加孔位标签，每个点下方都加标签
                    canvas.save()
                    canvas.rotate(45f, x, topPadding + graphHeight + 20) // 标签倾斜45度，避免重叠
                    canvas.drawText(wellLabel, x, topPadding + graphHeight + 35, pointLabelPaint)
                    canvas.restore()
                }
                canvas.drawPath(path, linePaint)
            }

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成浓度趋势图位图失败", e)
            return null
        }
    }

    // ... [其余的Bitmap生成和辅助函数保持不变，但为了完整性，这里全部提供] ...

    private fun generateValidationRegressionBitmap(analyteDetail: AnalyteResultDetails): Bitmap? {
        val validationData = analyteDetail.validationData ?: return null
        val chartData = validationData.regressionPlotData
        try {
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }

            val titlePaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            canvas.drawText("${analyteDetail.analyte.name} Regression Analysis", 500f, 70f, titlePaint)

            val metricsPaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 30f; isAntiAlias = true }
            val r2 = validationData.regressionMetrics["R²"] ?: "N/A"
            val rmse = validationData.regressionMetrics["RMSE"] ?: "N/A"
            canvas.drawText("R² = $r2", 150f, 120f, metricsPaint)
            canvas.drawText("RMSE = $rmse", 150f, 160f, metricsPaint)

            drawChartAxisAndGrid(canvas, chartData, "Actual", "Predicted")
            drawChartScatterPoints(canvas, chartData)
            drawChartLines(canvas, chartData)

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成验证回归图位图失败", e)
            return null
        }
    }

    private fun generateValidationBlandAltmanBitmap(analyteDetail: AnalyteResultDetails): Bitmap? {
        val validationData = analyteDetail.validationData ?: return null
        val chartData = validationData.blandAltmanPlotData
        try {
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }

            val titlePaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            canvas.drawText("${analyteDetail.analyte.name} Bland-Altman Analysis", 500f, 70f, titlePaint)

            drawChartAxisAndGrid(canvas, chartData, "Mean", "Difference")
            drawChartScatterPoints(canvas, chartData)
            drawChartLines(canvas, chartData, isBlandAltman = true)

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成Bland-Altman分析图位图失败", e)
            return null
        }
    }

    private fun generateStandardCurveBitmap(analyteDetail: AnalyteResultDetails): Bitmap? {
        val chartData = analyteDetail.standardCurveChartData ?: return null
        try {
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE) }

            val titlePaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            canvas.drawText("${analyteDetail.analyte.name} ${context.getString(R.string.pdf_standard_curve_title)}", 500f, 70f, titlePaint)

            drawChartAxisAndGrid(canvas, chartData, chartData.xAxisLabel, chartData.yAxisLabel)
            drawChartScatterPoints(canvas, chartData)
            drawChartLines(canvas, chartData)

            return bitmap
        } catch (e: Exception) {
            Log.e("ExportViewModel", "生成标准曲线位图失败", e)
            return null
        }
    }

    private fun drawChartAxisAndGrid(canvas: Canvas, chartData: ChartData, xLabel: String, yLabel: String) {
        val axisPaint = Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = 2f }
        val gridPaint = Paint().apply { color = android.graphics.Color.LTGRAY; strokeWidth = 1f }
        val labelPaint = TextPaint().apply { color = android.graphics.Color.BLACK; textSize = 24f; isAntiAlias = true }

        val left = 100f; val top = 100f; val right = 900f; val bottom = 900f
        val graphWidth = right - left; val graphHeight = bottom - top

        canvas.drawLine(left, bottom, right, bottom, axisPaint) // X
        canvas.drawLine(left, top, left, bottom, axisPaint)     // Y

        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(xLabel, left + graphWidth / 2, bottom + 60, labelPaint)

        canvas.save()
        canvas.rotate(-90f)
        canvas.drawText(yLabel, -(top + graphHeight / 2), left - 60, labelPaint)
        canvas.restore()

        val xMin = chartData.xRange.first; val xMax = chartData.xRange.second
        val yMin = chartData.yRange.first; val yMax = chartData.yRange.second
        val numTicks = 5
        labelPaint.textSize = 20f

        labelPaint.textAlign = Paint.Align.CENTER
        for (i in 0..numTicks) {
            val value = xMin + i * (xMax - xMin) / numTicks
            val x = left + i * (graphWidth / numTicks)
            canvas.drawLine(x, bottom, x, bottom + 10, axisPaint)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            canvas.drawText(String.format(Locale.US, "%.2f", value), x, bottom + 35, labelPaint)
        }

        labelPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..numTicks) {
            val value = yMin + i * (yMax - yMin) / numTicks
            val y = bottom - i * (graphHeight / numTicks)
            canvas.drawLine(left, y, left - 10, y, axisPaint)
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText(String.format(Locale.US, "%.2f", value), left - 15, y + 8, labelPaint)
        }
    }

    private fun drawChartScatterPoints(canvas: Canvas, chartData: ChartData) {
        val pointPaint = Paint().apply { color = android.graphics.Color.RED; style = Paint.Style.FILL }
        val left = 100f; val top = 100f; val graphWidth = 800f; val graphHeight = 800f
        val xMin = chartData.xRange.first; val xMax = chartData.xRange.second
        val yMin = chartData.yRange.first; val yMax = chartData.yRange.second

        val pointsToDraw = chartData.scatterPoints ?: chartData.standardPoints.map { com.muc.fluocolorquant.ui.components.charts.ChartPoint(it.first, it.second) }

        pointsToDraw.forEach { point ->
            val x = left + ((point.x - xMin) / (xMax - xMin) * graphWidth).toFloat()
            val y = top + graphHeight - ((point.y - yMin) / (yMax - yMin) * graphHeight).toFloat()
            canvas.drawCircle(x, y, 8f, pointPaint)
        }
    }

    private fun drawChartLines(canvas: Canvas, chartData: ChartData, isBlandAltman: Boolean = false) {
        val linePaint = Paint().apply { strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
        val left = 100f; val top = 100f; val graphWidth = 800f; val graphHeight = 800f
        val xMin = chartData.xRange.first; val xMax = chartData.xRange.second
        val yMin = chartData.yRange.first; val yMax = chartData.yRange.second

        if (chartData.curvePoints.isNotEmpty()) {
            linePaint.color = android.graphics.Color.BLUE
            val path = android.graphics.Path()
            chartData.curvePoints.forEachIndexed { index, pair ->
                val x = left + ((pair.first - xMin) / (xMax - xMin) * graphWidth).toFloat()
                val y = top + graphHeight - ((pair.second - yMin) / (yMax - yMin) * graphHeight).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }

        val linesToDraw = if (isBlandAltman) chartData.additionalLines else mapOf("ideal" to chartData.standardPoints)
        linesToDraw.forEach { (key, points) ->
            if (points.size >= 2) {
                linePaint.color = when(key) {
                    "mean" -> android.graphics.Color.GREEN
                    "upperLimit", "lowerLimit" -> android.graphics.Color.RED
                    else -> android.graphics.Color.GRAY
                }
                linePaint.pathEffect = if (key == "mean" || key == "ideal") null else android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)

                val path = android.graphics.Path()
                points.forEachIndexed { index, pair ->
                    val x = left + ((pair.first - xMin) / (xMax - xMin) * graphWidth).toFloat()
                    val y = top + graphHeight - ((pair.second - yMin) / (yMax - yMin) * graphHeight).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, linePaint)
            }
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, format: String, fileName: String): Boolean {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            val file = File(downloadDir, fileName)
            FileOutputStream(file).use { fos ->
                val compressFormat = when (format.lowercase()) {
                    "png" -> Bitmap.CompressFormat.PNG
                    "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                    else -> Bitmap.CompressFormat.PNG
                }
                val quality = if (compressFormat == Bitmap.CompressFormat.JPEG) 90 else 100
                bitmap.compress(compressFormat, quality, fos)
            }
            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))
            true
        } catch (e: IOException) {
            Log.e("ExportViewModel", "Failed to save bitmap to file", e)
            false
        }
    }

    /**
     * 【完全重构】导出完整的PDF报告
     *
     * PDF导出功能进行了全面优化，解决了以下问题：
     * 1. 修复了封面页布局问题，确保与pdf_cover_page.xml定义的布局一致
     * 2. 优化了图表显示，确保热力图和趋势图完整展示，包括所有坐标轴和标签
     * 3. 改进了多章节PDF结构，包括封面、总览页和每个分析物的独立章节
     * 4. 实现了清晰的页眉页脚，提升了PDF的专业感
     * 5. 优化了图表大小和布局，避免内容重叠或被裁剪
     * 6. 合理分隔验证数据的回归分析和Bland-Altman分析
     * 7. 添加了附录页面，包含原始数据表格
     *
     * @return 保存的文件路径
     */
    private suspend fun exportFullPdfReport(
        reportData: ReportData,
        targetFile: File? = null
    ): String = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "FluoColor_Report_${reportData.project.name.replace(" ", "_")}_$timestamp.pdf"
        val reportFile = targetFile ?: File(getDownloadDirectory(), fileName)
        val document = PdfDocument()

        try {
            var pageCount = 1
            // 1. 创建封面
            val coverPage = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, pageCount).create())
            createCoverPage(coverPage.canvas, reportData)
            document.finishPage(coverPage)
            pageCount++

            // 2. 创建实验总览页
            val summaryPage = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, pageCount).create())
            createSummaryPageDirectly(summaryPage.canvas, reportData, pageCount, countTotalPages(reportData))
            document.finishPage(summaryPage)
            pageCount++

            // 3. 为每个分析物创建章节
            val totalPages = countTotalPages(reportData)
            for ((chapterIndex, analyteDetail) in reportData.analyteDetails.withIndex()) {
                val chapterResult = createAnalyteChapter(document, analyteDetail, chapterIndex + 1, pageCount, totalPages)
                pageCount = chapterResult
            }

            // 4. 附录：创建原始数据表（支持多页）
            pageCount = createRawDataAppendix(document, reportData, pageCount, totalPages)

            // 5. 保存PDF
            FileOutputStream(reportFile).use { out ->
                document.writeTo(out)
            }
        } catch (e: Exception) {
            Log.e("ExportViewModel", "导出PDF报告失败", e)
            throw e
        } finally {
            document.close()
        }

        if (targetFile == null) {
            val fileUri = Uri.fromFile(reportFile)
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))
        }
        return@withContext reportFile.absolutePath
    }

    /**
     * 预估总页数
     */
    private fun countTotalPages(reportData: ReportData): Int {
        var pageCount = 2  // 封面 + 总览页

        // 预估每个分析物需要的页数
        for (analyteDetail in reportData.analyteDetails) {
            pageCount += 2  // 每个分析物至少需要2页
            if (analyteDetail.validationData != null) {
                pageCount++  // 验证数据需要额外1页
            }
        }

        // 预估附录页数
        val rowsPerPage = ((PDF_CONTENT_HEIGHT - 80f - TABLE_CELL_HEIGHT) / TABLE_CELL_HEIGHT).toInt()
        val totalRows = reportData.analyteDetails.sumOf { it.wellResults.size }
        val appendixPages = if (totalRows == 0) 1 else (totalRows + rowsPerPage - 1) / rowsPerPage

        pageCount += appendixPages

        return pageCount
    }

    /**
     * 使用项目既有的 pdf_cover_page.xml 生成96孔板/传统结果报告封面。
     *
     * 这里与规则阵列导出共享同一个渲染器，避免注释声称使用模板、实际却手工绘制的漂移。
     */
    private fun createCoverPage(canvas: Canvas, reportData: ReportData) {
        val project = reportData.project
        val totalPages = countTotalPages(reportData)
        val detectionModeText = when (project.detectionMode) {
            "FLUORESCENCE" -> context.getString(R.string.fluorescence_detection)
            "COLORIMETRIC" -> context.getString(R.string.colorimetric_detection)
            else -> project.detectionMode
        }
        val chapterLines = reportData.analyteDetails.mapIndexed { index, detail ->
            context.getString(R.string.pdf_chapter_title_format, index + 1, detail.analyte.name)
        }
        PdfCoverPageRenderer.draw(
            context = context,
            targetCanvas = canvas,
            pageWidth = PDF_PAGE_WIDTH,
            pageHeight = PDF_PAGE_HEIGHT,
            content = PdfCoverPageContent(
                title = context.getString(R.string.pdf_title_fluocolorquant_report),
                projectLine = context.getString(R.string.pdf_label_project_name_format, project.name),
                dateLine = context.getString(
                    R.string.pdf_label_creation_date,
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(project.createTime)
                ),
                detectionModeLine = context.getString(
                    R.string.pdf_label_detection_mode_format,
                    detectionModeText
                ),
                overviewLines = chapterLines,
                generatedAtLine = context.getString(
                    R.string.pdf_generated_on,
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                ),
                pageNumberLine = context.getString(R.string.pdf_page_number, 1, totalPages)
            )
        )
        Log.d("ExportViewModel", "已使用 pdf_cover_page.xml 创建封面页")
    }

    private fun buildTraceabilityPdfLines(reportData: ReportData): List<String> {
        val traceability = reportData.analyteDetails.firstOrNull()?.traceabilityInfo ?: return emptyList()
        val captureLine = buildString {
            traceability.captureMetadata?.capturedAtLabel?.let {
                append(context.getString(R.string.result_traceability_capture_time))
                append(it)
            }
            traceability.captureMetadata?.iso?.let {
                if (isNotEmpty()) append("  |  ")
                append(context.getString(R.string.result_traceability_capture_iso))
                append(it)
            }
            traceability.captureMetadata?.exposureTimeMs?.let {
                if (isNotEmpty()) append("  |  ")
                append(context.getString(R.string.result_traceability_capture_exposure))
                append(context.getString(R.string.result_traceability_exposure_value, formatPdfDecimal(it)))
            }
        }

        return buildList {
            traceability.detectionModelName?.let {
                add(context.getString(R.string.result_traceability_detection_model) + it)
            }
            traceability.concentrationModelName?.let {
                add(context.getString(R.string.result_traceability_concentration_model) + it)
            }
            if (captureLine.isNotBlank()) {
                add(captureLine)
            }
            val thresholdLine = buildString {
                traceability.confidenceThreshold?.let {
                    append(context.getString(R.string.result_traceability_conf_threshold))
                    append(context.getString(R.string.result_traceability_threshold_value, formatPdfDecimal(it.toDouble())))
                }
                traceability.iouThreshold?.let {
                    if (isNotEmpty()) append("  |  ")
                    append(context.getString(R.string.result_traceability_iou_threshold))
                    append(context.getString(R.string.result_traceability_threshold_value, formatPdfDecimal(it.toDouble())))
                }
            }
            if (thresholdLine.isNotBlank()) {
                add(thresholdLine)
            }
        }
    }

    /**
     * 报告数值格式化；实现见 [PdfPageCanvas.formatDecimal]。
     *
     * 与旧实现的唯一差异：NaN/Infinity 现在输出短横线而不是字面量 "NaN"，与报告其余位置
     * 的缺失值表示保持一致。
     */
    private fun formatPdfDecimal(value: Double): String = PdfPageCanvas.formatDecimal(value)

    /**
     * 【修复】正确测量和布局View，解决ConstraintLayout布局问题
     */
    private fun measureAndLayoutViewFixed(parentContainer: FrameLayout): Boolean {
        return try {
            Log.d("ExportViewModel", "开始测量和布局View")
            
            // 第一步：测量父容器，使用EXACTLY模式
            val parentWidthSpec = View.MeasureSpec.makeMeasureSpec(PDF_PAGE_WIDTH, View.MeasureSpec.EXACTLY)
            val parentHeightSpec = View.MeasureSpec.makeMeasureSpec(PDF_PAGE_HEIGHT, View.MeasureSpec.EXACTLY)
            
            parentContainer.measure(parentWidthSpec, parentHeightSpec)
            parentContainer.layout(0, 0, PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT)
            
            // 验证测量结果
            if (parentContainer.measuredWidth <= 0 || parentContainer.measuredHeight <= 0) {
                Log.e("ExportViewModel", "父容器测量失败: ${parentContainer.measuredWidth}x${parentContainer.measuredHeight}")
                return false
            }
            
            // 第二步：递归处理所有子视图
            forceLayoutChildrenFixed(parentContainer)
            
            Log.d("ExportViewModel", "View布局完成: ${parentContainer.measuredWidth}x${parentContainer.measuredHeight}")
            true
        } catch (e: Exception) {
            Log.e("ExportViewModel", "测量和布局失败", e)
            false
        }
    }

    /**
     * 【修复】递归强制布局所有子视图，确保ConstraintLayout约束正确解析
     */
    private fun forceLayoutChildrenFixed(viewGroup: ViewGroup) {
        try {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)

                // 确保子视图有正确的布局参数
                if (child.layoutParams == null) {
                    child.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                // 对于ConstraintLayout的子视图，确保约束正确应用
                if (viewGroup is androidx.constraintlayout.widget.ConstraintLayout) {
                    val childWidthSpec = View.MeasureSpec.makeMeasureSpec(
                        child.layoutParams.width,
                        if (child.layoutParams.width == ViewGroup.LayoutParams.MATCH_PARENT) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
                    )
                    val childHeightSpec = View.MeasureSpec.makeMeasureSpec(
                        child.layoutParams.height,
                        if (child.layoutParams.height == ViewGroup.LayoutParams.MATCH_PARENT) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
                    )

                    child.measure(childWidthSpec, childHeightSpec)
                    child.layout(child.left, child.top, child.left + child.measuredWidth, child.top + child.measuredHeight)
                }

                // 递归处理子ViewGroup
                if (child is ViewGroup) {
                    forceLayoutChildrenFixed(child)
                }
            }
        } catch (e: Exception) {
            Log.w("ExportViewModel", "强制布局子视图时出现异常", e)
        }
    }

//    /**
//     * 【修复】创建View的Bitmap，增强错误处理和布局验证
//     */
//    private fun createViewBitmapFixed(view: View): Bitmap? {
//        return try {
//            Log.d("ExportViewModel", "开始创建View Bitmap")
//
//            // 验证View的测量状态
//            if (view.measuredWidth <= 0 || view.measuredHeight <= 0) {
//                Log.e("ExportViewModel", "View尺寸无效: ${view.measuredWidth}x${view.measuredHeight}")
//                return null
//            }
//
//            // 创建Bitmap，使用PDF页面尺寸确保一致性
//            val bitmap = Bitmap.createBitmap(
//                PDF_PAGE_WIDTH,
//                PDF_PAGE_HEIGHT,
//                Bitmap.Config.ARGB_8888
//            )
//
//            val bitmapCanvas = Canvas(bitmap)
//
//            // 设置白色背景
//            bitmapCanvas.drawColor(android.graphics.Color.WHITE)
//
//            // 保存Canvas状态
//            bitmapCanvas.save()
//
//            try {
//                // 确保绘制在正确的区域内
//                bitmapCanvas.clipRect(0, 0, PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT)
//
//                // 绘制View到Canvas
//                view.draw(bitmapCanvas)
//
//                Log.d("ExportViewModel", "View绘制到Bitmap成功")
//            } finally {
//                // 恢复Canvas状态
//                bitmapCanvas.restore()
//            }
//
//            // 验证Bitmap是否有效
//            if (bitmap.isRecycled) {
//                Log.e("ExportViewModel", "Bitmap已被回收")
//                return null
//            }
//
//            Log.d("ExportViewModel", "封面页Bitmap创建成功: ${bitmap.width}x${bitmap.height}")
//            return bitmap
//
//        } catch (e: OutOfMemoryError) {
//            Log.e("ExportViewModel", "内存不足，无法创建Bitmap", e)
//            // 尝试强制垃圾回收
//            System.gc()
//            null
//        } catch (e: Exception) {
//            Log.e("ExportViewModel", "创建Bitmap失败", e)
//            null
//        }
//    }

//    /**
//     * 直接绘制封面页（作为备用方案）
//     */
//    private fun createCoverPageDirectly(canvas: Canvas, reportData: ReportData) {
//        Log.d("ExportViewModel", "使用直接绘制方式创建封面页")
//
//        drawPageHeaderFooter(canvas, reportData.project.name, context.getString(R.string.pdf_title_fluocolorquant_report), 1, countTotalPages(reportData))
//
//        var yOffset = PDF_CONTENT_START_Y + 80f // 为标题留出空间
//
//        // 绘制主标题
//        val titlePaint = createTextPaint(24f, isBold = true, align = Paint.Align.CENTER)
//        canvas.drawText(context.getString(R.string.pdf_title_fluocolorquant_report), PDF_PAGE_WIDTH / 2f, yOffset, titlePaint)
//        yOffset += 80f
//
//        // 绘制项目信息卡片
//        val cardPaint = Paint().apply { color = android.graphics.Color.parseColor("#F5F5F5"); style = Paint.Style.FILL }
//        val cardBorderPaint = Paint().apply { color = android.graphics.Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2f }
//        val cardLeft = PDF_MARGIN + 20f
//        val cardRight = PDF_PAGE_WIDTH - PDF_MARGIN - 20f
//        val cardTop = yOffset
//        val cardHeight = 120f
//
//        // 绘制项目信息卡片背景
//        canvas.drawRect(cardLeft, cardTop, cardRight, cardTop + cardHeight, cardPaint)
//        canvas.drawRect(cardLeft, cardTop, cardRight, cardTop + cardHeight, cardBorderPaint)
//
//        // 绘制项目信息
//        val textPaint = createTextPaint(14f)
//        val project = reportData.project
//        val padding = 16f
//
//        yOffset = cardTop + padding
//        yOffset = drawFormattedText(canvas,
//            context.getString(R.string.pdf_label_project_name_format, project.name),
//            cardLeft + padding, yOffset, textPaint, cardRight - cardLeft - 2 * padding) + 8f
//
//        yOffset = drawFormattedText(canvas,
//            context.getString(R.string.pdf_label_creation_date, SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(project.createTime)),
//            cardLeft + padding, yOffset, textPaint, cardRight - cardLeft - 2 * padding) + 8f
//
//        val detectionModeText = when(project.detectionMode) {
//            "FLUORESCENCE" -> context.getString(R.string.fluorescence_detection)
//            "COLORIMETRIC" -> context.getString(R.string.colorimetric_detection)
//            else -> project.detectionMode
//        }
//
//        drawFormattedText(canvas,
//            context.getString(R.string.pdf_label_detection_mode_format, detectionModeText),
//            cardLeft + padding, yOffset, textPaint, cardRight - cardLeft - 2 * padding)
//
//        // 绘制内容总览卡片
//        yOffset = cardTop + cardHeight + 24f
//        val overviewCardTop = yOffset
//        val overviewCardHeight = 200f
//
//        // 绘制内容总览卡片背景
//        canvas.drawRect(cardLeft, overviewCardTop, cardRight, overviewCardTop + overviewCardHeight, cardPaint)
//        canvas.drawRect(cardLeft, overviewCardTop, cardRight, overviewCardTop + overviewCardHeight, cardBorderPaint)
//
//        // 绘制内容总览标题
//        val overviewTitlePaint = createTextPaint(16f, isBold = true)
//        yOffset = overviewCardTop + padding
//        yOffset = drawFormattedText(canvas,
//            context.getString(R.string.pdf_report_content_overview),
//            cardLeft + padding, yOffset, overviewTitlePaint, cardRight - cardLeft - 2 * padding) + 8f
//
//        // 绘制章节列表
//        val chapterPaint = createTextPaint(14f)
//        reportData.analyteDetails.forEachIndexed { index, detail ->
//            val chapterTitle = context.getString(R.string.pdf_chapter_title_format, index + 1, detail.analyte.name)
//            yOffset = drawFormattedText(canvas, chapterTitle,
//                cardLeft + padding, yOffset, chapterPaint, cardRight - cardLeft - 2 * padding) + 8f
//        }
//
//        // 绘制页脚信息
//        val footerPaint = createTextPaint(10f, android.graphics.Color.GRAY)
//        canvas.drawText(
//            context.getString(R.string.pdf_generated_on, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())),
//            PDF_MARGIN,
//            PDF_PAGE_HEIGHT - 40f,
//            footerPaint
//        )
//
//        footerPaint.textAlign = Paint.Align.RIGHT
//        canvas.drawText(
//            context.getString(R.string.pdf_page_number, 1, countTotalPages(reportData)),
//            PDF_PAGE_WIDTH - PDF_MARGIN,
//            PDF_PAGE_HEIGHT - 40f,
//            footerPaint
//        )
//
//        Log.d("ExportViewModel", "直接绘制封面页完成")
//    }



    /**
     * 【已重构】创建实验总览页 - 直接绘制版本
     */
    private fun createSummaryPageDirectly(canvas: Canvas, reportData: ReportData, pageNumber: Int, totalPages: Int) {
        drawPageHeaderFooter(canvas, reportData.project.name, context.getString(R.string.pdf_report_content_overview), pageNumber, totalPages)

        var yOffset = PDF_CONTENT_START_Y

        // 绘制章节标题
        val titlePaint = createTextPaint(18f, isBold = true)
        yOffset = drawFormattedText(canvas, context.getString(R.string.pdf_report_content_overview), PDF_MARGIN, yOffset, titlePaint, PDF_CONTENT_WIDTH) + 20f

        // 项目信息
        val textPaint = createTextPaint(12f)
        val project = reportData.project

        yOffset = drawFormattedText(canvas,
            context.getString(R.string.pdf_label_project_name_format, project.name),
            PDF_MARGIN, yOffset, textPaint, PDF_CONTENT_WIDTH) + 10f

        yOffset = drawFormattedText(canvas,
            context.getString(R.string.pdf_label_creation_date, SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(project.createTime)),
            PDF_MARGIN, yOffset, textPaint, PDF_CONTENT_WIDTH) + 10f

        val detectionModeText = when(project.detectionMode) {
            "FLUORESCENCE" -> context.getString(R.string.fluorescence_detection)
            "COLORIMETRIC" -> context.getString(R.string.colorimetric_detection)
            else -> project.detectionMode
        }

        yOffset = drawFormattedText(canvas,
            context.getString(R.string.pdf_label_detection_mode_format, detectionModeText),
            PDF_MARGIN, yOffset, textPaint, PDF_CONTENT_WIDTH) + 20f

        buildTraceabilityPdfLines(reportData).forEach { line ->
            yOffset = drawFormattedText(
                canvas,
                line,
                PDF_MARGIN,
                yOffset,
                textPaint,
                PDF_CONTENT_WIDTH
            ) + 10f
        }

        // 章节目录
        val chapterTitlePaint = createTextPaint(14f)
        reportData.analyteDetails.forEachIndexed { index, detail ->
            val chapterTitle = context.getString(R.string.pdf_chapter_title_format, index + 1, detail.analyte.name)
            yOffset = drawFormattedText(canvas, chapterTitle, PDF_MARGIN + 20f, yOffset, chapterTitlePaint, PDF_CONTENT_WIDTH - 20f) + 10f
        }
    }

    /**
     * 创建原始数据附录页 - 直接绘制版本
     */
    private fun createRawDataAppendixDirectly(canvas: Canvas, reportData: ReportData, pageNumber: Int, totalPages: Int) {
        drawPageHeaderFooter(canvas, reportData.project.name, context.getString(R.string.pdf_appendix_title), pageNumber, totalPages)

        var yOffset = PDF_CONTENT_START_Y

        // 绘制章节标题
        val titlePaint = createTextPaint(18f, isBold = true)
        yOffset = drawFormattedText(canvas, context.getString(R.string.pdf_appendix_title), PDF_MARGIN, yOffset, titlePaint, PDF_CONTENT_WIDTH) + 20f

        // 绘制原始数据表格
        val headers = listOf(
            context.getString(R.string.pdf_table_header_well),
            context.getString(R.string.analyte),
            context.getString(R.string.pdf_table_header_analysis_method),
            context.getString(R.string.pdf_table_header_role),
            context.getString(R.string.pdf_table_header_predicted_concentration),
            context.getString(R.string.pdf_table_header_true_concentration),
            context.getString(R.string.pdf_table_header_unit)
        )

        val data = mutableListOf<List<String>>()

        for (analyteDetail in reportData.analyteDetails) {
            val analyteName = analyteDetail.analyte.name
            val method = if (analyteDetail.analysisMethod == "CURVE_FIT")
                context.getString(R.string.pdf_analysis_curve_fit)
            else
                context.getString(R.string.pdf_analysis_dl_model)

            for (wellResult in analyteDetail.wellResults) {
                val wellLabel = resolveWellLabel(wellResult, reportData.project)

                // 确保通过wellResult访问wellType属性
                val wellTypeValue = try {
                    // 尝试通过反射获取属性，如果不存在则返回默认值
                    val field = wellResult.javaClass.getDeclaredField("wellType")
                    field.isAccessible = true
                    field.get(wellResult)?.toString() ?: "UNKNOWN"
                } catch (e: Exception) {
                    "UNKNOWN" // 如果没有该属性则默认使用UNKNOWN
                }

                val role = when(wellTypeValue) {
                    "STANDARD" -> context.getString(R.string.pdf_well_role_standard)
                    "SAMPLE" -> context.getString(R.string.pdf_well_role_sample)
                    "BLANK" -> context.getString(R.string.pdf_well_role_blank)
                    "QUALITY_CONTROL" -> context.getString(R.string.pdf_well_role_quality_control)
                    else -> context.getString(R.string.pdf_well_role_unknown)
                }

                val predicted = wellResult.predictedConcentration?.let { String.format(Locale.US, "%.4f", it) } ?: "-"
                val trueVal = wellResult.trueConcentration?.let { String.format(Locale.US, "%.4f", it) } ?: "-"

                data.add(listOf(
                    wellLabel,
                    analyteName,
                    method,
                    role,
                    predicted,
                    trueVal,
                    analyteDetail.concentrationUnit
                ))
            }
        }

        // 如果数据量太大，只显示一部分
        val maxRowsToShow = 20
        val displayedData = if (data.size > maxRowsToShow) data.take(maxRowsToShow) else data

        yOffset = drawTable(
            canvas,
            yOffset,
            headers,
            displayedData,
            floatArrayOf(60f, 85f, 85f, 70f, 70f, 70f, 45f)
        )

        if (data.size > maxRowsToShow) {
            val notePaint = createTextPaint(10f, android.graphics.Color.GRAY, false, Paint.Align.CENTER)
            canvas.drawText(
                context.getString(R.string.pdf_note_more_results, data.size - maxRowsToShow),
                PDF_PAGE_WIDTH / 2f,
                yOffset + 15f,
                notePaint
            )
        }

        // 添加导出的数据文件说明
        val notePaint = createTextPaint(12f)
        yOffset += 30f
//        yOffset = drawFormattedText(
//            canvas,
//            "注意：完整的原始数据已导出为CSV文件，包含所有孔位的详细像素值和浓度数据。",
//            PDF_MARGIN,
//            yOffset,
//            notePaint,
//            PDF_CONTENT_WIDTH
//        ) + 10f
    }

    /**
     * 绘制页眉和页脚
     */
    /** 统一页眉页脚；实现见 [PdfPageCanvas]，此处仅补齐 Context。 */
    private fun drawPageHeaderFooter(canvas: Canvas, projectName: String, chapterTitle: String, pageNumber: Int, totalPages: Int) {
        PdfPageCanvas.drawPageHeaderFooter(canvas, context, projectName, chapterTitle, pageNumber, totalPages)
    }

    /**
     * 创建文本绘制画笔
     */
    /** 统一文本画笔；实现见 [PdfPageCanvas.textPaint]。 */
    private fun createTextPaint(
        size: Float,
        color: Int = android.graphics.Color.BLACK,
        isBold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT
    ): TextPaint = PdfPageCanvas.textPaint(size, color, isBold, align)

    /**
     * 绘制格式化文本
     * @return 返回文本结束的Y坐标
     */
    @SuppressLint("NewApi")
    /** 自动折行文本；实现见 [PdfPageCanvas.drawWrappedText]。 */
    private fun drawFormattedText(canvas: Canvas, text: String, x: Float, y: Float, paint: TextPaint, maxWidth: Float): Float =
        PdfPageCanvas.drawWrappedText(canvas, text, x, y, paint, maxWidth)

    /**
     * 绘制表格（支持指定起始X坐标）
     */
    /** 三线风格表格；实现见 [PdfPageCanvas.drawTable]。 */
    private fun drawTable(
        canvas: Canvas,
        startY: Float,
        headers: List<String>,
        data: List<List<String>>,
        columnWidths: FloatArray,
        startX: Float = PDF_MARGIN
    ): Float = PdfPageCanvas.drawTable(canvas, startY, headers, data, columnWidths, startX)

    /**
     * 创建分析物章节，返回下一页的页号
     */
    private fun createAnalyteChapter(
        document: PdfDocument,
        analyteDetail: AnalyteResultDetails,
        chapterIndex: Int,
        startPageNumber: Int,
        totalPages: Int
    ): Int {
        var currentPageNumber = startPageNumber
        val chapterTitle = context.getString(R.string.pdf_chapter_title_format, chapterIndex, analyteDetail.analyte.name)

        // 开始第一页 - 分析方案和标准曲线
        var page = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, currentPageNumber).create())
        var canvas = page.canvas

        // 绘制页眉页脚
        drawPageHeaderFooter(canvas, analyteDetail.analyte.name, chapterTitle, currentPageNumber, totalPages)

        var yOffset = PDF_CONTENT_START_Y

        // 绘制章节标题
        val titlePaint = createTextPaint(18f, isBold = true)
        yOffset = drawFormattedText(canvas, chapterTitle, PDF_MARGIN, yOffset, titlePaint, PDF_CONTENT_WIDTH) + 20f

        // 分析方案部分
        yOffset = drawAnalysisPlan(canvas, yOffset, analyteDetail)

        // 绘制标准曲线（如果有）
        if (analyteDetail.analysisMethod == "CURVE_FIT" && analyteDetail.standardCurveChartData != null) {
            yOffset = drawStandardCurve(canvas, yOffset, analyteDetail)
        }

        document.finishPage(page)
        currentPageNumber++

        // 开始第二页 - 浓度分布
        page = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, currentPageNumber).create())
        canvas = page.canvas
        drawPageHeaderFooter(canvas, analyteDetail.analyte.name, chapterTitle, currentPageNumber, totalPages)

        yOffset = PDF_CONTENT_START_Y
        yOffset = drawConcentrationDistribution(canvas, yOffset, analyteDetail)

        document.finishPage(page)
        currentPageNumber++

        // 如果有验证数据，创建额外的页面
        if (analyteDetail.validationData != null) {
            page = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, currentPageNumber).create())
            canvas = page.canvas
            drawPageHeaderFooter(canvas, analyteDetail.analyte.name, chapterTitle, currentPageNumber, totalPages)

            yOffset = PDF_CONTENT_START_Y
            yOffset = drawValidationSection(canvas, yOffset, analyteDetail)

            document.finishPage(page)
            currentPageNumber++
        }

        return currentPageNumber
    }

    /**
     * 绘制分析方案部分
     */
    private fun drawAnalysisPlan(canvas: Canvas, startY: Float, details: AnalyteResultDetails): Float {
        var y = startY
        val sectionTitlePaint = createTextPaint(16f, isBold = true)
        val textPaint = createTextPaint(12f)

        y = drawFormattedText(canvas, context.getString(R.string.pdf_analysis_plan_title), PDF_MARGIN, y, sectionTitlePaint, PDF_CONTENT_WIDTH) + 15f

        val method = if (details.analysisMethod == "CURVE_FIT")
            context.getString(R.string.pdf_analysis_curve_fit)
        else
            context.getString(R.string.pdf_analysis_dl_model)

        y = drawFormattedText(canvas, "${context.getString(R.string.pdf_analysis_method_title)} $method", PDF_MARGIN + 20f, y, textPaint, PDF_CONTENT_WIDTH - 20f) + 10f

        val template = details.usedTemplate?.templateName ?: context.getString(R.string.pdf_no_template)
        y = drawFormattedText(canvas, "${context.getString(R.string.pdf_template_used_title)} $template", PDF_MARGIN + 20f, y, textPaint, PDF_CONTENT_WIDTH - 20f) + 10f

        // 添加试剂信息（如果有）
        details.usedTemplate?.let { template ->
            val reagentInfo = mutableListOf<String>()

            // 尝试安全访问模板的antigen属性
            try {
                val antigenField = template.javaClass.getDeclaredField("antigen")
                antigenField.isAccessible = true
                val antigenObj = antigenField.get(template)

                if (antigenObj != null) {
                    val nameField = antigenObj.javaClass.getDeclaredField("name")
                    nameField.isAccessible = true
                    val name = nameField.get(antigenObj)?.toString() ?: context.getString(R.string.pdf_reagent_unknown_name)

                    val manufacturerField = antigenObj.javaClass.getDeclaredField("manufacturer")
                    manufacturerField.isAccessible = true
                    val manufacturer = manufacturerField.get(antigenObj)?.toString() ?: context.getString(R.string.pdf_reagent_unknown_manufacturer)

                    reagentInfo.add(context.getString(R.string.pdf_reagent_antigen_format, name, manufacturer))
                }
            } catch (e: Exception) {
                Log.d("ExportViewModel", "No antigen info available: ${e.message}")
            }

            // 尝试安全访问模板的antibody属性
            try {
                val antibodyField = template.javaClass.getDeclaredField("antibody")
                antibodyField.isAccessible = true
                val antibodyObj = antibodyField.get(template)

                if (antibodyObj != null) {
                    val nameField = antibodyObj.javaClass.getDeclaredField("name")
                    nameField.isAccessible = true
                    val name = nameField.get(antibodyObj)?.toString() ?: context.getString(R.string.pdf_reagent_unknown_name)

                    val manufacturerField = antibodyObj.javaClass.getDeclaredField("manufacturer")
                    manufacturerField.isAccessible = true
                    val manufacturer = manufacturerField.get(antibodyObj)?.toString() ?: context.getString(R.string.pdf_reagent_unknown_manufacturer)

                    reagentInfo.add(context.getString(R.string.pdf_reagent_antibody_format, name, manufacturer))
                }
            } catch (e: Exception) {
                Log.d("ExportViewModel", "No antibody info available: ${e.message}")
            }

            if (reagentInfo.isNotEmpty()) {
                y = drawFormattedText(canvas, context.getString(R.string.pdf_reagent_section_title), PDF_MARGIN + 20f, y, textPaint, PDF_CONTENT_WIDTH - 20f) + 5f

                reagentInfo.forEach { info ->
                    y = drawFormattedText(canvas, "• $info", PDF_MARGIN + 40f, y, textPaint, PDF_CONTENT_WIDTH - 40f) + 5f
                }

                y += 5f
            }
        }

        return y + 10f
    }

    /**
     * 绘制标准曲线部分
     */
    private fun drawStandardCurve(canvas: Canvas, startY: Float, details: AnalyteResultDetails): Float {
        var y = startY
        val sectionTitlePaint = createTextPaint(16f, isBold = true)
        val textPaint = createTextPaint(12f)
        val smallTextPaint = createTextPaint(10f)

        y = drawFormattedText(canvas, context.getString(R.string.pdf_standard_curve_title), PDF_MARGIN, y, sectionTitlePaint, PDF_CONTENT_WIDTH) + 15f

        val curveBitmap = generateStandardCurveBitmap(details)
        if (curveBitmap != null) {
            // 调整图表大小，确保完全显示
            val scale = min(0.6f, (PDF_PAGE_HEIGHT - y - PDF_FOOTER_HEIGHT - 100f) / curveBitmap.height)
            val scaledWidth = curveBitmap.width * scale
            val scaledHeight = curveBitmap.height * scale

            val destRect = Rect(
                PDF_MARGIN.toInt(),
                y.toInt(),
                (PDF_MARGIN + scaledWidth).toInt(),
                (y + scaledHeight).toInt()
            )
            canvas.drawBitmap(curveBitmap, null, destRect, null)
            y += scaledHeight + 15f
        }

        // 绘制函数表达式和参数
        details.fittedCurveModel?.let { model ->
            y = drawFormattedText(canvas, context.getString(R.string.function_expression), PDF_MARGIN, y, textPaint, PDF_CONTENT_WIDTH) + 5f
            val latexExpression = FittingEngine.formatParametersToLatex(model.function, model.parameters)
            y = drawFormattedText(canvas, latexExpression, PDF_MARGIN + 20f, y, textPaint, PDF_CONTENT_WIDTH - 20f) + 10f

            y = drawFormattedText(canvas, context.getString(R.string.curve_parameters), PDF_MARGIN, y, textPaint, PDF_CONTENT_WIDTH) + 5f

            val paramText = model.parameters.entries.joinToString(", ") { (key, value) ->
                "$key: ${String.format(Locale.US, "%.4f", value)}"
            }
            y = drawFormattedText(canvas, paramText, PDF_MARGIN + 20f, y, smallTextPaint, PDF_CONTENT_WIDTH - 20f) + 10f

            // 拟合指标
            model.metrics?.let { metrics ->
                y = drawFormattedText(canvas, context.getString(R.string.fitting_quality), PDF_MARGIN, y, textPaint, PDF_CONTENT_WIDTH) + 5f

                val metricText = metrics.entries.joinToString(", ") { (key, value) ->
                    "$key: ${String.format(Locale.US, "%.4f", value)}"
                }
                y = drawFormattedText(canvas, metricText, PDF_MARGIN + 20f, y, smallTextPaint, PDF_CONTENT_WIDTH - 20f) + 10f
            }
        }

        return y
    }

    /**
     * 绘制浓度分布部分
     */
    private fun drawConcentrationDistribution(canvas: Canvas, startY: Float, details: AnalyteResultDetails): Float {
        var y = startY
        val sectionTitlePaint = createTextPaint(16f, isBold = true)

        y = drawFormattedText(canvas, context.getString(R.string.pdf_concentration_distribution_title), PDF_MARGIN, y, sectionTitlePaint, PDF_CONTENT_WIDTH) + 15f

        // 先绘制热力图
        val heatmapBitmap = generateHeatmapBitmap(details)
        if (heatmapBitmap != null) {
            val maxHeight = (PDF_PAGE_HEIGHT - y - PDF_FOOTER_HEIGHT - 120f) / 2 // 预留足够空间给两个图表
            val scale = min(0.8f, maxHeight / heatmapBitmap.height)
            val scaledWidth = heatmapBitmap.width * scale
            val scaledHeight = heatmapBitmap.height * scale

            val destRect = Rect(
                ((PDF_PAGE_WIDTH - scaledWidth) / 2).toInt(),
                y.toInt(),
                ((PDF_PAGE_WIDTH + scaledWidth) / 2).toInt(),
                (y + scaledHeight).toInt()
            )
            canvas.drawBitmap(heatmapBitmap, null, destRect, null)
            y += scaledHeight + 20f
        }

        // 再绘制趋势图
        val trendBitmap = generateTrendChartBitmap(details)
        if(trendBitmap != null) {
            val maxHeight = PDF_PAGE_HEIGHT - y - PDF_FOOTER_HEIGHT - 30f
            val scale = min(0.8f, maxHeight / trendBitmap.height)
            val scaledWidth = trendBitmap.width * scale
            val scaledHeight = trendBitmap.height * scale

            val destRect = Rect(
                ((PDF_PAGE_WIDTH - scaledWidth) / 2).toInt(),
                y.toInt(),
                ((PDF_PAGE_WIDTH + scaledWidth) / 2).toInt(),
                (y + scaledHeight).toInt()
            )
            canvas.drawBitmap(trendBitmap, null, destRect, null)
            y += scaledHeight + 10f
        }

        return y
    }

    /**
     * 绘制验证部分
     */
    private fun drawValidationSection(canvas: Canvas, startY: Float, details: AnalyteResultDetails): Float {
        val validationData = details.validationData ?: return startY
        var y = startY
        val sectionTitlePaint = createTextPaint(16f, isBold = true)
        val subSectionPaint = createTextPaint(14f, isBold = true)

        y = drawFormattedText(canvas, context.getString(R.string.pdf_validation_title), PDF_MARGIN, y, sectionTitlePaint, PDF_CONTENT_WIDTH) + 15f

        // 绘制回归分析部分
        y = drawFormattedText(canvas, context.getString(R.string.pdf_regression_title), PDF_MARGIN + 10f, y, subSectionPaint, PDF_CONTENT_WIDTH - 10f) + 10f

        // 计算左侧图表和右侧表格的布局
        val leftWidth = (PDF_CONTENT_WIDTH - 20f) * 0.65f  // 左侧图表占65%宽度
        val rightWidth = (PDF_CONTENT_WIDTH - 20f) * 0.35f // 右侧表格占35%宽度
        val regressionSectionStartY = y

        // 绘制回归分析图表（左侧）
        val regressionBitmap = generateValidationRegressionBitmap(details)
        var regressionSectionHeight = 0f

        if (regressionBitmap != null) {
            val scale = leftWidth / regressionBitmap.width
            val scaledHeight = regressionBitmap.height * scale

            val destRect = Rect(
                PDF_MARGIN.toInt(),
                y.toInt(),
                (PDF_MARGIN + leftWidth).toInt(),
                (y + scaledHeight).toInt()
            )
            canvas.drawBitmap(regressionBitmap, null, destRect, null)
            regressionSectionHeight = scaledHeight
        }

        // 绘制回归分析指标表格（右侧）
        if (validationData.regressionMetrics.isNotEmpty()) {
            val headers = listOf("Metric Name", "Value")
            val data = validationData.regressionMetrics.map { (key, value) -> listOf(key, value) }

            drawTable(
                canvas,
                regressionSectionStartY,
                headers,
                data,
                floatArrayOf(rightWidth * 0.6f, rightWidth * 0.4f),
                PDF_MARGIN + leftWidth + 10f  // 表格从图表右侧开始
            )
        }

        // 更新Y坐标到回归分析部分的底部
        y = regressionSectionStartY + regressionSectionHeight + 30f

        // 绘制Bland-Altman分析部分
        y = drawFormattedText(canvas, context.getString(R.string.pdf_bland_altman_title), PDF_MARGIN + 10f, y, subSectionPaint, PDF_CONTENT_WIDTH - 10f) + 10f
        val blandAltmanSectionStartY = y

        // 绘制Bland-Altman图表（左侧）
        val blandAltmanBitmap = generateValidationBlandAltmanBitmap(details)
        var blandAltmanSectionHeight = 0f

        if (blandAltmanBitmap != null) {
            val scale = leftWidth / blandAltmanBitmap.width
            val scaledHeight = blandAltmanBitmap.height * scale

            val destRect = Rect(
                PDF_MARGIN.toInt(),
                y.toInt(),
                (PDF_MARGIN + leftWidth).toInt(),
                (y + scaledHeight).toInt()
            )
            canvas.drawBitmap(blandAltmanBitmap, null, destRect, null)
            blandAltmanSectionHeight = scaledHeight
        }

        // 绘制Bland-Altman指标表格（右侧）
        if (validationData.blandAltmanMetrics.isNotEmpty()) {
            val headers = listOf("Metric Name", "Value")
            val data = validationData.blandAltmanMetrics.map { (key, value) -> listOf(key, value) }

            drawTable(
                canvas,
                blandAltmanSectionStartY,
                headers,
                data,
                floatArrayOf(rightWidth * 0.6f, rightWidth * 0.4f),
                PDF_MARGIN + leftWidth + 10f  // 表格从图表右侧开始
            )
        }

        // 更新Y坐标到Bland-Altman分析部分的底部
        y = blandAltmanSectionStartY + blandAltmanSectionHeight + 20f

        return y
    }

    /**
     * 【重构】创建原始数据附录 - 支持多页显示
     * @return 返回下一页的页号
     */
    private fun createRawDataAppendix(
        document: PdfDocument,
        reportData: ReportData,
        startPageNumber: Int,
        totalPages: Int
    ): Int {
        var currentPageNumber = startPageNumber

        // 准备原始数据
        val headers = listOf(
            context.getString(R.string.pdf_table_header_well),
            "Images",
            context.getString(R.string.analyte),
            "Role",
            "Predicted Conc.",
            "True Conc.",
            "Unit"
        )

        val data = mutableListOf<List<String>>()
        val imageMap = mutableMapOf<Int, Bitmap?>() // 存储图片索引和对应的Bitmap

        for (analyteDetail in reportData.analyteDetails) {
            val analyteName = analyteDetail.analyte.name

            for (wellResult in analyteDetail.wellResults) {
                val wellLabel = resolveWellLabel(wellResult, reportData.project)

                // 获取角色类型
                val roleType = try {
                    val field = wellResult.javaClass.getDeclaredField("roleType")
                    field.isAccessible = true
                    field.get(wellResult)?.toString() ?: "UNKNOWN"
                } catch (e: Exception) {
                    "UNKNOWN" // 如果没有该属性则默认使用UNKNOWN
                }

                val role = when(roleType) {
                    "STANDARD" -> "Standard"
                    "SAMPLE" -> "Sample"
                    "BLANK" -> "Blank"
                    "QC" -> "QC"
                    "UNKNOWN" -> "Unknown"
                    "NONE" -> "None"
                    else -> roleType
                }

                // 获取裁剪图片标识符
                val imageId = try {
                    val field = wellResult.javaClass.getDeclaredField("croppedImageIdentifier")
                    field.isAccessible = true
                    field.get(wellResult)?.toString() ?: ""
                } catch (e: Exception) {
                    "" // 如果没有该属性则默认使用空字符串
                }

                // 尝试加载图片
                if (imageId.isNotEmpty()) {
                    try {
                        val imageIndex = data.size // 使用当前数据索引作为图片索引
                        // 异步加载图片，避免阻塞主线程
                        imageMap[imageIndex] = loadImageFromPath(imageId)
                    } catch (e: Exception) {
                        Log.e("ExportViewModel", "加载图片失败: $imageId", e)
                    }
                }

                val predicted = wellResult.predictedConcentration?.let { String.format(Locale.US, "%.4f", it) } ?: "-"
                val trueVal = wellResult.trueConcentration?.let { String.format(Locale.US, "%.4f", it) } ?: "-"

                data.add(listOf(
                    wellLabel,
                    imageId, // 暂时存储路径，稍后会替换为图片
                    analyteName,
                    role,
                    predicted,
                    trueVal,
                    analyteDetail.concentrationUnit
                ))
            }
        }

        // 计算每页可以显示的行数
        val rowHeight = TABLE_CELL_HEIGHT
        val headerHeight = rowHeight
        val pageContentHeight = PDF_CONTENT_HEIGHT - 80f // 减去标题和说明的空间
        val rowsPerPage = ((pageContentHeight - headerHeight) / rowHeight).toInt()

        // 分页显示数据
        val appendixPages = if (data.isEmpty()) 1 else (data.size + rowsPerPage - 1) / rowsPerPage

        for (pageIndex in 0 until appendixPages) {
            val startRow = pageIndex * rowsPerPage
            val endRow = minOf((pageIndex + 1) * rowsPerPage, data.size)
            val pageData = data.subList(startRow, endRow)

            val page = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, currentPageNumber).create())
            val canvas = page.canvas

            // 绘制页眉页脚
            val pageTitle = if (pageIndex == 0)
                context.getString(R.string.pdf_appendix_title)
            else
                context.getString(R.string.pdf_appendix_title) + " (Continued)"

            drawPageHeaderFooter(canvas, reportData.project.name, pageTitle, currentPageNumber, totalPages)

            var yOffset = PDF_CONTENT_START_Y

            // 绘制章节标题
            if (pageIndex == 0) {
                val titlePaint = createTextPaint(18f, isBold = true)
                yOffset = drawFormattedText(canvas, context.getString(R.string.pdf_appendix_title), PDF_MARGIN, yOffset, titlePaint, PDF_CONTENT_WIDTH) + 20f
            } else {
                val titlePaint = createTextPaint(18f, isBold = true)
                yOffset = drawFormattedText(canvas, context.getString(R.string.pdf_appendix_title) + " (Continued)", PDF_MARGIN, yOffset, titlePaint, PDF_CONTENT_WIDTH) + 20f
            }

            // 绘制表格
            yOffset = drawTableWithImages(
                canvas,
                yOffset,
                headers,
                pageData,
                floatArrayOf(60f, 60f, 85f, 70f, 70f, 70f, 45f),
                imageMap,
                startRow
            )

            // 如果是最后一页，添加导出说明
            if (pageIndex == appendixPages - 1) {
                val notePaint = createTextPaint(12f)
                yOffset += 30f
                drawFormattedText(
                    canvas,
                    "Note: Complete raw data has been exported as CSV file, containing detailed pixel values and concentration data for all wells.",
                    PDF_MARGIN,
                    yOffset,
                    notePaint,
                    PDF_CONTENT_WIDTH
                )
            }

            document.finishPage(page)
            currentPageNumber++
        }

        // 清理图片资源
        for (bitmap in imageMap.values) {
            bitmap?.recycle()
        }

        return currentPageNumber
    }

    /**
     * 加载图片路径并返回Bitmap
     */
    /** 按缩略图尺寸降采样加载插图；实现见 [PdfPageCanvas.loadThumbnail]。 */
    private fun loadImageFromPath(imagePath: String): Bitmap? =
        PdfPageCanvas.loadThumbnail(context, imagePath)

    /**
     * 计算合适的图片缩放比例
     */
    /** 降采样比例；实现见 [PdfPageCanvas.calculateInSampleSize]。 */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int =
        PdfPageCanvas.calculateInSampleSize(options, reqWidth, reqHeight)

    /**
     * 绘制带图片的表格
     */
    private fun drawTableWithImages(
        canvas: Canvas,
        startY: Float,
        headers: List<String>,
        data: List<List<String>>,
        columnWidths: FloatArray,
        imageMap: Map<Int, Bitmap?>,
        startRowIndex: Int,
        startX: Float = PDF_MARGIN
    ): Float {
        var currentY = startY
        val rowHeight = TABLE_CELL_HEIGHT
        val headerPaint = Paint().apply { color = android.graphics.Color.parseColor("#E0E0E0"); style = Paint.Style.FILL }
        val rowPaint = Paint().apply { color = android.graphics.Color.parseColor("#F5F5F5"); style = Paint.Style.FILL }
        val borderPaint = Paint().apply { color = android.graphics.Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 1f }
        val textPaint = createTextPaint(10f)
        val headerTextPaint = createTextPaint(10f, isBold = true)

        // Draw header
        canvas.drawRect(startX, currentY, startX + columnWidths.sum(), currentY + rowHeight, headerPaint)
        var currentX = startX
        headers.forEachIndexed { i, header ->
            canvas.drawText(header, currentX + 5, currentY + rowHeight - 10, headerTextPaint)
            currentX += columnWidths[i]
        }
        currentY += rowHeight

        // Draw rows
        data.forEachIndexed { rowIndex, rowData ->
            val absoluteRowIndex = startRowIndex + rowIndex

            if (rowIndex % 2 != 0) {
                canvas.drawRect(startX, currentY, startX + columnWidths.sum(), currentY + rowHeight, rowPaint)
            }

            currentX = startX

            // 绘制第一列（Well ID）
            canvas.drawText(rowData[0], currentX + 5, currentY + rowHeight - 10, textPaint)
            currentX += columnWidths[0]

            // 绘制第二列（图片）
            val imageBitmap = imageMap[absoluteRowIndex]
            if (imageBitmap != null) {
                // 计算保持纵横比的图像尺寸
                val imageWidth = columnWidths[1] - 10
                val imageHeight = rowHeight - 6

                // 计算保持原始纵横比的尺寸
                val originalRatio = imageBitmap.width.toFloat() / imageBitmap.height.toFloat()

                // 确定最终绘制尺寸，保持纵横比
                val finalWidth: Float
                val finalHeight: Float

                if (originalRatio > 1) {  // 宽图
                    finalWidth = imageWidth
                    finalHeight = finalWidth / originalRatio
                } else {  // 高图或正方形
                    finalHeight = imageHeight
                    finalWidth = finalHeight * originalRatio
                }

                // 计算居中位置
                val leftPadding = (imageWidth - finalWidth) / 2 + 5
                val topPadding = (imageHeight - finalHeight) / 2 + 3

                val imageRect = RectF(
                    currentX + leftPadding,
                    currentY + topPadding,
                    currentX + leftPadding + finalWidth,
                    currentY + topPadding + finalHeight
                )

                // 绘制边框
                val imageBorderPaint = Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    style = Paint.Style.STROKE
                    strokeWidth = 0.5f
                }
                canvas.drawRect(
                    currentX + 5,
                    currentY + 3,
                    currentX + 5 + imageWidth,
                    currentY + 3 + imageHeight,
                    imageBorderPaint
                )

                // 绘制图片
                canvas.drawBitmap(imageBitmap, null, imageRect, null)
            }
            currentX += columnWidths[1]

            // 绘制剩余列
            for (i in 2 until rowData.size) {
                canvas.drawText(rowData[i], currentX + 5, currentY + rowHeight - 10, textPaint)
                currentX += columnWidths[i]
            }

            currentY += rowHeight
        }

        // Draw table borders
        canvas.drawRect(startX, startY, startX + columnWidths.sum(), currentY, borderPaint)
        currentX = startX
        for (width in columnWidths) {
            canvas.drawLine(currentX, startY, currentX, currentY, borderPaint)
            currentX += width
        }
        canvas.drawLine(currentX, startY, currentX, currentY, borderPaint)

        return currentY + 10f
    }
    // ==================== 光谱导出辅助方法 ====================
    
    /**
     * 导出光谱PDF报告
     */
    /**
     * 导出光谱 PDF 报告。
     *
     * 页面结构与绘制已下沉为 [SpectrumPdfExporter]（领域层）；这里只保留落盘、媒体扫描
     * 等平台职责，与 CSV 导出保持同一分工。
     */
    private suspend fun exportSpectrumPdfReport(data: SpectrumExportData): String = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "FluoColor_Spectrum_Report_${data.project.name.replace(" ", "_")}_$timestamp.pdf"
        val reportFile = File(getDownloadDirectory(), fileName)
        val document = SpectrumPdfExporter.createDocument(context, data)
        try {
            FileOutputStream(reportFile).use { out: FileOutputStream ->
                document.writeTo(out)
            }
        } catch (e: Exception) {
            Log.e("ExportViewModel", "导出光谱PDF失败", e)
            throw e
        } finally {
            document.close()
        }
        val fileUri = Uri.fromFile(reportFile)
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))
        return@withContext reportFile.absolutePath
    }
}

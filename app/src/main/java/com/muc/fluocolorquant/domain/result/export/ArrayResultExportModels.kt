package com.muc.fluocolorquant.domain.result.export

/** 阵列结果页支持的科研导出格式。 */
enum class ArrayResultExportFormat(
    val fileExtension: String,
    val mimeType: String
) {
    CSV("csv", "text/csv"),
    PDF("pdf", "application/pdf"),
    ZIP("zip", "application/zip")
}

/**
 * ZIP 导出读取附件时使用的最小边界。
 *
 * 纯 JVM 测试可注入内存数据，Android 页面则通过 ContentResolver 或本地文件读取。
 */
fun interface ArrayExportEvidenceReader {
    fun readBytes(sourcePath: String): ByteArray?
}

/** ZIP 内每个已写入文件的摘要，manifest 自身不参与循环校验。 */
data class ArrayArchiveEntryRecord(
    val archivePath: String,
    val status: String,
    val byteCount: Long?,
    val sha256: String?
)

/** 原始/派生附件的读取与校验结果；缺失证据必须明确记录。 */
data class ArrayArchiveEvidenceRecord(
    val artifactId: String,
    val captureRole: String,
    val evidenceKind: String,
    val sourcePath: String,
    val archivePath: String,
    val status: String,
    val byteCount: Long?,
    val declaredSha256: String?,
    val actualSha256: String?,
    val checksumMatches: Boolean?
)

/** 可复现 ZIP 的固定清单结构。 */
data class ArrayArchiveManifest(
    val schemaVersion: String,
    val exporterVersion: String,
    val csvSchemaVersion: String,
    val runId: String,
    val projectId: String,
    val projectName: String,
    val runTimestampEpochMillis: Long,
    val runStatus: String,
    val detectionMode: String,
    val carrierId: String,
    val carrierVersion: Int,
    val rows: Int,
    val columns: Int,
    val physicalSiteCount: Int,
    val measurementCount: Int,
    val entries: List<ArrayArchiveEntryRecord>,
    val evidence: List<ArrayArchiveEvidenceRecord>
)

/** PDF 绘制文字全部由 strings.xml 在 Compose 层构造，导出核心不硬编码界面语言。 */
data class ArrayResultPdfLabels(
    val documentTitle: String,
    val frozenEvidenceNote: String,
    val project: String,
    val runId: String,
    val runTime: String,
    val status: String,
    val detectionMode: String,
    val carrier: String,
    val layout: String,
    val physicalSites: String,
    val measurements: String,
    val reliableMeasurements: String,
    val overviewHeatmap: String,
    val analyteSection: String,
    val model: String,
    val primaryFeature: String,
    val reliableRange: String,
    val concentrationHeatmap: String,
    val signalHeatmap: String,
    val qualityControl: String,
    val frameIssueCount: String,
    val siteFailureCount: String,
    val lowSignalCount: String,
    val traceability: String,
    val locator: String,
    val processor: String,
    val frozenSnapshot: String,
    val noValue: String,
    val pageFormat: String
)

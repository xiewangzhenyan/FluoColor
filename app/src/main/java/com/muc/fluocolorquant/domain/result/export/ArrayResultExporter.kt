package com.muc.fluocolorquant.domain.result.export

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.resolveQualityLevel
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 阵列结果的纯数据导出核心。
 *
 * CSV 与 ZIP 只消费 [ArrayResultSnapshot] 冻结证据，不读取当前模板库，也不重新执行定位、
 * 光度或浓度模型。这样历史运行导出与屏幕显示使用完全相同的数据来源。
 */
object ArrayResultExporter {
    const val CSV_SCHEMA_VERSION: String = "array-measurements-csv-v2"
    const val ARCHIVE_SCHEMA_VERSION: String = "array-result-archive-v2"
    const val EXPORTER_VERSION: String = "array-result-exporter-v3"

    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    private val csvColumns = listOf(
        "run_id",
        "project_id",
        "project_name",
        "run_timestamp_epoch_ms",
        "run_status",
        "detection_mode",
        "carrier_id",
        "carrier_name",
        "carrier_type",
        "carrier_version",
        "array_rows",
        "array_columns",
        "site_index",
        "row_index",
        "column_index",
        "site_key",
        "site_enabled",
        "sample_slot",
        "default_sample_slot",
        "site_role",
        "assigned_analyte_id",
        "measurement_id",
        "analyte_id",
        "analyte_name",
        "raw_signal_json",
        "corrected_signal_json",
        "primary_feature",
        "primary_feature_value",
        "concentration",
        "concentration_unit",
        // 项目量程和曲线标定范围具有不同科学语义，必须分别输出，不能再用一个
        // reliable_range 字段让下游软件猜测。
        "project_range_min",
        "project_range_max",
        "calibration_range_min",
        "calibration_range_max",
        "reliable_range_status",
        "background",
        "snr",
        "measurement_confidence",
        "signal_detectable",
        "quality_reliable",
        "measurement_quality_level",
        "geometry_source",
        "geometry_confidence",
        "geometry_flags",
        "photometry_flags",
        "quantification_status",
        "quantification_scope",
        "quantification_reason",
        "processor_name",
        "processor_version",
        "model_id",
        "model_name",
        "model_version",
        "model_snapshot_json",
        "qc_json",
        "quantification_qc_json"
    )

    /** 创建带 UTF-8 BOM 的 RFC 4180 CSV；每条 SiteMeasurement 对应且仅对应一行。 */
    fun createMeasurementsCsv(snapshot: ArrayResultSnapshot): ByteArray {
        val output = StringBuilder()
        output.append(csvColumns.joinToString(",", transform = ::escapeCsv))
            .append("\r\n")
        for (site in snapshot.sites.sortedBy { it.siteIndex }) {
            for (measurement in site.measurements.sortedBy { it.measurementId }) {
                val analyte = snapshot.analytes.firstOrNull {
                    it.analyteId == measurement.analyteId
                }
                val values = listOf(
                    snapshot.runId,
                    snapshot.projectId,
                    snapshot.projectName,
                    snapshot.runTimestampEpochMillis.toString(),
                    snapshot.runStatus,
                    snapshot.detectionMode,
                    snapshot.carrier.id,
                    snapshot.carrier.name,
                    snapshot.carrier.carrierType,
                    snapshot.carrier.version.toString(),
                    snapshot.rows.toString(),
                    snapshot.columns.toString(),
                    site.siteIndex.toString(),
                    site.rowIndex.toString(),
                    site.columnIndex.toString(),
                    site.siteKey,
                    site.enabled.toString(),
                    site.sampleSlot.orEmpty(),
                    site.defaultSampleSlot.orEmpty(),
                    site.roleCode.orEmpty(),
                    site.analyteId.orEmpty(),
                    measurement.measurementId.toString(),
                    measurement.analyteId.orEmpty(),
                    analyte?.name.orEmpty(),
                    measurement.rawSignalJson,
                    measurement.correctedSignalJson.orEmpty(),
                    measurement.primaryFeatureName,
                    measurement.primaryFeatureValue.csvNumber(),
                    measurement.concentrationValue.csvNumber(),
                    measurement.concentrationUnit.orEmpty(),
                    analyte?.projectRangeMin.csvNumber(),
                    analyte?.projectRangeMax.csvNumber(),
                    analyte?.calibrationRangeMin.csvNumber(),
                    analyte?.calibrationRangeMax.csvNumber(),
                    measurement.reliableRangeStatus.orEmpty(),
                    measurement.backgroundValue.csvNumber(),
                    measurement.signalToNoiseRatio.csvNumber(),
                    measurement.confidence.csvNumber(),
                    measurement.signalDetectable.toString(),
                    measurement.qualityReliable.toString(),
                    measurement.resolveQualityLevel(site).name,
                    site.geometry.source.name,
                    site.geometry.confidence.csvNumber(),
                    site.geometry.flags.map(Enum<*>::name).sorted().joinToString("|"),
                    measurement.qc.photometryFlags.sorted().joinToString("|"),
                    measurement.qc.quantificationStatus.orEmpty(),
                    measurement.qc.quantificationScope.orEmpty(),
                    measurement.qc.quantificationReason.orEmpty(),
                    measurement.processorName,
                    measurement.processorVersion,
                    analyte?.modelId.orEmpty(),
                    analyte?.modelName.orEmpty(),
                    analyte?.modelVersion?.toString().orEmpty(),
                    measurement.modelSnapshotJson.orEmpty(),
                    measurement.qcJson.orEmpty(),
                    measurement.quantificationQcJson.orEmpty()
                )
                check(values.size == csvColumns.size) {
                    "CSV column contract drift: ${values.size} != ${csvColumns.size}"
                }
                output.append(values.joinToString(",", transform = ::escapeCsv))
                    .append("\r\n")
            }
        }
        val csvBytes = output.toString().toByteArray(StandardCharsets.UTF_8)
        return UTF8_BOM + csvBytes
    }

    /** 便于测试和小型调用方直接获得 ZIP 字节；Android 文件保存优先使用 [writeArchive]。 */
    fun createArchive(
        snapshot: ArrayResultSnapshot,
        evidenceReader: ArrayExportEvidenceReader,
        supplementalFiles: Map<String, ByteArray> = emptyMap()
    ): ByteArray {
        return ByteArrayOutputStream().use { output ->
            writeArchive(snapshot, evidenceReader, output, supplementalFiles)
            output.toByteArray()
        }
    }

    /**
     * 直接流式写入 ZIP，避免原图较大时同时在内存中保留全部附件副本和完整 ZIP 副本。
     * manifest 写在归档末尾，因为附件读取完成后才能确定真实 SHA-256 与缺失状态。
     */
    fun writeArchive(
        snapshot: ArrayResultSnapshot,
        evidenceReader: ArrayExportEvidenceReader,
        output: OutputStream,
        supplementalFiles: Map<String, ByteArray> = emptyMap()
    ) {
        val staticFiles = linkedMapOf<String, ByteArray>()
        val entryRecords = mutableListOf<ArrayArchiveEntryRecord>()
        val evidenceRecords = mutableListOf<ArrayArchiveEvidenceRecord>()

        addFile(staticFiles, "measurements.csv", createMeasurementsCsv(snapshot))
        addFile(staticFiles, "snapshots/result-domain.json", jsonBytes(snapshot))
        addFile(staticFiles, "snapshots/run.json", jsonBytes(runSnapshot(snapshot)))
        addFile(staticFiles, "snapshots/models.json", jsonBytes(snapshot.analytes))
        addFile(
            staticFiles,
            "snapshots/template-effective-config.json",
            snapshot.effectiveConfigSnapshotJson.toByteArray(StandardCharsets.UTF_8)
        )
        addFile(
            staticFiles,
            "snapshots/frame-qc.json",
            snapshot.frame.frameQcJson.toByteArray(StandardCharsets.UTF_8)
        )
        addOptionalSnapshot(staticFiles, entryRecords, "snapshots/configuration-deviation.json", snapshot.configurationDeviationJson)
        addOptionalSnapshot(staticFiles, entryRecords, "snapshots/acquisition-metadata.json", snapshot.acquisitionMetadataJson)
        addOptionalSnapshot(staticFiles, entryRecords, "snapshots/processing-versions.json", snapshot.processingVersionJson)
        addOptionalSnapshot(staticFiles, entryRecords, "snapshots/model-usage.json", snapshot.modelUsageJson)
        addOptionalSnapshot(staticFiles, entryRecords, "snapshots/site-qc-summary.json", snapshot.siteQcSummaryJson)
        // Android协调层可注入由同一冻结快照生成的PDF、PNG和验证JSON；导出核心统一校验
        // 路径安全与重复项，并将其SHA-256写入manifest。
        supplementalFiles.toSortedMap().forEach { (path, bytes) ->
            addFile(staticFiles, path, bytes)
        }

        // ZIP 自身需要 close 以释放 Deflater，但不能提前关闭由系统文件选择器提供的外层流。
        ZipOutputStream(NonClosingOutputStream(output), StandardCharsets.UTF_8).use { zip ->
            for ((path, bytes) in staticFiles.toSortedMap()) {
                writeZipEntry(zip, path, bytes)
                entryRecords += includedEntry(path, bytes)
            }

            snapshot.artifacts.sortedBy { it.artifactId }.forEachIndexed { index, artifact ->
                val safeId = sanitizePathPart(artifact.artifactId)
                val prefix = index.toString().padStart(3, '0') + "_" + safeId
                val original = readEvidence(
                    artifactId = artifact.artifactId,
                    captureRole = artifact.captureRole,
                    evidenceKind = "original",
                    sourcePath = artifact.originalPath,
                    archivePath = "attachments/original/$prefix.${extensionOf(artifact.originalPath)}",
                    declaredSha256 = artifact.checksumSha256,
                    reader = evidenceReader
                )
                evidenceRecords += original.record
                original.bytes?.let { bytes ->
                    writeZipEntry(zip, original.record.archivePath, bytes)
                    entryRecords += includedEntry(original.record.archivePath, bytes)
                }

                artifact.derivedPath?.takeIf(String::isNotBlank)?.let { derivedPath ->
                    val derived = readEvidence(
                        artifactId = artifact.artifactId,
                        captureRole = artifact.captureRole,
                        evidenceKind = "derived",
                        sourcePath = derivedPath,
                        archivePath = "attachments/derived/$prefix.${extensionOf(derivedPath)}",
                        declaredSha256 = null,
                        reader = evidenceReader
                    )
                    evidenceRecords += derived.record
                    derived.bytes?.let { bytes ->
                        writeZipEntry(zip, derived.record.archivePath, bytes)
                        entryRecords += includedEntry(derived.record.archivePath, bytes)
                    }
                }
            }

            val manifest = ArrayArchiveManifest(
                schemaVersion = ARCHIVE_SCHEMA_VERSION,
                exporterVersion = EXPORTER_VERSION,
                csvSchemaVersion = CSV_SCHEMA_VERSION,
                runId = snapshot.runId,
                projectId = snapshot.projectId,
                projectName = snapshot.projectName,
                runTimestampEpochMillis = snapshot.runTimestampEpochMillis,
                runStatus = snapshot.runStatus,
                detectionMode = snapshot.detectionMode,
                carrierId = snapshot.carrier.id,
                carrierVersion = snapshot.carrier.version,
                rows = snapshot.rows,
                columns = snapshot.columns,
                physicalSiteCount = snapshot.sites.size,
                measurementCount = snapshot.sites.sumOf { it.measurements.size },
                entries = entryRecords.sortedBy(ArrayArchiveEntryRecord::archivePath),
                evidence = evidenceRecords.sortedWith(
                    compareBy(
                        ArrayArchiveEvidenceRecord::artifactId,
                        ArrayArchiveEvidenceRecord::evidenceKind
                    )
                )
            )
            writeZipEntry(zip, "manifest.json", jsonBytes(manifest))
        }
    }

    fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    private fun readEvidence(
        artifactId: String,
        captureRole: String,
        evidenceKind: String,
        sourcePath: String,
        archivePath: String,
        declaredSha256: String?,
        reader: ArrayExportEvidenceReader
    ): EvidencePayload {
        val bytes = runCatching { reader.readBytes(sourcePath) }.getOrNull()
        if (bytes == null) {
            return EvidencePayload(
                record = ArrayArchiveEvidenceRecord(
                    artifactId = artifactId,
                    captureRole = captureRole,
                    evidenceKind = evidenceKind,
                    sourcePath = sourcePath,
                    archivePath = archivePath,
                    status = "missing",
                    byteCount = null,
                    declaredSha256 = declaredSha256,
                    actualSha256 = null,
                    checksumMatches = null
                ),
                bytes = null
            )
        }
        val actualSha = sha256(bytes)
        return EvidencePayload(
            record = ArrayArchiveEvidenceRecord(
                artifactId = artifactId,
                captureRole = captureRole,
                evidenceKind = evidenceKind,
                sourcePath = sourcePath,
                archivePath = archivePath,
                status = "included",
                byteCount = bytes.size.toLong(),
                declaredSha256 = declaredSha256,
                actualSha256 = actualSha,
                checksumMatches = declaredSha256?.equals(actualSha, ignoreCase = true)
            ),
            bytes = bytes
        )
    }

    private fun addOptionalSnapshot(
        files: MutableMap<String, ByteArray>,
        entryRecords: MutableList<ArrayArchiveEntryRecord>,
        path: String,
        json: String?
    ) {
        if (json.isNullOrBlank()) {
            // 缺失可选快照进入清单，但不创建会被误认为真实证据的空 JSON 文件。
            entryRecords += ArrayArchiveEntryRecord(
                archivePath = path,
                status = "missing",
                byteCount = null,
                sha256 = null
            )
        } else {
            addFile(files, path, json.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun includedEntry(path: String, bytes: ByteArray): ArrayArchiveEntryRecord {
        return ArrayArchiveEntryRecord(
            archivePath = path,
            status = "included",
            byteCount = bytes.size.toLong(),
            sha256 = sha256(bytes)
        )
    }

    private fun addFile(files: MutableMap<String, ByteArray>, path: String, bytes: ByteArray) {
        require(path.isNotBlank() && !path.startsWith('/') && ".." !in path) {
            "Unsafe archive path: $path"
        }
        check(files.put(path, bytes) == null) { "Duplicate archive path: $path" }
    }

    private fun writeZipEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path).apply {
            // 固定时间戳，避免相同冻结快照因导出时刻不同而产生不必要的 ZIP 漂移。
            time = 0L
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun runSnapshot(snapshot: ArrayResultSnapshot): Map<String, Any> {
        return linkedMapOf(
            "runId" to snapshot.runId,
            "projectId" to snapshot.projectId,
            "projectName" to snapshot.projectName,
            "timestampEpochMillis" to snapshot.runTimestampEpochMillis,
            "status" to snapshot.runStatus,
            "detectionMode" to snapshot.detectionMode,
            "carrier" to snapshot.carrier,
            "rows" to snapshot.rows,
            "columns" to snapshot.columns,
            "frame" to snapshot.frame,
            "artifacts" to snapshot.artifacts
        )
    }

    private fun jsonBytes(value: Any): ByteArray {
        return gson.toJson(value).toByteArray(StandardCharsets.UTF_8)
    }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (value.any { it == ',' || it == '\"' || it == '\r' || it == '\n' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    private fun Double?.csvNumber(): String = this?.takeIf(Double::isFinite)?.toString().orEmpty()

    private fun sanitizePathPart(value: String): String {
        return value.map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
        }.joinToString("").trim('_').take(64).ifBlank { "artifact" }
    }

    private fun extensionOf(path: String): String {
        val withoutQuery = path.substringBefore('?').substringBefore('#')
        val extension = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
            .take(8)
        return extension.ifBlank { "bin" }
    }

    private data class EvidencePayload(
        val record: ArrayArchiveEvidenceRecord,
        val bytes: ByteArray?
    )

    /** 只把 close 转换为 flush，外层 OutputStream 的生命周期仍由调用方控制。 */
    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() {
            flush()
        }
    }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}

package com.muc.fluocolorquant.domain.result.export

import com.google.gson.Gson
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayCaptureEvidence
import com.muc.fluocolorquant.domain.result.ArrayCarrierResult
import com.muc.fluocolorquant.domain.result.ArrayFrameResult
import com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail
import com.muc.fluocolorquant.domain.result.ArrayMeasurementQc
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteGeometry
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CSV RFC 4180 契约与 ZIP 完整证据清单的纯 JVM 测试。 */
class ArrayResultExporterTest {

    @Test
    fun `CSV每条测量一行并正确转义逗号引号和换行`() {
        val snapshot = snapshot()

        val bytes = ArrayResultExporter.createMeasurementsCsv(snapshot)
        assertArrayEquals(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            bytes.copyOfRange(0, 3)
        )

        val rows = parseCsv(String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF"))
        assertEquals(3, rows.size)
        val rawSignalColumn = rows.first().indexOf("raw_signal_json")
        assertTrue(rawSignalColumn >= 0)
        assertEquals("{\"note\":\"a,b\\\"c\"}", rows[1][rawSignalColumn])
        assertEquals("{\"note\":\"line1\nline2\"}", rows[2][rawSignalColumn])
    }

    @Test
    fun `ZIP包含固定快照CSV附件校验和并记录缺失派生证据`() {
        val snapshot = snapshot()
        val originalBytes = "frozen-image".toByteArray(StandardCharsets.UTF_8)
        val archive = ArrayResultExporter.createArchive(
            snapshot = snapshot,
            evidenceReader = ArrayExportEvidenceReader { path ->
                if (path == "content://endpoint.jpg") originalBytes else null
            }
        )
        val entries = unzip(archive)

        assertTrue("manifest.json" in entries)
        assertTrue("measurements.csv" in entries)
        assertTrue("snapshots/result-domain.json" in entries)
        assertTrue("snapshots/template-effective-config.json" in entries)
        val originalEntry = entries.keys.single { it.startsWith("attachments/original/") }
        assertArrayEquals(originalBytes, entries.getValue(originalEntry))

        val manifest = Gson().fromJson(
            String(entries.getValue("manifest.json"), StandardCharsets.UTF_8),
            ArrayArchiveManifest::class.java
        )
        assertEquals(2, manifest.measurementCount)
        assertEquals("run-export", manifest.runId)
        assertEquals(ArrayResultExporter.CSV_SCHEMA_VERSION, manifest.csvSchemaVersion)
        val includedOriginal = manifest.evidence.single { it.evidenceKind == "original" }
        assertEquals("included", includedOriginal.status)
        assertEquals(ArrayResultExporter.sha256(originalBytes), includedOriginal.actualSha256)
        assertEquals(true, includedOriginal.checksumMatches)
        val missingDerived = manifest.evidence.single { it.evidenceKind == "derived" }
        assertEquals("missing", missingDerived.status)

        for (record in manifest.entries.filter { it.status == "included" }) {
            val payload = entries[record.archivePath]
            assertNotNull(payload)
            assertEquals(record.sha256, ArrayResultExporter.sha256(payload!!))
            assertEquals(record.byteCount, payload.size.toLong())
        }
        assertTrue(
            manifest.entries.any {
                it.archivePath == "snapshots/acquisition-metadata.json" && it.status == "missing"
            }
        )
    }

    @Test
    fun `流式ZIP写入不会提前关闭系统提供的目标流`() {
        val output = TrackingOutputStream()

        ArrayResultExporter.writeArchive(
            snapshot = snapshot(),
            evidenceReader = ArrayExportEvidenceReader { null },
            output = output
        )

        assertTrue(!output.closed)
        assertTrue("manifest.json" in unzip(output.bytes()))
    }

    private fun snapshot(): ArrayResultSnapshot {
        val analyte = ArrayAnalyteResult(
            analyteId = "analyte-cea",
            name = "CEA",
            displayOrder = 0,
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.0,
            reliableRangeMax = 100.0,
            modelId = "model-cea",
            modelName = "CEA curve",
            modelType = "STANDARD_CURVE",
            modelVersion = 2,
            primaryFeature = "DELTA_E_2000",
            processorName = "colorimetric-photometry",
            processorVersion = "v1"
        )
        val first = measurement(
            id = 1L,
            rawJson = "{\"note\":\"a,b\\\"c\"}",
            concentration = 12.5
        )
        val second = measurement(
            id = 2L,
            rawJson = "{\"note\":\"line1\nline2\"}",
            concentration = 25.0
        )
        val imageBytes = "frozen-image".toByteArray(StandardCharsets.UTF_8)
        return ArrayResultSnapshot(
            runId = "run-export",
            projectId = "project-export",
            projectName = "Export, project",
            runTimestampEpochMillis = 1_000L,
            runStatus = "Completed",
            detectionMode = "COLORIMETRIC",
            carrier = ArrayCarrierResult(
                id = "carrier-chip",
                name = "1×2 chip",
                carrierType = "MICROFLUIDIC_CHIP",
                version = 3,
                siteShape = "CIRCLE",
                orientationMarkerJson = null
            ),
            rows = 1,
            columns = 2,
            analytes = listOf(analyte),
            sites = listOf(
                site(0, first),
                site(1, second)
            ),
            frame = ArrayFrameResult(
                locatorName = "pg-grid",
                locatorVersion = "2.1",
                rectifiedWidth = 200,
                rectifiedHeight = 100,
                chipRegionMethod = "test",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 1.0,
                    trusted = true,
                    observedRatio = 1.0,
                    geometryRmsePx = 0.1,
                    inlierCount = 2,
                    outlierCount = 0,
                    meanConfidence = 0.98
                ),
                qcIssues = emptyList(),
                frameQcJson = "{\"frame\":true}"
            ),
            artifacts = listOf(
                ArrayCaptureEvidence(
                    artifactId = "endpoint/unsafe",
                    captureRole = "ENDPOINT",
                    originalPath = "content://endpoint.jpg",
                    derivedPath = "content://overlay.png",
                    capturedAtEpochMillis = 1_000L,
                    operatorId = "operator",
                    actualMetadataJson = null,
                    profileSnapshotJson = null,
                    imageQcJson = null,
                    checksumSha256 = ArrayResultExporter.sha256(imageBytes),
                    locked = true,
                    revision = 1
                )
            ),
            effectiveConfigSnapshotJson = "{\"templateVersion\":3}",
            configurationDeviationJson = "{}",
            acquisitionMetadataJson = null,
            processingVersionJson = "{\"processor\":\"v1\"}",
            modelUsageJson = "{\"analyte-cea\":{\"execution\":\"standard_curve_applied\"}}",
            siteQcSummaryJson = "{\"total\":2,\"reliable\":2}"
        )
    }

    private fun site(index: Int, measurement: ArraySiteMeasurementResult): ArrayPhysicalSiteResult {
        val point = GridPoint(index * 20.0 + 10.0, 10.0)
        return ArrayPhysicalSiteResult(
            siteIndex = index,
            rowIndex = 0,
            columnIndex = index,
            siteKey = "R01C0${index + 1}",
            enabled = true,
            roleCode = "SAMPLE",
            analyteId = "analyte-cea",
            defaultSampleSlot = "S${index + 1}",
            sampleSlot = "Sample, ${index + 1}",
            overrideReason = null,
            standardConcentration = null,
            repeatGroup = null,
            referenceScope = null,
            geometry = ArraySiteGeometry(
                rectified = point,
                original = point,
                confidence = 0.98,
                source = GridPointSource.CANDIDATE_REFINED,
                flags = emptySet()
            ),
            measurements = listOf(measurement)
        )
    }

    private fun measurement(
        id: Long,
        rawJson: String,
        concentration: Double
    ): ArraySiteMeasurementResult {
        return ArraySiteMeasurementResult(
            measurementId = id,
            analyteId = "analyte-cea",
            detectionMode = "COLORIMETRIC",
            primaryFeatureName = "DELTA_E_2000",
            primaryFeatureValue = concentration,
            concentrationValue = concentration,
            concentrationUnit = "ng/mL",
            reliableRangeStatus = "WITHIN_RANGE",
            backgroundValue = 1.0,
            signalToNoiseRatio = 12.0,
            confidence = 0.98,
            signalDetectable = true,
            qualityReliable = true,
            processorName = "colorimetric-photometry",
            processorVersion = "v1",
            modelSnapshotJson = "{\"modelVersion\":2}",
            rawSignalJson = rawJson,
            correctedSignalJson = "{\"corrected\":true}",
            qcJson = "{}",
            quantificationQcJson = "{\"status\":\"QUANTIFIED\"}",
            qc = ArrayMeasurementQc(
                geometrySourceCode = "CANDIDATE_REFINED",
                geometryFlags = emptySet(),
                photometryFlags = emptySet(),
                quantificationStatus = "QUANTIFIED",
                quantificationScope = null,
                quantificationReason = null
            ),
            detail = ArrayMeasurementDetail.LegacyUnparsed(rawJson, null)
        )
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes), StandardCharsets.UTF_8).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    /** 测试用最小 RFC 4180 解析器，确保带换行的 JSON 仍只占一个 CSV 字段。 */
    private fun parseCsv(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < csv.length) {
            val character = csv[index]
            when {
                character == '"' && quoted && index + 1 < csv.length && csv[index + 1] == '"' -> {
                    field.append('"')
                    index += 1
                }
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    row += field.toString()
                    field.clear()
                }
                character == '\r' && !quoted && index + 1 < csv.length && csv[index + 1] == '\n' -> {
                    row += field.toString()
                    field.clear()
                    rows += row.toList()
                    row.clear()
                    index += 1
                }
                else -> field.append(character)
            }
            index += 1
        }
        return rows
    }

    /** 模拟 SAF 输出流，验证 ZIP 完成时只 flush，最终 close 仍由页面层负责。 */
    private class TrackingOutputStream : OutputStream() {
        private val delegate = ByteArrayOutputStream()
        var closed: Boolean = false
            private set

        override fun write(value: Int) {
            check(!closed)
            delegate.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!closed)
            delegate.write(bytes, offset, length)
        }

        override fun close() {
            closed = true
        }

        fun bytes(): ByteArray = delegate.toByteArray()
    }
}

package com.muc.fluocolorquant.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.data.model.ResultValidationRecord
import com.muc.fluocolorquant.domain.result.validation.ResultBlandAltmanMetrics
import com.muc.fluocolorquant.domain.result.validation.ResultRegressionMetrics
import com.muc.fluocolorquant.domain.result.validation.ResultValidationEngine
import com.muc.fluocolorquant.domain.result.validation.ResultValidationPoint
import com.muc.fluocolorquant.domain.result.validation.ResultValidationSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface ResultValidationRepository {
    suspend fun getLatestByRun(runId: String): Map<String, ResultValidationSnapshot>

    suspend fun save(
        runId: String,
        analyteId: String,
        concentrationUnit: String,
        points: List<ResultValidationPoint>
    ): ResultValidationSnapshot
}
/** 验证记录的Room实现；保存和修订号分配位于同一个事务，避免快速重复点击产生冲突。 */
@Singleton
class ResultValidationRepositoryImpl @Inject constructor(
    private val database: AppDatabase
) : ResultValidationRepository {
    private val gson = Gson()

    override suspend fun getLatestByRun(runId: String): Map<String, ResultValidationSnapshot> {
        if (runId.isBlank()) return emptyMap()
        return database.resultValidationDao().getByRun(runId)
            .groupBy(ResultValidationRecord::analyteId)
            .mapNotNull { (analyteId, records) ->
                records.maxByOrNull(ResultValidationRecord::revision)
                    ?.toSnapshotOrNull()
                    ?.let { snapshot -> analyteId to snapshot }
            }
            .toMap()
    }

    override suspend fun save(
        runId: String,
        analyteId: String,
        concentrationUnit: String,
        points: List<ResultValidationPoint>
    ): ResultValidationSnapshot {
        require(runId.isNotBlank()) { "运行ID不能为空" }
        require(analyteId.isNotBlank()) { "分析物ID不能为空" }
        val validPoints = points.filter { point ->
            point.predictedValue.isFinite() && point.referenceValue.isFinite()
        }.sortedBy(ResultValidationPoint::siteIndex)
        val calculated = ResultValidationEngine.calculate(validPoints)
        val fingerprint = sha256(
            buildString {
                append(runId).append('|').append(analyteId).append('|').append(concentrationUnit)
                validPoints.forEach { point ->
                    append('|').append(point.siteIndex)
                        .append(':').append(point.predictedValue)
                        .append(':').append(point.referenceValue)
                }
            }
        )
        val record = database.withTransaction {
            val revision = (database.resultValidationDao()
                .getLatestRevision(runId, analyteId) ?: 0) + 1
            ResultValidationRecord(
                validationId = UUID.randomUUID().toString(),
                runId = runId,
                analyteId = analyteId,
                revision = revision,
                concentrationUnit = concentrationUnit,
                validationPointsJson = gson.toJson(validPoints),
                regressionResultJson = gson.toJson(calculated.regression),
                blandAltmanResultJson = gson.toJson(calculated.blandAltman),
                processorVersion = ResultValidationEngine.PROCESSOR_VERSION,
                inputFingerprint = fingerprint,
                createdAt = System.currentTimeMillis()
            ).also { newRecord -> database.resultValidationDao().insert(newRecord) }
        }
        return record.toSnapshotOrNull() ?: error("VALIDATION_RECORD_SERIALIZATION_FAILED")
    }

    private fun ResultValidationRecord.toSnapshotOrNull(): ResultValidationSnapshot? = runCatching {
        val pointType = object : TypeToken<List<ResultValidationPoint>>() {}.type
        ResultValidationSnapshot(
            validationId = validationId,
            runId = runId,
            analyteId = analyteId,
            revision = revision,
            concentrationUnit = concentrationUnit,
            points = gson.fromJson(validationPointsJson, pointType),
            regression = gson.fromJson(regressionResultJson, ResultRegressionMetrics::class.java),
            blandAltman = gson.fromJson(
                blandAltmanResultJson,
                ResultBlandAltmanMetrics::class.java
            ),
            processorVersion = processorVersion,
            inputFingerprint = inputFingerprint,
            createdAtEpochMillis = createdAt
        )
    }.getOrNull()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

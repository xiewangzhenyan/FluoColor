package com.muc.fluocolorquant.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.DualModalAdjudicationRecord
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.domain.result.ArrayResultErrorCode
import com.muc.fluocolorquant.domain.result.ArrayResultLoadResult
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalAdjudication
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalAdjudicationEngine
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalAdjudicationSnapshot
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalIncompatibility
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalPairCheck
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalReading
import com.muc.fluocolorquant.domain.result.dualmodal.DualModalThresholds
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 可与当前运行配对的另一模态运行。 */
data class DualModalPairingCandidate(
    val runId: String,
    val projectId: String,
    val projectName: String,
    val detectionMode: String,
    val timestampEpochMillis: Long
)

/** 当前运行有效的配对：最新判定修订与配对方的身份。 */
data class DualModalCurrentPairing(
    val snapshot: DualModalAdjudicationSnapshot,
    val counterpart: DualModalPairingCandidate?
)

sealed interface DualModalPairOutcome {
    data class Paired(val pairing: DualModalCurrentPairing) : DualModalPairOutcome
    data class Incompatible(val reasons: Set<DualModalIncompatibility>) : DualModalPairOutcome
    data class LoadFailed(val errorCode: ArrayResultErrorCode) : DualModalPairOutcome
}

/**
 * 双模态判定的数据边界。
 *
 * 判定是两次运行之上的派生分析：只读取两次运行的冻结快照，保存时只追加修订，
 * 不修改 DetectionRun、SiteMeasurement 或任何一侧的浓度。
 */
interface DualModalAdjudicationRepository {
    /** 当前运行最新且未撤销的判定；没有配对时为空。 */
    suspend fun getCurrent(runId: String): DualModalCurrentPairing?

    /** 同一用户、模式相反、网格与模板相同、已完成且有逐位点结果的运行，按时间倒序。 */
    suspend fun findCandidates(runId: String): List<DualModalPairingCandidate>

    /** 加载两次运行的快照，检查能否配对，计算并保存一条新的判定修订。 */
    suspend fun pair(runId: String, counterpartRunId: String): DualModalPairOutcome

    /** 追加一条撤销修订；当前没有有效配对时返回 false。 */
    suspend fun unpair(runId: String): Boolean
}

/** Room 实现；修订号分配与插入位于同一个事务，避免快速重复点击产生冲突。 */
@Singleton
class DualModalAdjudicationRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val arrayResults: ArrayResultRepository
) : DualModalAdjudicationRepository {
    private val gson = Gson()

    override suspend fun getCurrent(runId: String): DualModalCurrentPairing? {
        if (runId.isBlank()) return null
        val record = database.dualModalAdjudicationDao().getLatestForRun(runId) ?: return null
        if (record.revoked) return null
        val snapshot = record.toSnapshotOrNull() ?: return null
        val counterpartRunId = if (record.colorimetricRunId == runId) {
            record.fluorescenceRunId
        } else {
            record.colorimetricRunId
        }
        return DualModalCurrentPairing(snapshot = snapshot, counterpart = candidateOf(counterpartRunId))
    }

    override suspend fun findCandidates(runId: String): List<DualModalPairingCandidate> {
        if (runId.isBlank()) return emptyList()
        val run = database.detectionRunDao().getDetectionRunById(runId) ?: return emptyList()
        val project = database.projectDao().getProjectById(run.projectId) ?: return emptyList()
        val opposite = oppositeMode(project.detectionMode) ?: return emptyList()
        return database.projectDao().getProjectsByUserId(project.userId)
            .filter { other ->
                other.id != project.id &&
                    other.detectionMode.equals(opposite, ignoreCase = true) &&
                    other.rows == project.rows && other.columns == project.columns &&
                    sameTemplate(project, other)
            }
            .flatMap { other ->
                database.detectionRunDao().getDetectionRunsByProjectId(other.id)
                    .filter { candidate ->
                        candidate.status.equals(STATUS_COMPLETED, ignoreCase = true) &&
                            database.detectionRunDao().hasSiteMeasurements(candidate.runId)
                    }
                    .map { candidate -> candidate.toCandidate(other) }
            }
            .sortedByDescending(DualModalPairingCandidate::timestampEpochMillis)
    }

    override suspend fun pair(runId: String, counterpartRunId: String): DualModalPairOutcome {
        val first = when (val result = arrayResults.loadSnapshot(runId)) {
            is ArrayResultLoadResult.Success -> result.snapshot
            is ArrayResultLoadResult.Failure -> return DualModalPairOutcome.LoadFailed(result.errorCode)
        }
        val second = when (val result = arrayResults.loadSnapshot(counterpartRunId)) {
            is ArrayResultLoadResult.Success -> result.snapshot
            is ArrayResultLoadResult.Failure -> return DualModalPairOutcome.LoadFailed(result.errorCode)
        }
        val (colorimetric, fluorescence) = when (val check = DualModalAdjudicationEngine.check(first, second)) {
            is DualModalPairCheck.Incompatible -> return DualModalPairOutcome.Incompatible(check.reasons)
            is DualModalPairCheck.Compatible -> check.colorimetric to check.fluorescence
        }
        val adjudication = DualModalAdjudicationEngine.adjudicate(colorimetric, fluorescence, DualModalThresholds())
        val record = insertRevision(adjudication, revoked = false)
        val snapshot = record.toSnapshotOrNull() ?: error("DUAL_MODAL_RECORD_SERIALIZATION_FAILED")
        return DualModalPairOutcome.Paired(
            DualModalCurrentPairing(snapshot = snapshot, counterpart = candidateOf(counterpartRunId))
        )
    }

    override suspend fun unpair(runId: String): Boolean {
        if (runId.isBlank()) return false
        val latest = database.dualModalAdjudicationDao().getLatestForRun(runId) ?: return false
        if (latest.revoked) return false
        val adjudication = latest.toSnapshotOrNull()?.adjudication ?: return false
        insertRevision(adjudication, revoked = true)
        return true
    }

    private suspend fun insertRevision(adjudication: DualModalAdjudication, revoked: Boolean): DualModalAdjudicationRecord {
        val readingsJson = gson.toJson(adjudication.readings)
        val thresholdsJson = gson.toJson(adjudication.thresholds)
        val fingerprint = sha256(
            listOf(
                adjudication.ruleVersion,
                adjudication.colorimetricRunId,
                adjudication.fluorescenceRunId,
                thresholdsJson,
                readingsJson
            ).joinToString("|")
        )
        return database.withTransaction {
            val dao = database.dualModalAdjudicationDao()
            val revision = (dao.getLatestRevision(adjudication.colorimetricRunId, adjudication.fluorescenceRunId) ?: 0) + 1
            DualModalAdjudicationRecord(
                adjudicationId = UUID.randomUUID().toString(),
                colorimetricRunId = adjudication.colorimetricRunId,
                fluorescenceRunId = adjudication.fluorescenceRunId,
                revision = revision,
                revoked = revoked,
                ruleVersion = adjudication.ruleVersion,
                thresholdsJson = thresholdsJson,
                readingsJson = readingsJson,
                inputFingerprint = fingerprint,
                createdAt = System.currentTimeMillis()
            ).also { record -> dao.insert(record) }
        }
    }

    private suspend fun candidateOf(runId: String): DualModalPairingCandidate? {
        val run = database.detectionRunDao().getDetectionRunById(runId) ?: return null
        val project = database.projectDao().getProjectById(run.projectId) ?: return null
        return run.toCandidate(project)
    }

    private fun DetectionRun.toCandidate(project: Project) = DualModalPairingCandidate(
        runId = runId,
        projectId = project.id,
        projectName = project.name,
        detectionMode = project.detectionMode,
        timestampEpochMillis = timestamp.time
    )

    private fun DualModalAdjudicationRecord.toSnapshotOrNull(): DualModalAdjudicationSnapshot? = runCatching {
        val readingType = object : TypeToken<List<DualModalReading>>() {}.type
        DualModalAdjudicationSnapshot(
            adjudicationId = adjudicationId,
            revision = revision,
            createdAtEpochMillis = createdAt,
            inputFingerprint = inputFingerprint,
            adjudication = DualModalAdjudication(
                ruleVersion = ruleVersion,
                thresholds = gson.fromJson(thresholdsJson, DualModalThresholds::class.java),
                colorimetricRunId = colorimetricRunId,
                fluorescenceRunId = fluorescenceRunId,
                readings = gson.fromJson(readingsJson, readingType)
            )
        )
    }.getOrNull()

    private fun sameTemplate(a: Project, b: Project): Boolean {
        if (a.templateId != b.templateId) return false
        val va = a.templateVersion
        val vb = b.templateVersion
        return va == null || vb == null || va == vb
    }

    private fun oppositeMode(mode: String): String? = when (mode.uppercase()) {
        DualModalAdjudicationEngine.COLORIMETRIC -> DualModalAdjudicationEngine.FLUORESCENCE
        DualModalAdjudicationEngine.FLUORESCENCE -> DualModalAdjudicationEngine.COLORIMETRIC
        else -> null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val STATUS_COMPLETED = "Completed"
    }
}

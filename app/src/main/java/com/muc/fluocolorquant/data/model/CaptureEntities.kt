package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date
import java.util.UUID

/**
 * 一次检测运行中的原始采集附件。
 *
 * 比色和荧光当前只创建 ENDPOINT；单图光谱创建 SPECTRUM_SINGLE；后续 LSPR 可以在
 * 不修改检测运行表结构的前提下增加暗场、参考、目标物加入前基线和反应后终点。
 */
@Entity(
    tableName = "capture_artifacts",
    foreignKeys = [
        ForeignKey(
            entity = DetectionRun::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("runId"),
        Index(value = ["runId", "captureRole", "revision"]),
        Index("pairingKey")
    ]
)
data class CaptureArtifact(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val runId: String,
    val captureRole: String,
    val originalPath: String,
    val derivedPath: String? = null,
    val capturedAt: Date,
    val operatorId: String? = null,
    val actualMetadataJson: String? = null,
    val profileSnapshotJson: String? = null,
    val imageQcJson: String? = null,
    val checksumSha256: String? = null,
    val pairingKey: String? = null,
    val locked: Boolean = false,
    val revision: Int = 1,
    val supersedesArtifactId: String? = null
)

/**
 * 模态无关的单个位点科学测量结果。
 *
 * 旧 [WellResult] 暂时保留以降低迁移风险；新算法把原始信号、校正信号、主特征和
 * 可靠性分开保存，避免只记录最终浓度而丢失可解释证据。
 */
@Entity(
    tableName = "site_measurements",
    foreignKeys = [
        ForeignKey(
            entity = DetectionRun::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Analyte::class,
            parentColumns = ["id"],
            childColumns = ["analyteId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("runId"),
        Index("analyteId"),
        Index(value = ["runId", "siteIndex", "analyteId"])
    ]
)
data class SiteMeasurement(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runId: String,
    val siteIndex: Int,
    val analyteId: String? = null,
    val detectionMode: String,
    val rawSignalJson: String,
    val correctedSignalJson: String? = null,
    val primaryFeatureName: String,
    val primaryFeatureValue: Double? = null,
    val backgroundValue: Double? = null,
    val signalToNoiseRatio: Double? = null,
    val confidence: Double? = null,
    val signalDetectable: Boolean,
    val qualityReliable: Boolean,
    val qcJson: String? = null,
    val processorName: String,
    val processorVersion: String
)

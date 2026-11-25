package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 比色—荧光双模态判定记录：把同一块芯片的比色运行与荧光运行配对后得到的派生分析。
 *
 * 记录采用修订式保存：重新判定或解除配对都追加新修订，不覆盖旧记录，也不修改两次运行
 * 冻结的浓度。规则版本、阈值与逐样本判定保存为 JSON，页面总是按一次完整判定读取。
 * 第 20 版起，同一修订还可冻结一份 DualNet 网络判读（[networkJson]）；旧修订该列为空。
 * 任一侧运行被删除时，对应记录随之级联删除。
 */
@Entity(
    tableName = "dual_modal_adjudication_records",
    foreignKeys = [
        ForeignKey(
            entity = DetectionRun::class,
            parentColumns = ["runId"],
            childColumns = ["colorimetricRunId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DetectionRun::class,
            parentColumns = ["runId"],
            childColumns = ["fluorescenceRunId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("colorimetricRunId"),
        Index("fluorescenceRunId"),
        Index(value = ["colorimetricRunId", "fluorescenceRunId", "revision"], unique = true)
    ]
)
data class DualModalAdjudicationRecord(
    @PrimaryKey val adjudicationId: String,
    val colorimetricRunId: String,
    val fluorescenceRunId: String,
    val revision: Int,
    /** 解除配对时追加一条撤销修订；最新修订被撤销即视为未配对。 */
    val revoked: Boolean,
    val ruleVersion: String,
    val thresholdsJson: String,
    val readingsJson: String,
    val inputFingerprint: String,
    val createdAt: Long,
    /** DualNet 网络判读的冻结 JSON；第 19 版及更早的修订、或判定时网络未运行，均为空。 */
    val networkJson: String? = null
)

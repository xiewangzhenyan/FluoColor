package com.muc.fluocolorquant.data

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.muc.fluocolorquant.R

/**
 * 在所有构建（含 Release）幂等播种一套起始实验资源。
 *
 * 解决的问题：重构后新建项目必须引用已发布的采集设备档案与载体档案，导致普通用户被迫
 * 先进入“载体与布局库”创建基础几何。本回调预置 10×10、15×15、96 三个常用载体，
 * 并提供一个明确标注“未标定探索”的采集占位档案；正式定量模型仍需绑定实验室验证过的
 * 采集设备版本，不能依赖占位档案绕过科学兼容检查。
 *
 * 与 [MicrofluidicDemoDatabaseCallback] 的区别：演示回调仅在 Debug 注册、数据标记为
 * “非真实定量”，且附带演示模型/模板；本回调只播种采集档案与载体档案这类无科学定量含义
 * 的基础资源，因此可以安全进入正式数据库。全部使用 [BuiltInResourceIds] 固定 ID 并以
 * `INSERT OR IGNORE` 写入，对新库与既有库都安全，无需提升 Room 版本。
 */
class DefaultResourceDatabaseCallback(
    context: Context
) : RoomDatabase.Callback() {

    private val appContext = context.applicationContext

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)

        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            insertDefaultAcquisitionProfile(db, now)
            insertMicrofluidicCarrier(
                db,
                id = BuiltInResourceIds.CARRIER_MICROFLUIDIC_10X10_ID,
                nameRes = R.string.builtin_carrier_10x10_name,
                rows = 10,
                columns = 10,
                locatorJson = BuiltInResourceIds.PG_GRID_DARK_LOCATOR_JSON,
                now = now
            )
            insertMicrofluidicCarrier(
                db,
                id = BuiltInResourceIds.CARRIER_MICROFLUIDIC_15X15_ID,
                nameRes = R.string.builtin_carrier_15x15_name,
                rows = 15,
                columns = 15,
                // 10×10 与 15×15 都是玻璃表面激光加工的方块结构，不能按规格臆测亮暗。
                // 当前已知实物为亮背景上的暗目标，因此两种规格使用同一暗目标配置。
                locatorJson = BuiltInResourceIds.PG_GRID_DARK_LOCATOR_JSON,
                now = now
            )
            insertPlateCarrier(db, now)
            repairLegacyBuiltInSemantics(db, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 未标定探索采集档案；允许建草稿，但正式模型必须由用户显式选择兼容设备。 */
    private fun insertDefaultAcquisitionProfile(db: SupportSQLiteDatabase, now: Long) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO acquisition_profiles (
                id, name, supportedModesJson, compatibleCarrierTypesJson,
                deviceMatcherJson, opticalModuleName, fixtureId,
                cameraControlStrategy, cameraConstraintsJson, imageQcProfileJson,
                status, version, createdAt, updatedAt
            ) VALUES (?, ?, ?, ?, NULL, NULL, NULL, 'AUTO_AND_LOCK', NULL, NULL,
                'ACTIVE', 1, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                BuiltInResourceIds.DEFAULT_ACQUISITION_ID,
                appContext.getString(R.string.builtin_acquisition_name),
                BuiltInResourceIds.DEFAULT_SUPPORTED_MODES_JSON,
                BuiltInResourceIds.DEFAULT_COMPATIBLE_CARRIER_TYPES_JSON,
                now,
                now
            )
        )
    }

    /** 微流控方形阵列载体，locatorConfigJson 只保存已确认的目标亮暗极性。 */
    private fun insertMicrofluidicCarrier(
        db: SupportSQLiteDatabase,
        id: String,
        nameRes: Int,
        rows: Int,
        columns: Int,
        locatorJson: String,
        now: Long
    ) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO carrier_profiles (
                id, name, carrierType, rows, columns, siteShape,
                orientationMarkerJson, roiConfigJson, locatorConfigJson,
                status, version, createdAt, updatedAt
            ) VALUES (?, ?, 'MICROFLUIDIC_CHIP', ?, ?, 'SQUARE', NULL, NULL, ?,
                'ACTIVE', 1, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                id,
                appContext.getString(nameRes),
                rows,
                columns,
                locatorJson,
                now,
                now
            )
        )
    }

    /** 兼容旧孔板流程的 96 孔（8×12）圆形位点载体；孔板走旧定位链路，无需极性配置。 */
    private fun insertPlateCarrier(db: SupportSQLiteDatabase, now: Long) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO carrier_profiles (
                id, name, carrierType, rows, columns, siteShape,
                orientationMarkerJson, roiConfigJson, locatorConfigJson,
                status, version, createdAt, updatedAt
            ) VALUES (?, ?, 'PLATE', 8, 12, 'CIRCLE', NULL, NULL, NULL,
                'ACTIVE', 1, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                BuiltInResourceIds.CARRIER_PLATE_96_ID,
                appContext.getString(R.string.builtin_carrier_96_name),
                now,
                now
            )
        )
    }

    /**
     * 修正早期 Claude 版本把 15×15 规格写死为亮目标的已播种数据。
     *
     * 仅当固定内置 ID 仍保存“旧内置亮目标 JSON”时更新；用户通过版本库创建的其他亮目标
     * 载体具有不同 ID，不会被触碰。这样已有数据库无需清库，也能使用当前确认的暗方块实物
     * 语义，同时保留用户自建载体的完整自主性。
     */
    private fun repairLegacyBuiltInSemantics(db: SupportSQLiteDatabase, now: Long) {
        db.execSQL(
            """
            UPDATE carrier_profiles
            SET locatorConfigJson = ?, updatedAt = ?
            WHERE id = ? AND locatorConfigJson = ?
            """.trimIndent(),
            arrayOf<Any>(
                BuiltInResourceIds.PG_GRID_DARK_LOCATOR_JSON,
                now,
                BuiltInResourceIds.CARRIER_MICROFLUIDIC_15X15_ID,
                LEGACY_15X15_BRIGHT_LOCATOR_JSON
            )
        )
    }

    companion object {
        /** 仅用于识别并迁移旧内置错误值，不能再用于创建新载体。 */
        private const val LEGACY_15X15_BRIGHT_LOCATOR_JSON =
            "{\"schemaVersion\":\"pg-grid-carrier-v1\",\"targetPolarity\":\"BRIGHT\"}"
    }
}

package com.muc.fluocolorquant.data

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.muc.fluocolorquant.R

/**
 * 为 Debug 构建幂等补齐一套可直接演示的 10×10 微流控荧光检测资源。
 *
 * 这套数据只解决“新安装应用没有已发布资源，无法立即走通完整流程”的演示阻断：
 * 载体、采集档案、分析模型和实验模板均使用固定 ID，并在名称和说明中明确标记为演示。
 * 标准曲线采用 y=x，仅用于验证定位、光度、定量、Room 保存和结果页导航，绝不能用于
 * 真实实验结论。正式 Release 构建不会注册本回调，因此不会污染实验室正式数据。
 */
class MicrofluidicDemoDatabaseCallback(
    context: Context
) : RoomDatabase.Callback() {

    private val appContext = context.applicationContext

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)

        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val analyteId = findOrCreateDemoAnalyte(db)
            insertCarrier(db, now)
            insertAcquisitionProfile(db, now)
            insertAnalysisModel(db, analyteId, now)
            insertExperimentTemplate(db, analyteId, now)
            insertTemplateSites(db, analyteId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 优先复用预置 CEA，避免同一数据库出现两个同名分析物。 */
    private fun findOrCreateDemoAnalyte(db: SupportSQLiteDatabase): String {
        val analyteName = appContext.getString(R.string.demo_microfluidic_analyte_name)
        db.query(
            "SELECT id FROM analytes WHERE name = ? ORDER BY rowid LIMIT 1",
            arrayOf<Any>(analyteName)
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }

        db.execSQL(
            "INSERT OR IGNORE INTO analytes (id, name) VALUES (?, ?)",
            arrayOf<Any>(DEMO_ANALYTE_ID, analyteName)
        )
        return DEMO_ANALYTE_ID
    }

    /** 写入与 PG-Grid 测试图一致的暗目标、10×10 方形阵列载体。 */
    private fun insertCarrier(db: SupportSQLiteDatabase, now: Long) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO carrier_profiles (
                id, name, carrierType, rows, columns, siteShape,
                orientationMarkerJson, roiConfigJson, locatorConfigJson,
                status, version, createdAt, updatedAt
            ) VALUES (?, ?, 'MICROFLUIDIC_CHIP', 10, 10, 'SQUARE', NULL, NULL, ?,
                'ACTIVE', 1, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                DEMO_CARRIER_ID,
                appContext.getString(R.string.demo_microfluidic_carrier_name),
                PG_GRID_DARK_LOCATOR_JSON,
                now,
                now
            )
        )
    }

    /** 写入不绑定具体手机型号的演示采集档案，便于模拟器导入测试图。 */
    private fun insertAcquisitionProfile(db: SupportSQLiteDatabase, now: Long) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO acquisition_profiles (
                id, name, supportedModesJson, compatibleCarrierTypesJson,
                deviceMatcherJson, opticalModuleName, fixtureId,
                cameraControlStrategy, cameraConstraintsJson, imageQcProfileJson,
                status, version, createdAt, updatedAt
            ) VALUES (?, ?, '["FLUORESCENCE"]', '["MICROFLUIDIC_CHIP"]',
                NULL, ?, NULL, 'AUTO_LOCKED', NULL, ?, 'ACTIVE', 1, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                DEMO_ACQUISITION_ID,
                appContext.getString(R.string.demo_microfluidic_acquisition_name),
                appContext.getString(R.string.demo_microfluidic_optical_module_name),
                DEMO_IMAGE_QC_JSON,
                now,
                now
            )
        )
    }

    /**
     * 写入与当前荧光处理器严格匹配的发布态模型及线性演示曲线。
     *
     * 主特征使用荧光 SNR，可靠范围覆盖处理器 0～9999 的稳定输出；因此合成测试图可以
     * 生成可展示的数值，而名称和验证元数据仍明确声明其不具备真实浓度学意义。
     */
    private fun insertAnalysisModel(
        db: SupportSQLiteDatabase,
        analyteId: String,
        now: Long
    ) {
        val unit = appContext.getString(R.string.demo_microfluidic_unit)
        db.execSQL(
            """
            INSERT OR IGNORE INTO analysis_models (
                id, name, modelType, analyteId, detectionMode, inputProtocol,
                primaryFeature, processorName, processorVersion,
                compatibleCarrierTypesJson, compatibleAcquisitionProfileIdsJson,
                concentrationUnit, reliableRangeMin, reliableRangeMax,
                validationMetricsJson, status, version, createdAt, updatedAt
            ) VALUES (?, ?, 'STANDARD_CURVE', ?, 'FLUORESCENCE', 'ENDPOINT_ONLY',
                'FLUORESCENCE_SNR', 'fluorescence-photometry', 'v1',
                '["MICROFLUIDIC_CHIP"]', ?, ?, 0.0, 10000.0, ?,
                'PUBLISHED', 1, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                DEMO_MODEL_ID,
                appContext.getString(R.string.demo_microfluidic_model_name),
                analyteId,
                "[\"$DEMO_ACQUISITION_ID\"]",
                unit,
                DEMO_VALIDATION_JSON,
                now,
                now
            )
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO standard_curve_definitions (
                analysisModelId, fittingFunction, parametersJson,
                monotonicDirection, inverseRuleJson, lod, loq
            ) VALUES (?, 'linear', '{"a":1.0,"b":0.0}', 'INCREASING', NULL, NULL, NULL)
            """.trimIndent(),
            arrayOf<Any>(DEMO_MODEL_ID)
        )
    }

    /** 写入已发布模板和 CEA 分析物配置，使新建项目页能够立即选择。 */
    private fun insertExperimentTemplate(
        db: SupportSQLiteDatabase,
        analyteId: String,
        now: Long
    ) {
        val unit = appContext.getString(R.string.demo_microfluidic_unit)
        db.execSQL(
            """
            INSERT OR IGNORE INTO experiment_templates (
                id, templateName, analyteId, reagentAntigenId, reagentAntibodyId,
                fkCurveModelId, reliableRangeMin, reliableRangeMax, concentrationUnit,
                defaultLayoutJson, createdAt, updatedAt, version, status,
                carrierProfileId, detectionMode, readoutLayout, acquisitionProfileId,
                inputProtocol, qcProfileJson, publishedAt, purpose, versionNote
            ) VALUES (?, ?, NULL, NULL, NULL, NULL, 0.0, 10000.0, ?, NULL,
                ?, ?, 1, 'PUBLISHED', ?, 'FLUORESCENCE', 'GRID_SITES', ?,
                'ENDPOINT_ONLY', ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                DEMO_TEMPLATE_ID,
                appContext.getString(R.string.demo_microfluidic_template_name),
                unit,
                now,
                now,
                DEMO_CARRIER_ID,
                DEMO_ACQUISITION_ID,
                DEMO_TEMPLATE_QC_JSON,
                now,
                appContext.getString(R.string.demo_microfluidic_template_purpose),
                appContext.getString(R.string.demo_microfluidic_template_version_note)
            )
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO template_analyte_configs (
                id, templateId, analyteId, reagentAntigenId, reagentAntibodyId,
                analysisModelId, concentrationUnit, reliableRangeMin,
                reliableRangeMax, displayOrder, displayConfigJson
            ) VALUES (?, ?, ?, NULL, NULL, ?, ?, 0.0, 10000.0, 0, ?)
            """.trimIndent(),
            arrayOf<Any>(
                DEMO_TEMPLATE_ANALYTE_CONFIG_ID,
                DEMO_TEMPLATE_ID,
                analyteId,
                DEMO_MODEL_ID,
                unit,
                FLUORESCENCE_GREEN_DISPLAY_JSON
            )
        )
    }

    /**
     * 生成完整 100 位点布局：R01C01 作为空白，其余位点归入同一个演示样本。
     *
     * 使用单一样本槽可以让新建项目页把 99 个样本位合并成一条摘要，避免组会演示时
     * 逐位点录入；底层仍会对全部 100 个物理位点分别定位、计算并保存结果。
     */
    private fun insertTemplateSites(db: SupportSQLiteDatabase, analyteId: String) {
        val sampleSlot = appContext.getString(R.string.demo_microfluidic_sample_slot)
        repeat(DEMO_ROWS) { row ->
            repeat(DEMO_COLUMNS) { column ->
                val isBlank = row == 0 && column == 0
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO template_site_assignments (
                        id, templateId, rowIndex, columnIndex, analyteId, roleType,
                        standardConcentration, repeatGroup, defaultSampleSlot,
                        referenceScope, enabled
                    ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, 1)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        "$DEMO_TEMPLATE_ID-site-$row-$column",
                        DEMO_TEMPLATE_ID,
                        row,
                        column,
                        analyteId,
                        if (isBlank) "BLANK" else "SAMPLE",
                        if (isBlank) null else sampleSlot,
                        if (isBlank) "ANALYTE" else null
                    )
                )
            }
        }
    }

    private companion object {
        const val DEMO_ROWS = 10
        const val DEMO_COLUMNS = 10
        const val DEMO_ANALYTE_ID = "demo-analyte-cea"
        const val DEMO_CARRIER_ID = "demo-carrier-microfluidic-10x10-v1"
        const val DEMO_ACQUISITION_ID = "demo-acquisition-fluorescence-v1"
        const val DEMO_MODEL_ID = "demo-model-cea-fluorescence-v1"
        const val DEMO_TEMPLATE_ID = "demo-template-cea-fluorescence-10x10-v1"
        const val DEMO_TEMPLATE_ANALYTE_CONFIG_ID = "demo-template-cea-config-v1"

        const val PG_GRID_DARK_LOCATOR_JSON =
            "{\"schemaVersion\":\"pg-grid-carrier-v1\",\"targetPolarity\":\"DARK\"}"
        const val FLUORESCENCE_GREEN_DISPLAY_JSON =
            "{\"schemaVersion\":\"fluorescence-display-v1\",\"fluorescenceChannel\":\"GREEN\"}"
        const val DEMO_IMAGE_QC_JSON =
            "{\"schemaVersion\":\"demo-image-qc-v1\",\"demoOnly\":true}"
        const val DEMO_TEMPLATE_QC_JSON =
            "{\"schemaVersion\":\"demo-template-qc-v1\",\"demoOnly\":true}"
        const val DEMO_VALIDATION_JSON =
            "{\"demoOnly\":true,\"notForRealQuantification\":true}"
    }
}

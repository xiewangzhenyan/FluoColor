package com.muc.fluocolorquant.domain.result.dualmodal.network

import com.google.gson.Gson
import com.muc.fluocolorquant.data.migration.DatabaseMigrations
import java.io.File
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网络判读随判定修订冻结为 JSON；仓库用默认 Gson 读写，这里保证往返无损，并核对第 20 版表结构。 */
class DualNetAssessmentJsonTest {

    @Test
    fun `网络判读经 Gson 往返后逐字段相同`() {
        val head = { h: DualNetHead, available: Boolean ->
            DualNetHeadReading(h, available, if (available) 12.5 else null, if (available) 0.05 else null, available)
        }
        val assessment = DualNetAssessment(
            analytes = listOf(
                DualNetAnalyteAssessment(
                    analyteId = "cea",
                    status = DualNetStatus.COMPLETED,
                    unavailableReason = null,
                    calibrationColorimetricRunId = "cal-col",
                    calibrationFluorescenceRunId = "cal-flu",
                    recalibrations = listOf(DualNetRecalibration(DualNetHead.FUSED, 0.0125, 0.9456, 7)),
                    readings = listOf(
                        DualNetReading(
                            analyteId = "cea",
                            sampleKey = "S01",
                            siteCount = 15,
                            colorimetric = head(DualNetHead.COLORIMETRIC, true),
                            fluorescence = head(DualNetHead.FLUORESCENCE, false),
                            fused = head(DualNetHead.FUSED, false),
                            deltaPercent = null,
                            decision = DualNetDecision.ADOPT_COLORIMETRIC,
                            reason = DualNetDecisionReason.COLORIMETRIC_FALLBACK,
                            suggestedConcentration = 12.5,
                            withinEvaluatedRange = true
                        )
                    )
                ),
                DualNetAnalyteAssessment(
                    analyteId = "afp",
                    status = DualNetStatus.UNAVAILABLE,
                    unavailableReason = DualNetUnavailableReason.CALIBRATION_RUNS_NOT_FOUND,
                    calibrationColorimetricRunId = null,
                    calibrationFluorescenceRunId = null,
                    recalibrations = emptyList(),
                    readings = emptyList()
                )
            )
        )
        val gson = Gson()

        assertEquals(assessment, gson.fromJson(gson.toJson(assessment), DualNetAssessment::class.java))
    }

    @Test
    fun `第 20 版只为判定修订表追加可空的 networkJson 列`() {
        assertEquals(19, DatabaseMigrations.MIGRATION_19_20.startVersion)
        assertEquals(20, DatabaseMigrations.MIGRATION_19_20.endVersion)
        val schema = JsonParser.parseString(
            File("schemas/com.muc.fluocolorquant.data.AppDatabase/20.json").readText(Charsets.UTF_8)
        ).asJsonObject.getAsJsonObject("database")
        assertEquals(20, schema["version"].asInt)
        val table = schema.getAsJsonArray("entities").map { it.asJsonObject }
            .single { it["tableName"].asString == "dual_modal_adjudication_records" }
        val column = table.getAsJsonArray("fields").map { it.asJsonObject }.single { it["columnName"].asString == "networkJson" }
        assertEquals("TEXT", column["affinity"].asString)
        assertFalse(column["notNull"].asBoolean)
        val previous = JsonParser.parseString(
            File("schemas/com.muc.fluocolorquant.data.AppDatabase/19.json").readText(Charsets.UTF_8)
        ).asJsonObject.getAsJsonObject("database").getAsJsonArray("entities").map { it.asJsonObject }
            .single { it["tableName"].asString == "dual_modal_adjudication_records" }
        // 除新增列外，其余列与第 19 版逐一相同，迁移只需 ADD COLUMN。
        assertEquals(
            previous.getAsJsonArray("fields"),
            com.google.gson.JsonArray().also { array ->
                table.getAsJsonArray("fields").filter { it.asJsonObject["columnName"].asString != "networkJson" }.forEach(array::add)
            }
        )
        assertTrue(table["createSql"].asString.contains("`networkJson` TEXT"))
    }
}

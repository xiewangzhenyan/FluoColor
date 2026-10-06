package com.muc.fluocolorquant.domain.result.dualmodal.network

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot

/**
 * 从运行冻结的配置快照里追溯"本批标定板"。
 *
 * DualNet 必须用同批标定板逐批重标定（W3：不重标定时洁净样本静默错误约 12%）。测试运行定量所用的
 * 标准曲线在冻结快照中保留了全部标定点，每个标定点记着产生它的标定运行 runId；现场拟合与从曲线库
 * 选用的曲线都是如此。这里只读冻结快照，不读取当前曲线库，避免曲线被修改后历史判读跟着漂移。
 */
object DualNetCalibrationRuns {

    /** 返回该分析物曲线的全部标定运行 runId；快照缺字段或解析失败时为空集合。 */
    fun runIds(snapshot: ArrayResultSnapshot, analyteId: String): Set<String> = runCatching {
        val root = JsonParser.parseString(snapshot.effectiveConfigSnapshotJson).asJsonObject
        val analytes = root.getAsJsonArray("analytes") ?: return@runCatching emptySet()
        analytes.asSequence()
            .map(JsonElement::getAsJsonObject)
            .filter { entry -> entry.getAsJsonObject("analyte")?.get("id")?.asString == analyteId }
            .flatMap { entry ->
                entry.getAsJsonObject("analysisModel")
                    ?.getAsJsonArray("calibrationPoints")
                    ?.asSequence()
                    ?.map(JsonElement::getAsJsonObject)
                    ?.filterNot { point -> point.get("excluded")?.takeIf { !it.isJsonNull }?.asBoolean == true }
                    ?.mapNotNull { point -> point.get("runId")?.takeIf { !it.isJsonNull }?.asString?.takeIf(String::isNotBlank) }
                    ?: emptySequence()
            }
            .toSet()
    }.getOrDefault(emptySet())
}

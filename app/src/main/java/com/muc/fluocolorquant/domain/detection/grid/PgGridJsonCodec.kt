package com.muc.fluocolorquant.domain.detection.grid

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException

/**
 * PG-Grid V2.1 JSON 的唯一编解码入口。
 *
 * 统一入口保证数据库快照、Android/Python 金标准和后续导出使用相同的枚举编码与严格
 * 校验。调用方不得直接 new Gson() 解析该科学契约，以免未知版本或点数损坏被忽略。
 */
object PgGridJsonCodec {
    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .create()

    /** 编码前再次验证，防止 copy() 产生的不完整对象进入数据库。 */
    fun encode(result: PgGridResult): String {
        return gson.toJson(result.requireValid())
    }

    /**
     * 解码后执行完整结构验证；所有错误统一暴露为 [IllegalArgumentException]，使领域层
     * 不依赖 Gson 的具体异常类型，也便于 UI 映射为“历史结果损坏/版本不兼容”。
     */
    fun decode(json: String): PgGridResult {
        require(json.isNotBlank()) { "PG-Grid JSON 不能为空" }
        val result = try {
            gson.fromJson(json, PgGridResult::class.java)
                ?: throw IllegalArgumentException("PG-Grid JSON 解析结果为空")
        } catch (error: JsonParseException) {
            throw IllegalArgumentException("PG-Grid JSON 格式无效", error)
        } catch (error: NullPointerException) {
            throw IllegalArgumentException("PG-Grid JSON 缺少必需字段", error)
        }
        return result.requireValid()
    }
}

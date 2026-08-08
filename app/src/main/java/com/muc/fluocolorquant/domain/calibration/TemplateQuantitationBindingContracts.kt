package com.muc.fluocolorquant.domain.calibration

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 模板绑定中冻结的资源摘要。
 *
 * 标准曲线包含函数、参数和原始标准点；深度学习包含文件名、SHA-256、输入尺寸与归一化
 * 协议，但不复制模型二进制。信号模式同样保留处理器版本，方便历史解释。
 */
data class TemplateQuantitationResourceSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val quantitation: AnalyteQuantitationSnapshot,
    val analysisModel: AnalysisModelBundle
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/** 模板定量摘要的唯一 JSON 编解码入口。 */
object TemplateQuantitationResourceSnapshotCodec {
    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .create()

    fun encode(snapshot: TemplateQuantitationResourceSnapshot): String {
        require(snapshot.schemaVersion == TemplateQuantitationResourceSnapshot.CURRENT_SCHEMA_VERSION) {
            "不支持写入模板定量摘要版本"
        }
        return gson.toJson(snapshot)
    }

    fun decode(json: String): TemplateQuantitationResourceSnapshot {
        require(json.isNotBlank()) { "模板定量摘要不能为空" }
        val decoded = runCatching {
            gson.fromJson(json, TemplateQuantitationResourceSnapshot::class.java)
        }.getOrElse { error ->
            throw IllegalArgumentException("模板定量摘要JSON无效", error)
        } ?: throw IllegalArgumentException("模板定量摘要解析结果为空")
        require(
            decoded.schemaVersion == TemplateQuantitationResourceSnapshot.CURRENT_SCHEMA_VERSION
        ) { "不支持的模板定量摘要版本" }
        return decoded
    }
}

/** 绑定内容指纹不包含模板 ID 和数据库主键，同一科学方案可以稳定去重与审计。 */
object TemplateQuantitationBindingFingerprint {
    fun create(snapshot: TemplateQuantitationResourceSnapshot): String {
        val canonical = TemplateQuantitationResourceSnapshotCodec.encode(snapshot)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

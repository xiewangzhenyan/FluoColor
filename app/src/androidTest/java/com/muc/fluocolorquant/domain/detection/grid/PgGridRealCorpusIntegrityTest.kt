package com.muc.fluocolorquant.domain.detection.grid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 实拍语料的完整性回归。
 *
 * “固定语料”的科学价值完全建立在**输入字节永不改动**之上：只有输入被钉死，输出的任何
 * 变化才能 100% 归因于代码。扰动语料一直有 SHA-256 校验，实拍语料此前只在 manifest 中
 * 记录了摘要却没有人验证，等于把这条保证写在纸上而没有执行。本测试补齐执行。
 *
 * 一旦有人重新压缩、旋转或替换了某张实拍图，这里会立即失败，而不是让下游的支撑率、
 * 分割数量门槛悄悄漂移之后再去猜原因。
 */
@RunWith(AndroidJUnit4::class)
class PgGridRealCorpusIntegrityTest {

    @Test
    fun `实拍语料每张图片的字节与冻结摘要一致`() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val manifest = Gson().fromJson(
            context.assets.open(MANIFEST_ASSET).use { it.readBytes().toString(Charsets.UTF_8) },
            RealCorpusManifest::class.java
        )

        assertEquals(EXPECTED_SCHEMA, manifest.schemaVersion)
        assertEquals(
            "语料应包含 6 张未裁切原图与 6 张紧裁派生图",
            EXPECTED_CASE_COUNT,
            manifest.cases.size
        )

        val seen = hashSetOf<String>()
        manifest.cases.forEach { case ->
            assertTrue("语料存在重复用例 id：${case.id}", seen.add(case.id))
            val bytes = context.assets.open("$CORPUS_ROOT/${case.image}").use { it.readBytes() }
            assertTrue("语料图片为空：${case.image}", bytes.isNotEmpty())
            assertEquals(
                "语料图片字节已改变：${case.image}",
                case.sha256.uppercase(),
                sha256Hex(bytes)
            )
            assertTrue("用例缺少有效行列规格：${case.id}", case.gridSize > 0)
        }

        // 紧裁用例必须真正来自同一批原图，避免将来有人只加图不加派生说明。
        val cropped = manifest.cases.filter { it.id.endsWith(CROPPED_SUFFIX) }
        assertEquals(EXPECTED_CASE_COUNT / 2, cropped.size)
        cropped.forEach { case ->
            val sourceId = case.id.removeSuffix(CROPPED_SUFFIX)
            assertTrue(
                "紧裁用例 ${case.id} 找不到对应原图用例",
                manifest.cases.any { it.id == sourceId }
            )
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02X".format(byte) }
    }

    /** manifest 仅承载测试资产索引，不进入应用运行时领域模型。 */
    private data class RealCorpusManifest(
        @SerializedName("schemaVersion") val schemaVersion: String,
        @SerializedName("cases") val cases: List<RealCorpusCase>
    )

    private data class RealCorpusCase(
        @SerializedName("id") val id: String,
        @SerializedName("image") val image: String,
        @SerializedName("sha256") val sha256: String,
        @SerializedName("gridSize") val gridSize: Int
    )

    private companion object {
        const val CORPUS_ROOT: String = "pg_grid/real_v1"
        const val MANIFEST_ASSET: String = "$CORPUS_ROOT/manifest.json"
        const val EXPECTED_SCHEMA: String = "pg-grid-real-v1"
        const val EXPECTED_CASE_COUNT: Int = 12
        const val CROPPED_SUFFIX: String = "_cropped"
    }
}

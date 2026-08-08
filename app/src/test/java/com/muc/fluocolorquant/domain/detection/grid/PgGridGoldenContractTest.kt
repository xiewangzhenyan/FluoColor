package com.muc.fluocolorquant.domain.detection.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Python 参考工程导出的冻结金标准契约测试。
 *
 * 本测试不重新运行 Python，而是验证已经进入 Android 仓库的快照始终可以被严格
 * V2.1 Codec 解析，从而防止后续字段改名、枚举编码变化或点位排序变化。
 */
class PgGridGoldenContractTest {

    @Test
    fun `冻结的Python金标准符合Android二点一契约`() {
        val fixtures = listOf(
            GoldenFixture("10x10-dark.json", rows = 10, columns = 10),
            GoldenFixture("15x15-bright.json", rows = 15, columns = 15),
            GoldenFixture("4x4-legacy.json", rows = 4, columns = 4)
        )

        fixtures.forEach { fixture ->
            val result = PgGridJsonCodec.decode(
                resourceText("pg_grid/v2_1/${fixture.fileName}")
            )

            assertEquals(fixture.rows, result.rows)
            assertEquals(fixture.columns, result.columns)
            assertEquals(fixture.rows * fixture.columns, result.sites.size)
            result.sites.forEachIndexed { index, site ->
                assertEquals(index, site.siteIndex)
                assertEquals(index / fixture.columns, site.key.rowIndex)
                assertEquals(index % fixture.columns, site.key.columnIndex)
            }
            assertTrue(result.locatorVersion.isNotBlank())
        }
    }

    /** 从 JVM 测试资源中读取 UTF-8 JSON，资源缺失时明确失败而不是返回空字符串。 */
    private fun resourceText(path: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "缺少 PG-Grid 金标准资源：$path"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private data class GoldenFixture(
        val fileName: String,
        val rows: Int,
        val columns: Int
    )
}

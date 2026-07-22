package com.muc.fluocolorquant.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 内置载体与未标定采集档案的真实 Room 回调测试。
 *
 * 测试重复打开磁盘数据库，既验证 `INSERT OR IGNORE` 幂等，也锁定实验已确认的事实：
 * 10×10 和 15×15 都是亮背景上的暗方块目标，不能再按芯片规格臆测不同极性。
 */
@RunWith(AndroidJUnit4::class)
class DefaultResourceDatabaseCallbackTest {

    private lateinit var context: Context
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "default-resource-${System.nanoTime()}.db"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun 默认资源重复打开保持幂等且两种芯片均为暗目标() = runBlocking {
        val first = openDatabase()
        val acquisition = first.acquisitionProfileDao()
            .getById(BuiltInResourceIds.DEFAULT_ACQUISITION_ID)
        val carrier10 = first.carrierProfileDao()
            .getById(BuiltInResourceIds.CARRIER_MICROFLUIDIC_10X10_ID)
        val carrier15 = first.carrierProfileDao()
            .getById(BuiltInResourceIds.CARRIER_MICROFLUIDIC_15X15_ID)

        assertNotNull(acquisition)
        assertEquals(
            GridTargetPolarity.DARK,
            ScientificDetectionConfigCodec.decodeCarrierPolarity(carrier10?.locatorConfigJson)
        )
        assertEquals(
            GridTargetPolarity.DARK,
            ScientificDetectionConfigCodec.decodeCarrierPolarity(carrier15?.locatorConfigJson)
        )
        // 模拟旧版本已经把内置 15×15 错误保存为亮目标；重新打开必须定向修复。
        first.carrierProfileDao().update(
            requireNotNull(carrier15).copy(
                locatorConfigJson =
                    "{\"schemaVersion\":\"pg-grid-carrier-v1\",\"targetPolarity\":\"BRIGHT\"}"
            )
        )
        first.close()

        val reopened = openDatabase()
        assertEquals(
            3,
            reopened.carrierProfileDao().observeAll().first().count {
                it.id in setOf(
                    BuiltInResourceIds.CARRIER_MICROFLUIDIC_10X10_ID,
                    BuiltInResourceIds.CARRIER_MICROFLUIDIC_15X15_ID,
                    BuiltInResourceIds.CARRIER_PLATE_96_ID
                )
            }
        )
        assertEquals(
            1,
            reopened.acquisitionProfileDao().observeAll().first().count {
                it.id == BuiltInResourceIds.DEFAULT_ACQUISITION_ID
            }
        )
        assertEquals(
            GridTargetPolarity.DARK,
            ScientificDetectionConfigCodec.decodeCarrierPolarity(
                reopened.carrierProfileDao()
                    .getById(BuiltInResourceIds.CARRIER_MICROFLUIDIC_15X15_ID)
                    ?.locatorConfigJson
            )
        )
        reopened.close()
    }

    /** 使用正式 Room schema 打开独立磁盘数据库，显式触发 onOpen 回调。 */
    private fun openDatabase(): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addCallback(DefaultResourceDatabaseCallback(context))
            .build()
            .also { database -> database.openHelper.writableDatabase }
    }
}

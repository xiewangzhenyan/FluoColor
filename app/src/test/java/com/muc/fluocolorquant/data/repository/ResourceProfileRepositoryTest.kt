package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.AcquisitionProfileDao
import com.muc.fluocolorquant.data.dao.CarrierProfileDao
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.CarrierProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 资源仓库版本化行为测试。
 *
 * 测试使用真实 Repository 和最小 Fake DAO，不模拟 Repository 自身，确保版本递增、
 * 新 ID 和旧版本归档规则在没有 Android 设备的 JVM 环境中即可快速回归。
 */
class ResourceProfileRepositoryTest {

    @Test
    fun `载体创建新版本时归档旧版本并生成新ID`() = runBlocking {
        val dao = FakeCarrierProfileDao()
        val repository = CarrierProfileRepositoryImpl(dao)
        val original = CarrierProfile(
            id = "carrier-v1",
            name = "实验室微流控芯片",
            carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
            rows = 10,
            columns = 10,
            siteShape = SiteShape.SQUARE.code,
            version = 1
        )
        dao.insert(original)

        val next = repository.createNextVersion(
            previousId = original.id,
            replacement = original.copy(rows = 15, columns = 15)
        )

        assertNotEquals(original.id, next.id)
        assertEquals(2, next.version)
        assertEquals(ResourceStatus.ACTIVE.code, next.status)
        assertEquals(ResourceStatus.ARCHIVED.code, dao.getById(original.id)?.status)
        assertEquals(15, dao.getById(next.id)?.rows)
    }

    @Test
    fun `采集设备创建新版本时保留旧记录`() = runBlocking {
        val dao = FakeAcquisitionProfileDao()
        val repository = AcquisitionProfileRepositoryImpl(dao)
        val original = AcquisitionProfile(
            id = "device-v1",
            name = "手机采集盒",
            supportedModesJson = "[\"COLORIMETRIC\"]",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            cameraControlStrategy = "AUTO_AND_LOCK",
            version = 1
        )
        dao.insert(original)

        val next = repository.createNextVersion(
            previousId = original.id,
            replacement = original.copy(opticalModuleName = "荧光模块 A")
        )

        assertNotNull(dao.getById(original.id))
        assertNotNull(dao.getById(next.id))
        assertNotEquals(original.id, next.id)
        assertEquals(2, next.version)
        assertEquals(ResourceStatus.ARCHIVED.code, dao.getById(original.id)?.status)
    }

    /** 使用 StateFlow 模拟 Room 的响应式查询。 */
    private class FakeCarrierProfileDao : CarrierProfileDao {
        private val state = MutableStateFlow<List<CarrierProfile>>(emptyList())

        override fun observeAll(): Flow<List<CarrierProfile>> = state

        override suspend fun getById(id: String): CarrierProfile? = state.value.find { it.id == id }

        override fun observeActive(): Flow<List<CarrierProfile>> =
            state.map { profiles -> profiles.filter { it.status == ResourceStatus.ACTIVE.code } }

        override suspend fun insert(profile: CarrierProfile) {
            check(state.value.none { it.id == profile.id })
            state.value = state.value + profile
        }

        override suspend fun update(profile: CarrierProfile) {
            state.value = state.value.map { current -> if (current.id == profile.id) profile else current }
        }

        override suspend fun getLatestVersionByName(name: String): Int? =
            state.value.filter { it.name == name }.maxOfOrNull(CarrierProfile::version)

        override suspend fun updateStatus(id: String, status: String) {
            state.value = state.value.map { current ->
                if (current.id == id) current.copy(status = status) else current
            }
        }
    }

    /** 采集设备 Fake DAO 与载体 Fake DAO 使用相同的版本化语义。 */
    private class FakeAcquisitionProfileDao : AcquisitionProfileDao {
        private val state = MutableStateFlow<List<AcquisitionProfile>>(emptyList())

        override fun observeAll(): Flow<List<AcquisitionProfile>> = state

        override suspend fun getById(id: String): AcquisitionProfile? = state.value.find { it.id == id }

        override fun observeActive(): Flow<List<AcquisitionProfile>> =
            state.map { profiles -> profiles.filter { it.status == ResourceStatus.ACTIVE.code } }

        override suspend fun insert(profile: AcquisitionProfile) {
            check(state.value.none { it.id == profile.id })
            state.value = state.value + profile
        }

        override suspend fun update(profile: AcquisitionProfile) {
            state.value = state.value.map { current -> if (current.id == profile.id) profile else current }
        }

        override suspend fun getLatestVersionByName(name: String): Int? =
            state.value.filter { it.name == name }.maxOfOrNull(AcquisitionProfile::version)

        override suspend fun updateStatus(id: String, status: String) {
            state.value = state.value.map { current ->
                if (current.id == id) current.copy(status = status) else current
            }
        }
    }
}

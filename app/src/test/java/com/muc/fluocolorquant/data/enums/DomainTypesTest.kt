package com.muc.fluocolorquant.data.enums

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 多模态领域枚举契约测试。
 *
 * 这些编码会被 Room、模板快照和导出文件长期保存，因此一旦发布就不能随意改名，
 * 同时也不能把未知编码静默回退成某个默认值，否则历史科研记录会被错误解释。
 */
class DomainTypesTest {

    @Test
    fun `稳定编码可以恢复枚举且未知编码不静默回退`() {
        assertEquals(
            CarrierType.MICROFLUIDIC_CHIP,
            CarrierType.fromCode("MICROFLUIDIC_CHIP")
        )
        assertEquals(CaptureRole.ENDPOINT, CaptureRole.fromCode("ENDPOINT"))
        assertNull(CaptureRole.fromCode("UNKNOWN_ROLE"))
    }

    @Test
    fun `旧项目和旧模板使用明确兼容语义`() {
        assertEquals(CarrierType.PLATE, LegacyDomainDefaults.PROJECT_CARRIER_TYPE)
        assertEquals(TemplateLifecycleStatus.LEGACY, LegacyDomainDefaults.TEMPLATE_STATUS)
        assertEquals(InputProtocol.ENDPOINT_ONLY, LegacyDomainDefaults.STANDARD_INPUT_PROTOCOL)
        assertEquals(
            InputProtocol.SINGLE_SPECTRUM_ANALYSIS,
            LegacyDomainDefaults.SPECTRUM_INPUT_PROTOCOL
        )
    }
}

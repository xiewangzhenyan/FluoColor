package com.muc.fluocolorquant.domain.detection

import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 微流控科学配置必须使用严格、版本化且可往返的 JSON 契约。 */
class ScientificDetectionConfigCodecTest {

    @Test
    fun `暗结构载体配置往返后仍为DARK`() {
        val json = ScientificDetectionConfigCodec.encodeCarrierLocator(GridTargetPolarity.DARK)

        assertEquals(
            GridTargetPolarity.DARK,
            ScientificDetectionConfigCodec.decodeCarrierPolarity(json)
        )
    }

    @Test
    fun `荧光绿色通道配置往返后仍为GREEN`() {
        val json = ScientificDetectionConfigCodec.encodeFluorescenceDisplay(FluorescenceChannel.GREEN)

        assertEquals(
            FluorescenceChannel.GREEN,
            ScientificDetectionConfigCodec.decodeFluorescenceChannel(json)
        )
    }

    @Test
    fun `未知版本和损坏JSON不得静默回退默认值`() {
        assertNull(
            ScientificDetectionConfigCodec.decodeCarrierPolarity(
                """{"schemaVersion":"unknown","targetPolarity":"DARK"}"""
            )
        )
        assertNull(ScientificDetectionConfigCodec.decodeFluorescenceChannel("{broken"))
    }
}

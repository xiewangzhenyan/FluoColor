package com.muc.fluocolorquant.domain.result.dualmodal.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 输入张量的通道顺序必须与 W3 `w3_train.assemble` 一致：先比色后荧光，每个模态依次为位点、阴性参照、
 * 中水平阳控、高水平阳控，每张裁切按 R、G、B 展开。顺序错位时网络照样输出有限值，所以逐通道核对。
 */
class DualNetTensorAssemblerTest {

    private val plane = DualNetSpec.CROP_SIZE * DualNetSpec.CROP_SIZE

    /** 每张裁切的字节值编码了（模态，位点号，通道，像素），便于反查落点。 */
    private fun crop(modality: Int, site: Int): ByteArray = ByteArray(DualNetSpec.CROP_BYTES) { offset ->
        val pixel = offset / 3
        val channel = offset % 3
        (modality * 120 + (site % 4) * 30 + channel * 9 + pixel % 7).toByte()
    }

    @Test
    fun `通道顺序与 W3 参考实现相同`() {
        val sites = listOf(DualNetSiteRefs(site = 20, negative = 15, qcMid = 28, qcHigh = 29), DualNetSiteRefs(21, 15, 28, 29))
        val indices = sites.flatMap { it.all() }.toSet()
        val colorimetric = indices.associateWith { crop(0, it) }
        val fluorescence = indices.associateWith { crop(1, it) }

        val tensor = DualNetTensorAssembler.assemble(colorimetric, fluorescence, sites)

        assertEquals(2 * DualNetSpec.CHANNELS * plane, tensor.size)
        sites.forEachIndexed { n, refs ->
            listOf(colorimetric, fluorescence).forEachIndexed { modality, crops ->
                refs.all().forEachIndexed { slot, siteIndex ->
                    for (channel in 0 until 3) {
                        for (pixel in listOf(0, 1, 33, plane - 1)) {
                            val expected = (crops.getValue(siteIndex)[pixel * 3 + channel].toInt() and 0xFF) / 255f
                            val position = n * DualNetSpec.CHANNELS * plane + ((modality * 4 + slot) * 3 + channel) * plane + pixel
                            assertEquals("样本 $n 模态 $modality 槽 $slot 通道 $channel 像素 $pixel", expected, tensor[position], 0f)
                        }
                    }
                }
            }
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `缺少任一参照孔的裁切时拒绝拼装`() {
        val refs = DualNetSiteRefs(20, 15, 28, 29)
        val crops = listOf(20, 15, 28).associateWith { crop(0, it) }
        DualNetTensorAssembler.assemble(crops, crops, listOf(refs))
    }
}

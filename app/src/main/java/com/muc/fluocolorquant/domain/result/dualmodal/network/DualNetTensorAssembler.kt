package com.muc.fluocolorquant.domain.result.dualmodal.network

/**
 * 把裁切图拼成 DualNet 的输入张量（N × 24 × 32 × 32，NCHW，取值 0–1）。
 *
 * 通道顺序与 W3 `w3_train.assemble` 完全相同：先比色后荧光；每个模态依次为位点、阴性参照、
 * 中水平阳控、高水平阳控；每张裁切按 R、G、B 三个通道展开。裁切图为行优先 HWC 的 RGB 字节，
 * 与训练时 `cv2.resize` 后再 `permute` 的布局一致。顺序一旦错位，网络照样输出有限值，只是数值
 * 失去意义，因此由 DualNetTensorAssemblerTest 固定。
 */
object DualNetTensorAssembler {

    private const val PLANE: Int = DualNetSpec.CROP_SIZE * DualNetSpec.CROP_SIZE
    private const val SAMPLE_FLOATS: Int = DualNetSpec.CHANNELS * PLANE

    fun assemble(
        colorimetric: Map<Int, ByteArray>,
        fluorescence: Map<Int, ByteArray>,
        sites: List<DualNetSiteRefs>
    ): FloatArray {
        val out = FloatArray(sites.size * SAMPLE_FLOATS)
        sites.forEachIndexed { n, refs ->
            listOf(colorimetric, fluorescence).forEachIndexed { modality, crops ->
                refs.all().forEachIndexed { slot, siteIndex ->
                    val crop = crops[siteIndex] ?: error("缺少位点 $siteIndex 的裁切")
                    require(crop.size == DualNetSpec.CROP_BYTES) { "裁切尺寸不符：${crop.size}" }
                    val channelBase = n * SAMPLE_FLOATS + (modality * 4 + slot) * 3 * PLANE
                    for (pixel in 0 until PLANE) {
                        for (channel in 0 until 3) {
                            val value = crop[pixel * 3 + channel].toInt() and 0xFF
                            out[channelBase + channel * PLANE + pixel] = value / 255f
                        }
                    }
                }
            }
        }
        return out
    }
}

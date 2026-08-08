package com.muc.fluocolorquant.ui.screens.settings.resources

import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.SiteShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源档案编辑状态的纯 JVM 测试。
 *
 * 这些规则会同时被载体库、采集设备库和后续实验模板向导使用，因此必须脱离
 * Compose 与 Android Context 独立验证，避免不同页面出现不一致的规格判断。
 */
class ResourceProfileFormStateTest {

    @Test
    fun `常用载体预设生成正确几何且四乘四不是主预设`() {
        val chip10 = CarrierPreset.MICROFLUIDIC_10_X_10.createDraft("10×10 芯片")
        val chip15 = CarrierPreset.MICROFLUIDIC_15_X_15.createDraft("15×15 芯片")
        val plate96 = CarrierPreset.PLATE_96.createDraft("96 孔板")
        val legacy4 = CarrierPreset.LEGACY_4_X_4.createDraft("4×4 旧芯片")

        assertEquals(CarrierType.MICROFLUIDIC_CHIP, chip10.carrierType)
        assertEquals("10", chip10.rowsInput)
        assertEquals("10", chip10.columnsInput)
        assertEquals(SiteShape.SQUARE, chip10.siteShape)

        assertEquals("15", chip15.rowsInput)
        assertEquals("15", chip15.columnsInput)
        assertEquals(CarrierType.PLATE, plate96.carrierType)
        assertEquals("8", plate96.rowsInput)
        assertEquals("12", plate96.columnsInput)
        assertEquals(SiteShape.CIRCLE, plate96.siteShape)

        assertEquals("4", legacy4.rowsInput)
        assertEquals("4", legacy4.columnsInput)
        assertFalse(CarrierPreset.LEGACY_4_X_4.isPrimary)
        assertTrue(CarrierPreset.MICROFLUIDIC_10_X_10.isPrimary)
        assertTrue(CarrierPreset.MICROFLUIDIC_15_X_15.isPrimary)
    }

    @Test
    fun `载体表单拒绝空名称和越界行列`() {
        val draft = CarrierProfileDraft(
            name = " ",
            carrierType = CarrierType.CUSTOM,
            rowsInput = "0",
            columnsInput = "100",
            siteShape = SiteShape.CUSTOM
        )

        assertEquals(
            setOf(
                ResourceFormError.NAME_REQUIRED,
                ResourceFormError.ROWS_OUT_OF_RANGE,
                ResourceFormError.COLUMNS_OUT_OF_RANGE
            ),
            draft.validate()
        )
    }

    @Test
    fun `一到九十九行列的自定义载体可以保存`() {
        val draft = CarrierProfileDraft(
            name = "自定义 20×30 阵列",
            carrierType = CarrierType.CUSTOM,
            rowsInput = "20",
            columnsInput = "30",
            siteShape = SiteShape.POINT
        )

        assertTrue(draft.validate().isEmpty())
        assertEquals(600, draft.siteCountOrNull())
    }

    @Test
    fun `采集设备至少需要一个模态和一个兼容载体`() {
        val draft = AcquisitionProfileDraft(
            name = "实验室采集盒",
            supportedModes = emptySet(),
            compatibleCarrierTypes = emptySet(),
            cameraControlStrategy = CameraControlStrategy.AUTO_AND_LOCK
        )

        assertEquals(
            setOf(
                ResourceFormError.DETECTION_MODE_REQUIRED,
                ResourceFormError.COMPATIBLE_CARRIER_REQUIRED
            ),
            draft.validate()
        )
    }

    @Test
    fun `稳定编码集合可以JSON往返且忽略未知编码`() {
        val source = setOf(
            DetectionModality.COLORIMETRIC.code,
            DetectionModality.FLUORESCENCE.code
        )
        val encoded = ResourceProfileJsonCodec.encodeCodes(source)
        val decoded = ResourceProfileJsonCodec.decodeCodes(
            json = encoded.dropLast(1) + ",\"UNKNOWN_MODE\"]",
            allowedCodes = DetectionModality.entries.mapTo(mutableSetOf()) { it.code }
        )

        assertEquals(source, decoded)
        assertEquals("[\"COLORIMETRIC\",\"FLUORESCENCE\"]", encoded)
    }

    @Test
    fun `资源筛选不会把未知状态静默当成启用`() {
        assertTrue(matchesResourceStatus(ResourceStatus.ACTIVE.code, ResourceStatusFilter.ACTIVE))
        assertTrue(matchesResourceStatus(ResourceStatus.ARCHIVED.code, ResourceStatusFilter.ARCHIVED))
        assertTrue(matchesResourceStatus("FUTURE_STATUS", ResourceStatusFilter.ALL))
        assertFalse(matchesResourceStatus("FUTURE_STATUS", ResourceStatusFilter.ACTIVE))
        assertFalse(matchesResourceStatus("FUTURE_STATUS", ResourceStatusFilter.ARCHIVED))
    }

    @Test
    fun `设备匹配说明以JSON对象保存并支持引号`() {
        val encoded = ResourceProfileJsonCodec.encodeNoteObject("Pixel 设备 \"后置相机\"")

        assertEquals("{\"note\":\"Pixel 设备 \\\"后置相机\\\"\"}", encoded)
        assertEquals("Pixel 设备 \"后置相机\"", ResourceProfileJsonCodec.decodeNoteObject(encoded))
        assertEquals(null, ResourceProfileJsonCodec.encodeNoteObject("  "))
    }
}

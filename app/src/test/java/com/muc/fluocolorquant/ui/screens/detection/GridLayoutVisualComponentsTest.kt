package com.muc.fluocolorquant.ui.screens.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 虚拟布局画笔的坐标映射必须与行优先位点编号严格一致。 */
class GridLayoutVisualComponentsTest {

    @Test
    fun `画笔命中首格中间格和末格时返回正确位点编号`() {
        assertEquals(0, gridSiteIndexAt(5f, 5f, 15, 15, 10f, 2f))
        assertEquals(112, gridSiteIndexAt(89f, 89f, 15, 15, 10f, 2f))
        assertEquals(224, gridSiteIndexAt(173f, 173f, 15, 15, 10f, 2f))
    }

    @Test
    fun `画笔落在孔位间距或阵列外时不产生误标`() {
        assertNull(gridSiteIndexAt(11f, 5f, 15, 15, 10f, 2f))
        assertNull(gridSiteIndexAt(-1f, 5f, 15, 15, 10f, 2f))
        assertNull(gridSiteIndexAt(181f, 5f, 15, 15, 10f, 2f))
    }
}

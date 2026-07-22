package com.muc.fluocolorquant.domain.result

import com.muc.fluocolorquant.ui.screens.result.array.calculateArrayImageFitTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 原图 ContentScale.Fit 与冻结点位必须共用同一套坐标变换。 */
class ArrayImageFitTransformTest {
    @Test
    fun `宽图在方形容器中垂直居中并保持原图坐标比例`() {
        val transform = calculateArrayImageFitTransform(
            containerWidth = 400f,
            containerHeight = 400f,
            imageWidth = 200f,
            imageHeight = 100f
        )

        assertNotNull(transform)
        transform!!
        assertEquals(2f, transform.scale, 0f)
        assertEquals(0f, transform.left, 0f)
        assertEquals(100f, transform.top, 0f)
        val mapped = transform.map(100.0, 50.0)
        assertEquals(200f, mapped.x, 0f)
        assertEquals(200f, mapped.y, 0f)
    }

    @Test
    fun `无效图像尺寸不会生成伪坐标变换`() {
        assertNull(calculateArrayImageFitTransform(400f, 400f, 0f, 100f))
    }
}

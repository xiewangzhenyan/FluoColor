package com.muc.fluocolorquant.ui.screens.settings.resources

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 资源档案 JSON 编解码器的 Android 运行时回归测试。
 *
 * JVM 单元测试使用的是桌面 Java 正则引擎，而真机和模拟器使用 Android ICU 正则引擎，
 * 两者对部分多余转义字符的兼容性不同。因此这里必须在 Android 运行时再次验证，避免
 * 编解码器在首次初始化时抛出 [java.util.regex.PatternSyntaxException] 并导致保存页面崩溃。
 */
@RunWith(AndroidJUnit4::class)
class ResourceProfileJsonCodecAndroidTest {

    @Test
    fun 设备匹配说明在Android运行时可以安全往返() {
        val source = "Pixel 设备 \"后置相机\"\\实验盒"

        val encoded = ResourceProfileJsonCodec.encodeNoteObject(source)

        assertEquals(source, ResourceProfileJsonCodec.decodeNoteObject(encoded))
    }
}

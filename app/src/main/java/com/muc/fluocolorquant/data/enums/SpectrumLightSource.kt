package com.muc.fluocolorquant.data.enums

import androidx.annotation.StringRes
import com.muc.fluocolorquant.R

/**
 * 光源类型枚举，携带显示名称的字符串资源
 */
enum class SpectrumLightSource(@StringRes val displayNameRes: Int) {
    MERCURY(R.string.spectrum_light_mercury),
    HALOGEN(R.string.spectrum_light_halogen),
    LED_WHITE(R.string.spectrum_light_led_white),
    SUNLIGHT(R.string.spectrum_light_sunlight),
}

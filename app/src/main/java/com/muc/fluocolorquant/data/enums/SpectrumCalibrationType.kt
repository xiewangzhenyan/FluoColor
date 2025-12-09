package com.muc.fluocolorquant.data.enums

/**
 * Calibration strategy for spectrum analysis.
 */
enum class SpectrumCalibrationType(val code: String) {
    AUTO_IMAGE("AUTO_IMAGE"),
    MANUAL_POINT("MANUAL_POINT");

    companion object {
        fun fromCode(code: String?): SpectrumCalibrationType? {
            if (code == null) return null
            return values().firstOrNull { it.code.equals(code, ignoreCase = true) }
        }
    }
}

package com.muc.fluocolorquant.data.enums

/**
 * 像素类型枚举类
 * 定义所有支持的像素特征提取方式及其标识符
 */
enum class PixelType(val displayName: String, val identifier: String) {
    // Grayscale & Combinations
    GRAY_LUMINOSITY("0.299R+0.587G+0.114B", "gray_luminosity"),
    EUCLIDEAN_NORM("(R²+G²+B²)¹/²", "euclidean_norm"),
    AVERAGE_RGB("(R+G+B)/3", "average_rgb"),
    INVERSE_RB_AVG("((255-R)+(255-B))/2", "inverse_rb_avg"),
    RB_DIFF("R-B+255", "rb_diff"),

    // Ratios
    RATIO_RG("R/G", "ratio_rg"),
    RATIO_RB("R/B", "ratio_rb"),
    RATIO_GB("G/B", "ratio_gb"),

    // Single Channels (RGB)
    RED("Red (RGB)", "channel_r"),
    GREEN("Green (RGB)", "channel_g"),
    BLUE("Blue (RGB)", "channel_b"),

    // HSV/HSL Color Space
    HUE("Hue (HSV/HSL)", "hsv_h"),
    SATURATION_HSV("Saturation (HSV)", "hsv_s"),
    VALUE_HSV("Value (HSV)", "hsv_v"),
    SATURATION_HSL("Saturation (HSL)", "hsl_s"),
    LIGHTNESS_HSL("Lightness (HSL)", "hsl_l"),

    // CIE XYZ Color Space
    CIE_X("X (CIE XYZ)", "cie_x"),
    CIE_Y("Y (CIE XYZ)", "cie_y"),
    CIE_Z("Z (CIE XYZ)", "cie_z"),
    CIE_x("x (CIE xy)", "cie_small_x"),
    CIE_y("y (CIE xy)", "cie_small_y"),

    // CIE L*a*b* Color Space
    CIE_L("L* (CIE Lab)", "lab_l"),
    CIE_a("a* (CIE Lab)", "lab_a"),
    CIE_b("b* (CIE Lab)", "lab_b"),

    // YCbCr Color Space
    YCBCR_Y("Y (YCbCr)", "ycbcr_y"),
    YCBCR_CB("Cb (YCbCr)", "ycbcr_cb"),
    YCBCR_CR("Cr (YCbCr)", "ycbcr_cr"),

    // CMYK Color Space
    CYAN("Cyan (CMYK)", "cmyk_c"),
    MAGENTA("Magenta (CMYK)", "cmyk_m"),
    YELLOW("Yellow (CMYK)", "cmyk_y"),
    BLACK("Black (CMYK)", "cmyk_k");
    
    // 实际的计算逻辑将在一个单独的图像处理工具类中实现，
    // 该工具类会接受一个PixelType枚举作为参数来决定执行哪个计算。
    
    companion object {
        /**
         * 根据标识符查找像素类型
         * @param identifier 像素类型标识符
         * @return 对应的像素类型枚举，如果未找到则返回null
         */
        fun fromIdentifier(identifier: String): PixelType? {
            return values().find { it.identifier == identifier }
        }
        
        /**
         * 获取所有像素类型的显示名称列表
         * @return 显示名称列表
         */
        fun getAllDisplayNames(): List<String> {
            return values().map { it.displayName }
        }
        
        /**
         * 按类别获取像素类型
         * @return 按类别分组的像素类型映射
         */
        fun getByCategory(): Map<String, List<PixelType>> {
            return mapOf(
                "灰度与组合" to listOf(GRAY_LUMINOSITY, EUCLIDEAN_NORM, AVERAGE_RGB, INVERSE_RB_AVG, RB_DIFF),
                "比率" to listOf(RATIO_RG, RATIO_RB, RATIO_GB),
                "单通道 (RGB)" to listOf(RED, GREEN, BLUE),
                "HSV/HSL 颜色空间" to listOf(HUE, SATURATION_HSV, VALUE_HSV, SATURATION_HSL, LIGHTNESS_HSL),
                "CIE XYZ 颜色空间" to listOf(CIE_X, CIE_Y, CIE_Z, CIE_x, CIE_y),
                "CIE L*a*b* 颜色空间" to listOf(CIE_L, CIE_a, CIE_b),
                "YCbCr 颜色空间" to listOf(YCBCR_Y, YCBCR_CB, YCBCR_CR),
                "CMYK 颜色空间" to listOf(CYAN, MAGENTA, YELLOW, BLACK)
            )
        }
    }
} 
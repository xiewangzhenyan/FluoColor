package com.muc.fluocolorquant.data.enums

/**
 * 拟合函数枚举类
 * 定义所有支持的数学模型及其标识符
 */
enum class FittingFunction(
    val displayName: String,
    val identifier: String,
    val latexFormula: String,
    // 【新增】定义每个函数必需的参数列表
    val requiredParams: List<String>
) {
    LINEAR("Linear", "linear", "y = ax + b", listOf("a", "b")),
    QUADRATIC("Quadratic", "quadratic", "y = ax^2 + bx + c", listOf("a", "b", "c")),
    CUBIC("Cubic", "cubic", "y = ax^3 + bx^2 + cx + d", listOf("a", "b", "c", "d")),
    QUARTIC("Quartic", "quartic", "y = ax^4 + bx^3 + cx^2 + dx + e", listOf("a", "b", "c", "d", "e")),
    EXPONENTIAL("Exponential", "exponential", "y = a \\cdot e^{bx}", listOf("a", "b")),
    POWER("Power", "power", "y = a \\cdot x^b", listOf("a", "b")),
    LOG("Log", "log", "y = a + b \\cdot \\ln(x)", listOf("a", "b")),
    RODBARD("Rodbard (4PL)", "rodbard_4pl", "y = d + \\frac{a-d}{1 + (\\frac{x}{c})^b}", listOf("a", "b", "c", "d")),
    GAMMA_VARIATE("Gamma Variate", "gamma_variate", "y = a \\cdot (x-b)^c \\cdot e^{-\\frac{x-b}{d}}", listOf("a", "b", "c", "d")),
    CUSTOM_LOG("y = a+b*ln(x-c)", "custom_log", "y = a + b \\cdot \\ln(x-c)", listOf("a", "b", "c")),
    RODBARD_NIH("Rodbard (NIH Image)", "rodbard_nih", "y = a \\cdot \\frac{1}{1 + (\\frac{x}{c})^b}", listOf("a", "b", "c")),
    EXPONENTIAL_WITH_OFFSET("Exponential with Offset", "exp_offset", "y = a \\cdot e^{-bx} + c", listOf("a", "b", "c")),
    GAUSSIAN("Gaussian", "gaussian", "y = a + (b-a) \\cdot e^{-\\frac{(x-c)^2}{2d^2}}", listOf("a", "b", "c", "d")),
    EXPONENTIAL_RECOVERY("Exponential Recovery", "exp_recovery", "y = a \\cdot (1 - e^{-bx})", listOf("a", "b")),
    LOGISTIC("Logistic (5PL)", "logistic_5pl", "y = d + \\frac{a-d}{(1 + (\\frac{x}{c})^b)^g}", listOf("a", "b", "c", "d", "g")),
    GOMPERTZ("Gompertz", "gompertz", "y = a \\cdot e^{-b \\cdot e^{-cx}}", listOf("a", "b", "c")),
    HILL("Hill", "hill", "y = \\frac{a \\cdot x^b}{c^b + x^b}", listOf("a", "b", "c")),
    GENERAL_GOMPERTZ("General Gompertz", "gompertz_general", "y = a \\cdot e^{-b \\cdot e^{-cx^d}}", listOf("a", "b", "c", "d")),
    RICHARDS("Richards", "richards", "y = \\frac{a}{(1 + b \\cdot e^{-cx})^{\\frac{1}{d}}}", listOf("a", "b", "c", "d")),
    INTERPOLATION("Interpolation", "interpolation", "\\text{Interpolation}", listOf());

    companion object {
        fun fromIdentifier(identifier: String): FittingFunction? {
            return values().find { it.identifier == identifier }
        }
        fun getAllDisplayNames(): List<String> {
            return values().map { it.displayName }
        }
    }
}
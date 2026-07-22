package com.muc.fluocolorquant.data

/**
 * 内置默认实验资源的稳定标识。
 *
 * 这些资源由 [DefaultResourceDatabaseCallback] 在所有构建（含 Release）幂等播种，
 * 目的是给新数据库提供可识别的起始载体和“未标定探索采集”占位档案。正式定量模型仍
 * 必须显式声明经过验证的采集设备兼容范围，不能把该占位档案理解为万能正式设备。
 *
 * 与 `MicrofluidicDemoDatabaseCallback` 的演示资源（`demo-*`）刻意区分：演示资源仅
 * 存在于 Debug 构建且标记“非真实定量”，而这里的默认资源是正式可用的采集/载体档案。
 * ID 一旦发布不可更改，否则历史模板快照的外键引用会失效。
 */
object BuiltInResourceIds {

    /** 内置未标定探索采集档案；只用于初始配置和非正式验证。 */
    const val DEFAULT_ACQUISITION_ID = "builtin-acquisition-default-v1"

    /** 常用微流控 10×10 载体；当前玻璃激光加工结构按暗目标声明。 */
    const val CARRIER_MICROFLUIDIC_10X10_ID = "builtin-carrier-microfluidic-10x10-v1"

    /** 常用微流控 15×15 载体；与 10×10 使用相同玻璃加工结构和暗目标语义。 */
    const val CARRIER_MICROFLUIDIC_15X15_ID = "builtin-carrier-microfluidic-15x15-v1"

    /** 兼容旧孔板流程的 96 孔（8×12）载体。 */
    const val CARRIER_PLATE_96_ID = "builtin-carrier-plate-96-v1"

    /** 新建默认优先选中的载体（微流控 10×10）。 */
    const val DEFAULT_CARRIER_ID = CARRIER_MICROFLUIDIC_10X10_ID

    /** PG-Grid 载体定位配置：暗结构目标。 */
    const val PG_GRID_DARK_LOCATOR_JSON =
        "{\"schemaVersion\":\"pg-grid-carrier-v1\",\"targetPolarity\":\"DARK\"}"

    /** 未标定探索采集支持全部模态，便于建立草稿；正式模型不会自动绑定它。 */
    const val DEFAULT_SUPPORTED_MODES_JSON = "[\"COLORIMETRIC\",\"FLUORESCENCE\",\"SPECTRUM\"]"

    /** 默认采集档案兼容的载体类型（覆盖全部类型）。 */
    const val DEFAULT_COMPATIBLE_CARRIER_TYPES_JSON =
        "[\"MICROFLUIDIC_CHIP\",\"PLATE\",\"CUSTOM\"]"

}

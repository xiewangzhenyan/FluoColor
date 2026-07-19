package com.muc.fluocolorquant.ui.screens.settings.resources

import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.SiteShape

/**
 * 应用当前支持的检测模态稳定编码。
 *
 * 这里不复用 `NewProjectScreen` 内部的界面枚举，因为资源档案属于跨页面领域数据，
 * 其编码会写入数据库 JSON，必须与具体 Compose 页面解耦。
 */
enum class DetectionModality(val code: String) {
    COLORIMETRIC("COLORIMETRIC"),
    FLUORESCENCE("FLUORESCENCE"),
    SPECTRUM("SPECTRUM")
}

/**
 * 采集设备对相机参数的控制策略。
 *
 * 普通实验人员只会看到策略说明，不需要手动理解曝光时间、ISO 或传感器增益。
 */
enum class CameraControlStrategy(val code: String) {
    AUTO_AND_LOCK("AUTO_AND_LOCK"),
    TEMPLATE_CONSTRAINED("TEMPLATE_CONSTRAINED"),
    FIXED_PARAMETERS("FIXED_PARAMETERS")
}

/** 资源表单可以返回的稳定错误类型，由 Compose 页面映射为中英文字符串。 */
enum class ResourceFormError {
    NAME_REQUIRED,
    ROWS_OUT_OF_RANGE,
    COLUMNS_OUT_OF_RANGE,
    DETECTION_MODE_REQUIRED,
    COMPATIBLE_CARRIER_REQUIRED
}

/** 资源列表的生命周期筛选条件。 */
enum class ResourceStatusFilter {
    ALL,
    ACTIVE,
    ARCHIVED
}

/**
 * 资源页面的一次性事件。
 *
 * ViewModel 只发送稳定事件，不发送硬编码中文或英文；Compose 页面收到事件后使用
 * `stringResource()` 映射为当前语言，并交给 `LocalToastManager` 显示。
 */
sealed interface ResourceProfileEvent {
    data class ValidationFailed(val errors: Set<ResourceFormError>) : ResourceProfileEvent
    data class SaveSucceeded(val createdNewVersion: Boolean) : ResourceProfileEvent
    data object ArchiveSucceeded : ResourceProfileEvent
    data object SaveFailed : ResourceProfileEvent
    data object ArchiveFailed : ResourceProfileEvent
}

/**
 * 判断数据库状态是否命中筛选条件。
 *
 * 未知状态只会出现在“全部”中，避免未来版本的新状态被旧客户端误判为可用于实验。
 */
fun matchesResourceStatus(status: String, filter: ResourceStatusFilter): Boolean = when (filter) {
    ResourceStatusFilter.ALL -> true
    ResourceStatusFilter.ACTIVE -> status == ResourceStatus.ACTIVE.code
    ResourceStatusFilter.ARCHIVED -> status == ResourceStatus.ARCHIVED.code
}

/**
 * 载体编辑草稿。
 *
 * 行列使用字符串保存，允许用户在输入过程中暂时清空文本；只有执行保存校验时才解析，
 * 避免每次键盘输入都被强行改回数字而造成光标跳动。
 */
data class CarrierProfileDraft(
    val name: String = "",
    val carrierType: CarrierType = CarrierType.MICROFLUIDIC_CHIP,
    val rowsInput: String = "10",
    val columnsInput: String = "10",
    val siteShape: SiteShape = SiteShape.SQUARE
) {
    /** 返回全部校验错误，页面可以一次性高亮所有问题。 */
    fun validate(): Set<ResourceFormError> = buildSet {
        if (name.isBlank()) add(ResourceFormError.NAME_REQUIRED)
        if (rowsInput.toIntOrNull() !in VALID_DIMENSION_RANGE) {
            add(ResourceFormError.ROWS_OUT_OF_RANGE)
        }
        if (columnsInput.toIntOrNull() !in VALID_DIMENSION_RANGE) {
            add(ResourceFormError.COLUMNS_OUT_OF_RANGE)
        }
    }

    /** 只有行列均合法时才计算位点数，防止无效输入参与后续数据库写入。 */
    fun siteCountOrNull(): Int? {
        val rows = rowsInput.toIntOrNull()?.takeIf { it in VALID_DIMENSION_RANGE } ?: return null
        val columns = columnsInput.toIntOrNull()?.takeIf { it in VALID_DIMENSION_RANGE } ?: return null
        return rows * columns
    }

    companion object {
        private val VALID_DIMENSION_RANGE = 1..99
    }
}

/**
 * 载体快捷预设。
 *
 * 10×10 和 15×15 是当前实验室主规格；4×4 仍完整支持，但通过 [isPrimary] 标记为
 * 非主入口，由界面放入“更多规格/旧规格”区域。
 */
enum class CarrierPreset(val isPrimary: Boolean) {
    MICROFLUIDIC_10_X_10(true),
    MICROFLUIDIC_15_X_15(true),
    PLATE_96(true),
    LEGACY_4_X_4(false),
    CUSTOM(false);

    fun createDraft(name: String): CarrierProfileDraft = when (this) {
        MICROFLUIDIC_10_X_10 -> CarrierProfileDraft(
            name = name,
            carrierType = CarrierType.MICROFLUIDIC_CHIP,
            rowsInput = "10",
            columnsInput = "10",
            siteShape = SiteShape.SQUARE
        )
        MICROFLUIDIC_15_X_15 -> CarrierProfileDraft(
            name = name,
            carrierType = CarrierType.MICROFLUIDIC_CHIP,
            rowsInput = "15",
            columnsInput = "15",
            siteShape = SiteShape.SQUARE
        )
        PLATE_96 -> CarrierProfileDraft(
            name = name,
            carrierType = CarrierType.PLATE,
            rowsInput = "8",
            columnsInput = "12",
            siteShape = SiteShape.CIRCLE
        )
        LEGACY_4_X_4 -> CarrierProfileDraft(
            name = name,
            carrierType = CarrierType.MICROFLUIDIC_CHIP,
            rowsInput = "4",
            columnsInput = "4",
            siteShape = SiteShape.SQUARE
        )
        CUSTOM -> CarrierProfileDraft(
            name = name,
            carrierType = CarrierType.CUSTOM,
            rowsInput = "",
            columnsInput = "",
            siteShape = SiteShape.CUSTOM
        )
    }
}

/**
 * 采集设备编辑草稿。
 *
 * 光学模块和固定装置均为可选命名字段；拍摄距离不由普通用户输入，若存在固定装置，
 * 物理距离由装置结构保证，若不存在则由后续采集页面使用画面占比进行引导。
 */
data class AcquisitionProfileDraft(
    val name: String = "",
    val supportedModes: Set<String> = emptySet(),
    val compatibleCarrierTypes: Set<String> = emptySet(),
    val deviceMatcherNote: String = "",
    val opticalModuleName: String = "",
    val fixtureId: String = "",
    val cameraControlStrategy: CameraControlStrategy = CameraControlStrategy.AUTO_AND_LOCK
) {
    fun validate(): Set<ResourceFormError> = buildSet {
        if (name.isBlank()) add(ResourceFormError.NAME_REQUIRED)
        if (supportedModes.isEmpty()) add(ResourceFormError.DETECTION_MODE_REQUIRED)
        if (compatibleCarrierTypes.isEmpty()) add(ResourceFormError.COMPATIBLE_CARRIER_REQUIRED)
    }
}

/**
 * 仅用于稳定枚举编码集合的轻量 JSON 编解码器。
 *
 * 编码结果排序后输出，保证模板快照、测试和 Git 差异稳定；解码时必须提供允许集合，
 * 未知编码直接丢弃并交由上层显示不兼容状态，绝不静默替换成第一个默认项。
 */
object ResourceProfileJsonCodec {
    private val quotedValueRegex = Regex("\\\"([^\\\"]*)\\\"")
    private val noteObjectRegex = Regex(
        """^\s*\{\s*"note"\s*:\s*"((?:\\.|[^"])*)"\s*}\s*$"""
    )

    fun encodeCodes(codes: Set<String>): String {
        return codes
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .joinToString(prefix = "[", postfix = "]", separator = ",") { code ->
                "\"${escapeJsonString(code)}\""
            }
    }

    fun decodeCodes(json: String?, allowedCodes: Set<String>): Set<String> {
        if (json.isNullOrBlank() || allowedCodes.isEmpty()) return emptySet()
        return quotedValueRegex
            .findAll(json)
            .map { match -> unescapeJsonString(match.groupValues[1]) }
            .filter { code -> code in allowedCodes }
            .toCollection(linkedSetOf())
    }

    /** 将管理员输入的设备匹配说明包装为具名 JSON 字段。 */
    fun encodeNoteObject(note: String): String? {
        val normalized = note.trim()
        if (normalized.isEmpty()) return null
        return "{\"note\":\"${escapeJsonString(normalized)}\"}"
    }

    /** 解析当前版本的设备匹配说明；格式不兼容时返回空文本供页面明确展示。 */
    fun decodeNoteObject(json: String?): String {
        if (json.isNullOrBlank()) return ""
        val encodedNote = noteObjectRegex.matchEntire(json)?.groupValues?.getOrNull(1) ?: return ""
        return unescapeJsonString(encodedNote)
    }

    private fun escapeJsonString(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun unescapeJsonString(value: String): String {
        return value.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}

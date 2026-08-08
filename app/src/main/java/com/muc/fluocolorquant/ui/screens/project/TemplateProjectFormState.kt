package com.muc.fluocolorquant.ui.screens.project

import com.muc.fluocolorquant.domain.project.TemplatePreflightIssue

/**
 * 一个需要样本编号的模板位点。
 *
 * [suggestedSampleSlot] 来自模板，仅用于页面预填；真正写入项目的是用户确认后的
 * `sampleSlotMapping`，因此修改模板不会影响已经创建的项目。
 */
data class TemplateSampleSiteField(
    val siteKey: String,
    val analyteName: String,
    val suggestedSampleSlot: String
)

/**
 * 按实际样本编号合并后的便携式摘要。
 *
 * 十乘十芯片可能包含一百个样本角色位点，但很多位点属于同一样本的重复或不同分析物。
 * 默认页面展示该分组摘要，只有用户主动展开管理时才显示逐位点编辑，避免移动端出现一百
 * 个输入框的过度设计。
 */
data class TemplateSampleGroup(
    val sampleSlot: String,
    val siteKeys: List<String>,
    val analyteNames: List<String>
)

/** 模板优先新建项目的纯 Kotlin 表单状态和提交门控。 */
data class TemplateProjectFormState(
    val projectName: String = "",
    val projectBatch: String = "",
    val sampleBatch: String = "",
    val selectedTemplateId: String? = null,
    val imageUri: String? = null,
    val templateReady: Boolean = false,
    val requiredSampleSites: List<TemplateSampleSiteField> = emptyList(),
    val sampleSlotMapping: Map<String, String> = emptyMap(),
    val preflightIssues: List<TemplatePreflightIssue> = emptyList(),
    val isSubmitting: Boolean = false
) {
    /** 尚未提供有效样本编号的样本位点。 */
    val missingSampleSiteKeys: Set<String>
        get() = requiredSampleSites
            .asSequence()
            .map(TemplateSampleSiteField::siteKey)
            .filter { key -> sampleSlotMapping[key].isNullOrBlank() }
            .toCollection(linkedSetOf())

    /** 按样本编号合并位点，供默认摘要视图使用。 */
    val sampleGroups: List<TemplateSampleGroup>
        get() {
            val siteByKey = requiredSampleSites.associateBy(TemplateSampleSiteField::siteKey)
            return sampleSlotMapping
                .asSequence()
                .map { (key, value) -> key to value.trim() }
                .filter { (_, value) -> value.isNotEmpty() }
                .groupBy(keySelector = { it.second }, valueTransform = { it.first })
                .map { (sampleSlot, siteKeys) ->
                    TemplateSampleGroup(
                        sampleSlot = sampleSlot,
                        siteKeys = siteKeys.distinct().sorted(),
                        analyteNames = siteKeys
                            .mapNotNull { key -> siteByKey[key]?.analyteName }
                            .distinct()
                            .sorted()
                    )
                }
                .sortedBy(TemplateSampleGroup::sampleSlot)
        }

    /** 创建按钮只有在模板、图片、样本映射和预检全部就绪时才启用。 */
    val canSubmit: Boolean
        get() = projectName.isNotBlank() &&
            !selectedTemplateId.isNullOrBlank() &&
            !imageUri.isNullOrBlank() &&
            templateReady &&
            preflightIssues.isEmpty() &&
            missingSampleSiteKeys.isEmpty() &&
            !isSubmitting
}

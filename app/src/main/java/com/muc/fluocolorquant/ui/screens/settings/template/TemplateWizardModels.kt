package com.muc.fluocolorquant.ui.screens.settings.template

import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.TemplateReferenceScope
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import kotlin.math.max
import kotlin.math.min

/** 模板创建向导的稳定步骤，页面可据此显示进度和限制前后跳转。 */
enum class TemplateWizardStep {
    BASIC,
    DETECTION_AND_RESOURCES,
    ANALYTES,
    LAYOUT,
    QC_AND_REVIEW
}

/** 模板草稿和发布检查使用的稳定错误码。 */
enum class TemplateWizardError {
    NAME_REQUIRED,
    DETECTION_MODE_REQUIRED,
    READOUT_LAYOUT_REQUIRED,
    INPUT_PROTOCOL_INCOMPATIBLE,
    CARRIER_REQUIRED,
    ACQUISITION_PROFILE_REQUIRED,
    ANALYTE_REQUIRED,
    DUPLICATE_ANALYTE,
    ANALYSIS_MODEL_REQUIRED,
    FLUORESCENCE_CHANNEL_REQUIRED,
    CONCENTRATION_UNIT_REQUIRED,
    RELIABLE_RANGE_INVALID,
    LAYOUT_SIZE_INVALID,
    LAYOUT_OUT_OF_BOUNDS,
    LAYOUT_NOT_FULLY_ASSIGNED,
    SITE_ANALYTE_REQUIRED,
    SITE_ANALYTE_UNKNOWN,
    STANDARD_CONCENTRATION_INVALID,
    SAMPLE_SITE_REQUIRED,
    ANALYTE_BLANK_REQUIRED
}

/**
 * 规则阵列中的中性零基坐标。
 *
 * 数据库保存零基行列以便数学计算；界面和报告统一显示 `R01C01`，孔板页面如果需要
 * `A1` 别名应在适配层额外生成，不能改变这里的通用坐标协议。
 */
data class TemplateSiteCoordinate(
    val rowIndex: Int,
    val columnIndex: Int
) : Comparable<TemplateSiteCoordinate> {
    init {
        require(rowIndex >= 0) { "rowIndex 必须为非负数" }
        require(columnIndex >= 0) { "columnIndex 必须为非负数" }
    }

    val displayName: String
        get() = "R${(rowIndex + 1).toString().padStart(2, '0')}" +
            "C${(columnIndex + 1).toString().padStart(2, '0')}"

    override fun compareTo(other: TemplateSiteCoordinate): Int {
        val rowComparison = rowIndex.compareTo(other.rowIndex)
        return if (rowComparison != 0) rowComparison else columnIndex.compareTo(other.columnIndex)
    }
}

/** 单个位点在模板草稿中的科学配置。 */
data class TemplateSiteDraft(
    val analyteId: String? = null,
    val role: TemplateSiteRole = TemplateSiteRole.SAMPLE,
    val standardConcentrationInput: String = "",
    val repeatGroup: String = "",
    val defaultSampleSlot: String = "",
    val referenceScope: TemplateReferenceScope = TemplateReferenceScope.ANALYTE,
    val enabled: Boolean = true
) {
    /** 禁用位点不应携带容易被下游误解释的分析物或浓度信息。 */
    fun normalized(): TemplateSiteDraft {
        return if (role == TemplateSiteRole.DISABLED) {
            copy(
                analyteId = null,
                standardConcentrationInput = "",
                repeatGroup = "",
                defaultSampleSlot = "",
                enabled = false
            )
        } else {
            copy(
                analyteId = analyteId?.trim()?.ifEmpty { null },
                standardConcentrationInput = standardConcentrationInput.trim(),
                repeatGroup = repeatGroup.trim(),
                defaultSampleSlot = defaultSampleSlot.trim(),
                enabled = true
            )
        }
    }
}

/**
 * 通用阵列布局草稿。
 *
 * 选择集合与位点分配分开保存：选择只是编辑器瞬时状态，不会进入数据库；批量应用后
 * 才写入 [assignments]。所有操作都返回新对象，便于 ViewModel 和 Compose 做不可变更新。
 */
data class TemplateArrayLayoutDraft(
    val rows: Int = 0,
    val columns: Int = 0,
    val assignments: Map<TemplateSiteCoordinate, TemplateSiteDraft> = emptyMap(),
    val selectedSites: Set<TemplateSiteCoordinate> = emptySet(),
    val rectangleAnchor: TemplateSiteCoordinate? = null
) {
    val siteCount: Int
        get() = if (rows > 0 && columns > 0) rows * columns else 0

    val allCoordinates: Set<TemplateSiteCoordinate>
        get() = if (rows <= 0 || columns <= 0) {
            emptySet()
        } else {
            buildSet(siteCount) {
                repeat(rows) { row ->
                    repeat(columns) { column ->
                        add(TemplateSiteCoordinate(row, column))
                    }
                }
            }
        }

    fun contains(coordinate: TemplateSiteCoordinate): Boolean {
        return coordinate.rowIndex in 0 until rows && coordinate.columnIndex in 0 until columns
    }

    fun toggleSelection(coordinate: TemplateSiteCoordinate): TemplateArrayLayoutDraft {
        if (!contains(coordinate)) return this
        val nextSelection = if (coordinate in selectedSites) {
            selectedSites - coordinate
        } else {
            selectedSites + coordinate
        }
        return copy(selectedSites = nextSelection, rectangleAnchor = null)
    }

    fun selectRow(rowIndex: Int): TemplateArrayLayoutDraft {
        if (rowIndex !in 0 until rows) return this
        return copy(
            selectedSites = (0 until columns).mapTo(linkedSetOf()) { column ->
                TemplateSiteCoordinate(rowIndex, column)
            },
            rectangleAnchor = null
        )
    }

    fun selectColumn(columnIndex: Int): TemplateArrayLayoutDraft {
        if (columnIndex !in 0 until columns) return this
        return copy(
            selectedSites = (0 until rows).mapTo(linkedSetOf()) { row ->
                TemplateSiteCoordinate(row, columnIndex)
            },
            rectangleAnchor = null
        )
    }

    fun selectAll(): TemplateArrayLayoutDraft = copy(
        selectedSites = allCoordinates,
        rectangleAnchor = null
    )

    fun clearSelection(): TemplateArrayLayoutDraft = copy(
        selectedSites = emptySet(),
        rectangleAnchor = null
    )

    /** 选择两个端点构成的闭区间矩形，端点顺序不影响结果。 */
    fun selectRectangle(
        start: TemplateSiteCoordinate,
        end: TemplateSiteCoordinate
    ): TemplateArrayLayoutDraft {
        if (!contains(start) || !contains(end)) return this
        val top = min(start.rowIndex, end.rowIndex)
        val bottom = max(start.rowIndex, end.rowIndex)
        val left = min(start.columnIndex, end.columnIndex)
        val right = max(start.columnIndex, end.columnIndex)
        val rectangle = buildSet {
            for (row in top..bottom) {
                for (column in left..right) {
                    add(TemplateSiteCoordinate(row, column))
                }
            }
        }
        return copy(selectedSites = rectangle, rectangleAnchor = null)
    }

    /** 矩形模式第一次点击记录锚点，第二次点击完成矩形选择。 */
    fun selectRectanglePoint(coordinate: TemplateSiteCoordinate): TemplateArrayLayoutDraft {
        if (!contains(coordinate)) return this
        val anchor = rectangleAnchor
        return if (anchor == null) {
            copy(selectedSites = setOf(coordinate), rectangleAnchor = coordinate)
        } else {
            selectRectangle(anchor, coordinate)
        }
    }

    fun applyToSelection(site: TemplateSiteDraft): TemplateArrayLayoutDraft {
        if (selectedSites.isEmpty()) return this
        val normalized = site.normalized()
        // 先在布局接收者上计算有效选区，避免进入 MutableMap.apply 后 `contains` 被解析为
        // Map.containsKey，导致空 Map 把全部待写入坐标错误过滤掉。
        val validSelection = selectedSites.filter { coordinate -> contains(coordinate) }
        return copy(
            assignments = assignments.toMutableMap().apply {
                validSelection.forEach { coordinate ->
                    this[coordinate] = normalized
                }
            },
            rectangleAnchor = null
        )
    }

    /** 更换载体时裁掉越界配置，绝不把旧几何坐标静默映射到新阵列。 */
    fun resized(newRows: Int, newColumns: Int): TemplateArrayLayoutDraft {
        val safeRows = newRows.coerceAtLeast(0)
        val safeColumns = newColumns.coerceAtLeast(0)
        return TemplateArrayLayoutDraft(
            rows = safeRows,
            columns = safeColumns,
            assignments = assignments.filterKeys { coordinate ->
                coordinate.rowIndex in 0 until safeRows &&
                    coordinate.columnIndex in 0 until safeColumns
            }
        )
    }
}

/** 模板中的单个分析物配置草稿。 */
data class TemplateAnalyteDraft(
    val analyteId: String = "",
    val reagentAntigenId: String? = null,
    val reagentAntibodyId: String? = null,
    val analysisModelId: String? = null,
    val concentrationUnit: String = "",
    val reliableRangeMinInput: String = "",
    val reliableRangeMaxInput: String = "",
    /** 新建荧光分析物默认绿色；恢复缺失/损坏历史配置时使用 null 并由发布校验阻止。 */
    val fluorescenceChannel: FluorescenceChannel? = FluorescenceChannel.GREEN
) {
    fun reliableRangeOrNull(): Pair<Double, Double>? {
        val minimum = reliableRangeMinInput.toDoubleOrNull() ?: return null
        val maximum = reliableRangeMaxInput.toDoubleOrNull() ?: return null
        return if (minimum.isFinite() && maximum.isFinite() && maximum > minimum) {
            minimum to maximum
        } else {
            null
        }
    }
}

/** 完整实验模板向导草稿。 */
data class TemplateWizardDraft(
    val templateName: String = "",
    val purpose: String = "",
    val versionNote: String = "",
    val detectionMode: String = DetectionModality.COLORIMETRIC.code,
    val readoutLayout: String = ReadoutLayout.GRID_SITES.code,
    val inputProtocol: String = InputProtocol.ENDPOINT_ONLY.code,
    val carrierProfileId: String = "",
    val acquisitionProfileId: String = "",
    val analytes: List<TemplateAnalyteDraft> = emptyList(),
    val layout: TemplateArrayLayoutDraft = TemplateArrayLayoutDraft(),
    val qcProfileJson: String = "{}"
) {
    val allowedProtocols: Set<String>
        get() = when (DetectionModality.fromCode(detectionMode)) {
            DetectionModality.COLORIMETRIC,
            DetectionModality.FLUORESCENCE -> setOf(InputProtocol.ENDPOINT_ONLY.code)
            DetectionModality.SPECTRUM -> setOf(
                InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code,
                InputProtocol.LSPR_PAIRED_QUANTIFICATION.code
            )
            null -> emptySet()
        }

    /** 草稿阶段校验可解释的科学身份，但允许布局和兼容资源稍后补齐。 */
    fun validateForDraft(): Set<TemplateWizardError> = buildSet {
        if (templateName.isBlank()) add(TemplateWizardError.NAME_REQUIRED)
        if (DetectionModality.fromCode(detectionMode) == null) {
            add(TemplateWizardError.DETECTION_MODE_REQUIRED)
        }
        if (ReadoutLayout.fromCode(readoutLayout) == null) {
            add(TemplateWizardError.READOUT_LAYOUT_REQUIRED)
        }
        if (inputProtocol !in allowedProtocols) {
            add(TemplateWizardError.INPUT_PROTOCOL_INCOMPATIBLE)
        }
        val nonBlankAnalyteIds = analytes.map { it.analyteId.trim() }.filter { it.isNotEmpty() }
        if (nonBlankAnalyteIds.size != nonBlankAnalyteIds.distinct().size) {
            add(TemplateWizardError.DUPLICATE_ANALYTE)
        }
        analytes.forEach { analyte ->
            if (analyte.analyteId.isBlank()) add(TemplateWizardError.ANALYTE_REQUIRED)
            if (
                detectionMode == DetectionModality.FLUORESCENCE.code &&
                analyte.fluorescenceChannel == null
            ) {
                add(TemplateWizardError.FLUORESCENCE_CHANNEL_REQUIRED)
            }
            // 未选择定量模型时，模板明确表示“仅信号”，不要求填写没有实际用途的
            // 浓度单位与可靠范围；一旦选择模型，这两项才成为有效的定量配置。
            if (!analyte.analysisModelId.isNullOrBlank()) {
                if (analyte.concentrationUnit.isBlank()) {
                    add(TemplateWizardError.CONCENTRATION_UNIT_REQUIRED)
                }
                if (analyte.reliableRangeOrNull() == null) {
                    add(TemplateWizardError.RELIABLE_RANGE_INVALID)
                }
            }
        }
    }

    /**
     * 发布阶段补齐资源、模型、全阵列覆盖和模态专用参考要求。
     *
     * 比色的 ΔE/光密度处理器当前必须依赖模板冻结的空白或参考位完成全局白平衡；荧光
     * 处理器则对每个位点使用 ROI 外局部背景环扣除，因此没有实验空白位时也可以发布，
     * 不能为了通过校验而伪造一个不存在的空白物理方块。
     */
    fun validateForPublication(): Set<TemplateWizardError> = buildSet {
        addAll(validateForDraft())
        if (carrierProfileId.isBlank()) add(TemplateWizardError.CARRIER_REQUIRED)
        if (analytes.isEmpty()) add(TemplateWizardError.ANALYTE_REQUIRED)
        if (layout.rows <= 0 || layout.columns <= 0) {
            add(TemplateWizardError.LAYOUT_SIZE_INVALID)
        }

        val allCoordinates = layout.allCoordinates
        if (layout.assignments.keys.any { it !in allCoordinates }) {
            add(TemplateWizardError.LAYOUT_OUT_OF_BOUNDS)
        }
        if (allCoordinates.isNotEmpty() && !layout.assignments.keys.containsAll(allCoordinates)) {
            add(TemplateWizardError.LAYOUT_NOT_FULLY_ASSIGNED)
        }

        val analyteIds = analytes.mapTo(linkedSetOf()) { it.analyteId }
        layout.assignments.values.forEach { rawSite ->
            val site = rawSite.normalized()
            val roleNeedsAnalyte = site.role in ANALYTE_BOUND_ROLES
            if (roleNeedsAnalyte && site.analyteId.isNullOrBlank()) {
                add(TemplateWizardError.SITE_ANALYTE_REQUIRED)
            }
            if (!site.analyteId.isNullOrBlank() && site.analyteId !in analyteIds) {
                add(TemplateWizardError.SITE_ANALYTE_UNKNOWN)
            }
            if (site.role == TemplateSiteRole.STANDARD) {
                val concentration = site.standardConcentrationInput.toDoubleOrNull()
                if (concentration == null || !concentration.isFinite() || concentration < 0.0) {
                    add(TemplateWizardError.STANDARD_CONCENTRATION_INVALID)
                }
            }
        }

        analyteIds.filter(String::isNotBlank).forEach { analyteId ->
            val sites = layout.assignments.values.map(TemplateSiteDraft::normalized)
            if (sites.none { it.role == TemplateSiteRole.SAMPLE && it.analyteId == analyteId }) {
                add(TemplateWizardError.SAMPLE_SITE_REQUIRED)
            }
            val hasColorimetricReference = sites.any { site ->
                site.role in setOf(TemplateSiteRole.BLANK, TemplateSiteRole.REFERENCE) &&
                    (
                        site.analyteId == analyteId ||
                            site.referenceScope == TemplateReferenceScope.GLOBAL
                        )
            }
            if (
                detectionMode == DetectionModality.COLORIMETRIC.code &&
                !hasColorimetricReference
            ) {
                add(TemplateWizardError.ANALYTE_BLANK_REQUIRED)
            }
        }
    }

    companion object {
        private val ANALYTE_BOUND_ROLES = setOf(
            TemplateSiteRole.SAMPLE,
            TemplateSiteRole.STANDARD,
            TemplateSiteRole.BLANK,
            TemplateSiteRole.NEGATIVE_CONTROL,
            TemplateSiteRole.POSITIVE_CONTROL
        )
    }
}

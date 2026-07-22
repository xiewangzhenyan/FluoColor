# 通用阵列结果、历史与快照导出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将新 `SiteMeasurement` 微流控检测运行完整呈现为可缩放阵列、多分析物结果、原图定位、QC、运行历史和快照导出，同时保留旧孔板 `WellResult` 页面兼容能力。

**Architecture:** 新结果链只从 `DetectionRun.effectiveConfigSnapshotJson`、`CaptureArtifact` 和 `SiteMeasurement` 重建，不读取当前可变模板。`ResultGatewayScreen` 根据运行是否存在新位点测量选择通用阵列页或旧结果页；阵列页使用独立领域适配、ViewModel 和导出器，浓度色带与 QC 图层分离。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、Room 2.6.1、Hilt、Coroutines、Gson、Canvas/Transformable、Android `PdfDocument`、ZIP、JUnit4、AndroidX Compose UI Test。

**执行约束：** 使用现有 `F:\Code\Android\FluoColor` 工作区；不创建 worktree、不提交；新增和修改业务代码写详细中文注释；所有用户可见文本进入中英文 `strings.xml`；Toast 使用 `LocalToastManager`；README 不写默认账号或密码。

---

## 文件结构

### 结果持久化和定量

- Modify: `app/src/main/java/com/muc/fluocolorquant/data/model/CaptureEntities.kt`
  - 为 `SiteMeasurement` 增加浓度、单位、可靠范围状态和模型快照字段。
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/migration/DatabaseMigrations.kt`
- Create: `app/schemas/com.muc.fluocolorquant.data.AppDatabase/12.json`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/quantification/EndpointQuantificationContracts.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/quantification/StandardCurveQuantifier.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/domain/detection/GridDetectionCoordinator.kt`

### 快照结果领域和仓库

- Create: `app/src/main/java/com/muc/fluocolorquant/domain/result/ArrayResultContracts.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/result/ArrayResultSnapshotMapper.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/ArrayResultRepository.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/ArrayResultRepositoryImpl.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/CaptureArtifactDao.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/DetectionRunDao.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/RepositoryModule.kt`

### Compose 结果界面

- Create: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ArrayResultViewModel.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ResultGatewayViewModel.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/ResultGatewayScreen.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayResultScreen.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayHeatmap.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArraySiteDetailSheet.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayImageOverlay.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayQcPanel.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayRunHistorySheet.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/navigation/AppNavigation.kt`

### 导出

- Create: `app/src/main/java/com/muc/fluocolorquant/domain/result/export/ArrayResultExportModels.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/result/export/ArrayResultExporter.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/result/export/ArrayResultPdfExporter.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayResultExportSheet.kt`

---

### Task 1: 补齐浓度结果存储契约和 Room 12 迁移

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/model/CaptureEntities.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/migration/DatabaseMigrations.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/DatabaseModule.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/data/AppDatabaseMigrationTest.kt`

- [x] **Step 1: 写失败迁移测试——Room 11 数据升级后保留原测量并新增空浓度字段**

测试在版本 11 插入一条 `site_measurements`，执行 11→12 后断言原字段不变，且以下列存在并为 null：`concentrationValue`、`concentrationUnit`、`reliableRangeStatus`、`modelSnapshotJson`、`quantificationQcJson`。

- [x] **Step 2: 运行迁移测试并确认因缺少 11→12 迁移而失败**

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.data.AppDatabaseMigrationTest`

- [x] **Step 3: 增加字段和迁移**

```kotlin
data class SiteMeasurement(
    // 既有字段保持不变
    val concentrationValue: Double? = null,
    val concentrationUnit: String? = null,
    val reliableRangeStatus: String? = null,
    val modelSnapshotJson: String? = null,
    val quantificationQcJson: String? = null
)
```

迁移只使用五条 `ALTER TABLE ... ADD COLUMN`，不重建科研数据表；数据库版本升到 12 并导出 schema。

- [x] **Step 4: 运行迁移、数据库和完整 JVM 测试**

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.data.AppDatabaseMigrationTest`

Run: `./gradlew.bat :app:testDebugUnitTest`

Expected: PASS。

---

### Task 2: 标准曲线浓度反算和运行时模型快照

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/quantification/EndpointQuantificationContracts.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/quantification/StandardCurveQuantifier.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/domain/detection/GridDetectionCoordinator.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/quantification/StandardCurveQuantifierTest.kt`
- Modify: `app/src/test/java/com/muc/fluocolorquant/domain/detection/GridDetectionCoordinatorTest.kt`

- [x] **Step 1: 写失败测试——已发布兼容线性标准曲线必须从主特征反算浓度**

```kotlin
@Test
fun `线性曲线y等于2x加1时信号21反算浓度10`() {
    val result = StandardCurveQuantifier.quantify(bundle, signalValue = 21.0)
    assertEquals(10.0, (result as EndpointQuantificationResult.Quantified).concentration, 1e-6)
}
```

同时覆盖递增/递减曲线、可靠范围内/外、插值模型、无法单调反算、缺少参数和非有限信号。

- [x] **Step 2: 运行并确认量化器不存在而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*StandardCurveQuantifierTest"`

- [x] **Step 3: 实现统一量化结果和数值反算**

```kotlin
sealed interface EndpointQuantificationResult {
    data class Quantified(
        val concentration: Double,
        val unit: String,
        val rangeStatus: ReliableRangeStatus,
        val modelSnapshotJson: String
    ) : EndpointQuantificationResult
    data class SignalOnly(val reason: EndpointQuantificationReason) : EndpointQuantificationResult
}
```

非插值曲线在模型可靠浓度范围内使用单调二分反算；`INTERPOLATION` 先对未排除的同浓度重复点求信号均值，再从原始标定段正向插值得到可靠浓度上下边界的信号，只在该可靠区间内按信号分段反算，边界外返回 `OutOfRange` 而不外推。标准曲线定义、参数、标定点、模型版本和验证指标全部写入 `modelSnapshotJson`。深度学习定义若没有与当前处理器一致的端点输入协议，不执行也不猜测，保持 `SignalOnly`。

- [x] **Step 4: 协调器在兼容模型上写入浓度字段**

兼容标准曲线逐测量调用量化器；成功时状态为 `Completed`。超可靠范围不得外推，`concentrationValue` 必须为 null，只保存原始/校正信号、`BELOW_RANGE`/`ABOVE_RANGE`、稳定的 `OUT_OF_RELIABLE_RANGE` 原因和模型快照；模型不可执行时只保存信号且运行状态为 `SignalOnlyCompleted`。

- [x] **Step 5: 运行量化器和协调器测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*StandardCurveQuantifierTest" --tests "*GridDetectionCoordinatorTest"`

Expected: PASS。

---

### Task 3: 建立只读快照结果领域和仓库

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/result/ArrayResultContracts.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/result/ArrayResultSnapshotMapper.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/ArrayResultRepository.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/ArrayResultRepositoryImpl.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/CaptureArtifactDao.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/DetectionRunDao.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/RepositoryModule.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/result/ArrayResultSnapshotMapperTest.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/data/ArrayResultRepositoryTest.kt`

- [x] **Step 1: 写失败测试——100/225 位点按冻结行列、角色和分析物重建**

领域模型至少包含：运行、项目标题、载体类型、行列、模态、分析物列表、完整物理位点、测量值、浓度状态、几何来源、原图/矫正坐标、帧级和位点级 QC、附件及处理器/模型版本。

- [x] **Step 2: 运行并确认快照映射器不存在而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*ArrayResultSnapshotMapperTest"`

- [x] **Step 3: 实现严格映射**

只使用 `DetectionRun.effectiveConfigSnapshotJson` 解析 `TemplateProjectSnapshot`；位点角色和分析物来自快照 `siteAssignments`，几何来自运行 `frameQcJson.pgGrid`，模式详情来自 `rawSignalJson/correctedSignalJson`。快照、PG-Grid 或位点索引损坏时返回稳定错误，不用当前模板补齐。

- [x] **Step 4: 实现 Room 仓库查询**

增加 `CaptureArtifactDao.getByRun()`、`DetectionRunDao.hasSiteMeasurements()` 和项目运行历史查询；仓库一次加载运行、项目、附件、测量后交给纯映射器，避免 Compose 直接拼 JSON。

- [x] **Step 5: 运行领域和 Room 仓库测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*ArrayResultSnapshotMapperTest"`

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.data.ArrayResultRepositoryTest`

Expected: PASS。

---

### Task 4: 结果路由和通用阵列页面骨架

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ResultGatewayViewModel.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ArrayResultViewModel.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/ResultGatewayScreen.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayResultScreen.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Test: `app/src/test/java/com/muc/fluocolorquant/ui/viewmodels/ArrayResultViewModelTest.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/ui/screens/result/array/ResultGatewayScreenTest.kt`

- [x] **Step 1: 写失败测试——新运行进入阵列页，旧运行进入旧结果页**

网关只查询是否存在 `SiteMeasurement`；不得根据 10×10/15×15 行列猜测。阵列页面固定四个标签：芯片总览、分析物结果、原图与定位、质量控制。

- [x] **Step 2: 运行并确认当前导航无新结果分流而失败**

- [x] **Step 3: 实现网关、状态机和页面骨架**

`ArrayResultViewModel` 状态为 `Loading/Success/NotFound/CorruptSnapshot/Error`；所有 DAO/JSON 工作在 IO/Default，UI 只展示状态。旧 `NewResultScreen` 不改内部逻辑，由网关作为兼容分支调用。

- [x] **Step 4: 运行 ViewModel 和 Compose 路由测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*ArrayResultViewModelTest"`

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.ui.screens.result.array.ResultGatewayScreenTest`

Expected: PASS。

---

### Task 5: 通用阵列热力图和多分析物切换

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayHeatmap.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayResultScreen.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/result/ArrayHeatmapScaleTest.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayHeatmapTest.kt`

- [x] **Step 1: 写失败测试——浓度颜色和 QC 编码必须相互独立**

可靠浓度色带按当前分析物自己的单位和范围归一化；信号-only 时改用主特征色带。`WARNING` 只改变边框，`FAILURE` 使用斜纹/禁用覆盖层，不能替换底层浓度色。

- [x] **Step 2: 实现 4×4、10×10、15×15 和自定义阵列布局**

4×4 显示编号和数值；10×10 自动适配宽度；15×15 使用 `transformable` 支持双指缩放和平移，初始完整显示。点击通过零基行列转换为 `siteIndex = row * columns + column`，不得保留 8×12 映射。

- [x] **Step 3: 实现总览和分析物视图**

总览使用角色/可靠范围百分比，不混用不同分析物绝对单位；分析物标签页切换自己的绝对浓度或信号色带，并显示可靠、警告、失败、未检测数量。

- [x] **Step 4: 运行纯逻辑和 Compose 测试**

Expected: 4×4/10×10/15×15/4×6 均创建固定 `rows × columns` 单元，点击最后一个单元索引正确。

---

### Task 6: 位点详情、原图定位和 QC 页面

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArraySiteDetailSheet.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayImageOverlay.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayQcPanel.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayResultScreen.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayResultDetailTest.kt`

- [x] **Step 1: 写失败 UI 测试——点击位点显示公共信息和模态专用详情**

公共信息：R/C 位点、样本槽、分析物、角色、浓度/仅信号、可靠范围、几何置信度、模型、处理器。比色增加 RGB/Lab、ΔE、OD、参考和反光；荧光增加通道、原始/校正强度、背景、SNR、饱和、热点和可检出状态。

- [x] **Step 2: 实现原图与定位叠加**

使用附件原图和冻结 `PgGridResult` 原图坐标绘制候选精修、模型补位、不调整和无效位点；支持显示/隐藏标签。显示增强只作用于 UI Bitmap，所有文字明确说明不会改变保存定量。

- [x] **Step 3: 实现帧级和位点级 QC**

按严重级别分组展示机器码映射的用户说明与可执行建议；部分位点失败不阻止查看其他结果，严重帧级失败运行只显示重拍原因、不伪造热力图。

- [x] **Step 4: 运行 Compose 详情与 QC 测试**

Expected: 比色/荧光详情字段分流正确，补位点和低 SNR 的视觉含义不同。

---

### Task 7: 多运行历史和快照切换

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayRunHistorySheet.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ArrayResultViewModel.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayResultScreen.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/ui/viewmodels/ArrayResultViewModelTest.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayRunHistoryTest.kt`

- [x] **Step 1: 写失败测试——项目多次运行按时间倒序且切换不覆盖旧运行**

历史项显示时间、状态、位点数、可靠率、模型版本和重拍/仅信号原因；选择历史运行后重新从该运行快照加载，不读取当前模板。

- [x] **Step 2: 实现运行列表和切换**

当前运行以强调色标识；重拍和重新分析是独立 runId。切换只改变页面当前 runId，不修改项目 `lastRunTimestamp` 或数据库结果。

- [x] **Step 3: 运行 ViewModel 和 Compose 历史测试**

Expected: 三次运行顺序稳定，切换后显示对应快照模型版本。

---

### Task 8: CSV、PDF 和 ZIP 完整快照导出

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/result/export/ArrayResultExportModels.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/result/export/ArrayResultExporter.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayResultExportSheet.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayResultScreen.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/result/export/ArrayResultExporterTest.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/ui/screens/result/array/ArrayResultExportTest.kt`

- [x] **Step 1: 写失败测试——CSV 和 ZIP 必须包含全部快照与位点字段**

CSV 每行包含运行、行列、样本、分析物、角色、原始/校正信号、主特征、浓度、单位、SNR、置信度、可检出、可靠性、QC、处理器和模型版本。ZIP 包含 `manifest.json`、`measurements.csv`、模板/模型/设备/运行快照 JSON、原始附件和存在的派生叠加图。

- [x] **Step 2: 实现纯 Kotlin CSV/ZIP 生成**

CSV 使用 RFC 4180 转义和 UTF-8 BOM；ZIP 内路径固定且做 SHA-256 清单。附件缺失时在 manifest 记录 `missing`，不悄悄省略证据。

- [x] **Step 3: 实现 PDF 摘要和系统文件保存**

PDF 使用 `PdfDocument` 输出项目/运行摘要、阵列热力图、分析物统计、QC 和追溯信息；Compose 通过 `ActivityResultContracts.CreateDocument` 保存，成功/失败提示使用 `LocalToastManager`。

- [x] **Step 4: 运行导出测试**

Expected: CSV 可解析行数等于测量数，ZIP 校验清单完整，PDF 页数至少 1 且文件头为 `%PDF`。

---

### Task 9: 工作包 5 全流程验收和实施记录

**Files:**
- Modify: `docs/superpowers/plans/2026-07-20-array-results-history-export.md`
- Modify: `docs/superpowers/specs/2026-07-19-microfluidic-multimodal-redesign-design.md`

- [x] **Step 1: 运行完整 JVM、仪器和构建测试**

Run: `./gradlew.bat :app:testDebugUnitTest`

Run: `./gradlew.bat :app:connectedDebugAndroidTest`

Run: `./gradlew.bat :app:assembleDebug`

- [x] **Step 2: 在 API 35 模拟器验证新旧结果分流**

微流控 10×10/15×15 运行进入阵列页；旧孔板运行仍进入旧 `NewResultScreen`；仅信号、部分 QC 失败和重拍运行不显示伪浓度。

- [x] **Step 3: 更新设计状态和本文件实施记录**

记录实际测试数量、迁移版本、截图路径、导出示例、性能和保留风险；不得以“编译通过”替代交互和文件内容验收。

---

## 完成门槛

- [x] `Screen.NewResult` 能自动分流新 `SiteMeasurement` 与旧 `WellResult`。
- [x] 4×4、10×10、15×15 和自定义非方阵按冻结行列显示完整物理位点。
- [x] 多分析物绝对浓度色带互不混用，总览不混用单位。
- [x] 浓度颜色与 QC 编码分离，低 SNR 与质量不可靠含义独立。
- [x] 比色和荧光位点详情展示不同的科学字段。
- [x] 原图叠加能够区分候选精修、模型补位和失败位点。
- [x] 同一项目多运行可追溯且旧运行不被覆盖。
- [x] CSV、PDF、ZIP 从运行快照重建并包含完整证据和版本。
- [x] 标准曲线兼容时输出浓度；模型不可执行时只保存/展示信号。
- [x] Room 11→12 迁移、JVM、仪器测试和 Debug 构建全部通过。

## 实施记录

- 2026-07-20：完成现状审计。新检测链已经保存 `DetectionRun + CaptureArtifact + SiteMeasurement`，但 `Screen.NewResult` 仍主要读取旧 `WellResult`；确认采用网关分流而不是重写旧孔板页面，历史和导出只从运行快照重建。
- 2026-07-20：Task 1/2 初次审计记录。Room 数据库版本为 12，`MIGRATION_11_12` 仅对 `site_measurements` 追加 5 个可空列（浓度、单位、可靠范围状态、模型快照、量化 QC），保留 Room 11 的原始科学信号和既有表结构。`AppDatabaseMigrationTest` 定向仪器测试实际执行 3 项，全部通过。
- 2026-07-20：初次实现已限制非插值曲线在可靠浓度范围内反算，并对超范围、非有限信号、缺失/损坏参数、非单调曲线及非标准曲线模型做了安全降级；初版量化器测试和协调器测试均通过，但遗漏了“完整标定域宽于模型可靠范围”时插值仍错误使用全标定端点的缺陷。该缺陷由后续规范审查发现，并通过新增红灯测试后完成回修；初版 10×10 荧光协调器仪器测试 1 项通过，验证 100 条位点测量和浓度写入。
- 2026-07-20：全量验证命令 `:app:testDebugUnitTest`、`:app:connectedDebugAndroidTest`、`:app:assembleDebug` 均成功。JVM XML 报告合计 123 项、0 失败、0 错误、0 跳过；仪器 XML 报告 33 项、0 失败、0 错误、0 跳过。Gradle 提示现有弃用特性，未影响本次测试或构建结果。
- 2026-07-20：规范审查回修完成。先运行扩展后的 `StandardCurveQuantifierTest`，14 项中 5 项按预期失败，暴露了插值误用全标定域、NaN/Infinity 在 Gson 快照阶段抛异常及旧正向公式漂移；加入 LOD、LOQ 与 NaN 参数的补充回归后，最终同一测试 16/16 通过，`GridDetectionCoordinatorTest` 3/3 通过，完整 `:app:testDebugUnitTest` 成功。量化器现在复用 `FittingEngine.calculate` 的唯一正向公式，序列化快照前校验全部标定点及 LOD/LOQ 的有限性并捕获序列化异常；插值先合并重复点，再在可靠浓度边界正向分段插值，边界外仅返回范围状态。重新执行完整仪器测试 33/33、0 失败、0 错误、0 跳过，以及 `:app:assembleDebug` 成功。
- 2026-07-20：Task 2 质量审查阶段 A 回修。审查进一步发现量化器仍在逐位点重复解析参数、生成快照和扫描单调性，固定绝对 `1e-9` 会吞掉极小有效信号并在大尺度边界误判，同时旧绘图公式的容错值仍可能进入科研定量。测试先扩展到 29 项并取得 8 项行为级红灯，随后新增 `prepare(bundle)` 的不可变 `Ready / SignalOnly` 契约：准备阶段一次完成模型类型、标准曲线、有限科学字段、快照、严格定义域、参数解析、257 点可靠区间采样或插值可靠域构建；Ready 仅处理逐位点信号。容差改为“比较跨度的极小相对量 + `Math.ulp`”，二分和插值结果必须为有限值并重新落入可靠浓度范围。最终 `StandardCurveQuantifierTest` 29/29 通过，快照继续记录 `endpointQuantifier` 和 `formulaEngine` 稳定版本。
- 2026-07-20：Task 2 质量审查阶段 B 回修。审查发现协调器仍逐位点调用兼容量化包装，任一 `NON_FINITE_SIGNAL` 会错误清空同分析物其他成功浓度并把运行降级；深度快照缺少定位前结构回归测试；比色校正结果未保存实际参考索引、白平衡增益及参考 RGB/Lab，且无分析物的全局空白/参考物理位点没有独立数据库证据。协调器测试先扩展至 9 项并取得 2 项行为级红灯，修复后每个分析物只调用一次 `prepare` 并复用 Ready，位点级失败只写 `scope=SITE` 的量化 QC，模型级准备失败或不兼容才写 `scope=MODEL` 并进入 `SignalOnlyCompleted`。`modelUsage` 现在记录 `quantifiedCount / outOfRangeCount / siteSignalOnlyCount / total`，执行状态区分 `standard_curve_applied`、`standard_curve_applied_with_warnings` 和 `signal_only`。
- 2026-07-20：阶段 B 同时完成比色参考证据契约。比色样本/分析物位点的 `correctedSignalJson` 升级为 `colorimetric-corrected-signal-v1`，包含 `site` 及带 `referenceIndices / whiteBalanceGains / referenceRgb / referenceLab` 的 `calibrationContext`；荧光 JSON 保持原结构。全局 `BLANK / REFERENCE` 位点在分析物循环外只保存一次 `analyteId=null` 的 `COLORIMETRIC_REFERENCE_EVIDENCE` 行，原始 JSON 使用 `colorimetric-reference-evidence-v1`，保留 PG-Quant 光度、几何/QC 和处理器版本，不参与分析物浓度及模型计数。真实 10×10 多分析物图片集成测试先以 3 项中 1 项失败证明参考证据缺失，回修后 3/3 通过；四类损坏快照均在定位器调用前 `Blocked`，没有产生 `Completed + 0` 或索引崩溃。
- 2026-07-20：阶段 A/B 最终全量验证完成。定向 JVM 测试为 `GridDetectionCoordinatorTest` 9/9、`StandardCurveQuantifierTest` 29/29；完整 `:app:testDebugUnitTest --rerun-tasks` 的 XML 汇总为 150 项、0 失败、0 错误、0 跳过；API 35 模拟器完整 `:app:connectedDebugAndroidTest` 为 35 项、0 失败、0 错误、0 跳过；`:app:assembleDebug` 成功。Task 1/2 在上述全量证据取得后继续保持完成勾选，后续 Task 3 起仍按计划未完成。
- 2026-07-20：Task 2 关系完整性规范复审回修。新增红灯测试证明两类缺陷：量化器会接受属于其他分析模型的 `StandardCurveDefinition` 或 `CalibrationPoint`；协调器会接受重复分析物快照及模板、分析物、模型、标准曲线、标定点之间的外键错配。修复后 `StandardCurveQuantifier.prepare()` 将曲线定义和全部标定点的 `analysisModelId` 纳入模型级准备校验；定位前预检拒绝重复分析物，完整校验 `TemplateAnalyteConfig`、`AnalysisModel`、标准曲线、深度学习定义和标定点的关系，并约束标准曲线/深度学习模型只能携带各自所需的专用定义。新增稳定阻断原因 `DUPLICATE_ANALYTE_SNAPSHOT`、`INCONSISTENT_ANALYTE_SNAPSHOT`，同时补齐 Compose 网关穷尽映射及中英文资源。扩展后的定向 JVM 测试为 `StandardCurveQuantifierTest` 31/31、`GridDetectionCoordinatorTest` 11/11；API 35 定向 `GridDetectionCoordinatorIntegrationTest` 3/3，并验证全部损坏关系均在定位器调用前阻断；完整 JVM XML 汇总 154 项、完整仪器 35 项，均为 0 失败、0 错误、0 跳过，`:app:assembleDebug` 成功。Task 1/2 继续保持完成，Task 3 起状态不变。
- 2026-07-20：Task 2 最终 Important 质量复审回修。新增定向 JVM 测试后先取得 47 项中 3 项行为红灯：`y=(x-0.1)^2` 在 `[0,256]` 的窄回折被 257 点采样误判为递增；项目模板版本及模板到载体/采集档案、位点到模板的顶层关系错配未阻断；Ready 中途返回模型故障时仍保留先前部分浓度。修复后，LINEAR/QUADRATIC/CUBIC/QUARTIC 使用 Commons Math `LaguerreSolver` 求导数全部近实根，并在端点与相邻根之间证明导数符号，允许不改变符号的孤立导数零；其余非插值 `FittingFunction` 按解析导数符号、定义域及 Gamma 峰 `b+c*d`、Gaussian 中心 `c` 等临界点证明可靠区间方向，声明方向必须匹配证明结果。257 点采样仅保留正向公式有限性检查，不再承担单调性判定。协调器预检增加 `project.templateVersion`、模板载体/采集档案外键及所有 `TemplateSiteAssignment.templateId` 校验。新增 `PreparedEndpointQuantificationResult` 将 `Quantified / OutOfRange / SiteSignalOnly / ModelFailure` 用类型系统分离，只有 `NON_FINITE_SIGNAL` 可成为位点级仅信号；批量出现任一 `ModelFailure` 时撤销整个分析物的部分浓度并进入模型级 `signal_only`。最终定向 JVM 为 `StandardCurveQuantifierTest` 34/34、`GridDetectionCoordinatorTest` 13/13，API 35 定向集成 3/3；完整 JVM XML 159 项、完整仪器 35 项，均为 0 失败、0 错误、0 跳过，`:app:assembleDebug` 成功。Task 1/2 状态不变，Task 3 起仍未开始。
- 2026-07-20：Task 3 只读快照结果领域和仓库完成。新增 `ArrayResultContracts`、`ArrayResultSnapshotMapper` 与 `ArrayResultRepository`：Mapper 仅使用 `DetectionRun.effectiveConfigSnapshotJson / configurationDeviationJson / frameQcJson.pgGrid`、`CaptureArtifact` 和 `SiteMeasurement`，Project 只提供身份与标题，绝不回读当前模板或当前项目覆盖。PG-Grid 固定生成 `rows × columns` 全部物理位点；10×10、15×15、样本槽覆盖、比色校正上下文、荧光详情、全局参考证据、损坏声明版 JSON、越界/模态错配和超范围伪浓度均有纯 JVM 契约。协调器改为始终冻结本次实际执行的 `request.snapshot`，并把项目样本映射原样复制到运行 `configurationDeviationJson`。Room 仓库使用单事务加载运行、项目、附件和测量，DAO 新增附件固定快照查询及 `EXISTS(site_measurements)` 新旧结果分流查询；项目运行历史保持时间倒序。`ArrayResultSnapshotMapperTest` 11/11、`ArrayResultRepositoryTest` 4/4，协调器与仓库定向仪器合计 7/7；完整 JVM XML 170 项、API 35 完整仪器 39 项均为 0 失败、0 错误、0 跳过，`:app:assembleDebug` 成功。Task 4 起仍未完成。
- 2026-07-20：Task 4 新旧结果网关和通用阵列页面骨架完成。`Screen.Result` 与 `Screen.NewResult` 统一进入 `ResultGatewayScreen`，仅以当前运行是否存在 `SiteMeasurement` 决定进入新阵列页或旧 `NewResultScreen`，不再用载体行列猜测数据类型。阵列结果页建立 `Loading / Success / NotFound / CorruptSnapshot / Error` 状态机，并提供芯片总览、分析物结果、原图与定位、质量控制四个固定标签及项目、载体、分析物、附件和几何 QC 摘要。定向 JVM `ArrayResultViewModelTest` 4/4、Compose 网关测试 3/3；完整 JVM XML 174 项、API 35 完整仪器 42 项均为 0 失败、0 错误、0 跳过，`:app:assembleDebug` 成功。Task 5 起仍未完成。
- 2026-07-20：Task 5 通用阵列热力图和多分析物切换完成。新增独立热力图绘制模型，底色只编码当前分析物浓度或主特征，警告使用琥珀色边框、质量失败使用半透明斜纹、低信号使用蓝色圆点，三类 QC 均不替换科学数值底色；芯片总览使用独立的失败红—警告黄—可靠绿百分比色带，避免混合不同分析物单位。4×4 显示位点编号和数值，10×10 自适应宽度，15×15 及更大自定义阵列支持 1～4 倍双指缩放和平移且初始完整显示；4×6 等非方阵继续使用 `siteIndex = row * columns + column`。分析物结果页使用筛选芯片切换各自的可靠浓度范围；整个分析物无浓度时才整体回退到主特征色带，属于其他分析物的物理位点保留在网格中但不计为漏检。定向纯逻辑测试 5/5、Compose 测试 3/3；完整 JVM XML 179 项、API 35 完整仪器 45 项均为 0 失败、0 错误、0 跳过，`:app:assembleDebug` 成功。Task 6 起仍未完成。
- 2026-07-20：Task 6 位点详情、原图定位和完整 QC 页面完成。热力图、原图叠加与 QC 列表共享 `ArraySiteSelection`，点击后打开只读底部详情：公共段展示样本、分析物、角色、浓度或仅信号、可靠范围、几何来源/置信度、模型和处理器；比色段单独展示白平衡 RGB、Lab、ΔE2000、OD、实际参考位、增益及反光证据，荧光段单独展示模板读出通道、原始/校正/净/积分强度、背景、SNR、饱和、热点和可检出性。原图叠加严格使用冻结 `original` 坐标，绿色/琥珀色/灰色/红色分别表示候选精修、模型补位、未调整和质量失败；标签可关闭，显示增强仅改变 Compose 显示矩阵。为避免 EXIF 自动旋转导致坐标错位，原图使用与检测入口一致的 `BitmapFactory` 原始像素解码，并将显示最长边采样至 2048 像素，坐标仍按原始尺寸映射。QC 页面把帧级原因码映射为中英文说明和可执行建议，位点级明确区分质量失败、低信号、模型补位、外推、超范围及具体光度问题；严重帧级失败会阻止总览和分析物热力图创建，但仍允许查看原图和重拍原因。定向原图变换测试 2/2、Compose 详情/QC 测试 5/5；完整 JVM XML 181 项、API 35 完整仪器 50 项均为 0 失败、0 错误、0 跳过，`:app:assembleDebug` 成功，随后进入 Task 7。
- 2026-07-20：Task 7 多运行历史完成。`ArrayResultViewModel` 在初次读取运行快照后加载同项目 `DetectionRun`，按时间倒序生成只读摘要；历史卡展示状态、测量数、可靠率、冻结分析物模型版本以及重拍/仅信号原因，当前运行使用强调色和完整 runId 标记。切换历史只调用 `loadSnapshot(targetRunId)`，保持历史列表不变，不更新项目 `lastRunTimestamp`、模板或数据库结果；页面在切换期间保留当前热力图并显示轻量进度。定向 ViewModel 5/5、API 35 Compose 历史测试 2/2 通过。
- 2026-07-20：Task 8 完整快照导出完成。CSV 使用 UTF-8 BOM、CRLF 和 RFC 4180 转义，每条 `SiteMeasurement` 一行并保留原始/校正 JSON、主特征、浓度、QC、处理器与模型版本。ZIP 使用固定目录、稳定 schema、逐文件 SHA-256 清单和附件 declared/actual checksum 对比；原始或派生附件无法读取时在 `manifest.json` 标记 `missing`。Android 保存路径改用 `ActivityResultContracts.CreateDocument`，成功/失败通过 `LocalToastManager` 提示；ZIP 直接流式写入 SAF 输出流，每次只保留当前附件字节，避免同时持有全部附件和完整 ZIP。PDF 使用 `PdfDocument` 输出运行摘要、总览可靠性热力图、逐分析物浓度/主特征热力图、QC 统计与冻结配置追溯。定向 JVM 导出测试 3/3、API 35 PDF/Compose 导出测试 2/2 通过。
- 2026-07-20：工作包 5 最终验收完成。完整 `:app:testDebugUnitTest --rerun-tasks` 的 XML 汇总为 185 项、0 失败、0 错误、0 跳过；API 35 模拟器完整 `:app:connectedDebugAndroidTest` 为 54 项、0 失败、0 错误、0 跳过；`:app:assembleDebug` 成功。新旧结果分流、10×10/15×15/自定义阵列、仅信号、部分 QC 失败、帧级重拍、历史切换和导出格式均由真实 Android 仪器测试覆盖。本轮没有生成独立截图文件；交互证据位于 Compose 仪器测试报告，PDF 测试文件在测试缓存中验证后删除，ZIP/CSV 使用内存归档逐项校验。保留风险：历史附件若因外部 SAF 授权失效而不可读，ZIP 会明确标记 `missing`；大量分析物会增加 PDF 页数；系统文件选择器的厂商界面不做像素级自动化断言。

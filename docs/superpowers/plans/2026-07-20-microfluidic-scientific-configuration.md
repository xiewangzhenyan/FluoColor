# 微流控定位极性与荧光通道配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为微流控载体和荧光实验模板提供普通用户可理解的定位极性、定量通道编辑入口，稳定生成检测协调器要求的配置快照，避免用户手写 JSON 或在检测时才发现配置缺失。

**Architecture:** 载体表单负责生成 `pg-grid-carrier-v1` 定位配置，模板分析物表单负责生成荧光 `displayConfigJson`；页面只展示枚举选项，JSON 编解码集中在纯 Kotlin Codec。既有缺失配置的历史版本保持可读，新建和新版本必须保存显式选择。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、Room、Hilt、JUnit4、AndroidX Compose UI Test。

**执行约束：** 使用现有 `F:\Code\Android\FluoColor` 工作区，不创建 worktree、不提交；新增和修改业务代码写详细中文注释；用户可见文本全部进入中英文 `strings.xml`；Toast 使用 `LocalToastManager`；README 不写默认账号或密码。

---

### Task 1: 稳定配置 Codec 与表单状态

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/resources/ResourceProfileFormState.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/template/TemplateWizardModels.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/ScientificDetectionConfigCodec.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/ScientificDetectionConfigCodecTest.kt`
- Modify: `app/src/test/java/com/muc/fluocolorquant/ui/screens/settings/resources/ResourceProfileFormStateTest.kt`

- [x] **Step 1: 写失败测试——载体极性和荧光通道必须生成严格版本化 JSON**

```kotlin
@Test
fun `暗结构载体配置往返后仍为DARK`() {
    val json = ScientificDetectionConfigCodec.encodeCarrierLocator(GridTargetPolarity.DARK)
    assertEquals(GridTargetPolarity.DARK, ScientificDetectionConfigCodec.decodeCarrierPolarity(json))
}

@Test
fun `荧光绿色通道配置往返后仍为GREEN`() {
    val json = ScientificDetectionConfigCodec.encodeFluorescenceDisplay(FluorescenceChannel.GREEN)
    assertEquals(FluorescenceChannel.GREEN, ScientificDetectionConfigCodec.decodeFluorescenceChannel(json))
}
```

- [x] **Step 2: 运行测试并确认因 Codec 不存在而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*ScientificDetectionConfigCodecTest"`

Expected: FAIL，报 `Unresolved reference: ScientificDetectionConfigCodec`。

- [x] **Step 3: 实现严格 Codec 和草稿字段**

```kotlin
object ScientificDetectionConfigCodec {
    const val CARRIER_SCHEMA = "pg-grid-carrier-v1"
    const val FLUORESCENCE_SCHEMA = "fluorescence-display-v1"

    fun encodeCarrierLocator(polarity: GridTargetPolarity): String
    fun decodeCarrierPolarity(json: String?): GridTargetPolarity?
    fun encodeFluorescenceDisplay(channel: FluorescenceChannel): String
    fun decodeFluorescenceChannel(json: String?): FluorescenceChannel?
}
```

`CarrierProfileDraft` 增加非空 `targetPolarity`；10×10/4×4 预设为 `DARK`，15×15 预设为 `BRIGHT`。`TemplateAnalyteDraft` 增加非空 `fluorescenceChannel`，默认 `GREEN`。未知 schema、未知枚举和损坏 JSON 返回 `null`，不得猜测。

- [x] **Step 4: 运行纯 Kotlin 测试并确认通过**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*ScientificDetectionConfigCodecTest" --tests "*ResourceProfileFormStateTest"`

Expected: PASS。

---

### Task 2: 载体库保存和编辑定位极性

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/CarrierProfileViewModel.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/resources/CarrierProfileManagementScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Test: `app/src/test/java/com/muc/fluocolorquant/ui/viewmodels/CarrierProfileViewModelTest.kt`

- [x] **Step 1: 写失败测试——微流控新版本必须保存用户选择的极性**

测试创建 10×10 `DARK` 和 15×15 `BRIGHT` 草稿，保存后解析 `locatorConfigJson`，分别得到 `DARK/BRIGHT`；从已有版本打开编辑器时必须恢复原选择。

- [x] **Step 2: 运行并确认现有 ViewModel 仍复制旧 JSON 而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*CarrierProfileViewModelTest"`

- [x] **Step 3: 实现保存、恢复和 Material 3 选择控件**

微流控载体编辑页在“位点形状”后显示两个单选 FilterChip：暗色方块/暗结构、亮色光点/亮结构，并给出一句“选择相机图像中实际目标相对背景更暗还是更亮”的说明。孔板不显示该字段，也不写入 PG-Grid 配置。

- [x] **Step 4: 运行 ViewModel 与资源表单测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*CarrierProfileViewModelTest" --tests "*ResourceProfileFormStateTest"`

Expected: PASS。

---

### Task 3: 实验模板按分析物保存荧光通道

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ExperimentTemplateWizardViewModel.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/template/ExperimentTemplateWizardScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Test: `app/src/test/java/com/muc/fluocolorquant/ui/viewmodels/ExperimentTemplateWizardViewModelTest.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/ui/screens/settings/template/ExperimentTemplateWizardScreenTest.kt`

- [x] **Step 1: 写失败测试——荧光通道必须随模板版本保存并恢复**

荧光模板的分析物选择 `RED/GREEN/BLUE/GRAY` 后，`TemplateAnalyteConfig.displayConfigJson` 必须保存相同通道；打开下一版本必须恢复。比色模板不显示通道控件，也不写入荧光配置。

- [x] **Step 2: 运行测试并确认当前 displayConfigJson 始终为空而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*ExperimentTemplateWizardViewModelTest"`

- [x] **Step 3: 实现向导字段、保存恢复和按模态显示**

分析物卡片在检测模态为荧光时显示四个通道 FilterChip，并说明“选择该分析物用于定量的相机通道”；默认绿色只用于新建草稿，历史损坏 JSON 必须在发布预检中显示不完整，不能静默解释为绿色。

- [x] **Step 4: 运行 ViewModel 与 Compose UI 测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*ExperimentTemplateWizardViewModelTest"`

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.ui.screens.settings.template.ExperimentTemplateWizardScreenTest`

Expected: PASS。

---

### Task 4: 检测预检贯通与回归

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/domain/detection/GridDetectionCoordinator.kt`
- Modify: `docs/superpowers/plans/2026-07-20-microfluidic-scientific-configuration.md`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/GridDetectionCoordinatorTest.kt`

- [x] **Step 1: 将协调器解析切换到统一 Codec**

删除协调器内部重复 JSON 解析函数，统一使用 `ScientificDetectionConfigCodec`；保留缺失、损坏或未知 schema 时的稳定阻止原因。

- [x] **Step 2: 运行完整回归**

Run: `./gradlew.bat :app:testDebugUnitTest`

Run: `./gradlew.bat :app:connectedDebugAndroidTest`

Run: `./gradlew.bat :app:assembleDebug`

Expected: 全部 PASS。

- [x] **Step 3: 更新本文件实施记录**

记录实际命令、测试数量、最终 JSON 示例和保留风险；README 不写默认账户信息。

---

## 完成门槛

- [x] 普通用户无需输入 JSON 即可创建可检测的微流控载体和荧光模板。
- [x] 10×10 暗结构、15×15 亮结构和自定义选择可正确保存与恢复。
- [x] 每个荧光分析物独立保存定量通道，比色模板不出现无关字段。
- [x] 损坏或旧 schema 不静默补默认值，发布/检测预检明确阻止。
- [x] 所有新增文本均有中英文资源，所有新增业务代码含详细中文注释。
- [x] JVM、Compose 仪器测试和 Debug 构建全部通过。

## 实施记录

- 2026-07-20：完成现状审计。检测协调器已严格要求 `pg-grid-carrier-v1.targetPolarity` 和荧光 `displayConfigJson.fluorescenceChannel`，但载体库和模板向导尚无编辑入口；确定由两个表单生成配置，检测页不新增临时开关。
- 2026-07-20：完成统一 `ScientificDetectionConfigCodec`。载体配置固定为 `{"schemaVersion":"pg-grid-carrier-v1","targetPolarity":"DARK|BRIGHT"}`，荧光配置固定为 `{"schemaVersion":"fluorescence-display-v1","fluorescenceChannel":"RED|GREEN|BLUE|GRAY"}`；损坏、空值、未知 schema 和未知枚举均返回 `null`，由保存/发布/检测预检明确阻止。
- 2026-07-20：载体库已增加“目标更暗/目标更亮”Material 3 选择控件。10×10 和兼容 4×4 预设为 DARK，15×15 预设为 BRIGHT；打开已有版本会恢复 JSON 中的真实选择，孔板不显示也不保存 PG-Grid 配置。
- 2026-07-20：实验模板向导已在荧光模态下为每个分析物显示红、绿、蓝、灰度四个定量通道，随模板版本保存和恢复；比色模态不写入该配置。旧模板缺失通道时保持未配置，不静默视为绿色。
- 2026-07-20：协调器删除重复 JSON 解析并统一调用严格 Codec。TDD 记录包括 Codec 缺失编译失败、载体 JSON 断言失败、模板 `displayConfigJson` 保存/恢复断言失败，随后全部转绿。
- 2026-07-20：完整验证通过：`:app:testDebugUnitTest :app:assembleDebug` 成功；API 35 模拟器完整 `:app:connectedDebugAndroidTest` 为 32/32 通过。集成测试夹具同步升级到 `fluorescence-display-v1`，生产解析未放宽。README 未写入默认账户信息。

# 微流控实验模板向导与阵列位点编辑器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将旧单分析物孔板模板升级为支持微流控 10×10、15×15、自定义阵列、多分析物和批量位点分配的版本化实验模板向导。

**Architecture:** Room 11 将旧模板主表中的单分析物和旧曲线外键降级为可空兼容字段，新的科学配置统一存入 `TemplateAnalyteConfig` 与 `TemplateSiteAssignment`。纯 Kotlin 草稿模型负责步骤校验和阵列选择操作，Repository 负责原子保存、发布与版本切换，Compose 向导只消费 ViewModel 的不可变状态。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、MVVM、Room、Hilt、Coroutines/Flow、JUnit4、AndroidJUnit4。

---

## 文件结构

- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/template/TemplateWizardModels.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/template/TemplateArrayLayoutEditor.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/template/ExperimentTemplateWizardScreen.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ExperimentTemplateWizardViewModel.kt`
- Create: `app/src/test/java/com/muc/fluocolorquant/ui/screens/settings/template/TemplateWizardModelsTest.kt`
- Create: `app/src/test/java/com/muc/fluocolorquant/data/repository/ExperimentTemplateRepositoryTest.kt`
- Create: `app/src/test/java/com/muc/fluocolorquant/ui/viewmodels/ExperimentTemplateWizardViewModelTest.kt`
- Create: `app/src/androidTest/java/com/muc/fluocolorquant/ui/screens/settings/template/TemplateArrayLayoutEditorTest.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/enums/DomainTypes.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/model/ExperimentTemplate.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/ExperimentTemplateDao.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/repository/ExperimentTemplateRepository.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/repository/ExperimentTemplateRepositoryImpl.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/migration/DatabaseMigrations.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/DatabaseModule.kt`
- Modify: `app/src/androidTest/java/com/muc/fluocolorquant/data/AppDatabaseMigrationTest.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/ExperimentTemplateManagementScreen.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ExperimentTemplateViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `README.md`

### Task 1: 模板草稿、角色和阵列选择契约

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/enums/DomainTypes.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/template/TemplateWizardModels.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/ui/screens/settings/template/TemplateWizardModelsTest.kt`

- [ ] **Step 1: 写失败的草稿与阵列操作测试**

测试必须覆盖：10×10/15×15 坐标命名为 `R01C01`；矩形选择包含闭区间全部位点；批量分配保留分析物、角色、标准浓度和重复组；发布校验拒绝未绑定载体/设备、未覆盖全部位点、缺少样本或分析物空白的模板。

- [ ] **Step 2: 运行测试并确认 RED**

Run: `./gradlew.bat testDebugUnitTest --tests "*TemplateWizardModelsTest"`

Expected: FAIL because `TemplateWizardDraft`、`TemplateSiteRole` 和阵列操作尚不存在。

- [ ] **Step 3: 实现稳定角色枚举和纯 Kotlin 草稿模型**

稳定角色编码为 `SAMPLE / STANDARD / BLANK / NEGATIVE_CONTROL / POSITIVE_CONTROL / REFERENCE / DISABLED`；坐标使用零基行列持久化，显示名称统一由 `R%02dC%02d` 生成。草稿和布局操作不得依赖 Android Context。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run: `./gradlew.bat testDebugUnitTest --tests "*TemplateWizardModelsTest"`

Expected: PASS.

### Task 2: Room 11 兼容迁移与模板主表解耦

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/model/ExperimentTemplate.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/migration/DatabaseMigrations.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/DatabaseModule.kt`
- Modify: `app/src/androidTest/java/com/muc/fluocolorquant/data/AppDatabaseMigrationTest.kt`

- [ ] **Step 1: 写 10→11 迁移失败测试**

测试先创建 Room 10 数据库并插入旧模板，再迁移到 11；断言旧数据保留，并能插入 `analyteId = NULL`、`fkCurveModelId = NULL` 的新模板主档。

- [ ] **Step 2: 运行迁移测试并确认 RED**

Run: `./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.data.AppDatabaseMigrationTest"`

- [ ] **Step 3: 重建 `experiment_templates` 表**

迁移关闭外键检查后创建临时表，复制全部旧列，删除旧表并改名；旧 `analyteId` 与 `fkCurveModelId` 改为可空且删除语义改为 `SET NULL`，其余 10 版字段保持不变。更新 Room schema 到 11，并在 DatabaseModule 注册迁移。

- [ ] **Step 4: 修复旧兼容调用的可空处理并确认 GREEN**

旧孔板流程只有在兼容字段非空时才加载旧曲线；新模板以子表为准。

### Task 3: 模板 Bundle Repository、发布和版本切换

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/ExperimentTemplateDao.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/repository/ExperimentTemplateRepository.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/repository/ExperimentTemplateRepositoryImpl.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/data/repository/ExperimentTemplateRepositoryTest.kt`

- [ ] **Step 1: 写失败的事务和版本测试**

覆盖原子保存主档/分析物/位点；草稿可更新；发布模板禁止原地更新；创建 v2 时 v1 保持发布，v2 发布后才归档 v1；归档不物理删除子表。

- [ ] **Step 2: 运行测试并确认 RED**

Run: `./gradlew.bat testDebugUnitTest --tests "*ExperimentTemplateRepositoryTest"`

- [ ] **Step 3: 实现 `ExperimentTemplateBundle` 和事务方法**

Repository 创建新 ID 时同步重写所有子项 `templateId` 和主键。发布使用同一 Room 事务归档同名旧发布版本并更新 `publishedAt`。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run: `./gradlew.bat testDebugUnitTest --tests "*ExperimentTemplateRepositoryTest"`

### Task 4: 模板向导 ViewModel 与资源兼容筛选

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ExperimentTemplateWizardViewModel.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/ui/viewmodels/ExperimentTemplateWizardViewModelTest.kt`

- [ ] **Step 1: 写失败的向导状态测试**

覆盖步骤前进校验、载体变化重建阵列、检测模态变化重置协议、添加多个分析物、仅显示已发布且模态/载体/设备兼容的分析模型、批量位点分配、草稿保存和发布。

- [ ] **Step 2: 运行测试并确认 RED**

Run: `./gradlew.bat testDebugUnitTest --tests "*ExperimentTemplateWizardViewModelTest"`

- [ ] **Step 3: 实现不可变 UI 状态和稳定事件码**

ViewModel 同时收集载体、采集设备、分析物、试剂和分析模型；事件只传验证错误码、保存成功、发布成功和操作失败，不硬编码用户文本。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run: `./gradlew.bat testDebugUnitTest --tests "*ExperimentTemplateWizardViewModelTest"`

### Task 5: Material 3 多步骤向导和阵列位点编辑器

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/template/TemplateArrayLayoutEditor.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/template/ExperimentTemplateWizardScreen.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/ExperimentTemplateManagementScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/ui/screens/settings/template/TemplateArrayLayoutEditorTest.kt`

- [ ] **Step 1: 写失败的 Compose 语义测试**

断言 10×10 显示 100 个位点语义节点、点击位点进入选择、点击行列头进行批量选择、角色按钮对选区触发批量分配、15×15 编辑器可以双向滚动。

- [ ] **Step 2: 实现向导页面**

步骤为：基本信息、检测与资源、多分析物、位点布局、QC 与发布检查。4×4 只在“更多规格/旧规格”中显示；载体几何来自已保存载体档案，模板不能临时改行列。

- [ ] **Step 3: 实现阵列编辑器**

单元格显示角色色块和 `R01C01`；支持单点、多选、两点矩形框选、整行、整列、全选、清除选择和批量应用。15×15 使用固定可读单元格配合双向滚动，不把单元格压缩为不可点击像素。

- [ ] **Step 4: 编译并运行 UI 测试**

Run: `./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.ui.screens.settings.template.TemplateArrayLayoutEditorTest"`

### Task 6: README、完整验证和模拟器验收

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新 README**

记录 Room 11、模板生命周期、多分析物、微流控阵列坐标、发布门控和旧模板兼容行为。

- [ ] **Step 2: 完整验证**

Run: `./gradlew.bat testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`

Run: `./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.data.AppDatabaseMigrationTest,com.muc.fluocolorquant.ui.screens.settings.template.TemplateArrayLayoutEditorTest"`

- [ ] **Step 3: 静态检查**

确认中英文字符串 ID 完全对应，且 `android.widget.Toast`、`fallbackToDestructiveMigration` 无匹配。

- [ ] **Step 4: 模拟器验收**

创建一个 10×10、两个分析物的微流控模板；为不同区域批量分配样本、空白和标准位；缺少兼容资源或位点未覆盖时发布被阻止；补齐后发布成功；创建 v2 时 v1 保持发布直到 v2 发布。

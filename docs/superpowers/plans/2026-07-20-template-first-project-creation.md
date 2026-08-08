# 模板优先的新建项目 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将旧的“手工选择模态、分析方法、分析物和孔板行列”流程重构为“选择已发布实验模板、冻结完整科学快照、完成样本映射与实验前检查、原子创建项目并路由到对应检测链路”的模板优先流程。

**Architecture:** 新增纯 Kotlin 的项目快照、覆盖记录、预检和检测路由契约；由一个可注入的项目创建协调器负责重新读取已发布模板及关联载体、采集设备、分析物和分析模型，并在创建瞬间生成不可变 JSON。`ProjectDao` 负责项目主档与全部 `ProjectAnalyteJoin` 的单事务写入，`ProjectViewModel` 只维护页面状态和一次性事件，Compose 页面不再自行拼装科研配置。

**Tech Stack:** Kotlin 1.9、Jetpack Compose Material 3、Room 2.6、Hilt、Coroutines/Flow、Gson、JUnit4、Compose UI Test。

**工作区约束:** 当前 `main` 工作区包含大量用户未提交改动，且用户要求直接在 `F:\Code\Android\FluoColor` 继续。本计划不创建 C 盘 worktree，不执行 `git add` 或 `git commit`，以测试检查点替代提交检查点。

---

## 文件结构

- Create: `app/src/main/java/com/muc/fluocolorquant/domain/project/TemplateProjectContracts.kt`：项目模板快照、项目覆盖、预检问题、创建请求/结果、检测目的地和 Gson 编解码契约。
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/project/TemplateProjectCreationCoordinator.kt`：加载已发布模板、解析完整资源、发布态复核、兼容性预检、快照构建和原子创建编排。
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/project/TemplateProjectFormState.kt`：不依赖 Android 的表单验证、样本位点映射和按钮启用规则。
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/ProjectDao.kt`：增加严格插入和项目—分析物事务写入。
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/repository/ProjectRepository.kt`：暴露原子创建接口。
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/repository/ProjectRepositoryImpl.kt`：委托 Room 事务。
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/repository/ExperimentTemplateRepository.kt`：提供已发布模板流或稳定筛选入口。
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ProjectViewModel.kt`：替换旧检测后模板逻辑为模板优先状态机。
- Replace: `app/src/main/java/com/muc/fluocolorquant/ui/screens/project/NewProjectScreen.kt`：实现模板选择、项目批次、样本批次、样本槽位、采集来源、预检摘要和高级入口。
- Modify: `app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh/strings.xml`：补齐全部中英文可见文本。
- Modify: `README.md`：记录模板优先项目流程；不得加入默认账号和密码。
- Create: `app/src/test/java/com/muc/fluocolorquant/domain/project/TemplateProjectContractsTest.kt`。
- Create: `app/src/test/java/com/muc/fluocolorquant/domain/project/TemplateProjectCreationCoordinatorTest.kt`。
- Create: `app/src/test/java/com/muc/fluocolorquant/data/repository/ProjectRepositoryTest.kt`。
- Create: `app/src/test/java/com/muc/fluocolorquant/ui/screens/project/TemplateProjectFormStateTest.kt`。
- Create: `app/src/test/java/com/muc/fluocolorquant/ui/viewmodels/ProjectViewModelTest.kt`。
- Create: `app/src/androidTest/java/com/muc/fluocolorquant/ui/screens/project/NewProjectScreenTest.kt`。

---

### Task 1: 冻结项目快照与检测路由契约

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/project/TemplateProjectContracts.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/project/TemplateProjectContractsTest.kt`

- [x] **Step 1: 写入失败测试，证明快照可以往返且路由不静默回退**

```kotlin
@Test
fun `模板快照往返后保留模板载体设备模型和位点`() {
    val json = TemplateProjectSnapshotCodec.encode(snapshot())
    val decoded = TemplateProjectSnapshotCodec.decode(json)
    assertEquals("template-v2", decoded.template.id)
    assertEquals("carrier-10x10", decoded.carrierProfile.id)
    assertEquals("device-v1", decoded.acquisitionProfile.id)
    assertEquals("model-cea", decoded.analytes.single().analysisModel.model.id)
    assertEquals(100, decoded.siteAssignments.size)
}

@Test
fun `比色荧光终点进入阵列检测而单图光谱进入光谱标定`() {
    assertEquals(ProjectDetectionDestination.GRID_ENDPOINT,
        ProjectDetectionRouter.resolve("COLORIMETRIC", "ENDPOINT_ONLY", "GRID_SITES"))
    assertEquals(ProjectDetectionDestination.GRID_ENDPOINT,
        ProjectDetectionRouter.resolve("FLUORESCENCE", "ENDPOINT_ONLY", "GRID_SITES"))
    assertEquals(ProjectDetectionDestination.SPECTRUM_SINGLE,
        ProjectDetectionRouter.resolve("SPECTRUM", "SINGLE_SPECTRUM_ANALYSIS", "SPECTRAL_TRACKS"))
    assertEquals(ProjectDetectionDestination.LSPR_PAIRED,
        ProjectDetectionRouter.resolve("SPECTRUM", "LSPR_PAIRED_QUANTIFICATION", "SPECTRAL_TRACKS"))
}
```

- [x] **Step 2: 运行测试并确认因契约不存在而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.muc.fluocolorquant.domain.project.TemplateProjectContractsTest"`

Expected: FAIL，提示 `TemplateProjectSnapshotCodec` 或 `ProjectDetectionRouter` 未定义。

- [x] **Step 3: 实现最小完整契约**

```kotlin
data class TemplateProjectSnapshot(
    val schemaVersion: Int = 1,
    val frozenAtEpochMillis: Long,
    val template: ExperimentTemplate,
    val carrierProfile: CarrierProfile,
    val acquisitionProfile: AcquisitionProfile,
    val analytes: List<TemplateProjectAnalyteSnapshot>,
    val siteAssignments: List<TemplateSiteAssignment>
)

data class TemplateProjectAnalyteSnapshot(
    val analyte: Analyte,
    val templateConfig: TemplateAnalyteConfig,
    val analysisModel: AnalysisModelBundle
)

data class TemplateProjectOverrideSnapshot(
    val schemaVersion: Int = 1,
    val sampleSlotMapping: Map<String, String>,
    val reasons: Map<String, String> = emptyMap()
)

enum class ProjectDetectionDestination { GRID_ENDPOINT, SPECTRUM_SINGLE, LSPR_PAIRED, UNSUPPORTED }

object ProjectDetectionRouter {
    fun resolve(detectionMode: String?, inputProtocol: String?, readoutLayout: String?): ProjectDetectionDestination =
        when {
            detectionMode in setOf("COLORIMETRIC", "FLUORESCENCE") &&
                inputProtocol == "ENDPOINT_ONLY" && readoutLayout == "GRID_SITES" ->
                ProjectDetectionDestination.GRID_ENDPOINT
            detectionMode == "SPECTRUM" && inputProtocol == "SINGLE_SPECTRUM_ANALYSIS" ->
                ProjectDetectionDestination.SPECTRUM_SINGLE
            detectionMode == "SPECTRUM" && inputProtocol == "LSPR_PAIRED_QUANTIFICATION" ->
                ProjectDetectionDestination.LSPR_PAIRED
            else -> ProjectDetectionDestination.UNSUPPORTED
        }
}
```

编码器必须使用单一 `Gson` 实例，`decode` 对空文本和未知结构抛出明确异常，禁止返回空模板或第一个默认枚举。

- [x] **Step 4: 运行目标测试并确认通过**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.muc.fluocolorquant.domain.project.TemplateProjectContractsTest"`

Expected: PASS。

---

### Task 2: 实验前检查与完整配置解析

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/project/TemplateProjectCreationCoordinator.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/repository/ExperimentTemplateRepository.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/project/TemplateProjectCreationCoordinatorTest.kt`

- [x] **Step 1: 写入失败测试覆盖发布态、资源缺失和模型兼容性**

```kotlin
@Test
fun `只有已发布且资源模型完整兼容的模板可以创建项目`() = runTest {
    val result = coordinator.resolveTemplate("template-v1")
    assertTrue(result is TemplateResolution.Ready)
    val ready = result as TemplateResolution.Ready
    assertEquals(100, ready.configuration.snapshot.siteAssignments.size)
    assertTrue(ready.configuration.issues.isEmpty())
}

@Test
fun `模型模态或协议不匹配时大声失败`() = runTest {
    analysisModels["model-cea"] = modelBundle(detectionMode = "FLUORESCENCE")
    val result = coordinator.resolveTemplate("template-v1") as TemplateResolution.Blocked
    assertTrue(result.issues.any { it.code == TemplatePreflightCode.MODEL_MODALITY_MISMATCH })
}

@Test
fun `草稿模板不能进入普通新建项目`() = runTest {
    templates["template-v1"] = templateBundle(status = "DRAFT")
    val result = coordinator.resolveTemplate("template-v1") as TemplateResolution.Blocked
    assertTrue(result.issues.any { it.code == TemplatePreflightCode.TEMPLATE_NOT_PUBLISHED })
}
```

- [x] **Step 2: 运行测试并确认正确失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.muc.fluocolorquant.domain.project.TemplateProjectCreationCoordinatorTest"`

Expected: FAIL，缺少协调器和预检类型。

- [x] **Step 3: 实现解析与预检**

协调器必须逐项重新读取并验证：

```kotlin
enum class TemplatePreflightCode {
    TEMPLATE_NOT_FOUND, TEMPLATE_NOT_PUBLISHED,
    CARRIER_MISSING, CARRIER_ARCHIVED,
    ACQUISITION_MISSING, ACQUISITION_ARCHIVED,
    ANALYTE_CONFIG_MISSING, ANALYTE_MISSING,
    ANALYSIS_MODEL_MISSING, ANALYSIS_MODEL_NOT_PUBLISHED,
    MODEL_ANALYTE_MISMATCH, MODEL_MODALITY_MISMATCH,
    MODEL_PROTOCOL_MISMATCH, MODEL_CARRIER_MISMATCH,
    MODEL_ACQUISITION_MISMATCH, LAYOUT_INCOMPLETE,
    UNSUPPORTED_DETECTION_ROUTE
}
```

规则：

1. 普通入口只返回 `PUBLISHED` 模板，不自动选择第一项。
2. 载体和设备必须存在且未归档。
3. 每个 `TemplateAnalyteConfig` 必须解析出分析物和 `PUBLISHED` 模型。
4. 模型的分析物、模态、输入协议、载体类型和采集设备必须全部匹配。
5. 规则阵列必须覆盖载体全部行列，禁用位点也要有显式记录。
6. 当前工作包允许 `GRID_ENDPOINT` 和 `SPECTRUM_SINGLE`；LSPR 模板显示为尚未支持并阻止创建，直到工作包 7 接通。

- [x] **Step 4: 运行目标测试与模板仓库回归测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.muc.fluocolorquant.domain.project.TemplateProjectCreationCoordinatorTest" --tests "com.muc.fluocolorquant.data.repository.ExperimentTemplateRepositoryTest"`

Expected: PASS。

---

### Task 3: 项目与分析物关联原子保存

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/ProjectDao.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/repository/ProjectRepository.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/repository/ProjectRepositoryImpl.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/data/repository/ProjectRepositoryTest.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/data/AppDatabaseMigrationTest.kt`

- [x] **Step 1: 写入失败测试，要求仓库只调用一个原子 DAO 入口**

```kotlin
@Test
fun `模板项目通过单个事务入口保存项目和全部分析物关联`() = runTest {
    val dao = RecordingProjectDao()
    val repository = ProjectRepositoryImpl(dao)
    repository.createProjectWithAnalytes(project(), listOf(join("cea"), join("nse")))
    assertEquals(1, dao.atomicInsertCalls)
    assertEquals(listOf("cea", "nse"), dao.savedJoins.map { it.analyteId })
}
```

- [x] **Step 2: 运行测试并确认接口缺失失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.muc.fluocolorquant.data.repository.ProjectRepositoryTest"`

Expected: FAIL，缺少 `createProjectWithAnalytes`。

- [x] **Step 3: 增加 Room 事务和仓库接口**

```kotlin
@Insert(onConflict = OnConflictStrategy.ABORT)
suspend fun insertProjectStrict(project: Project)

@Insert(onConflict = OnConflictStrategy.ABORT)
suspend fun insertProjectAnalytesStrict(joins: List<ProjectAnalyteJoin>)

@Transaction
suspend fun insertProjectWithAnalytes(project: Project, joins: List<ProjectAnalyteJoin>) {
    require(joins.all { it.projectId == project.id }) { "项目分析物关联必须属于同一项目" }
    insertProjectStrict(project)
    if (joins.isNotEmpty()) insertProjectAnalytesStrict(joins)
}
```

仓库增加 `suspend fun createProjectWithAnalytes(project: Project, joins: List<ProjectAnalyteJoin>)`。旧 `createProject(Project)` 保留用于历史链路，但新页面不得调用。

- [x] **Step 4: 运行仓库测试和 Room 编译检查**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.muc.fluocolorquant.data.repository.ProjectRepositoryTest" :app:kaptDebugKotlin`

Expected: PASS，Room 生成代码无错误。

---

### Task 4: 完成创建协调器和项目科学字段映射

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/domain/project/TemplateProjectCreationCoordinator.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/domain/project/TemplateProjectContracts.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/project/TemplateProjectCreationCoordinatorTest.kt`

- [x] **Step 1: 写入失败测试，冻结快照并生成正确关联**

```kotlin
@Test
fun `创建时冻结模板版本并把每个分析物模型写入关联`() = runTest {
    val outcome = coordinator.create(
        TemplateProjectCreateRequest(
            name = "CEA 10×10 批次 01",
            templateId = "template-v2",
            projectBatch = "P-20260720",
            sampleBatch = "S-01",
            sampleSlotMapping = mapOf("R01C01" to "sample-001"),
            imageUri = "content://chip/1",
            userId = "1"
        )
    ) as TemplateProjectCreationOutcome.Created
    assertEquals(2, outcome.project.templateVersion)
    assertNotNull(outcome.project.templateSnapshotJson)
    assertEquals("P-20260720", outcome.project.projectBatch)
    assertEquals("S-01", outcome.project.sampleBatch)
    assertEquals("template-v2", savedJoins.single().fkTemplateId)
}
```

- [x] **Step 2: 运行测试确认因创建方法未完成而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.muc.fluocolorquant.domain.project.TemplateProjectCreationCoordinatorTest"`

Expected: FAIL。

- [x] **Step 3: 实现创建映射**

创建规则：

- `Project.detectionMode` 使用模板稳定模态编码。
- `rows/columns` 来自载体档案，绝不读取全局默认孔板设置。
- `analysisMethod` 使用模板模型类型摘要；多模型混合时写入 `TEMPLATE_MANAGED`。
- `templateId/templateVersion/templateSnapshotJson` 必须非空。
- `overrideJson` 保存样本槽位映射和所有覆盖原因；没有覆盖也写入带版本号的空对象。
- `ProjectAnalyteJoin` 的范围、单位来自模板分析物配置，`fkTemplateId` 指向已发布模板；标准曲线与智能模型不再通过旧字段伪装，统一模型 ID 只保存在模板快照和有效运行配置中。
- 创建前必须重新执行预检，防止用户选中后模板被归档或资源被替换。

- [x] **Step 4: 运行协调器全部测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.muc.fluocolorquant.domain.project.TemplateProjectCreationCoordinatorTest"`

Expected: PASS。

---

### Task 5: 表单状态和 ProjectViewModel 状态机

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/project/TemplateProjectFormState.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ProjectViewModel.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/ui/screens/project/TemplateProjectFormStateTest.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/ui/viewmodels/ProjectViewModelTest.kt`

- [x] **Step 1: 写入表单验证失败测试**

```kotlin
@Test
fun `未选择模板或图片时不能提交`() {
    assertFalse(TemplateProjectFormState().canSubmit)
    assertFalse(TemplateProjectFormState(name = "项目", selectedTemplateId = "t1").canSubmit)
}

@Test
fun `只有样本角色位点需要样本槽位`() {
    val state = stateWithSampleSites("R01C01", "R01C02")
    assertEquals(setOf("R01C01", "R01C02"), state.missingSampleSiteKeys)
    assertTrue(state.copy(sampleSlotMapping = mapOf(
        "R01C01" to "样本1", "R01C02" to "样本2"
    )).canSubmit)
}
```

- [x] **Step 2: 运行表单测试并确认失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.muc.fluocolorquant.ui.screens.project.TemplateProjectFormStateTest"`

Expected: FAIL。

- [x] **Step 3: 实现状态机并删除检测后模板状态**

`ProjectViewModel` 状态必须包含：加载中、已发布模板列表、当前模板解析结果、项目名称、项目/样本批次、样本位映射、图像 URI、是否显示高级研究入口、提交中和一次性创建事件。

必须删除：

- `_showTemplateSelectionDialog`
- `assignTemplateToProject()`
- 普通流程中的 `DetectionMode`、`AnalysisMethod`、手工行列和手工分析物配置状态
- 从设置读取默认行列的逻辑
- 自动选择第一项模板的任何代码

保留项目列表、项目详情和删除能力。高级无模板研究项目本工作包只提供明确入口说明和旧流程兼容路由，不得成为默认选中状态。

- [x] **Step 4: 运行状态和 ViewModel 测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.muc.fluocolorquant.ui.screens.project.TemplateProjectFormStateTest" --tests "com.muc.fluocolorquant.ui.viewmodels.ProjectViewModelTest"`

Expected: PASS。

---

### Task 6: Compose 模板优先新建项目页面

**Files:**
- Replace: `app/src/main/java/com/muc/fluocolorquant/ui/screens/project/NewProjectScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/ui/screens/project/NewProjectScreenTest.kt`

- [x] **Step 1: 写入失败 UI 测试**

```kotlin
@Test
fun normalFlowStartsWithPublishedTemplateAndDoesNotShowManualModalityControls() {
    composeRule.setContent { TestTheme { TemplateProjectContent(state = readyState(), onAction = {}) } }
    composeRule.onNodeWithTag("template_selector").assertIsDisplayed()
    composeRule.onNodeWithTag("project_name_input").assertIsDisplayed()
    composeRule.onNodeWithTag("manual_detection_mode").assertDoesNotExist()
    composeRule.onNodeWithTag("manual_rows_columns").assertDoesNotExist()
}

@Test
fun preflightFailureDisablesCreateAndShowsIssueList() {
    composeRule.setContent { TestTheme { TemplateProjectContent(state = blockedState(), onAction = {}) } }
    composeRule.onNodeWithTag("preflight_issue_list").assertIsDisplayed()
    composeRule.onNodeWithTag("create_project_button").assertIsNotEnabled()
}
```

- [x] **Step 2: 运行 UI 测试并确认页面契约不存在**

Run: `./gradlew.bat :app:compileDebugAndroidTestKotlin`

Expected: FAIL，缺少新的可测试内容函数或标签。

- [x] **Step 3: 重写页面**

页面按以下顺序呈现：

1. 顶部说明：实验模板是项目上游配置。
2. 已发布模板选择卡；为空时引导进入实验模板库，不自动选择。
3. 模板摘要：模态、输入协议、载体、行列、设备、多分析物和模型数量，全部只读锁定。
4. 项目名称、项目批次、样本批次。
5. 仅对 `SAMPLE` 且启用的位点显示样本槽位输入；预填 `defaultSampleSlot`，允许用户修改并记录在 `overrideJson`。
6. 图库导入或 CameraX 拍摄；使用模板模态作为 `captureMode`，单图光谱使用模板轨道数或默认 1。
7. 实验前检查摘要；错误逐项展示并禁用创建按钮。
8. “高级：无模板研究项目”折叠入口，明确不适合正式定量和追溯；不影响普通流程。

全部文本必须来自中英文 `strings.xml`；所有提示通过 `LocalToastManager`；删除硬编码“未获取到运行ID”文本和全部 `android.widget.Toast` 引用。

- [x] **Step 4: 接通创建事件和导航**

```kotlin
when (event.destination) {
    ProjectDetectionDestination.GRID_ENDPOINT ->
        navController.navigate(Screen.WellDetection.createRoute(Uri.encode(event.imageUri), event.projectId))
    ProjectDetectionDestination.SPECTRUM_SINGLE ->
        navController.navigate(Screen.SpectrumCalibration.createRoute(event.projectId, event.imageUri))
    else -> toastManager.showToast(unsupportedRouteMessage, ToastType.ERROR)
}
```

普通比色/荧光仍只上传一张反应后终点图，不出现前后图控件。

- [x] **Step 5: 编译 Compose UI 测试**

Run: `./gradlew.bat :app:compileDebugAndroidTestKotlin`

Expected: PASS。

---

### Task 7: 回归验证、文档和工作包 3 验收

**Files:**
- Modify: `README.md`
- Verify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/project/NewProjectScreen.kt`
- Verify: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ProjectViewModel.kt`

- [x] **Step 1: 更新 README 当前进度**

在“核心业务流程”“关键页面”“当前优化方向”中记录：正常项目必须选择已发布模板；项目冻结载体、设备、分析物、模型和布局快照；样本映射属于项目覆盖；比色/荧光为终点单图；微流控几何是下一工作包。不得写入默认账号、密码或默认账户初始化行为。

- [x] **Step 2: 扫描遗留逻辑和违规文本**

Run: `rg -n "showTemplateSelectionDialog|assignTemplateToProject|plate_size_limit_exceeded|未获取到运行ID|android\.widget\.Toast" app/src/main/java/com/muc/fluocolorquant/ui/screens/project app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/ProjectViewModel.kt`

Expected: 无匹配。

- [x] **Step 3: 运行工作包 3 单元测试**

Run: `./gradlew.bat :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL。

- [x] **Step 4: 构建 Debug APK**

Run: `./gradlew.bat :app:assembleDebug`

Expected: BUILD SUCCESSFUL。

- [x] **Step 5: 有模拟器时运行项目创建 UI 测试**

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.ui.screens.project.NewProjectScreenTest`

Expected: 所有模板优先 UI 测试通过。

- [x] **Step 6: 真实 Room 数据库证据检查**

通过 `TemplateProjectCreationDatabaseTest` 在真实 Room 内存数据库中创建一个 10×10 已发布微流控模板项目后，检查：

- `projects.templateId` 和 `templateVersion` 非空。
- `templateSnapshotJson` 可解码且含 100 个有序位点。
- `rows=10`、`columns=10` 来自载体档案。
- `project_analytes_join` 数量等于模板分析物数量，且全部 `fkTemplateId` 正确。
- 修改或归档模板后，项目快照 JSON 不改变。

Expected: 全部满足后，工作包 3 才算完成并进入工作包 4 的 PG-Grid 移植。

---

## 自检

- 规格覆盖：模板上游、发布态筛选、不可变快照、样本映射、实验前检查、原子保存、路由、无模板高级入口、终点单图与旧单图光谱兼容均有对应任务。
- 占位符扫描：计划无 `TBD`、`TODO` 或“以后补充适当处理”等未定义步骤。
- 类型一致性：所有任务统一使用 `TemplateProjectSnapshot`、`TemplateProjectCreateRequest`、`TemplateProjectCreationOutcome`、`TemplateResolution`、`ProjectDetectionDestination`。
- 范围边界：本计划不提前实现 PG-Grid、通用阵列结果页或 LSPR 配对算法，但会阻止尚未接通的 LSPR 模板创建，避免生成无法继续的科研项目。

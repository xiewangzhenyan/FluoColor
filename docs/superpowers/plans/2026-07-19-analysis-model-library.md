# 统一分析模型库 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将旧“曲线模型库”升级为可版本化、可发布、可做兼容性约束的统一分析模型库，同时保留旧曲线入口供历史项目使用。

**Architecture:** 继续使用 Room 10 中已存在的 `AnalysisModel`、`StandardCurveDefinition`、`CalibrationPoint` 和 `DeepLearningModelDefinition` 表，不新增数据库版本。领域层新增稳定的生命周期、主特征和表单校验；Repository 负责草稿保存、发布时归档同名旧版本和创建下一版本；Compose 页面只消费 ViewModel 的不可变状态和稳定事件。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、MVVM、Room、Hilt、Coroutines/Flow、JUnit4、AndroidJUnit4。

---

## 文件结构

- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/AnalysisModelRepository.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/AnalysisModelRepositoryImpl.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/analysis/AnalysisModelFormState.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/analysis/AnalysisModelManagementScreen.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/AnalysisModelViewModel.kt`
- Create: `app/src/test/java/com/muc/fluocolorquant/data/repository/AnalysisModelRepositoryTest.kt`
- Create: `app/src/test/java/com/muc/fluocolorquant/ui/screens/settings/analysis/AnalysisModelFormStateTest.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/enums/DomainTypes.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/model/AnalysisModelEntities.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/AnalysisModelDao.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/RepositoryModule.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `README.md`

### Task 1: 分析模型稳定领域契约与表单校验

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/enums/DomainTypes.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/model/AnalysisModelEntities.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/analysis/AnalysisModelFormState.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/ui/screens/settings/analysis/AnalysisModelFormStateTest.kt`

- [ ] **Step 1: Write the failing form contract tests**

```kotlin
@Test
fun `比色和荧光只允许终点输入协议`() {
    assertTrue(AnalysisModelDraft(detectionMode = "COLORIMETRIC").allowedProtocols == setOf("ENDPOINT_ONLY"))
    assertTrue(AnalysisModelDraft(detectionMode = "FLUORESCENCE").allowedProtocols == setOf("ENDPOINT_ONLY"))
}

@Test
fun `发布校验拒绝缺少设备兼容范围的模型`() {
    val errors = completeDraft.copy(compatibleAcquisitionProfileIds = emptySet()).validateForPublication()
    assertTrue(AnalysisModelFormError.ACQUISITION_PROFILE_REQUIRED in errors)
}

@Test
fun `可靠范围上限必须大于下限`() {
    val errors = completeDraft.copy(reliableRangeMinInput = "10", reliableRangeMaxInput = "1").validateForDraft()
    assertTrue(AnalysisModelFormError.RELIABLE_RANGE_INVALID in errors)
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalysisModelFormStateTest"`

Expected: FAIL because `AnalysisModelDraft` and lifecycle/feature enums do not exist.

- [ ] **Step 3: Add stable lifecycle and primary-feature enums**

```kotlin
enum class AnalysisModelLifecycleStatus(override val code: String) : StableDomainCode {
    DRAFT("DRAFT"), PUBLISHED("PUBLISHED"), ARCHIVED("ARCHIVED"), LEGACY("LEGACY")
}

enum class AnalysisPrimaryFeature(override val code: String) : StableDomainCode {
    DELTA_E_2000("DELTA_E_2000"),
    OPTICAL_DENSITY("OPTICAL_DENSITY"),
    NET_FLUORESCENCE_INTENSITY("NET_FLUORESCENCE_INTENSITY"),
    INTEGRATED_FLUORESCENCE_INTENSITY("INTEGRATED_FLUORESCENCE_INTENSITY"),
    FLUORESCENCE_SNR("FLUORESCENCE_SNR"),
    PEAK_WAVELENGTH_NM("PEAK_WAVELENGTH_NM"),
    DELTA_PEAK_WAVELENGTH_NM("DELTA_PEAK_WAVELENGTH_NM")
}
```

- [ ] **Step 4: Implement `AnalysisModelDraft` and separate draft/publication validation**

```kotlin
data class AnalysisModelDraft(
    val name: String = "",
    val modelType: AnalysisModelType = AnalysisModelType.STANDARD_CURVE,
    val analyteId: String = "",
    val detectionMode: String = "COLORIMETRIC",
    val inputProtocol: String = InputProtocol.ENDPOINT_ONLY.code,
    val primaryFeature: String = AnalysisPrimaryFeature.DELTA_E_2000.code,
    val processorName: String = "",
    val processorVersion: String = "",
    val concentrationUnit: String = "",
    val reliableRangeMinInput: String = "",
    val reliableRangeMaxInput: String = "",
    val compatibleCarrierTypes: Set<String> = emptySet(),
    val compatibleAcquisitionProfileIds: Set<String> = emptySet(),
    val fittingFunction: String = "linear",
    val parametersJson: String = "{}",
    val modelFileName: String = "",
    val checksumSha256: String = "",
    val inputWidthInput: String = "",
    val inputHeightInput: String = "",
    val normalizationJson: String = "{}",
    val trainingDataVersion: String = ""
)
```

Draft save validates identity, protocol/feature compatibility and numeric ranges. Publication additionally requires at least one carrier, one acquisition profile and a complete type-specific definition.

- [ ] **Step 5: Run tests and verify GREEN**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalysisModelFormStateTest"`

Expected: PASS.

### Task 2: Repository bundle persistence, publication, versioning and archive

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/AnalysisModelDao.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/AnalysisModelRepository.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/AnalysisModelRepositoryImpl.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/RepositoryModule.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/data/repository/AnalysisModelRepositoryTest.kt`

- [ ] **Step 1: Write failing versioning tests with a Fake DAO**

```kotlin
@Test
fun `创建下一版本时旧发布版本保持发布直到新版本发布`() = runBlocking {
    val v1 = repository.createDraft(bundle(name = "CEA 比色模型"))
    repository.publish(v1.model.id)
    val v2 = repository.createNextDraft(v1.model.id)
    assertEquals("PUBLISHED", dao.getById(v1.model.id)?.status)
    assertEquals("DRAFT", dao.getById(v2.model.id)?.status)
    repository.publish(v2.model.id)
    assertEquals("ARCHIVED", dao.getById(v1.model.id)?.status)
    assertEquals("PUBLISHED", dao.getById(v2.model.id)?.status)
}
```

- [ ] **Step 2: Run repository tests and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalysisModelRepositoryTest"`

Expected: FAIL because the repository and transaction methods do not exist.

- [ ] **Step 3: Add DAO queries and transaction boundaries**

```kotlin
@Query("SELECT MAX(version) FROM analysis_models WHERE name = :name")
suspend fun getLatestVersionByName(name: String): Int?

@Query("UPDATE analysis_models SET status = :status, updatedAt = :updatedAt WHERE id = :id")
suspend fun updateStatus(id: String, status: String, updatedAt: Date)

@Query("UPDATE analysis_models SET status = 'ARCHIVED', updatedAt = :updatedAt WHERE name = :name AND status = 'PUBLISHED' AND id != :exceptId")
suspend fun archivePublishedSiblings(name: String, exceptId: String, updatedAt: Date)
```

- [ ] **Step 4: Implement repository bundle methods**

```kotlin
data class AnalysisModelBundle(
    val model: AnalysisModel,
    val standardCurve: StandardCurveDefinition? = null,
    val deepLearning: DeepLearningModelDefinition? = null,
    val calibrationPoints: List<CalibrationPoint> = emptyList()
)

interface AnalysisModelRepository {
    fun observeAll(): Flow<List<AnalysisModel>>
    suspend fun getBundle(id: String): AnalysisModelBundle?
    suspend fun createDraft(bundle: AnalysisModelBundle): AnalysisModelBundle
    suspend fun updateDraft(bundle: AnalysisModelBundle)
    suspend fun createNextDraft(previousId: String): AnalysisModelBundle
    suspend fun publish(id: String)
    suspend fun archive(id: String)
}
```

`publish()` must archive same-name published siblings and publish the target inside one Room transaction. `updateDraft()` must reject non-draft models.

- [ ] **Step 5: Bind repository through Hilt and verify GREEN**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalysisModelRepositoryTest"`

Expected: PASS.

### Task 3: ViewModel state and one-time events

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/AnalysisModelViewModel.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/ui/viewmodels/AnalysisModelViewModelTest.kt`

- [ ] **Step 1: Write failing state tests**

```kotlin
@Test
fun `type and status filters are combined`() = runTest {
    viewModel.selectType(AnalysisModelType.STANDARD_CURVE)
    viewModel.selectStatus(AnalysisModelStatusFilter.PUBLISHED)
    assertEquals(listOf(publishedCurve), viewModel.uiState.value.visibleModels)
}
```

- [ ] **Step 2: Run ViewModel tests and verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalysisModelViewModelTest"`

Expected: FAIL because the ViewModel does not exist.

- [ ] **Step 3: Implement immutable UI state and events**

```kotlin
data class AnalysisModelUiState(
    val models: List<AnalysisModel> = emptyList(),
    val analytes: List<Analyte> = emptyList(),
    val acquisitionProfiles: List<AcquisitionProfile> = emptyList(),
    val selectedType: AnalysisModelType? = null,
    val selectedStatus: AnalysisModelStatusFilter = AnalysisModelStatusFilter.ALL,
    val draft: AnalysisModelDraft = AnalysisModelDraft(),
    val editorMode: AnalysisModelEditorMode? = null,
    val isSaving: Boolean = false
)
```

Events use stable codes only: validation failed, draft saved, version created, published, archived and operation failed. Compose maps them to localized strings and `LocalToastManager`.

- [ ] **Step 4: Run ViewModel tests and verify GREEN**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalysisModelViewModelTest"`

Expected: PASS.

### Task 4: Material 3 analysis-model library UI and legacy compatibility entry

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/analysis/AnalysisModelManagementScreen.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: Replace the old settings entry label and route content**

Keep `Screen.CurveModelLibrary.route` for route compatibility, but render `AnalysisModelManagementScreen`. Rename the settings item to “分析模型库 / Analysis Model Library” and remove the compatibility badge from the main entry.

- [ ] **Step 2: Build the list page**

The page contains:

```text
Hero summary: total / published / standard curves / intelligent models
Type chips: all / standard curve / intelligent model
Status chips: all / draft / published / archived / legacy
Model card: name, vN, analyte, modality, protocol, feature, processor, range, status
Actions: edit draft | create new version | publish | archive
```

- [ ] **Step 3: Build the editor bottom sheet**

Use a vertically scrollable `ModalBottomSheet`. All visible text comes from both `strings.xml` files. Type-specific sections only appear for the selected model type. Non-composable click callbacks use strings captured before the callback.

- [ ] **Step 4: Preserve the legacy curve library as a secondary compatibility action**

Add a page action “旧曲线库 / Legacy curve library” that navigates to a new compatibility route rendering the existing `CurveModelManagementScreen`. Do not delete `CurveModel`, `CurveModelViewModel`, manual curve input, manual data input or historical project queries.

- [ ] **Step 5: Compile UI and verify resources**

Run: `./gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

Expected: BUILD SUCCESSFUL; English and Chinese string IDs match.

### Task 5: Documentation, full verification and simulator acceptance

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update README**

Document that the main settings entry is now the unified analysis-model library, describe DRAFT/PUBLISHED/ARCHIVED lifecycle, publication compatibility checks and the secondary legacy curve entry.

- [ ] **Step 2: Run full verification**

Run:

```powershell
.\gradlew.bat testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.ui.screens.settings.resources.ResourceProfileJsonCodecAndroidTest"
rg -n "android\.widget\.Toast|fallbackToDestructiveMigration" app/src/main
```

Expected: all Gradle commands succeed; forbidden-pattern search returns no matches.

- [ ] **Step 3: Simulator acceptance**

Install the Debug APK and verify:

1. System Settings shows “Analysis Model Library”.
2. A draft standard-curve model can be created.
3. Publication is blocked when carrier/device compatibility is missing.
4. After compatibility is complete, publishing succeeds.
5. Creating v2 leaves v1 published until v2 is published, then archives v1.
6. Legacy curve library still opens and historical curves remain visible.
7. No crash buffer entry is produced.

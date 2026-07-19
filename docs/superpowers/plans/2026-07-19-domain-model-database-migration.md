# FluoColor 领域模型与数据库迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为微流控与多模态重构建立版本化载体、采集设备、实验模板、分析模型和通用采集附件数据基础，并将 Room 数据库从 9 安全迁移到 10。

**Architecture:** 保留现有 `Project`、`DetectionRun`、`ExperimentTemplate`、`CurveModel` 和旧光谱表，使用新增可空字段和新表实现渐进迁移。新的资源实体按职责拆分，项目与运行保存不可变快照，图片通过 `CaptureArtifact` 多附件建模；旧记录在迁移时写入明确的 `LEGACY`/`ENDPOINT`/`SINGLE_SPECTRUM_ANALYSIS` 兼容语义。

**Tech Stack:** Kotlin、Room 2.6.1、Hilt、JUnit 4、AndroidX Room Testing、Jetpack Compose 工程现有 Gradle/KAPT 配置。

---

## 文件结构

- `data/enums/DomainTypes.kt`：载体、布局、模板状态、分析模型和采集角色等稳定枚举。
- `data/model/ResourceProfiles.kt`：`CarrierProfile` 与 `AcquisitionProfile`。
- `data/model/TemplateDomainModels.kt`：`TemplateAnalyteConfig` 与 `TemplateSiteAssignment`。
- `data/model/AnalysisModelEntities.kt`：通用分析模型、标准曲线和深度学习模型定义。
- `data/model/CaptureEntities.kt`：`CaptureArtifact` 与 `SiteMeasurement`。
- `data/dao/*Dao.kt`：新实体的最小 CRUD/Flow 查询。
- `data/migration/DatabaseMigrations.kt`：公开给测试使用的 `MIGRATION_9_10`。
- `data/AppDatabase.kt`：注册实体、DAO、数据库版本和 schema 导出。
- `di/DatabaseModule.kt`：注册迁移、移除破坏性回退并提供 DAO。
- `data/model/Project.kt`、`DetectionRun.kt`、`ExperimentTemplate.kt`：增加兼容的新字段，全部提供默认值避免破坏现有构造调用。
- `app/schemas/.../9.json`、`10.json`：Room 迁移基线和目标 schema。
- `src/test/.../DomainTypesTest.kt`：稳定编码和旧数据默认语义的 JVM 单元测试。
- `src/androidTest/.../AppDatabaseMigrationTest.kt`：9→10 数据保留和新表结构迁移测试。
- `README.md`：记录工作包 1 已落地的领域模型和数据库版本。

### Task 1: 建立 Room schema 导出和迁移测试依赖

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/AppDatabase.kt`
- Create: `app/schemas/com.muc.fluocolorquant.data.AppDatabase/9.json`

- [ ] **Step 1: 为 Room 配置 schema 输出目录和测试依赖**

在 `app/build.gradle.kts` 中增加：

```kotlin
kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}
```

并增加：

```kotlin
androidTestImplementation("androidx.room:room-testing:2.6.1")
```

- [ ] **Step 2: 暂时保持数据库版本 9，仅将 `exportSchema` 改为 `true`**

- [ ] **Step 3: 生成并确认版本 9 schema**

Run: `.\gradlew.bat :app:kaptDebugKotlin`

Expected: `BUILD SUCCESSFUL`，生成 `app/schemas/com.muc.fluocolorquant.data.AppDatabase/9.json`。

- [ ] **Step 4: 提交 schema 基线**

```powershell
git add app/build.gradle.kts app/src/main/java/com/muc/fluocolorquant/data/AppDatabase.kt app/schemas
git commit -m "test: export room schema baseline"
```

### Task 2: 以 TDD 建立稳定领域枚举和旧数据默认值

**Files:**
- Create: `app/src/test/java/com/muc/fluocolorquant/data/enums/DomainTypesTest.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/enums/DomainTypes.kt`

- [ ] **Step 1: 编写失败测试**

```kotlin
package com.muc.fluocolorquant.data.enums

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainTypesTest {
    @Test
    fun `稳定编码可以恢复枚举且未知编码不静默回退`() {
        assertEquals(CarrierType.MICROFLUIDIC_CHIP, CarrierType.fromCode("MICROFLUIDIC_CHIP"))
        assertEquals(CaptureRole.ENDPOINT, CaptureRole.fromCode("ENDPOINT"))
        assertNull(CaptureRole.fromCode("UNKNOWN_ROLE"))
    }

    @Test
    fun `旧项目和旧模板使用明确兼容语义`() {
        assertEquals(CarrierType.PLATE, LegacyDomainDefaults.PROJECT_CARRIER_TYPE)
        assertEquals(TemplateLifecycleStatus.LEGACY, LegacyDomainDefaults.TEMPLATE_STATUS)
        assertEquals(InputProtocol.ENDPOINT_ONLY, LegacyDomainDefaults.STANDARD_INPUT_PROTOCOL)
        assertEquals(InputProtocol.SINGLE_SPECTRUM_ANALYSIS, LegacyDomainDefaults.SPECTRUM_INPUT_PROTOCOL)
    }
}
```

- [ ] **Step 2: 运行测试并确认因类型缺失而失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.muc.fluocolorquant.data.enums.DomainTypesTest"`

Expected: FAIL，错误包含 `Unresolved reference: CarrierType`。

- [ ] **Step 3: 实现最小领域枚举**

`DomainTypes.kt` 必须定义并为每个枚举提供 `code` 与严格 `fromCode()`：

```kotlin
enum class CarrierType(val code: String) { PLATE("PLATE"), MICROFLUIDIC_CHIP("MICROFLUIDIC_CHIP"), CUSTOM("CUSTOM"); companion object { fun fromCode(code: String?) = entries.find { it.code == code } } }
enum class SiteShape(val code: String) { CIRCLE("CIRCLE"), SQUARE("SQUARE"), POINT("POINT"), CUSTOM("CUSTOM"); companion object { fun fromCode(code: String?) = entries.find { it.code == code } } }
enum class ResourceStatus(val code: String) { ACTIVE("ACTIVE"), LEGACY("LEGACY"), ARCHIVED("ARCHIVED"); companion object { fun fromCode(code: String?) = entries.find { it.code == code } } }
enum class ReadoutLayout(val code: String) { GRID_SITES("GRID_SITES"), SPECTRAL_TRACKS("SPECTRAL_TRACKS"), SINGLE_REGION("SINGLE_REGION"), PER_SITE_SPECTRUM("PER_SITE_SPECTRUM"); companion object { fun fromCode(code: String?) = entries.find { it.code == code } } }
enum class InputProtocol(val code: String) { ENDPOINT_ONLY("ENDPOINT_ONLY"), SINGLE_SPECTRUM_ANALYSIS("SINGLE_SPECTRUM_ANALYSIS"), LSPR_PAIRED_QUANTIFICATION("LSPR_PAIRED_QUANTIFICATION"); companion object { fun fromCode(code: String?) = entries.find { it.code == code } } }
enum class TemplateLifecycleStatus(val code: String) { DRAFT("DRAFT"), PUBLISHED("PUBLISHED"), ARCHIVED("ARCHIVED"), LEGACY("LEGACY"); companion object { fun fromCode(code: String?) = entries.find { it.code == code } } }
enum class AnalysisModelType(val code: String) { STANDARD_CURVE("STANDARD_CURVE"), DEEP_LEARNING("DEEP_LEARNING"); companion object { fun fromCode(code: String?) = entries.find { it.code == code } } }
enum class CaptureRole(val code: String) { ENDPOINT("ENDPOINT"), SPECTRUM_SINGLE("SPECTRUM_SINGLE"), DARK("DARK"), REFERENCE("REFERENCE"), PRE_ANALYTE_BASELINE("PRE_ANALYTE_BASELINE"), POST_REACTION_ENDPOINT("POST_REACTION_ENDPOINT"); companion object { fun fromCode(code: String?) = entries.find { it.code == code } } }

object LegacyDomainDefaults {
    val PROJECT_CARRIER_TYPE = CarrierType.PLATE
    val TEMPLATE_STATUS = TemplateLifecycleStatus.LEGACY
    val STANDARD_INPUT_PROTOCOL = InputProtocol.ENDPOINT_ONLY
    val SPECTRUM_INPUT_PROTOCOL = InputProtocol.SINGLE_SPECTRUM_ANALYSIS
}
```

- [ ] **Step 4: 运行测试并确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.muc.fluocolorquant.data.enums.DomainTypesTest"`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/muc/fluocolorquant/data/enums/DomainTypes.kt app/src/test/java/com/muc/fluocolorquant/data/enums/DomainTypesTest.kt
git commit -m "feat: add multimodal domain contracts"
```

### Task 3: 新增载体和采集设备资源实体

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/data/model/ResourceProfiles.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/dao/CarrierProfileDao.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/dao/AcquisitionProfileDao.kt`

- [ ] **Step 1: 创建资源实体**

`CarrierProfile` 保存 `id/name/carrierType/rows/columns/siteShape/orientationMarkerJson/roiConfigJson/locatorConfigJson/status/version/createdAt/updatedAt`；`AcquisitionProfile` 保存 `id/name/supportedModesJson/compatibleCarrierTypesJson/deviceMatcherJson/opticalModuleName/fixtureId/cameraControlStrategy/cameraConstraintsJson/imageQcProfileJson/status/version/createdAt/updatedAt`。所有科学扩展配置使用带明确字段名的 JSON，不增加尚未使用的子表。

- [ ] **Step 2: 创建最小 DAO**

每个 DAO 提供 `observeAll()`、`getById()`、`insert()`、`update()` 和逻辑归档查询；禁止物理删除已被模板引用的已发布资源。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。

### Task 4: 新增模板子表和通用分析模型

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/model/ExperimentTemplate.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/model/TemplateDomainModels.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/model/AnalysisModelEntities.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/ExperimentTemplateDao.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/dao/AnalysisModelDao.kt`

- [ ] **Step 1: 兼容扩展旧模板**

在 `ExperimentTemplate` 尾部增加带默认值的 `version/status/carrierProfileId/detectionMode/readoutLayout/acquisitionProfileId/inputProtocol/qcProfileJson/publishedAt`，旧构造调用无需修改。

- [ ] **Step 2: 新增模板分析物与位点分配实体**

`TemplateAnalyteConfig` 以独立主键关联模板、分析物、抗原、抗体和 `analysisModelId`，保存单位与收窄后的可靠范围；`TemplateSiteAssignment` 保存模板、行、列、分析物、角色、标准浓度、重复组和默认样本槽，并建立模板+行+列唯一索引。

- [ ] **Step 3: 新增分析模型实体**

`AnalysisModel` 保存类型、分析物、模态、输入协议、主特征、处理器版本、兼容载体/设备 JSON、单位、可靠范围、验证指标、版本、状态和时间；`StandardCurveDefinition` 与 `DeepLearningModelDefinition` 通过一对一外键保存各自专用字段。

- [ ] **Step 4: 扩展 DAO 并编译**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。

### Task 5: 新增项目快照、运行快照和通用采集附件

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/model/Project.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/model/DetectionRun.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/model/CaptureEntities.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/dao/CaptureArtifactDao.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/dao/SiteMeasurementDao.kt`

- [ ] **Step 1: 兼容扩展项目和检测运行**

`Project` 增加默认可空字段 `templateId/templateVersion/templateSnapshotJson/overrideJson/projectBatch/sampleBatch`；`DetectionRun` 增加 `effectiveConfigSnapshotJson/acquisitionMetadataJson/processingVersionJson/frameQcJson/siteQcSummaryJson/configurationDeviationJson`。

- [ ] **Step 2: 新增采集附件和位点测量**

`CaptureArtifact` 关联 `runId`，保存 `captureRole/originalPath/derivedPath/capturedAt/operatorId/actualMetadataJson/profileSnapshotJson/imageQcJson/checksumSha256/pairingKey/locked/revision/supersedesArtifactId`。`SiteMeasurement` 保存运行、位点、分析物、模态、原始/校正信号、主特征、背景、SNR、置信度、`signalDetectable`、`qualityReliable`、QC 和处理器版本。

- [ ] **Step 3: 创建按运行和角色查询的 DAO 并编译**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。

### Task 6: 注册数据库实体、DAO和Hilt提供者

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/DatabaseModule.kt`

- [ ] **Step 1: 将新实体加入 `@Database.entities` 并将版本改为 10**

- [ ] **Step 2: 在 `AppDatabase` 增加新 DAO 抽象方法**

- [ ] **Step 3: 在 `DatabaseModule` 提供新 DAO，并移除 `fallbackToDestructiveMigration()`**

移除破坏性回退后，任何缺失迁移都应在开发阶段明确失败，不能清空科研历史数据。

- [ ] **Step 4: 运行编译，预期因缺失 9→10 迁移注册或 schema 不匹配失败**

Run: `.\gradlew.bat :app:kaptDebugKotlin`

Expected: 在 Task 7 完成前允许出现新 schema/迁移相关失败，但不接受 Kotlin 类型错误。

### Task 7: 以迁移测试驱动实现 `MIGRATION_9_10`

**Files:**
- Create: `app/src/androidTest/java/com/muc/fluocolorquant/data/AppDatabaseMigrationTest.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/migration/DatabaseMigrations.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/DatabaseModule.kt`
- Create: `app/schemas/com.muc.fluocolorquant.data.AppDatabase/10.json`

- [ ] **Step 1: 编写失败的 Room 迁移测试**

测试使用 `MigrationTestHelper` 创建版本 9 数据库，插入一个旧项目和检测运行，执行 `MIGRATION_9_10` 后验证：旧记录仍存在；项目新快照字段为空；检测运行新快照字段为空；新表全部存在；旧模板状态为 `LEGACY`；旧光谱结果仍保留。

- [ ] **Step 2: 编译测试并确认因迁移缺失而失败**

Run: `.\gradlew.bat :app:compileDebugAndroidTestKotlin`

Expected: FAIL，错误包含 `Unresolved reference: MIGRATION_9_10`。

- [ ] **Step 3: 实现迁移**

`MIGRATION_9_10` 必须：

1. 对 `projects`、`detection_runs` 和 `experiment_templates` 使用 `ALTER TABLE ADD COLUMN` 增加兼容字段。
2. 将已有模板 `status` 写为 `LEGACY`，`version` 写为 1，输入协议按旧语义写为 `ENDPOINT_ONLY`。
3. 创建 `carrier_profiles`、`acquisition_profiles`、`template_analyte_configs`、`template_site_assignments`、`analysis_models`、`standard_curve_definitions`、`deep_learning_model_definitions`、`capture_artifacts` 和 `site_measurements` 及全部 Room 索引/外键。
4. 不修改或推断旧 `spectrum_calibrations` 和 `spectrum_results` 的科学含义。

- [ ] **Step 4: 注册迁移并生成版本 10 schema**

Run: `.\gradlew.bat :app:kaptDebugKotlin`

Expected: BUILD SUCCESSFUL，生成版本 10 schema。

- [ ] **Step 5: 编译并在可用设备上执行迁移测试**

Run: `.\gradlew.bat :app:compileDebugAndroidTestKotlin`

Expected: BUILD SUCCESSFUL。

有连接设备时再运行：`.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.data.AppDatabaseMigrationTest`。

### Task 8: 回归验证、README和提交

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新 README**

将数据库说明更新为版本 10，增加载体/设备档案、模板子表、分析模型、采集附件和位点测量说明，并明确 UI 工作在后续工作包 2/3 开始。

- [ ] **Step 2: 运行全部 JVM 单元测试**

Run: `.\gradlew.bat testDebugUnitTest`

Expected: BUILD SUCCESSFUL，0 failures。

- [ ] **Step 3: 运行 Debug Kotlin 与 AndroidTest 编译**

Run: `.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 检查数据库危险回退和硬编码 UI 违规**

Run: `rg -n "fallbackToDestructiveMigration|android\.widget\.Toast" app/src/main/java`

Expected: 不出现 `fallbackToDestructiveMigration`；本工作包不新增 `android.widget.Toast`。

- [ ] **Step 5: 检查差异并提交**

```powershell
git diff --check
git status --short
git add README.md app docs/superpowers/plans/2026-07-19-domain-model-database-migration.md
git commit -m "feat: add multimodal domain database foundation"
```

## 计划自检

- 规格覆盖：工作包 1 的资源实体、模板子表、分析模型、项目/运行快照、通用采集附件、旧数据适配和 Room 迁移测试均有对应任务。
- 范围边界：本计划不实现模板管理 UI、新建项目向导、微流控定位或 LSPR 算法；这些分别属于后续工作包。
- 兼容策略：旧构造调用依靠默认字段继续编译；旧表保留，旧光谱数据不被错误升级；数据库禁止破坏性回退。
- 测试策略：领域编码先红后绿；Room 迁移测试先因迁移缺失失败，再实现 9→10；最后执行完整 JVM 测试和 AndroidTest 编译。

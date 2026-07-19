# FluoColor 资源库与系统设置重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Compose/Material 3 风格上完成系统设置的信息架构重构，并提供可实际使用的载体档案与采集设备档案管理闭环。

**Architecture:** 新资源通过 Repository 隔离 Room DAO，ViewModel 只暴露不可变 UI 状态和一次性事件。表单校验、预设规格和 JSON 编解码提取为纯 Kotlin 逻辑并使用 TDD 验证；Compose 页面只负责资源文本、本地化、交互和视觉呈现。科学资源不物理删除，编辑已启用资源时创建新版本，旧版本通过归档保留历史可追溯性。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、Room、Hilt、Coroutines/Flow、JUnit 4。

---

## 文件结构

- `data/repository/CarrierProfileRepository.kt`：载体档案仓库接口。
- `data/repository/CarrierProfileRepositoryImpl.kt`：载体档案 Room 实现和版本化保存。
- `data/repository/AcquisitionProfileRepository.kt`：采集设备档案仓库接口。
- `data/repository/AcquisitionProfileRepositoryImpl.kt`：设备档案 Room 实现和版本化保存。
- `ui/screens/settings/resources/ResourceProfileFormState.kt`：纯 Kotlin 表单状态、预设、错误类型和 JSON 编解码。
- `ui/viewmodels/CarrierProfileViewModel.kt`：载体列表、筛选、保存和归档状态。
- `ui/viewmodels/AcquisitionProfileViewModel.kt`：采集设备列表、筛选、保存和归档状态。
- `ui/screens/settings/resources/ResourceLibraryComponents.kt`：科研仪器式列表卡片、状态标签和编辑器公共组件。
- `ui/screens/settings/resources/CarrierProfileManagementScreen.kt`：载体与布局库页面。
- `ui/screens/settings/resources/AcquisitionProfileManagementScreen.kt`：采集设备档案页面。
- `ui/screens/settings/SettingsScreen.kt`：按“应用与数据、实验资源、设备与载体、光谱资源”重新分组。
- `ui/screens/settings/SettingsNavigationItem.kt`：支持分组色、尾部箭头和辅助标签的导航卡片。
- `ui/navigation/Screen.kt`、`AppNavigation.kt`：新增两个资源库路由。
- `res/values*/strings.xml`：全部中英文用户可见文本。
- `ui/components/CustomToast.kt`：移除遗留原生 Toast 包装，只保留 `LocalToastManager`。
- `README.md`：记录工作包 2A 的已实现能力。

### Task 1: 以 TDD 固定资源表单规则

**Files:**
- Create: `app/src/test/java/com/muc/fluocolorquant/ui/screens/settings/resources/ResourceProfileFormStateTest.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/resources/ResourceProfileFormState.kt`

- [ ] **Step 1: 编写失败测试**

测试必须覆盖：10×10、15×15、4×4、96 孔板预设；自定义行列合法性；名称为空、行列越界；设备至少选择一个模态和一个兼容载体；稳定编码集合可以 JSON 往返且未知值不会被静默加入。

- [ ] **Step 2: 运行测试并确认因类型缺失失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.muc.fluocolorquant.ui.screens.settings.resources.ResourceProfileFormStateTest"`

Expected: FAIL，包含 `Unresolved reference: CarrierProfileDraft`。

- [ ] **Step 3: 实现最小纯 Kotlin 状态与校验器**

定义 `CarrierPreset`、`CarrierProfileDraft`、`AcquisitionProfileDraft`、`ResourceFormError`、`ResourceProfileJsonCodec` 和 `validate()`。行列范围限制为 1..99；4×4 只作为旧规格预设，不在默认首选项中。

- [ ] **Step 4: 运行测试并确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.muc.fluocolorquant.ui.screens.settings.resources.ResourceProfileFormStateTest"`

Expected: PASS。

### Task 2: 建立资源 Repository 和版本化保存

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/CarrierProfileDao.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/AcquisitionProfileDao.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/CarrierProfileRepository.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/CarrierProfileRepositoryImpl.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/AcquisitionProfileRepository.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/AcquisitionProfileRepositoryImpl.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/RepositoryModule.kt`

- [ ] **Step 1: 扩展 DAO 查询**

增加按名称查询最高版本、按状态更新归档、观察全部资源；不增加物理删除 API。

- [ ] **Step 2: 实现仓库接口**

仓库提供 `observeAll()`、`getById()`、`create()`、`createNextVersion()` 和 `archive()`。新版本使用相同名称、递增版本号和新 UUID，旧版本归档后仍保留。

- [ ] **Step 3: 注册 Hilt 绑定并编译**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。

### Task 3: 实现两个资源 ViewModel

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/CarrierProfileViewModel.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/AcquisitionProfileViewModel.kt`

- [ ] **Step 1: 定义不可变 UI 状态**

状态包含资源列表、筛选状态、编辑草稿、编辑模式、加载状态和一次性 `ResourceProfileEvent`。ViewModel 不保存本地化文案，只发出错误枚举或成功事件。

- [ ] **Step 2: 实现保存、复制新版本和归档**

保存前调用 Task 1 校验；编辑已存在资源时调用 `createNextVersion()`，不原地修改科学参数；归档必须显式确认后执行。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。

### Task 4: 重构系统设置主页和公共视觉组件

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/SettingsNavigationItem.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/resources/ResourceLibraryComponents.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: 改为四组信息架构**

按设计规格展示“应用与数据、实验资源、设备与载体、光谱资源”。原检测设置标记为兼容工作偏好，不再暗示全局行列数决定科学方案。

- [ ] **Step 2: 建立科研仪器式视觉层级**

使用现有蓝灰主色、低饱和分组色、明确的状态标签、细边框和宽松留白；避免紫色渐变、悬浮玻璃和 Web 仪表盘风格。导航卡增加尾部箭头、辅助标签和按组区分的图标容器。

- [ ] **Step 3: 所有文本资源化并编译**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。

### Task 5: 实现载体与布局库页面

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/resources/CarrierProfileManagementScreen.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: 实现列表和筛选**

页面显示总数、启用数、芯片数，提供全部/启用/已归档筛选。卡片显示载体类型、行列、位点数、位点形状、版本和状态。

- [ ] **Step 2: 实现新增/新版本编辑器**

底部表单提供 10×10、15×15、96 孔板、更多规格中的 4×4 和自定义；名称、类型、行列、位点形状可编辑。保存成功通过 `LocalToastManager` 提示。

- [ ] **Step 3: 实现归档确认**

归档不删除历史数据；已归档卡片不可再次编辑，只能查看。

- [ ] **Step 4: 注册路由并编译**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。

### Task 6: 实现采集设备档案页面

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/ui/screens/settings/resources/AcquisitionProfileManagementScreen.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: 实现列表卡片**

展示支持模态、兼容载体、光学模块、固定装置、相机控制策略、版本和状态；普通用户不填写厘米距离或安装角度。

- [ ] **Step 2: 实现新增/新版本编辑器**

管理员选择检测模态、兼容载体和相机控制策略，可选填写光学模块、固定装置与设备匹配说明。集合字段通过经过测试的 JSON 编解码保存。

- [ ] **Step 3: 实现归档和路由**

沿用载体库的不可物理删除规则和 Toast 交互。

- [ ] **Step 4: 编译验证**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。

### Task 7: 清理 Toast 违规、更新 README 并回归

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/components/CustomToast.kt`
- Modify: `README.md`

- [ ] **Step 1: 移除遗留原生 Toast 包装**

确认 `Context.showCustomToast()` 没有调用后删除该扩展和 `android.widget.Toast` import，仅保留 Compose `ToastHost`/`LocalToastManager`。

- [ ] **Step 2: 更新 README**

记录工作包 2A 已实现页面、版本化规则和下一阶段模板向导范围。

- [ ] **Step 3: 完整验证**

Run:

```powershell
.\gradlew.bat testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
rg -n "android\.widget\.Toast|fallbackToDestructiveMigration" app/src/main/java
git diff --check
```

Expected: BUILD SUCCESSFUL；无 `android.widget.Toast` 和破坏性迁移回退；差异检查通过。

## 计划自检

- 规格覆盖：系统设置四分组、载体管理、采集设备管理、4×4 隐藏兼容、10×10/15×15 主预设、固定装置代替距离输入、资源版本化与归档均有实现任务。
- 范围边界：分析模型库和八步实验模板向导属于工作包 2B；本计划不创建占位页，也不修改检测算法。
- 类型一致：DAO、Repository、ViewModel、Compose 页面使用 `CarrierProfile`、`AcquisitionProfile` 和现有稳定编码。
- 测试策略：纯逻辑先红后绿；Room schema 不变，因此不新增数据库版本；最终执行完整 JVM 与 AndroidTest 编译。

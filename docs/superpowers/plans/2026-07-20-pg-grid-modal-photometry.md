# PG-Grid 微流控定位与模态专用光度 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Android 端建立与 `pg_grid_remote_check` 行为可对照的微流控规则阵列定位、比色/荧光专用光度、质量控制和检测运行持久化链路，并把 YOLO＋霍夫圆严格限制为旧孔板兼容能力。

**Architecture:** 新链路采用“领域契约 → 纯 Kotlin 几何 → OpenCV 图像定位 → 原始图科学光度 → 模态处理器 → 运行协调器 → Room 持久化”的分层结构。微流控定位器输出固定行优先顺序的 `rows × columns` 位点；比色和荧光只复用几何与通用 ROI 统计，从主特征、参考策略和 QC 开始完全分离。现有 `DetectionViewModel` 不承载 PG-Grid 算法，只负责旧孔板兼容或调用新的协调器。

**Tech Stack:** Kotlin 1.9、Jetpack Compose、OpenCV 4.5.3、Room 2.6.1、Hilt、Coroutines、Gson、JUnit4、AndroidX Instrumentation Test、Python/OpenCV 金标准。

**执行约束：** 使用现有 `F:\Code\Android\FluoColor` 工作区；保留用户已有修改；不创建 C 盘 worktree；不暂存、不提交；新增和修改业务代码写详细中文注释；所有用户可见文本进入中英文 `strings.xml`；不使用 `android.widget.Toast`；不把默认账号写入 README。

---

## 文件结构

### 领域契约与纯数学

- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/PgGridContracts.kt`
  - PG-Grid V2.1 稳定 JSON 契约、独立行列、坐标系、位点来源、QC 码和结果类型。
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/PgGridJsonCodec.kt`
  - 唯一 JSON 编解码入口，拒绝未知 schema 和不完整点数。
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/RegularGridGeometry.kt`
  - 行优先理论网格、pitch 估算、3×3 单应变换、点位回投影。
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/RobustHomographyFitter.kt`
  - 归一化 DLT、Tukey-IRLS 和补位元信息。

### OpenCV 定位

- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/PgGridLocator.kt`
  - 定位器接口与配置，不依赖具体 UI/ViewModel。
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/OpenCvPgGridLocator.kt`
  - 两档芯片区域、透视矫正、亮/暗候选、规则轴拟合、局部精修和诊断。
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/GridFrameQualityEvaluator.kt`
  - 帧级过曝、欠曝、模糊、光照不均、候选支撑和补位比例 QC。

### 光度与模态处理器

- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/photometry/PhotometryContracts.kt`
  - 科学定量图、ROI/背景环统计、逐位点输出和处理器接口。
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/photometry/PgQuantSampler.kt`
  - 0.18 pitch ROI、0.30–0.44 pitch 背景环、二阶平场、SNR 和基础 QC。
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/photometry/ColorimetricPhotometryProcessor.kt`
  - 全帧参考白/空白策略、校正 Lab、CIEDE2000、OD 和比色专用 QC。
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/photometry/FluorescencePhotometryProcessor.kt`
  - 局部背景扣除、净强度、积分强度、SNR、饱和和热点 QC。

### 编排与持久化

- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/GridDetectionCoordinator.kt`
  - 从项目快照解析路由，执行定位、光度、模型兼容检查和状态转换。
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/AnalysisModelCompatibilityChecker.kt`
  - 严格校验模态、协议、主特征、载体、设备和处理器版本，禁止通用模型静默回退。
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/GridDetectionRunRepository.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/GridDetectionRunRepositoryImpl.kt`
  - 在单事务中保存 `DetectionRun`、`CaptureArtifact` 和全部 `SiteMeasurement`。
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/DetectionRunDao.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/DatabaseModule.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/RepositoryModule.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/DetectionViewModel.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/detection/WellDetectionScreen.kt`

### 金标准与测试

- Create: `tools/pg_grid/export_v2_1_goldens.py`
- Create: `app/src/test/resources/pg_grid/v2_1/*.json`
- Create: `app/src/androidTest/assets/pg_grid/*.png`
- Create: `app/src/test/java/com/muc/fluocolorquant/domain/detection/grid/*.kt`
- Create: `app/src/androidTest/java/com/muc/fluocolorquant/domain/detection/grid/*.kt`
- Create: `app/src/test/java/com/muc/fluocolorquant/domain/detection/photometry/*.kt`
- Create: `app/src/androidTest/java/com/muc/fluocolorquant/domain/detection/GridDetectionDatabaseTest.kt`

---

### Task 1: 冻结 PG-Grid V2.1 领域与 JSON 契约

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/PgGridContracts.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/PgGridJsonCodec.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/grid/PgGridContractsTest.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/grid/PgGridJsonCodecTest.kt`

- [x] **Step 1: 写失败测试——行列、顺序、来源和可靠性必须是稳定协议**

```kotlin
@Test
fun `10乘15契约必须包含150个行优先位点`() {
    val result = fixture(rows = 10, columns = 15)
    assertEquals(150, result.sites.size)
    assertEquals(GridSiteKey(0, 0), result.sites.first().key)
    assertEquals(GridSiteKey(9, 14), result.sites.last().key)
}

@Test
fun `模型补位点固定使用低置信度与补位标志`() {
    val site = GridLocalizedSite.modelImputed(
        key = GridSiteKey(2, 3),
        rectified = GridPoint(40.0, 50.0),
        original = GridPoint(140.0, 150.0)
    )
    assertEquals(0.3, site.confidence, 1e-9)
    assertEquals(GridPointSource.MODEL_IMPUTED, site.source)
    assertTrue(GridSiteFlag.IMPUTED_POSITION in site.flags)
}
```

- [x] **Step 2: 运行测试，确认因类型不存在而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PgGridContractsTest" --tests "*PgGridJsonCodecTest"`

Expected: FAIL，报 `Unresolved reference: PgGridResult` 或同等缺失契约错误。

- [x] **Step 3: 实现最小稳定类型**

```kotlin
const val PG_GRID_SCHEMA_V2_1 = "pg-grid-v2.1"

data class GridSiteKey(val rowIndex: Int, val columnIndex: Int)
data class GridPoint(val x: Double, val y: Double)
enum class GridTargetPolarity { DARK, BRIGHT }
enum class GridPointSource { CANDIDATE_REFINED, MODEL_IMPUTED, UNADJUSTED }
enum class GridSiteFlag { IMPUTED_POSITION }
enum class GridQcSeverity { INFO, WARNING, FAILURE }

data class GridLocalizedSite(
    val key: GridSiteKey,
    val siteIndex: Int,
    val rectified: GridPoint,
    val original: GridPoint,
    val confidence: Double,
    val source: GridPointSource,
    val flags: Set<GridSiteFlag>
)
```

`PgGridResult` 必须包含 `schemaVersion`、`rows`、`columns`、`rectifiedWidth`、`rectifiedHeight`、四角、正逆单应矩阵、固定长度位点、候选支撑率、观测比例、几何 RMSE、帧级 QC 和定位器版本。构造时验证 `sites.size == rows * columns`、行列均大于 0、位点 `siteIndex == row * columns + column`。

- [x] **Step 4: 实现严格 JSON Codec**

```kotlin
object PgGridJsonCodec {
    fun encode(result: PgGridResult): String
    fun decode(json: String): PgGridResult
}
```

`decode` 必须拒绝空 JSON、未知 schema、点数不符、重复或越界行列；不得填充默认网格掩盖损坏数据。

- [x] **Step 5: 运行契约测试并确认通过**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PgGridContractsTest" --tests "*PgGridJsonCodecTest"`

Expected: PASS。

---

### Task 2: 建立 Python/Android 金标准导出

**Files:**
- Create: `tools/pg_grid/export_v2_1_goldens.py`
- Create: `app/src/test/resources/pg_grid/v2_1/10x10-dark.json`
- Create: `app/src/test/resources/pg_grid/v2_1/15x15-bright.json`
- Create: `app/src/test/resources/pg_grid/v2_1/4x4-legacy.json`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/grid/PgGridGoldenContractTest.kt`

- [x] **Step 1: 写失败测试——三类金标准必须可解析且点数、顺序稳定**

```kotlin
@Test
fun `冻结的Python金标准符合Android V2_1契约`() {
    listOf("10x10-dark.json", "15x15-bright.json", "4x4-legacy.json").forEach { name ->
        val result = PgGridJsonCodec.decode(resourceText("pg_grid/v2_1/$name"))
        assertEquals(result.rows * result.columns, result.sites.size)
        result.sites.forEachIndexed { index, site -> assertEquals(index, site.siteIndex) }
    }
}
```

- [x] **Step 2: 运行并确认资源缺失导致失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PgGridGoldenContractTest"`

Expected: FAIL，报资源不存在。

- [x] **Step 3: 编写导出适配器**

脚本通过 `--reference-dir` 接收 `pg_grid_remote_check` 目录，通过 `--output-dir` 写入项目测试资源。它调用参考工程的 `process_image`/`quantify_rectified`，把 `grid_size` 显式转换成 `rows` 和 `columns`，把 `candidate_refined/model_imputed/unadjusted`、flags、坐标和 QC 原样映射到 `pg-grid-v2.1`，并写入参考提交文件哈希和生成时间。

Run:

```powershell
python tools/pg_grid/export_v2_1_goldens.py `
  --reference-dir "D:\A-lunwen\8多模态手机方案\pg_grid_remote_check" `
  --output-dir "app/src/test/resources/pg_grid/v2_1"
```

Expected: 写出三个 UTF-8 JSON；10×10 为 100 点，15×15 为 225 点，4×4 为 16 点。

- [x] **Step 4: 运行 Python 参考测试与 Android 契约测试**

Run: `python -m pytest "D:\A-lunwen\8多模态手机方案\pg_grid_remote_check\tests" -q`

Expected: `42 passed`。

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PgGridGoldenContractTest"`

Expected: PASS。

---

### Task 3: 通用矩形阵列与单应几何

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/RegularGridGeometry.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/RobustHomographyFitter.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/grid/RegularGridGeometryTest.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/grid/RobustHomographyFitterTest.kt`

- [x] **Step 1: 写失败测试——自定义矩形阵列不能退化为方阵参数**

```kotlin
@Test
fun `4乘6理论网格按行优先生成且横纵pitch独立`() {
    val points = RegularGridGeometry.generate(
        rows = 4,
        columns = 6,
        width = 700.0,
        height = 500.0,
        marginRatio = 0.1
    )
    assertEquals(24, points.size)
    assertEquals(GridPoint(70.0, 50.0), points.first())
    assertEquals(GridPoint(630.0, 450.0), points.last())
}
```

- [x] **Step 2: 运行并确认函数不存在而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*RegularGridGeometryTest" --tests "*RobustHomographyFitterTest"`

- [x] **Step 3: 实现理论网格、pitch 和单应映射**

```kotlin
object RegularGridGeometry {
    fun generate(rows: Int, columns: Int, width: Double, height: Double, marginRatio: Double): List<GridPoint>
    fun estimatePitch(points: List<GridPoint>, rows: Int, columns: Int): GridPitch
    fun project(matrix: DoubleArray, point: GridPoint): GridPoint
}
```

横向 pitch 取同行相邻点横纵欧氏距离中位数，纵向 pitch 取同列相邻点中位数；矩阵固定为行主序 9 个 `Double`。

- [x] **Step 4: 写失败测试并实现 Tukey-IRLS**

测试构造 10×15 透视晶格，把 20 个观测点偏移 40 px；拟合后全部理论点平均误差小于 1.5 px，异常点权重接近 0。`RobustHomographyFitter.fit()` 至少需要 `max(4, ceil(rows*columns*0.4))` 个有效观测，否则返回 `InsufficientObservations`；不得输出伪可信结果。

- [x] **Step 5: 运行纯数学测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*RegularGridGeometryTest" --tests "*RobustHomographyFitterTest"`

Expected: PASS。

---

### Task 4: OpenCV 芯片区域、矫正和极性候选

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/PgGridLocator.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/grid/OpenCvPgGridLocator.kt`
- Create: `app/src/androidTest/assets/pg_grid/synthetic_10x10_dark_squares.png`
- Create: `app/src/androidTest/assets/pg_grid/synthetic_15x15_bright_points.png`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/domain/detection/grid/OpenCvPgGridLocatorTest.kt`

- [x] **Step 1: 写失败仪器测试——两张参考图必须定位固定点数**

```kotlin
@Test
fun `暗方块10乘10输出100个有序位点`() {
    val result = locator.locate(loadAssetBitmap("pg_grid/synthetic_10x10_dark_squares.png"), dark10x10Config)
    assertEquals(100, result.sites.size)
    assertTrue(result.candidateSupportRatio >= 0.80)
}

@Test
fun `亮点15乘15输出225个有序位点`() {
    val result = locator.locate(loadAssetBitmap("pg_grid/synthetic_15x15_bright_points.png"), bright15x15Config)
    assertEquals(225, result.sites.size)
    assertTrue(result.candidateSupportRatio >= 0.80)
}
```

- [x] **Step 2: 运行并确认定位器不存在而失败**

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocatorTest`

- [x] **Step 3: 实现两档芯片区域与透视矫正**

第一档使用模糊灰度 94 分位阈值、候选面积 0.2%～20%；无候选时使用 `min(94分位, Otsu)` 并放宽到 65%。候选按 `sqrt(area) * (meanBrightness + 1)` 评分，最小外接矩形四角外扩 10%；无候选时返回 `fallback_center` 且帧级 QC 必须为 WARNING，不能伪装成成功定位。

- [x] **Step 4: 实现显式极性候选**

`GridTargetPolarity.DARK` 使用边长 6.5% 的矩形 black-hat 核；`BRIGHT` 使用边长 5% 的 top-hat 核；规格只控制行列，不再以“10×10 必为暗、15×15 必为亮”硬编码极性。

- [x] **Step 5: 实现规则轴、局部观测和单应平差**

候选轴聚类容差为边长 2.25%；旋转搜索范围 ±6°、步长 0.25°；至少 40% 有效观测才拟合。残差合格点为 `CANDIDATE_REFINED`，无证据点为 `MODEL_IMPUTED/0.3/IMPUTED_POSITION`；候选支撑率低于 0.6 时 `trusted=false`。

- [x] **Step 6: 运行仪器测试并保存耗时**

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocatorTest`

Expected: 10×10/15×15 均 PASS；API 35 x86_64 模拟器单张参考图 P95 不高于 1.5 秒，峰值 Java heap 增量不高于 160 MB。

---

### Task 5: PG-Quant 通用 ROI、平场与 QC

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/photometry/PhotometryContracts.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/photometry/PgQuantSampler.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/photometry/PgQuantSamplerTest.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/domain/detection/photometry/PgQuantSamplerImageTest.kt`

- [x] **Step 1: 写失败测试——SNR 可检出性与图像可靠性必须独立**

```kotlin
@Test
fun `低SNR不单独判定质量不可靠`() {
    val qc = SitePhotometryQc.from(flags = setOf(PhotometryFlag.LOW_SNR), snr = 1.2, snrMin = 3.0)
    assertFalse(qc.signalDetectable)
    assertTrue(qc.qualityReliable)
}
```

- [x] **Step 2: 运行并确认类型缺失而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PgQuantSamplerTest"`

- [x] **Step 3: 实现 ROI/背景环和基础统计**

`PgQuantConfig` 默认值固定为：ROI 0.18 pitch、背景环 0.30～0.44 pitch、饱和灰度 250、饱和比例 0.05、欠曝中位数 8、SNR 3、边界裁切 0.05、污染比例 0.35、背景异常 z=3.5。统计使用中位数和 MAD，不在增强图上采样。

- [x] **Step 4: 实现二阶平场与 QC**

至少 12 个有效背景环时拟合 `[1,u,v,u²,uv,v²]`，残差超过 3 robust sigma 的点剔除后重拟合；退化时回退常量场。硬失败标志仅包含 `SATURATED`、`UNDER_EXPOSED`、`ROI_OUT_OF_BOUNDS`、`NON_UNIFORM`、`BACKGROUND_ANOMALY`，不包含 `LOW_SNR`。

- [x] **Step 5: 运行 JVM 与图像仪器测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PgQuantSamplerTest"`

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.domain.detection.photometry.PgQuantSamplerImageTest`

Expected: PASS。

---

### Task 6: 比色专用光度处理器

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/photometry/ColorimetricPhotometryProcessor.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/photometry/ColorimetricPhotometryProcessorTest.kt`

- [x] **Step 1: 写失败测试——比色输出 ΔE/OD，且不得逐 ROI 灰世界归一化**

测试用同一背景上的浅蓝与深蓝位点，参考位固定；深蓝位的 `deltaE2000` 和 `opticalDensity` 必须高于浅蓝位。将每个位点分别乘不同通道比例后，如果处理器逐 ROI 灰世界会把差异抹平，测试必须失败。

- [x] **Step 2: 运行并确认处理器不存在而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*ColorimetricPhotometryProcessorTest"`

- [x] **Step 3: 实现比色特征**

处理器输入为全帧/全阵列统计和模板参考位；白平衡只允许使用采集设备档案固定矩阵、全帧参考白或模板参考位聚合，不允许逐位点独立归一化。输出校正 RGB/Lab、CIEDE2000、`OD = -log10((signal + epsilon)/(reference + epsilon))`、反光比例和主特征。

- [x] **Step 4: 运行比色测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*ColorimetricPhotometryProcessorTest"`

Expected: PASS。

---

### Task 7: 荧光专用光度处理器

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/photometry/FluorescencePhotometryProcessor.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/photometry/FluorescencePhotometryProcessorTest.kt`

- [x] **Step 1: 写失败测试——荧光输出背景扣除、积分强度与热点 QC**

测试构造相同背景、不同绿色荧光强度的位点，并给一个位点注入单像素高亮热点。强信号位 `netIntensity/integratedIntensity/SNR` 必须更高；热点位必须产生 `HOT_PIXEL`，不能通过固定黑电平和任意绿色通道放大隐藏异常。

- [x] **Step 2: 运行并确认处理器不存在而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*FluorescencePhotometryProcessorTest"`

- [x] **Step 3: 实现荧光特征**

输出原始/校正通道中位数、局部背景、净强度、积分强度、SNR、饱和比例、热点比例和主特征；热点阈值基于 ROI 中位数与 MAD，不硬编码“放大绿色通道”。增强显示图仅用于 UI，不进入该处理器。

- [x] **Step 4: 运行荧光测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*FluorescencePhotometryProcessorTest"`

Expected: PASS。

---

### Task 8: 分析模型兼容性与禁止静默回退

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/AnalysisModelCompatibilityChecker.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/utils/DetectionModeSupport.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/AnalysisModelCompatibilityCheckerTest.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/utils/DetectionModeSupportTest.kt`

- [x] **Step 1: 写失败测试——任何科学配置不匹配均不输出浓度**

分别构造模态、输入协议、主特征、载体、采集设备和处理器版本不匹配；每一项都必须返回稳定原因码。`DetectionModeSupport.concentrationModelCandidates()` 不得再为比色/荧光返回通用浓度模型。

- [x] **Step 2: 运行并观察现有通用回退测试失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*AnalysisModelCompatibilityCheckerTest" --tests "*DetectionModeSupportTest"`

- [x] **Step 3: 实现严格兼容性检查并移除通用回退**

```kotlin
data class ModelCompatibilityRequest(
    val modality: DetectionModality,
    val inputProtocol: InputProtocol,
    val primaryFeature: AnalysisPrimaryFeature,
    val carrierProfileId: String,
    val acquisitionProfileId: String,
    val processorVersion: String
)

sealed interface ModelCompatibilityResult {
    data object Compatible : ModelCompatibilityResult
    data class Incompatible(val reasons: Set<ModelCompatibilityReason>) : ModelCompatibilityResult
}
```

- [x] **Step 4: 运行兼容性测试**

Expected: PASS，且 assets 缺少专用模型时状态为“不兼容/不可定量”，不再加载通用模型。

---

### Task 9: 检测运行协调器与 Room 单事务持久化

**Files:**
- Create: `app/src/main/java/com/muc/fluocolorquant/domain/detection/GridDetectionCoordinator.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/GridDetectionRunRepository.kt`
- Create: `app/src/main/java/com/muc/fluocolorquant/data/repository/GridDetectionRunRepositoryImpl.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/data/dao/DetectionRunDao.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/DatabaseModule.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/di/RepositoryModule.kt`
- Test: `app/src/test/java/com/muc/fluocolorquant/domain/detection/GridDetectionCoordinatorTest.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/domain/detection/GridDetectionDatabaseTest.kt`

- [x] **Step 1: 写失败测试——微流控与孔板路由不同**

模板快照载体为 `MICROFLUIDIC_CHIP` 时必须调用 `PgGridLocator`；载体为 `PLATE` 时必须返回 `LegacyPlateRequired`，由旧链路处理；不得根据行列数猜载体。

- [x] **Step 2: 写失败 Room 测试——一次成功运行原子保存全部记录**

10×10 成功运行后必须存在 1 个 `DetectionRun`、1 个 `ENDPOINT CaptureArtifact`、100 个 `SiteMeasurement`；人为让第 50 个测量插入失败时三类记录全部回滚。

- [x] **Step 3: 实现运行状态机**

状态固定为 `PREPARING → LOCATING → PHOTOMETRY → QUANTIFYING → COMPLETED`，严重帧级 QC 为 `RETAKE_REQUIRED`，模型不兼容为 `SIGNAL_ONLY_COMPLETED`，异常为 `FAILED`。所有图像和模型计算运行在 `Dispatchers.Default/IO`，UI 线程只收集状态。

- [x] **Step 4: 实现 Room 事务方法**

在 `DetectionRunDao` 增加带 `@Transaction` 的保存方法或建立事务 DAO，先插入运行和附件，再插入全部测量；任何异常向上抛出，不留下半成品。

- [x] **Step 5: 运行协调器和 Room 测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*GridDetectionCoordinatorTest"`

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.domain.detection.GridDetectionDatabaseTest`

Expected: PASS。

---

### Task 10: 接入现有检测入口并保留孔板兼容链

**Files:**
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/viewmodels/DetectionViewModel.kt`
- Modify: `app/src/main/java/com/muc/fluocolorquant/ui/screens/detection/WellDetectionScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Test: `app/src/test/java/com/muc/fluocolorquant/ui/viewmodels/DetectionViewModelRoutingTest.kt`
- Test: `app/src/androidTest/java/com/muc/fluocolorquant/ui/screens/detection/GridDetectionScreenTest.kt`

- [x] **Step 1: 写失败测试——模板快照自动路由且页面立即显示阶段进度**

微流控项目启动后不得初始化 YOLO；孔板项目仍可进入旧 AUTO/MANUAL 流程。定位失败、需重拍、仅信号结果、完整定量结果必须有独立页面状态。

- [x] **Step 2: 运行并确认旧页面无分流而失败**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*DetectionViewModelRoutingTest"`

- [x] **Step 3: 最小化改造 ViewModel 与 Compose 页面**

页面从项目不可变快照读取载体、模态、行列和极性；微流控显示“定位芯片/校正视角/提取信号/执行定量”阶段；QC 阻止时显示原因和重选图片操作。所有文本资源化，提示使用 `LocalToastManager`。

- [x] **Step 4: 运行 UI 与回归测试**

Run: `./gradlew.bat :app:testDebugUnitTest`

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.muc.fluocolorquant.ui.screens.detection.GridDetectionScreenTest`

Run: `./gradlew.bat :app:assembleDebug`

Expected: 全部 PASS。

---

### Task 11: Android/Python 同图一致性与端侧性能验收

**Files:**
- Create: `app/src/androidTest/java/com/muc/fluocolorquant/domain/detection/grid/PgGridGoldenParityTest.kt`
- Create: `app/src/androidTest/java/com/muc/fluocolorquant/domain/detection/grid/PgGridPerformanceTest.kt`
- Modify: `docs/superpowers/plans/2026-07-20-pg-grid-modal-photometry.md`

- [x] **Step 1: 对相同 PNG 逐字段比较**

坐标误差阈值按 rectified pitch 归一化：平均误差 ≤0.05 pitch，P95 ≤0.10 pitch；点数、行列、顺序、来源类别和硬 QC 标志必须完全一致。光度中位数/背景/校正信号允许绝对 1.0 灰度或相对 2%（取较大者），SNR 允许相对 5%。

- [x] **Step 2: 运行性能测试**

使用 API 35 模拟器分别运行 10×10、15×15 五次，丢弃首轮热身；记录 P50/P95、峰值 heap、输出位点数和失败原因。测试只在显式 instrumentation 组运行，避免普通 JVM CI 波动。

- [x] **Step 3: 更新计划勾选与实施记录**

在本文件末尾追加实际命令、结果、容差偏差和任何保留风险；不得用“编译成功”替代同图数值验收。

---

## 完成门槛

- [x] Python 参考工程 42 项测试仍全部通过。
- [x] Android 新增 JVM 与 instrumentation 测试全部通过。
- [x] 10×10、15×15、4×4 和一个非方形自定义阵列固定输出 `rows × columns` 个行优先位点。
- [x] 微流控项目不会加载 YOLO＋霍夫圆，孔板旧项目不受影响。
- [x] 比色与荧光保存不同的主特征、原始/校正字段和 QC。
- [x] `signalDetectable` 与 `qualityReliable` 独立。
- [x] 科学定量只读取原始定量图，显示增强不改变保存结果。
- [x] 模型不兼容时保存信号但不输出浓度，不存在通用模型静默回退。
- [x] Room 成功运行原子保存运行、附件和全部位点，失败时完整回滚。
- [x] README 只记录功能与架构变化，不包含默认账号和密码。

## 实施记录

- 2026-07-20：完成总设计、Python 参考实现、现有 Android 检测链、Room 11 实体与依赖审计；确定使用当前 F 盘工作区内联执行，不创建 worktree、不提交。
- 2026-07-20：完成 Task 1。新增 PG-Grid V2.1 领域契约和严格 JSON Codec；先确认两组测试因类型缺失失败，再实现并通过 `PgGridContractsTest`、`PgGridJsonCodecTest`。
- 2026-07-20：完成 Task 2。新增 Python→Android V2.1 金标准适配器，冻结 10×10 暗方块、15×15 亮点和兼容 4×4 三组 JSON；`PgGridGoldenContractTest` 通过，参考工程保持 `42 passed`。
- 2026-07-20：完成 Task 3。新增独立 rows/columns 的理论网格、横纵 pitch、3×3 单应投影，以及归一化 DLT＋Tukey-IRLS；透视晶格含 20 个大离群点时平均恢复误差低于测试门槛，观测少于 40% 时明确拒绝拟合。
- 2026-07-20：完成 Task 4 的功能实现与两图验收。OpenCV 定位器已包含两档芯片区域、透视矫正、显式亮/暗极性候选、旋转轴聚类、40% 观测门槛和补位元信息；API 35 模拟器 `OpenCvPgGridLocatorTest` 2/2 通过。性能 P95/heap 仍留在 Task 11 统一测量。
- 2026-07-20：完成 Task 5。PG-Quant 在矫正坐标定义 0.18 pitch ROI 和 0.30～0.44 pitch 背景环，但逐采样点回投影到原始 Bitmap；新增中位数/MAD、二阶平场、饱和/欠曝/越界/异质/背景异常 QC，并验证低 SNR 不自动等同质量不可靠。JVM 与 API 35 Bitmap 仪器测试通过。
- 2026-07-20：完成 Task 6/7。比色处理器使用模板参考位生成一组全局白平衡增益，输出 D65 Lab、CIEDE2000、OD 和反光 QC；荧光处理器按模板通道输出局部背景扣除净强度、逐通道积分强度、SNR 和热点 QC。两组 JVM 测试及更新后的原图采样仪器测试通过。
- 2026-07-20：完成 Task 8。新增分析物、模态、输入协议、主特征、载体类型、采集设备、处理器名称/版本和发布状态的全量兼容门控；损坏或空兼容 JSON 不能解释为“兼容全部”。旧孔板工具也已移除通用浓度模型回退，专用资源缺失时明确失败。
- 2026-07-20：完成 Task 9。新增模板快照驱动协调器阶段状态、载体路由、极性/荧光通道/参考位预检、微流控定位、模态光度、模型兼容标记和新 `SiteMeasurement` 组装；Room 使用数据库级事务保存运行、ENDPOINT 附件与全部测量。10×10 真实参考图协调器集成测试、100 位点成功事务与外键失败回滚测试均通过。
- 2026-07-20：完成 Task 10。检测入口先读取项目快照，微流控直接创建专用阶段 UI，只有孔板才延迟创建旧 `DetectionViewModel`，因此微流控不再预加载 YOLO；完成状态直接进入阵列结果路由，不再无条件跳转旧曲线拟合。Compose 测试 2/2、完整 JVM 回归和 `assembleDebug` 均通过。
- 2026-07-20：完成 Task 11 同图数值验收。`PgGridGoldenParityTest` 在 API 35 模拟器对相同 PNG 逐点比较：10×10 平均/P95 坐标误差为 `0.005328/0.035779 pitch`，15×15 为 `0.001271/0.002339 pitch`，均低于 `0.05/0.10 pitch` 门槛，点数、行优先顺序、来源类别和硬 QC 完全一致。
- 2026-07-20：补齐此前金标准只覆盖定位、不覆盖光度的计划缺口。`export_v2_1_goldens.py` 现在同时冻结 Python 原始 `pg-quant-v1` 输出；新增 `PgQuantGoldenParityTest`，按位点验证 ROI 灰度中位数、背景中位数、校正信号使用“绝对 1.0 或相对 2% 取较大者”容差，SNR 使用相对 5% 容差，并严格比较信号可检出、质量可靠与 QC 标志，10×10/15×15 同图测试通过。
- 2026-07-20：完成 API 35 端侧性能验收。每个规格先热身一次，再正式运行五次：10×10 的 P50/P95 为 `89.13/123.78 ms`，峰值 Java+Native 堆增量 `6.00 MB`，输出 100 位点；15×15 为 `213.24/217.53 ms`、`9.56 MB`，输出 225 位点，均低于 `1500 ms/160 MB` 门槛。
- 2026-07-20：完成工作包 4 全量回归。Python 参考工程在其根目录运行 `python -m pytest tests -q` 得到 `42 passed in 3.21s`；Android `:app:testDebugUnitTest :app:assembleDebug` 成功；API 35 模拟器运行完整 `:app:connectedDebugAndroidTest` 得到 31/31 通过。回归期间修复旧 `ImageCorrectionTest` 把 VectorDrawable XML 误交给 `BitmapFactory` 导致的空 Bitmap，测试加载器现可把矢量资源可靠绘制为位图。README 经检索未包含默认账号或密码。

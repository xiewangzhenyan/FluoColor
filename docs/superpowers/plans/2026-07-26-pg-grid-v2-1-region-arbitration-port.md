# 2026-07-26 PG-Grid V2.1 区域仲裁上游对齐实施记录

## 一、目标

把 Android 微流控定位器对齐到参考工程 [`xiewangzhenyan/pg_grid`](https://github.com/xiewangzhenyan/pg_grid)
当前主线（`bc9b9ef`）。本轮解决的是**单一主区域假设**带来的系统性失败：

1. 荧光/自发光成像下面板本身不发光，亮区路径抓不到连续亮区，只能退化为兜底框；
2. 用户裁掉背景后，高分位阈值被迫抬高，掩膜切在面板内部，检测框严重缩水；
3. 几何闸值按画幅边长归一化，隐含 `行列数≈15` 的假设，10×10 出现候选饥饿；
4. 晶格整体平移一个间距时，支撑率/包围率/观测率**全部看不见**，结果被判为可信。

## 二、上游变更范围（已逐提交核对）

Android 的移植基线对应上游 `3bf8672`/`0abf3f7`（本地快照 `pg_grid_github_realcheck_20260722`）。
`pg_grid.py` 从 1677 行增长到 2620 行（+1044 / −114）。

**`pg_quant.py` 自移植基线以来零变更**，`pg_unit_export.py` 与上游 HEAD 仅有换行符差异。
因此本轮改动 100% 集中在定位器，逐位点光度采样链（`PgQuantSampler`）不受影响。

| 上游提交 | 内容 | Android 落地 |
| --- | --- | --- |
| `a75fe89` | 极性证据加权，投影兜底也使用极性 | `polarityHintWithStrength` + `orderPolaritiesByEvidence` + `measureGridPolarityContrast` |
| `aaf3fd8` | 多阈值斑点提取 + 自发光点云区域检测 | `PgGridCandidateDetector.multiThresholdBlobs`、`PgGridRegionDetector.detectRegionFromEmittingDots` |
| `b40cee5` | 微弱基底轮廓区域检测（荧光首选路径） | `PgGridRegionDetector.detectRegionFromFaintSubstrate` |
| `190119e` | 区域假设联合选择，由图像证据仲裁 | `iterateHypotheses` + `solveWithRegion` + `PgGridRegionArbiter` |
| `bafd36b` | 仲裁切换保护 + 降采样/缓存/短路降开销 | `WIN_MARGIN`/`MINIMUM_EVIDENCE`、`cachedOriginalCandidates`、短路门限 |
| `cb8e2f5` `9ff157b` | 闸值与轴 pitch 窗口锚定到 15×15 参考 | `gateReferenceSide`、`expectedAxisPitch`、`axisPitchBounds` |
| `a1caf25` | 暗单元检测 `max_box` 钳制到一个间距以下 | `detectDarkSquareCandidates` 的安全钳制 |
| `201947f` | 整体晶格整数平移检测 | `PgGridLatticeIntegrity.detectPhantomEdge` |

上游同期新增的 `pg_colori_sim.py`、`pg_fluoro_sim.py`、`pg_calibration.py` 属于 Python 侧
仿真与标定数据生成工具，Android 无对应链路，本轮不移植。

## 三、同时修复的两处既有偏差

移植过程中发现 Android 相对**移植基线**就已经存在的两处偏差，一并纠正：

1. **主区域两档阈值只取第一档**。Python 自 `3bf8672` 起就同时评估高分位档与 Otsu 档的
   全部候选并统一评分，Android 却是“高分位档有候选就直接返回”，导致 Otsu 档永远不会被
   评估——这正是裁切后定位塌陷的根因。现已改为跨档统一评分，并补齐矩形填充度判据与
   1% 面积下限。
2. **支撑率缺少三态**。Python 自 `c4bb2dd` 起区分 `ok / inconclusive / unavailable`，
   Android 只有“支撑率 ≥ 0.6 即可信”。检测器整体失效（重模糊、单元尺寸超闸值）时支撑率
   为 0，旧实现会把“检查手段缺席”误判为“网格错了”。现已补齐三态。

## 四、架构与不可变约束

- 96 孔板定位（YOLO + 霍夫圆）、两套独立结果页、模态专用光度链保持独立，不受影响。
- 历史运行只读取冻结快照：`GridGeometryDiagnostics` 新增的四个字段一律**可空**，
  旧快照缺键时为 `null`，表示“无此证据”，绝不补算、不重新定位、不改变旧浓度。
  （Gson 不识别 Kotlin 默认参数值，非空声明会在 `requireValid` 里变成 NPE。）
- 处理器版本 `locatorVersion` 由 `2.1.0` 升到 `2.2.0` 并冻结进运行快照；
  `detectionModelUsed` 改为跟随定位器实际版本，不再硬编码。
- Schema 仍为 `pg-grid-v2.1`：新增字段只是可选诊断，坐标系、单位与既有字段含义未变。

## 五、文件结构

新增（均为 `internal`，不扩大公共面）：

- `PgGridCandidateDetector.kt` — 几何闸值锚定、多阈值斑点提取、暗/亮候选检测
- `PgGridRegionDetector.kt` — 三条主区域路径与惰性假设枚举
- `PgGridLatticeIntegrity.kt` — 幻影边缘（整格错位）检测
- `PgGridRegionArbiter.kt` — 仲裁评分与切换保护（纯函数，便于 JVM 覆盖）
- `PgGridGrayHistogram.kt` — 直方图分位数/中位数/MAD，按 numpy 线性插值约定

`OpenCvPgGridLocator.kt` 收敛为编排：假设枚举 → 逐假设走完几何链路 → 证据仲裁 → 结果构造。

### 一处与上游不同的实现选择

上游 `iter_region_hypotheses` 是 Python 生成器，外层持有 `gray` 并靠 `finally` 释放。
Kotlin 的 `sequence {}` 在调用方**提前终止迭代**时不会执行 `finally`——而短路正是本设计的
关键优化，照搬会静默泄漏原图尺度的 `Mat`。因此改为让每条路径各自申请并释放灰度图，
多付两次灰度转换，换取资源安全。

## 六、参考工程的一处重要发现：本地 PG-Quant 未推送到 GitHub

重生成金标准时发现，**`pg_grid_remote_check/pg_quant.py`（441 行）是本地增强版，
GitHub 上游只有 405 行**。本地版额外提供：

- `RELIABILITY_FAIL_FLAGS`：把 `low_snr` 从可靠性硬失败中剥离，改由 `signal_detectable` 表达；
- 逐单元 `signal_detectable` 字段；
- 输出中的 `config` 参数快照（便于追溯每次热图使用的阈值）；
- 汇总中的 `detectable_count` / `detectable_ratio`。

Android 的 `PgQuantSampler` 正是按这个增强版移植的（`qc.signalDetectable` 即来自于此）。
因此**参考目录必须组合使用**：`pg_grid.py` 取上游 HEAD，`pg_quant.py` 保留本地增强版。
最初误用纯上游目录重生成，导致定量金标准丢失上述字段并让 Android 解析出 NPE。

> 建议把本地 PG-Quant 增强推送到 GitHub，否则该仓库无法独立复现 Android 当前的定量契约。

## 七、验证结果

### Python 侧

- 环境：Python 3.13 + `opencv-python==4.10.0.84`（刻意选 4.x 以贴近 Android 的 OpenCV 4.5.3，
  而不是默认装到 5.0）。
- 参考目录 = 上游 `pg_grid.py` + 本地增强 `pg_quant.py`（见上一节）。
- `export_v2_1_goldens.py` 重新导出 6 份金标准，`verify_v2_1_goldens.py` 独立复现 **6/6 PASS**。
- `export_perturbation_corpus.py` 重新导出 14 个扰动 case；**输入 PNG 字节未变**，
  只有期望输出跟随新算法更新。

### 新旧算法差异（同一输入）

| 语料 | 结果 |
| --- | --- |
| 10×10 合成暗方块 | 位点最大位移 `0.082 px`，均值 `0.001 px`；支撑率、可信度、QC 均不变 |
| 15×15 合成亮点 | 位点最大位移 `0.002 px`；各项指标不变 |
| 七扰动 14 case | 极性、可信度、QC 全部不变；最大位移 `0.480 px`（10×10 遮挡），其余 ≤0.03 px |
| 4×4 旧规格 | **有实质变化**，见下 |

**4×4 旧规格**：`trusted` 由 `true` 变为 `false`，支撑率由 `null`（不适用）变为 `0.0`，
新增 `grid_support_low`。根因是闸值按 15×15 锚定后，4×4 的大方块单元面积（约 1545 px²）
超出 `max_area`（622 px²），候选数为 0 → 包围率 0 → 触发截断保护。

这不是定位精度退化：新的按行列数定义的 pitch 区间反而首次接纳了 4×4 的真实间距，
晶格 RMSE 由 `13.90` 改善到 `9.91`，位点均值移动 `30.5 px` 到真实位置。变化的是
**可信度表述**——系统如实声明“没有为这一单元尺寸标定过的候选检测器”，而不是沉默背书。
该结果与项目既定策略一致：4×4 只保留后台读取、固定点数和稳定顺序，若恢复为主规格
必须重新采集真实数据并单独发布定位/QC 门槛。

### Android 侧

- JVM 单元测试 **385 项、0 失败**（新增 3 个套件：闸值锚定、幻影边缘判据、仲裁门控）。
- `assembleDebug` 与 `assembleDebugAndroidTest` 均构建成功。
- `emulator-5554`（Medium_Phone_API_35）定向设备回归 **16/16 通过**：金标准 parity、
  七扰动 14 case、6 张用户实拍图、帧级 QC、性能门槛、PG-Quant 光度 parity、实拍紧致分割。

### 两处必须重新定基线的下游测试

两者都不是移植缺陷，但都是**真实的行为变化**，因此在测试里写明了原因而不是悄悄放宽：

**1）`PgUnitRealPhotoRegressionTest` 的 `real_10x10_02` 门槛 85 → 74。**
主区域改为跨两档阈值统一评分后（与 Python 一致，选中 `opencv_bright_region_wide`），
该图紧致分割成功数由 85+ 变为 76。**同版本 Python 参考在同一张图上同样输出 76/100、
24 个中位盒兜底**，两端逐值一致，属于上游行为属性。其余实拍图不降反升：

| case | Python 参考 | Android 实测 |
| --- | --- | --- |
| real_10x10_01 | 94/100 | 92/100 |
| real_10x10_02 | 76/100 | 76/100 |
| real_15x15_01～04 | 225/225 | 225/225（旧基线 real_15x15_04 仅要求 ≥190） |

**2）`PgQuantGoldenParityTest` 移除 4×4 数值对照，改为独立结构性回归。**
4×4 晶格首次被拟合到真实单元位置后，暴露出该合成夹具几何本身是退化的：单元约 39px，
而 ROI 半径 `0.18×pitch` = 33.8px、背景环外径 82px，ROI 与背景环必然同时跨越单元本体、
面板边框、面板底色与画面外背景四种灰度，中位数落在哪一档由“面积占比恰好越过 50%”决定。
Android 按科学契约在**原图**最近邻取样，Python 在**三次插值后的矫正图**上量化，本就不同域：
10×10/15×15 上差异仅 `0.36` 与 `3e-14` 灰度，可严格对照；4×4 上实测 ROI 中位数
Python `103`（插值中间值）vs Android `145`（边框灰度），背景环 `139` vs `145`。
继续逐字段放宽容差等于“调测试直到通过”，测的也不再是移植正确性，因此改为断言
Android 输出完整、有限、行列有序的 16 位点定量结果。

6 张实拍图极性全部自动裁决为 `DARK`，主区域均走 `opencv_bright_region_wide`：

| case | 支撑率 | 观测率 | trusted |
| --- | --- | --- | --- |
| real_10x10_01 | 0.87 | 0.98 | true |
| real_10x10_02 | 0.75 | 0.96 | true |
| real_15x15_01 | 1.00 | 1.00 | true |
| real_15x15_02 | 1.00 | 1.00 | true |
| real_15x15_03 | 1.00 | 1.00 | true |
| real_15x15_04 | 1.00 | 1.00 | true |

### 新增：紧裁场景固定回归语料

现有 6 张实拍语料全部是**未裁切原图**（芯片占比 11%~43%），而首页导入的相册与拍照两条
路径都会经过 uCrop，用户贴着芯片裁切是常规操作。本轮新增 6 张紧裁派生图补齐该场景：

- 生成工具 `tools/pg_grid/export_cropped_real_corpus.py`：沿检测到的芯片边界外扩 `4%`
  裁切，再按长边 `1600px` 用 `INTER_AREA` 降采样、JPEG 质量 95 编码。**确定性已验证**：
  重复生成得到逐字节相同的文件，12 个 case 的 SHA-256 全部一致，原有 6 张原图字节未动。
- 体积代价：语料由 11M 增至 13M（新增约 2.2M，仅进 androidTest APK）。降采样不影响判据
  ——包围率的容差本就是 `0.35` 个单元间距，定位链路计算包围率时也是先降到 1600px 再检测。
- 新增 `PgGridRealCorpusIntegrityTest`：补齐实拍语料的 SHA-256 校验。此前 manifest 记录了
  摘要却无人验证，等于把“输入字节永不改动”这条保证写在纸上而没有执行；扰动语料一直有
  校验，实拍语料没有。

紧裁后芯片占比升到 `59.8%~83.1%`，Android 实测（API 35 模拟器）：

| case | 芯片占比 | 极性 | 区域路径 | 支撑率 | 观测率 | trusted | 紧致分割 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| real_10x10_01_cropped | 69.1% | DARK | bright_region_wide | 0.92 | 0.98 | ✅ | 93/100 |
| real_10x10_02_cropped | 78.9% | DARK | bright_region_wide | 0.80 | 1.00 | ✅ | 85/100 |
| real_15x15_01_cropped | 79.1% | DARK | bright_region_wide | 1.00 | 1.00 | ✅ | 225/225 |
| real_15x15_02_cropped | 83.1% | DARK | bright_region_wide | 1.00 | 1.00 | ✅ | 225/225 |
| real_15x15_03_cropped | 82.6% | DARK | bright_region_wide | 1.00 | 1.00 | ✅ | 225/225 |
| real_15x15_04_cropped | 59.8% | DARK | bright_region_wide | 1.00 | 1.00 | ✅ | 225/225 |

两点值得记录：

1. **选中的区域路径全部是 `opencv_bright_region_wide`**，即 Otsu 面积无关档——正是旧实现
   “高分位档拿到候选就直接返回”而永远走不到的那一档，且旧的面积上限 `0.65` 本身也容不下
   占比 60%~83% 的紧裁图（现为 `0.92`）。这组用例的作用就是让这条路径不再退回去。
2. **紧裁不但没有变差，反而普遍更好**：`real_10x10_02` 的紧致分割由原图的 `76/100` 提升到
   `85/100`，支撑率由 `0.75` 升到 `0.80`——背景杂物被裁掉后误检候选减少。因此紧裁用例的
   门槛高于同一张原图。

### 未能在本轮完成验证的一项

`AndroidGridDeepLearningExecutorDeviceTest`（225 孔共享 PTL 整批推理）在本轮**未能跑完**：
测试进程连续两次被系统以 `installPackageLI` / `deletePackageX` 杀死，logcat 显示 APK 在
测试运行期间被重装。同一台机器上 Android Studio（PID 30688）处于运行状态并在并发构建，
同源现象还包括一次源文件被回退和两次 Kotlin 增量缓存损坏。这不是代码问题，需要在关闭
IDE 自动构建后补跑该项。

## 八、诚实的边界

1. **现有语料无法证明本次升级的收益**。合成语料与七扰动 case 的结果基本不变（这本身是
   保行为的好消息），真正的增益场景是荧光基底不可见、自发光点云、区域框截断——上游在
   `examples/fluo/` 下有对应样本，Android 侧尚未纳入回归。这是后续工作包。
2. **Python 与 Android 的 OpenCV 版本仍未对齐**（4.10 vs 4.5.3），形态学与 Otsu 边界像素
   仍可能存在实现差异，因此 parity 测试使用容差而不是逐位相等。
3. 本轮仍是 Kotlin + OpenCV Java API 实现，**不是** `docs/android_migration.md` 推荐的
   C++ 核心 + 薄 JNI 架构；网格规格仍需用户选择。这两项不因本轮改动而改变。
4. 4×4 旧规格当前没有可用的候选检测器，其 `trusted=false` 是如实声明而非可修复的缺陷。

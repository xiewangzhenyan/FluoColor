# GenePix Pro 核心机制借鉴与检测全链路可靠性加固方案

> 制定日期：2026-11-22
> 定位：面向现有比色 / 荧光规则阵列（微流控芯片、标准 96 孔板、自定义圆孔阵列）的**流程正确性与科学可靠性加固方案**，暂不引入重型商业化外围功能（如扫码、批量队列、复杂脚本查询）。

## 1. 目标与兼容前提

### 1.1 核心目标
借鉴商业微阵列分析金标准 **GenePix Pro 7**（官方手册 `0112-0178 D`）在**受控单点分割、三层互斥背景扣除、多统计量交叉质控、孔内稳健取值与人机复核闭环**上的成熟规则，修复 `FluoColor` 当前端侧链路中的物理与数值隐患，确保“原图输入 → 定位分割 → 背景扣除 → 信号与质控 → 标定/定量 → 多孔统计”全链路严密可靠。

### 1.2 不改变的既有契约
1. **默认设置不变**：「系统设置 → 阵列分析」默认仍为 **孔内取值 = 全孔中位数（`FULL_MEDIAN`）**、**多孔统计 = 分别显示（`INDIVIDUAL`）**；在孔内取值列表中新增 **中心区中位数（`CENTER_MEDIAN`）**，复用现有 `centerScale (50%–90%)` 滑块。
2. **原图无损采样不变**：所有定位增强、复合对比度平面和预览提亮滤镜仅服务几何/显示，定量像素仍 100% 通过逆单应从原始未增强 `Bitmap` 最近邻读取。
3. **历史快照与旧金标准零破坏**：
   - 已保存运行只读取冻结快照，不因处理器升级重算；
   - `PgQuantSampler` 在 `unitSegmentation == null` 时继续保留 `pg-quant-android-v1` 旧圆形 ROI 行为，确保既有 Python 金标准对照测试（`PgQuantGoldenParityTest`）不退化；
   - 携带分割掩膜的生产链路升级至新处理器版本（`pg-quant-android-v3`），通过版本门控防止旧标准曲线跨口径误用。

---

## 2. 四大模块具体改良设计

### 模块 1：定位后单孔分割与对齐（`OpenCvArrayUnitSegmenter`）

#### 1.1 模态感知的复合对比度分割平面（借鉴 GenePix CPI 复合像素强度，手册 p.22）
- **根因**：`OpenCvArrayUnitSegmenter.kt:73` 当前固定使用 `COLOR_RGBA2GRAY`（`0.299R + 0.587G + 0.114B`）配合 `minimumContrast = 12.0`。蓝色荧光（权重 `0.114`）与红色荧光（权重 `0.299`）对比度被大幅压缩；比色模式下与灰白基底等亮度的显色孔（如亮底黄孔）灰度差 `< 12`，会被误判为无前景。
- **方案**：
  - **荧光模式**：使用通道最大响应 $\max(R, G, B)$（或当前锁定荧光通道）构造单孔局部 Otsu 输入平面；
  - **比色模式**：以局部窗口外圈像素中位数估计基底背景色 $(R_{\text{bg}}, G_{\text{bg}}, B_{\text{bg}})$，构造最大通道绝对色差平面 $\Delta C(x,y) = \max(|R-R_{\text{bg}}|, |G-G_{\text{bg}}|, |B-B_{\text{bg}}|)$ 与灰度差中的较强响应执行局部 Otsu；
  - 输出仍为紧致 `ArrayUnitRegion` 二值掩膜，不改动原图像素。

#### 1.2 拆分“正常无斑点暗孔”与“溢流/错位异常孔”（借鉴 GenePix `Not Found` 四准则，手册 p.22）
- **根因**：当前所有分割未通过位点统一回退为 `FALLBACK_MEDIAN_GEOMETRY`，自动定位下既不持久化也不影响 `qualityReliable`。若一刀切判为坏孔，会误删荧光 `0` 浓度空白标准孔；若全部放行，则会把触窗粘连、溢流或严重偏心的坏孔混入标准曲线拟合。
- **方案**：将逐孔分割结果细化为三类并写入位点诊断快照：
  1. `SEGMENTED`：局部对比度、连通域面积、不触窗边界、尺寸比例与中心偏移全部通过，`qualityReliable = true`；
  2. `DARK_FALLBACK`：局部对比度 `< minimumContrast`（空白孔、阴性对照或 `<LOD` 暗孔的正常物理表现），使用全阵中位几何框兜底，**保持 `qualityReliable = true`**；
  3. `ALIGNMENT_REJECTED`：局部存在强对比信号，但因**触碰局部窗口边界（溢流粘连）、尺寸超出 `[0.40, 1.60] × 中位尺寸` 或中心偏移 `> 0.20 × pitch`** 被拒，标记 `PhotometryFlag.SEGMENTATION_ANOMALY` 并置 **`qualityReliable = false`**，自动从白平衡参考、现场曲线拟合与多孔均值中排除。

#### 1.3 局部采样中心跟随真实分割质心（借鉴 GenePix `Align Features`，手册 p.20, 61）
- 当位点为 `SEGMENTED` 时，孔内子区域（`WellReadoutGeometry`）与外围背景环统一以分割区域受限中心（相对晶格点偏移上限 `≤ 0.18 × pitch`）为圆心/对称中心，避免点样微偏心时背景环偏压到斑点一侧边缘。

---

### 模块 2：边界剔除、背景扣除与平场物理边界（`PgQuantSampler` & 模态处理器）

#### 2.1 基于真实单元外边界的“隔离谷区 + 邻孔互斥”背景环（借鉴 GenePix 3×背景域三剔除规则，手册 p.20 图 1-1）
- **根因**：`PgQuantSampler.kt:63-64, 258-260` 当前硬编码 `annulusInner = 0.30 × pitch` 且仅判断 `!inRoi`。在 96 孔板（圆孔半径约 `0.36～0.38 × pitch`，内缩 `0.9R` 后外圈 `0.33～0.40 × pitch` 恰为液面弯月面与塑料孔壁）和 15×15 方孔芯片（半宽约 `0.32 × pitch`）上，紧贴前景外的孔壁反光/腔壁阴影零间隔落入背景环，且未剔除相邻位点掩膜。
- **方案**（生产路径 `unitSegmentation != null`）：
  - **本孔禁采区**：以该孔**未内缩外边界**为基准，向外扩展 `valleyWidth = max(2px, round(0.06 × pitch))` 作为半影隔离谷区（Valley Exclusion），隔离带像素既不入前景也不入背景；
  - **邻孔互斥**：同时排除八邻域相邻位点的 `外边界 + valleyWidth`；
  - **自适应外环**：背景采样外径扩展至 `min(0.48 × pitch, 外边界半径 + 0.18 × pitch)`（受邻孔禁采区自动裁切，绝不侵入邻孔）；
  - **最小有效背景像素门控**：有效背景像素数 `< 16` 时标记 `BACKGROUND_INSUFFICIENT`（严重不足 `< 6` 时置 `qualityReliable = false`）。

#### 2.2 暗场荧光禁用“暗背景除法乘性平场”（借鉴 GenePix 加性背景扣除原则，手册 p.18–21, 27）
- **根因**：明场比色背景代表入射照明强度 $I_0(x,y)$，适合二阶乘性平场校正；而手机暗场荧光图的孔间背景仅为 `0～15` 灰阶的 CMOS 暗电流/黑电平与微弱杂散漏光（纯加性偏移，非激发光增益）。用近零暗背景拟合 `poly2` 做除法增益，会把局部 2～3 个灰阶的漏光波动放大成数倍的荧光信号失真。
- **方案**：
  - **比色模式**：当有效位点背景中位数均值 `≥ 45.0` 时启用二阶乘性平场 `poly2`，否则回退常量场 `1.0`；
  - **荧光模式**：**固定关闭基于孔间暗背景的乘性平场缩放（`flatFieldScale = 1.0`）**，严格执行干净的加性背景扣除 `netIntensity = foreground - backgroundMedian`；
  - **可选阴性对照本底扣除（Negative Control Subtraction）**：在荧光高级项中支持可选“按同板阴性对照/空白孔中位数扣本底”，用于消除免疫夹心反应的非特异性结合（NSB）。

---

### 模块 3：孔内取值扩展（含 `CENTER_MEDIAN`）与信号交叉质控

#### 3.1 孔内取值新增「中心区中位数（`CENTER_MEDIAN`）」
兼顾 **“避开孔壁边缘弯月面/腔壁阴影”** 与 **“抵抗孔中心单点灯珠高光/微气泡”** 两重需求：

| 枚举值 | 中文名称 | 英文名称 | 几何区域 | 聚合统计量 | 处理器版本后缀 |
| --- | --- | --- | --- | --- | --- |
| `FULL_MEDIAN`（默认） | 全孔中位数 | Full-well median | 完整有效前景掩膜 | 中位数 | 无后缀（兼容默认） |
| `FULL_MEAN` | 全孔均值 | Full-well mean | 完整有效前景掩膜 | 算术均值 | `-readout1-FULL_MEAN` |
| **`CENTER_MEDIAN`（新增）** | **中心区中位数** | **Center median** | **圆孔取同心圆、方孔取同心方框（比例 `centerScale` 50%–90%，默认 80%）** | **中位数** | **`-readout1-CENTER_MEDIAN-{scale}`** |
| `CENTER_MEAN` | 中心区均值 | Center mean | 同上（`centerScale` 50%–90%） | 算术均值 | `-readout1-CENTER_MEAN-{scale}` |
| `FIVE_REGION_MEAN` | 五区均值 | Five-region mean | 中/上/下/左/右 5 个等面积窗口 | 窗口均值等权平均 | `-readout1-FIVE_REGION_MEAN` |
| `CENTER_PIXEL` | 中心像素（诊断） | Center pixel (diagnostic) | 中心 1 像素 | 原值（禁止建标曲） | `-readout1-CENTER_PIXEL` |

**代码触点清单**：
- `PhotometryContracts.kt`：`WellReadoutMode` 增加 `CENTER_MEDIAN`，`PgQuantConfig.versionFor()` 与 `CENTER_MEAN` 共用带 `centerScale` 整数百分比的身份编码；
- `PgQuantSampler.kt`：`WellReadoutGeometry.region()` 将 `CENTER_MEDIAN` 与 `CENTER_MEAN` 归入同一形状感知内缩几何；`extractRawUnit()` 在 `CENTER_MEDIAN` 分支使用 `medianRgb()` 与 `median()` 计算窗口代表值；无论选何种前景口径，背景扣除一律保持 `backgroundMedian`；
- `ArrayAnalysisControls.kt` & `DetectionSettingsScreen.kt`：当选中 `CENTER_MEDIAN` 或 `CENTER_MEAN` 时均展开 `50%–90%` 中心区比例滑块与重标定提示；
- `GridProcessingEvidenceWriter.kt` & `Plate96ProcessingEvidenceWriter.kt`：第 5 张 `05_roi_background.png` 同步绘制 `CENTER_MEDIAN` 的实际中心采样边界；
- `values/strings.xml` & `values-zh/strings.xml`：同步新增 `array_readout_center_median`。

#### 3.2 有向荧光 SNR 与 `8-bit` 安全噪声分母（借鉴 GenePix SNR 定义，手册 p.25–26）
- **根因**：`FluorescencePhotometryProcessor.kt:98` 使用 `abs(netIntensity)`，导致 `F < B` 负净值被误判为高 SNR 可检出；`PgQuantSampler.kt:298` 仅用 `1.4826 × MAD`，在 8-bit 暗场图过半背景像素同为 `0/1` 时 `MAD = 0`，1 灰阶波动会触发 `SNR = 9999.0`。
- **方案**：
  - 定义 8-bit 有效背景噪声下界：同时计算局部背景像素的稳健 `MAD` 与截尾标准差 `SD_trimmed`，取 $\sigma_{\text{eff}} = \max(\sigma_{\text{MAD}},\; \sigma_{\text{SD\_trimmed}},\; 0.5\text{ LSB})$；
  - 荧光处理器保留**有符号净强度** `netIntensity` 与**有向检出信噪比** $\text{SNR}_{\text{directed}} = \text{netIntensity} / \sigma_{\text{eff}}$：仅当 $\text{SNR}_{\text{directed}} \ge \text{snrMinimum}$ 时置 `signalDetectable = true`；若 $\text{SNR}_{\text{directed}} \le -3.0$，附加 `NEGATIVE_NET_SIGNAL` 提示局部背景污染或暗气泡。

#### 3.3 同次遍历零成本补齐 3 项交叉质控指标（借鉴 GenePix 布尔 QC，手册 p.21, 77–78）
无需用户手动来回切换取值设置，在 `extractRawUnit` 单遍扫描中同步记录：
1. `pctAboveBgPlus2Sd`：前景像素满足 $I > B_{\text{median}} + 2\sigma_{\text{eff}}$ 的比例（区分真实整孔发光与单粒灰尘高光）；
2. `backgroundMeanMedianSkew`：$|B_{\text{mean}} - B_{\text{median}}| / \max(\sigma_{\text{eff}}, 1.0)$（检测局部背景环划痕、溢光尾巴）；
3. `foregroundMeanMedianSkew`：$|F_{\text{mean}} - F_{\text{median}}| / \max(\sigma_{F}, 1.0)$（检测孔内半边气泡、强反光或严重咖啡环）。

---

### 模块 4：人机检视、坏孔排除与重算锁定闭环

#### 4.1 定位确认页与手动四角页的“纯显示提亮滤镜”（借鉴 GenePix $\sqrt{I}$ 与反色显示，手册 p.16, 51）
- 在 `GridLocalizationConfirmation` 与 `ManualCornerEditor` 图像框右上角提供轻量三态切换：**`原图 | √I 提亮 | 反色增强`**（带“仅显示”标识）。
- `√I 提亮` 对预览副本执行 $I' = 255 \sqrt{I / 255}$ 与 `P01–P99.5` 拉伸，使暗场弱荧光孔与芯片暗角在屏幕上清晰可辨，解决手动点四角“摸黑盲点”痛点，底层采样输入完全不变。

#### 4.2 微流控单孔检视镜与手动标 `Bad`（借鉴 GenePix `Feature Viewer + Flag Bad`，手册 p.54, 65–66）
- 将微流控定位确认页升级为可点选画布（与 96 孔板交互对齐）：点击任意位点弹出紧凑内联检视卡，展示该孔放大裁切图（叠加真实前景掩膜、隔离谷区与有效背景环）、`F中位 / F均值 / B中位 / 有向SNR / %>B+2SD`；
- 提供 **`微调位置/尺寸`** 与 **`标为异常孔（Bad）`** 两个动作：
  - 被用户标记为 `MANUAL_BAD` 的孔冻结 `qualityReliable = false`；
  - 自动联动现有下游门控：自动退出比色参考白平衡（`ColorimetricPhotometryProcessor.kt:95`）、自动退出标准曲线拟合（`ArrayCalibrationEngine.kt:71`）、自动退出多孔均值/中位数聚合（`ArrayMultiHoleStatistics.kt:46`），但在结果页详情与导出 CSV/PDF 中完整保留原始读数与“人工标记异常”原因。

#### 4.3 联动失效保护（借鉴 GenePix `Linkage`，手册 p.69, 73）
- 确认页内任何单孔微调、定位算法切换、孔内取值切换（含 `CENTER_MEDIAN` 及比例调整）或手动 `Bad` 标记变更，均立即刷新光度会话并使 `CalibrationInputFingerprint` 变化，强制已预览的旧现场拟合失效重算，杜绝几何/质控与标准曲线参数脱节。

---

## 3. 分步实施与验证计划

- [x] **Task 1：孔内取值 `CENTER_MEDIAN` 与光度/SNR/平场核心加固（纯 `domain` 层）**
  - 完成 `CENTER_MEDIAN`、隔离谷区 + 邻孔互斥背景环、暗场荧光禁用乘性平场、`8-bit` 安全噪声下界 $\sigma_{\text{eff}}$、有向荧光 SNR 与 3 项交叉质控指标；
  - **验证**：新增 JVM 单元测试覆盖 `CENTER_MEDIAN` 圆/方孔几何、版本号隔离、`MAD=0` 暗图不爆 `9999.0`、负净荧光不触发 `signalDetectable`、96 孔板/方孔隔离谷区不采孔壁、邻孔强光不污染本孔背景；运行 `PgQuantGoldenParityTest` 确认旧 `v1` 金标准兼容不退化。
- [x] **Task 2：模态感知复合对比度分割与三态回退（`OpenCvArrayUnitSegmenter`）**
  - 接入荧光最大通道 / 比色最大色差分割平面，拆分 `SEGMENTED / DARK_FALLBACK / ALIGNMENT_REJECTED` 并联动采样中心微对齐与 `qualityReliable`；
  - **验证**：合成蓝/红弱荧光、等亮度黄/灰比色、0 浓度空白暗孔、跨窗溢流孔四组用例测试，确认零浓度标准孔保留、溢流坏孔自动拦截；复跑 6 张实拍回归图与七扰动语料。
- [x] **Task 3：设置接入与纯显示提亮 + 单孔检视 / 手动 `Bad` 交互（`ui` + `viewmodels`）**
  - 接通 `CENTER_MEDIAN` 设置项与滑块、中英文资源；在定位确认页与手动四角页接入纯显示提亮滤镜、单孔放大检视与手动标 `Bad` 闭环；
  - **验证**：中英文 `strings.xml` ID 与占位符一致性检查；`testDebugUnitTest` 全量通过；API 35 模拟器验证 360dp 中文浅色、英文深色、1.3 倍字体与横屏交互，确认标 `Bad` 的标准孔/样本复孔自动退出拟合与复孔统计且历史记录只读不变。

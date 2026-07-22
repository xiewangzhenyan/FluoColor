# FluoColor 用户体验回退实施日志

> 开始日期：2026-07-21
> 实施分支：`codex/ux-rollback-implementation`
> 方案依据：`docs/2026-07-21-ux-rollback-and-microfluidic-retention-plan.md`
> 记录规则：只记录已经实际完成或已经验证的工作，不把计划写成完成结果。

## 实施目标

- 恢复普通用户可以直接创建项目的流程。
- 模板、采集设备档案和已发布分析模型不再作为创建项目的强制前置条件。
- 没有定量模型时使用“仅信号模式”，保留定位、光度、热力图、QC 和结果保存。
- 标准曲线恢复为数据点驱动的自动拟合，不允许用户手工填写参数 JSON。
- 深度学习模型未接通真实执行器前，不再向普通用户暴露手工技术参数表单。
- 保留 Room 12、PG-Grid、模态专用光度链、通用阵列结果和科研导出。

## 实时记录

### 2026-07-21：实施准备

- 已读取项目 `AGENTS.md`、README 和最终重构方案。
- 已确认当前工作区包含大量未提交修改和未跟踪文件，后续禁止执行破坏性 Git 操作。
- 已从当前工作区创建安全实施分支 `codex/ux-rollback-implementation`，原有修改完整保留。
- 已确认第一批实现范围为“解除项目创建阻断 + 接通仅信号模式”。

### 2026-07-21：第一批直接新建主链

- 新增 `DirectProjectCreationCoordinator`，普通项目不再向模板、载体、采集设备或分析模型表写入一次性资源。
- 直接创建会在项目内部冻结一份专属快照，继续满足 PG-Grid、结果追溯和科研导出的数据要求。
- 新增 96 孔板、10×10 微流控、15×15 微流控和自定义规则微流控四种直接载体规格。
- 新增明确的仅信号模型快照。其标准曲线参数保持空对象，使量化器安全返回 `SIGNAL_ONLY`，不会生成默认斜率或伪造浓度。
- 微流控比色必须由用户填写真实参考位行列；缺少或越界时拒绝创建，程序不会自动把第一个位点伪装成空白位。
- 新增独立 `DirectProjectViewModel` 和直接新建页面，首页原有新建按钮仍使用原路由，但路由内容已切换到直接新建页面。
- 页面提供项目名称、检测方式、载体规格、分析物、浓度单位下拉、样本编号、比色参考位以及拍摄/导入图片。
- 模板高级创建页面仍保留，没有删除旧数据和模板兼容入口。
- 用户新增硬性约束：首页、历史、关于的 UI、动画和导航结构全部冻结。本批没有修改这三个页面文件。

### 2026-07-21：第一批验证

- `:app:compileDebugKotlin` 通过。
- 新增 `DirectProjectFormStateTest`，覆盖荧光直接创建、自定义行列、比色参考位和 96 孔板兼容。
- 新增 `DirectProjectCreationCoordinatorTest`，覆盖 10×10 仅信号快照、真实比色参考位、缺少参考位拒绝创建和 96 孔板路由。
- 两组新增单元测试全部通过。

### 2026-07-21：设置入口与模板交互回退

- 普通设置页继续只保留“曲线模型库”和“实验模板库”等用户可理解入口；载体库、采集设备档案和手工技术参数分析模型页不再作为普通入口。
- 实验模板列表已恢复“编辑、删除”，已发布模板允许原地编辑，不再要求创建新版本；删除前仍弹出确认提示，确认后执行真实删除。
- 模板向导已移除采集设备档案选择及其保存门槛，页面明确说明手机型号、Camera ID、ISO、曝光、焦距和光圈等由拍摄链自动记录。
- 分析模型改为可选项，并新增明确的“仅信号”选项；没有兼容曲线时仍可保存模板，后续保留信号、热力图和质量控制结果，不伪造浓度。
- 只有选择定量模型后才展示浓度单位和可靠范围。浓度单位读取与检测设置相同的 DataStore 列表，可靠范围输入实时拒绝字母、负号和重复小数点。
- 模板最终操作改为一次“保存模板”，保存后后台直接标记为可用；普通页面不再出现“发布条件”“发布模板”等生命周期术语。
- 新增仅信号模板校验测试，确认设备档案、分析模型、浓度单位和可靠范围不再成为仅信号模板的阻断项。
- `:app:compileDebugKotlin` 通过；模板相关测试源码编译通过。

### 2026-07-21：标准曲线与自动相机元数据

- 曲线模型库的新增按钮现在直接进入“标定点 + 自动拟合”页面，普通流程不再展示手工填写函数参数的入口。
- 标准曲线页面固定使用“浓度、信号值”两列，移除了行列数、列类型等额外配置；输入框实时过滤非法数字。
- 拟合函数改为下拉选择，默认项为“自动推荐”。自动模式调用现有 `FittingEngine.fit()` 比较适用函数；用户指定函数时调用 `fitSingle()`，两种方式都由程序计算参数。
- 新增 CSV/文本标定点导入，支持逗号、分号、制表符和空格分隔格式；标题或无效行会被忽略并向用户报告数量。
- 拟合结果只读展示选用函数、R²、曲线图、函数表达式和拟合指标，保存时写入旧 `CurveModel` 兼容表，历史项目继续可用。
- 新增 `CalibrationDataParserTest` 和 `CurveModelViewModelTest`，覆盖 CSV 标题处理、负浓度拒绝、自动拟合、指定函数拟合及信号特征保存。
- 相机旁路元数据在原有手机型号、Camera ID、ISO、曝光和 EXIF 基础上，补充镜头方向、可用焦距、可用光圈、最小对焦距离、传感器像素尺寸、连续对焦模式和实际缩放范围。
- `:app:compileDebugKotlin` 与 `:app:compileDebugUnitTestKotlin` 通过。

### 2026-07-21：文档清理与完整回归

- README 已改为当前真实流程：直接新建、模板可选、模型可空、仅信号不伪造浓度、曲线自动拟合、模板直接保存/编辑/删除。
- 已删除 `docs/stitch/FluoColor-Stitch-UI-Prompt-Pack.md`，README 不再引用 Stitch 设计记录；后续以代码、测试和本实施日志为准。
- 专项测试通过：`CalibrationDataParserTest`、`CurveModelViewModelTest`、`TemplateWizardModelsTest`、`ExperimentTemplateWizardViewModelTest`、`ExperimentTemplateRepositoryTest`。
- 完整 `:app:testDebugUnitTest` 通过。
- `:app:assembleDebug` 通过，生成 `app/build/outputs/apk/debug/app-debug.apk`。
- 当前环境没有可用 `adb` 命令，因此本轮未执行设备/模拟器仪器测试；已有 AndroidTest 源码和历史 API 35 回归结果均保留。
- 本轮没有编辑首页、历史、关于页面及其 Pager、底部导航或动画代码。工作区中这些文件已有的差异来自本轮开始前，未被覆盖或回滚。

### 2026-07-22：新建项目页视觉回退与整合

- 已从 Git 基线 `dd643eb` 读取旧版 `NewProjectScreen.kt`，提取其连续表单、明确字段顺序、完整图片预览和相册/拍照双入口等有效交互。
- 已重写 `DirectCreateProjectScreen.kt` 的页面布局，将原先分散的多张配置卡合并为一张“实验配置工作单”，减少页面碎片感和重复说明。
- 检测方式改为同一行三等分选择；96 孔板、10×10、15×15 和自定义阵列改为 2×2 规格网格，并保留当前直接创建所需的数据映射。
- 自定义阵列行列输入只在选中自定义规格时展开；微流控比色参考位只在对应场景显示，并继续使用表单状态执行范围校验。
- 分析物和浓度单位继续使用选择控件，未改回手工自由文本；样本编号保持可选，项目名称、数值输入和创建条件继续沿用现有校验逻辑。
- 图片区域改为约 220–232 dp 的完整比例预览，使用 `ContentScale.Fit` 避免裁掉芯片边缘，并提供更换和删除操作。
- 图片来源弹窗恢复相册与拍照两个直观入口；所有新增可见文案均已同步写入中英文字符串资源。
- 底部创建操作保持固定显示，并根据表单状态给出“可以开始检测”或“尚未完成必填项”的轻量提示。
- 本次只修改直接新建页面及其字符串资源，没有修改首页、历史、关于页面，也没有修改 Pager、底部导航或动画。
- `git diff --check` 已通过，`:app:compileDebugKotlin` 已通过。
- 完整 `:app:testDebugUnitTest` 与 `:app:assembleDebug` 已通过，Debug APK 已重新生成；Gradle 仅报告项目现有的弃用特性提示，没有本次新增测试或构建错误。
- 用户启动 Android 虚拟机后，已通过 `F:\Android\Sdk\platform-tools\adb.exe` 连接 `emulator-5554`，覆盖安装 Debug APK，并实际进入直接新建项目页截图审视。
- ADB 截图确认顶部连续表单、下半部分分析设置、仅信号提示、比色参考位和图片入口均可正常滚动显示；同时发现“自定义微流控阵列”在两列规格卡中断成三行，以及图片来源弹窗底部留白偏多。
- 根据截图反馈将规格名称缩短为“自定义阵列”，把样本编号的无线信号图标改为更符合语义的标签图标，并将图片来源弹窗改为带右上角关闭按钮的紧凑双入口面板。
- 修正后重新执行完整 `:app:testDebugUnitTest` 与 `:app:assembleDebug`，随后再次覆盖安装到 `emulator-5554`。第二轮截图确认自定义阵列标题保持单行、样本编号图标语义正确、弹窗空白明显减少，未发现新的重叠、截断或底部操作遮挡。
- 后续所有前端页面统一执行 ADB 视觉验收闭环：构建安装、进入目标页面、截图检查、根据实际截图修正、再次截图复验；固定坐标点击失败时必须读取 UIAutomator 层级确认真实页面，不能把脚本无报错视为验收通过。

## 当前状态

- 已完成：无需模板、设备档案和已发布模型的直接新建第一版。
- 已完成：仅信号项目快照与微流控比色参考位门控。
- 已完成：普通设置入口简化、实验模板直接编辑/删除、设备与模型非强制化。
- 已完成：标准曲线标定点录入/CSV 导入、候选函数下拉、自动拟合与只读结果展示。
- 已完成：相机自动元数据补齐镜头方向、焦距、光圈、对焦能力和缩放信息。
- 已完成：直接新建项目页按旧版连续表单思路完成视觉重构，同时保留当前微流控和仅信号业务能力。
- 已完成：README 清理、专项测试、完整 JVM 测试与 Debug APK 构建。
- 2026-07-21 首轮回归时未执行设备测试；2026-07-22 用户启动虚拟机后已恢复 ADB 验收能力。

### 2026-07-22：PG-Grid 实现与可视化证据审计

- 已读取 `D:\A-lunwen\8多模态手机方案\pg_grid_remote_check` 当前参考代码、README、V2 设计、测试、评估、benchmark 和可视化模块。
- 参考目录当前 42 项 pytest 全部通过；重新生成的 10×10、15×15、4×4 定位/光度六个金标准与 Android 冻结资源严格一致。
- 已在 `emulator-5554` 运行 19 项定位、PG-Quant、性能、协调器和界面仪器测试，全部通过；10×10/15×15 定位和光度误差仍位于现有容差内。
- 审计确认 Android 核心主链可运行，但不是 Python 逐算法等价移植：规则轴求解、局部精修、支撑距离和 IRLS 数值细节存在差异，也没有 Android 七扰动族退化曲线。
- 审计确认当前微流控处理中页面只有进度圈和阶段文字；完成后可以查看原图冻结定位、热力图、位点详情和 QC，但看不到矫正图、ROI/背景环、平场、SNR 热力图或校正颜色图。
- 当前直接新建微流控项目会直接进入 `WellDetection`，不会经过旧 `ImageCorrectionScreen`；PG-Grid 主链只执行单应透视矫正，没有接入旧径向畸变 `undistort` 代码。
- 详细证据、测试数字和建议已记录到 `docs/2026-07-22-pg-grid-android-audit.md`；本轮没有修改算法或 UI 实现。

### 2026-07-22：PG-Grid 七扰动语料与首轮算法修复

- 新增 `tools/pg_grid/export_perturbation_corpus.py`，直接调用用户参考工程的 `pg_benchmark.make_scene`、`apply_perturbation` 和 `pg_grid.process_image`，没有复制或另写一套 Python 定位算法。
- 首批冻结 10×10 暗方块、15×15 亮点两种主规格，以及旋转、透视、模糊、光照梯度、遮挡、高光、噪声七类中等扰动，共 14 个确定性 case。
- 每个 case 保存输入 PNG、输入 SHA-256、原图行优先真值坐标与信号真值、Python PG-Grid V2.1 适配结果、PG-Quant 原始结果和定位/光度/QC 摘要；语料共 43 个文件、约 9.6 MB。
- 连续两次重生成后的目录组合 SHA-256 均为 `F74898F3051C47180DAE801C12DAB40DA8032B0D26437487FA38559560A3E7B5`，确认去除生成时间后可逐字节复现。
- 新增 `PgGridPerturbationCorpusTest`，设备端重新校验图片 SHA-256，并逐 case 检查固定点数与顺序、原图坐标 `%pitch` 误差、候选支撑、可信状态、模型补位比例、校正信号 Spearman 和可靠位点比例；单个失败不会阻止其余 case 留下诊断日志。
- 首轮 API 35 回归发现 4 个真实失败：10×10 模糊平均误差约 `0.998 pitch`；15×15 光照、遮挡、高光平均误差约 `1.19 pitch`。共同根因是 Android 固定 K 的加权 k-means 会被额外强簇挤掉真实首/末轴，产生外观规则但整体错一格的晶格。
- 已将规则轴改为与 Python 参考实现同源的“轴向近邻聚簇 → 最强 `count+4` 簇限流 → 枚举物理规则组合 → 按间距残差、响应支持和成员数评分”，15×15 三个整数格错位全部消失。
- 10×10 模糊仍因黑帽连通域数量受 OpenCV 版本边界影响而退回过宽均分网格；已新增“暗/亮能量 → 大尺度背景扣除 → 行列投影平滑 → 贪心峰 → 规则组合”的图像初值路径，并在局部证据恢复后重新计算 `trusted`，不再永久沿用早期降级状态。
- 最终七扰动语料 `14/14 case` 全部通过；最差平均误差 `0.00693 pitch`，最差 P95 `0.01492 pitch`，最低信号 Spearman `0.8277`，最低可靠位点比例 `0.8533`。10×10 模糊从 `0.998 pitch` 降至 `0.00018 pitch`，100 个点全部重新获得局部证据。
- 原有定位、光度、性能和协调器专项共 10 项再次通过。冻结金标准仍为：10×10 平均/P95 `0.005328/0.035779 pitch`，15×15 `0.001271/0.002339 pitch`；最大光度差仍约 `0.362` 灰度单位。
- 修复后性能：10×10 P50/P95 `84.09/89.51 ms`、峰值堆增量 `5.82 MB`；15×15 P50/P95 `259.12/283.84 ms`、`9.25 MB`，继续低于既定 `1500 ms/160 MB` 门槛。
- 本工作包没有修改首页、历史、关于、Pager、底部导航或任何前端页面。实验室真实 10×10/15×15 拍摄图目前仍未出现在项目或参考目录中，因此“真实图片金标准”仍需后续采集后补入，不能把本批合成扰动语料宣传为真实样本。

### 2026-07-22：处理过程证据与结果页续作（已完成）

- 接续实现真实 PG-Grid 处理过程证据、帧级 QC 和阵列结果页“处理过程”标签，不重复已完成的七扰动与定位修复。
- 修复 `GridProcessingEvidenceWriterTest` 对当前 Gson 版本不兼容的问题：将新版静态 `JsonParser.parseString` 改为项目可用的实例解析 API，并保留中文注释说明兼容原因。
- 下一步先运行处理证据、协调器、Room 原子保存、Compose 结果页和帧级 QC 五组设备专项测试；通过后再执行完整算法回归与 ADB 视觉复验。
- 首页、历史、关于、Pager、底部导航和既有动画继续保持冻结，本工作包不触碰这些界面。
- 五组设备专项共 20 项首次运行时 19 项通过；唯一失败是 Compose 测试使用不存在的示例证据路径，Coil 切换到错误占位后，原先挂在 `AsyncImage` 瞬时节点上的测试语义标记随之消失。
- 已将标记移动到稳定的“证据图显示区域”，并明确保证图片正常、加载中和附件缺失三种状态共享同一可访问语义区域；结果页 6 项测试单独复跑通过。
- 随后五组设备专项 20/20 全部通过，确认九张 PNG 可解码且 SHA-256 一致、协调器携带诊断附件、Room 原子保存终点图与处理证据、Compose 可打开处理过程标签、帧级 QC 与重拍门控未回归。
- PG-Grid/PG-Quant 完整设备专项共 10/10 通过，覆盖旧定位行为、Python 冻结金标准、10×10/15×15 七扰动语料、PG-Quant 图像与金标准以及性能门槛；处理证据接入没有改变定位与定量结果。
- UI 修正与文档更新后再次执行完整 `:app:testDebugUnitTest` 和 `:app:assembleDebug`，最终回归通过；最终 APK 已覆盖安装到 `emulator-5554`。
- 使用最终 APK 从登录、中文切换、直接新建、10×10 比色、CA125、参考位 `R01C01`、相册导入到检测完成，重新走通一条真实应用内生产流程；运行短 ID 为 `44bdbe3eaafd`，保存 `100/100` 个位点。

- 应用私有目录 `files/processing_evidence/261c9632-6aa1-45bd-91e2-44bdbe3eaafd/` 实际存在九张处理证据，文件名从 `01_original_geometry.png` 到 `09_corrected_color_map.png`，不是仅由测试模拟生成。
- 首轮 ADB 截图发现五个可滚动一级标签把“质量控制”裁出屏幕，并在中文界面显示 `SignalOnlyCompleted`、`microfluidic-10x10` 机器值；已改为五个等宽短标题，并统一复用本地化状态，将内置载体显示为“微流控 10×10”。
- 第二轮截图确认“总览、分析物、原图、过程、质控”同时完整可见；“过程”首屏层级清楚，主图高度合适，说明和 `1/9` 进度没有遮挡。横向滑动步骤条后可见 ROI/背景环、背景场、校正信号、SNR、校正颜色，并成功打开第 `2/9` 候选响应与第 `8/9` SNR 热图。
- 截图证据保存于 `docs/superpowers/plans/screenshots/`：`array-result-overview-before.png`、`array-processing-before.png`、`array-processing-lower-before.png`、`array-result-overview-after.png`、`array-processing-after.png`、`array-processing-snr-after.png`。
- 首页、历史、关于、Pager、底部导航和既有动画未修改。最终验证图仍为项目确定性合成芯片图，不能替代实验室真实芯片金标准。

### 2026-07-22：系统相册与系统文件双通道导入（已完成）

- 直接新建页第一层图片来源保持“从相册选择 / 拍照”不变；点击“从相册选择”后新增第二层“系统相册 / 文件”，没有把 Download 写入普通界面文案。
- “系统相册”改用 `ActivityResultContracts.PickVisualMedia` 与 `ImageOnly` 请求，只允许选择图片；“文件”使用 `ActivityResultContracts.OpenDocument` 与 `image/*`，由 Android DocumentsUI 提供文件夹、存储卷和其他文档提供方浏览能力。
- 两种已有图片来源共用同一个 URI → uCrop 导航函数，避免相册与文件路径出现不同裁剪、表单回填或项目创建行为；新增实现包含中文维护注释，中英文字符串资源保持同一 ID。
- 两级面板复用同一个双选项布局，保持卡片、图标、间距和关闭入口一致。首轮 ADB 截图发现“浏览文件夹并选择图片”在 411 dp 屏幕上末字单独换行，已缩短为“浏览设备文件夹”并重新截图，最终两张卡片等高且无截断。
- `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:assembleDebug` 均通过；最终 APK 已覆盖安装到 `emulator-5554`。
- ADB 确认“系统相册”实际启动 `PhotoPickerActivity`（`android.provider.action.PICK_IMAGES`），“文件”实际启动 DocumentsUI `PickActivity`（`ACTION_OPEN_DOCUMENT`）；系统文件侧边栏可进入 Images、Downloads、设备内部存储和 SD 卡。
- 已从 DocumentsUI 的 Download 目录选择 `1.jpg` 实拍 15×15 芯片图，应用获得对应文档 URI 权限并成功进入 uCrop 裁剪页，证明原问题具备稳定替代路径。
- 复验截图：`direct-create-image-source-level1.png`、`direct-create-image-source-level2-after.png`、`direct-create-system-gallery-picker.png`、`direct-create-system-file-picker.png`、`direct-create-system-file-picker-folders.png`、`direct-create-file-selection-crop.png`。
- 本工作包未修改首页、历史、关于、Pager、底部导航或动画。

### 2026-07-22：用户实拍芯片兼容与强制重拍修复（已完成）

- 已读取 `D:\A-lunwen\8多模态手机方案\pg_grid_remote_check\docs\android_migration.md` 与用户新提供的
  `pg_grid_github_realcheck_20260722/examples` 实拍图片，确认 Android 当前并未完成推荐的 C++ 核心 +
  薄 JNI + Kotlin/Compose 架构，而是 Kotlin + OpenCV Java API 的独立实现；OpenCV 当前为 4.5.3，
  网格规格仍由项目行列提供，不能宣称迁移手册已经完整落地。
- 复现出两个直接根因：历史 15×15 载体把目标极性写成 `BRIGHT`，但用户 EL 背光实物是亮背景上的
  暗单元；协调器又把任何帧级 `FAILURE` 都直接转成 `RetakeRequired`，导致晶格已经可信的真实图片
  仍显示“需要重新拍摄”。
- 定位器现同时检测暗/亮候选，根据候选数量与理论位点数吻合度、内部灰度统计提示和候选晶格是否真正
  成立自动裁决极性。旧载体极性只作为最终平局偏好，不能覆盖真实图像证据；直接新建 15×15 的默认
  快照也修正为 `DARK`。
- 帧级过曝、欠曝、模糊、光照不均、透视和几何 RMSE 仍完整记录到运行与附件 QC，但不再把保守阈值
  等同于“算法不可计算”。只要定位器已经返回完整可信晶格，系统继续执行光度、保存测量和生成九步
  证据；完成页和质控页提示风险，由用户结合原图、定位和处理过程决定是否重拍。
- 用户提供的 2 张 10×10 与 4 张 15×15 原始 JPEG 已复制到
  `app/src/androidTest/assets/pg_grid/real_v1/images/`，保持文件字节不变，仅改 ASCII 文件名；清单保存
  原始名称、SHA-256、规格、Python 极性、支撑率和质量状态。
- 新增 `PgGridRealPhotoRegressionTest`，故意给 15×15 继续传入旧 `BRIGHT` 偏好。六张图片最终均自动
  识别为 `DARK`，候选支撑率为 `0.91 / 0.77 / 1.0 / 1.0 / 1.0 / 0.924444`，全部形成完整可信晶格。
- 在 `emulator-5554` 从系统 Files 的 Download 目录导入真实 15×15 图片完成生产检测，保存
  `225/225` 位点并得到 `211` 个可靠结果；不再进入“需要重新拍摄”，原图定位、最终晶格和九步处理
  证据均能正常查看。
- ADB 首轮完成页截图发现“查看阵列结果”在窄按钮中换成两行，已缩短为“查看结果”；结果页也已取消
  帧级失败对总览/分析物热力图的隐藏逻辑。最终 APK 安装和截图复验将在本工作包收尾检查中再次执行。
- 本工作包没有修改首页、历史、关于、Pager、底部导航或动画。

#### 最终收尾复验

- 实拍回归、Python 冻结金标准、七扰动语料、帧级 QC、检测协调器、检测完成页和阵列结果页设备专项
  最终共 `22/22` 通过，未出现失败或跳过。
- 完整 `:app:testDebugUnitTest` 与 `:app:assembleDebug` 已通过；最新 Debug APK 已覆盖安装到
  `emulator-5554`。
- 使用 Download 中真实 `1.jpg` 重新完成 `Real15Final` 生产流程，运行短 ID 为 `f48510db39cd`：
  保存 `225/225` 位点、可靠结果 `211`、有效观测率 `100%`、平均置信度 `99.6%`、几何 RMSE
  `0.70 px`，候选支撑率 `100%`。
- 该实拍图仍记录 1 项整帧过曝风险，但系统不再强制进入“需要重新拍摄”；完成页明确提示风险并保留结果，
  用户可在原图、处理过程和质控页复核后自行决定是否重拍。
- 完成页“查看结果”已保持单行；帧级 QC 风险不会再隐藏总览热力图。最终截图已复验：
  `real-15x15-completed-soft-qc-final.png`、`real-15x15-result-overview-final.png`、
  `real-15x15-original-overlay-final.png`、`real-15x15-processing-final.png`、
  `real-15x15-final-grid-final.png`、`real-15x15-qc-final.png`。
- 已重新通过 UIAutomator 精确点击“最终晶格”并覆盖最后一张截图，确认页面选中 `4 / 9`，完整
  15×15 晶格逐点落在真实暗单元中心；不再误把 `1 / 9` 芯片区域截图当作最终晶格证据。
- 当前结论保持不变：Android 已具备可运行的 Kotlin + OpenCV Java 实拍处理主链，但 C++ 核心 +
  薄 JNI、OpenCV 版本与桌面端统一、10×10/15×15 规格自动裁决仍属于后续独立迁移工作包，不能宣称
  `android_migration.md` 已完整实现。
- 本次最终收尾仍未修改首页、历史、关于、Pager、底部导航或动画。

### 2026-07-22：PDF 统一复用既有封面模板

- 审计发现规则阵列 PDF 第一页完全由 `ArrayResultPdfExporter` 手工绘制，没有使用用户指定的
  `res/layout/pdf_cover_page.xml`；旧96孔板导出虽然注释声称使用 XML，实际实现也已经退化为手工绘制。
- 新增共享 `PdfCoverPageRenderer`，96孔板与10×10/15×15规则阵列现在都实际 inflate 同一个
  `pdf_cover_page.xml`，统一 Logo、绿色页眉、报告标题、项目信息卡、内容概览卡和页脚。
- 模板使用固定 160dpi 布局上下文，避免不同手机密度把 80dp 页眉和卡片间距异常放大；输出前按
  2 倍尺寸栅格化再缩放到 A4 页面，兼顾固定版式与文字/Logo 清晰度。
- XML 中原有“Sample Project”等运行时硬编码示例已改为仅供 Android Studio 预览的 `tools:text`；
  实际 PDF 内容继续使用中英文字符串资源和当前应用语言。
- 规则阵列 PDF 页序现为“模板封面 → 总览 → 分析物分页 → QC/追溯”，总页数和各页页脚同步加一；
  既有冻结快照、热力图、QC 与追溯计算未改变。
- `:app:compileDebugKotlin`、完整 `:app:testDebugUnitTest` 通过；设备端 `ArrayResultExportTest` 2/2
  通过，验证一分析物报告共 4 页，并将第一页渲染为位图检查模板绿色页眉。
- 已从模拟器实际拉取测试 PDF，使用 PyMuPDF 以 2.2 倍分辨率渲染第一页完成视觉审查；未发现标题、
  卡片、目录或页脚裁切/重叠。截图保存为 `screenshots/array-pdf-template-cover.png`。
- 本工作包只修改 PDF 导出和模板渲染，没有修改首页、历史、关于、Pager、底部导航或动画。

### 2026-07-22：96孔板共享浓度模型与成熟标准曲线自动择优（已完成）

- 用户确认 `app/src/main/assets/models/improved_concentration_model_lite.ptl` 暂时同时服务比色和荧光，
  比色结果不增加“实验性”或“未经验证”提示；后续再通过用户上传的专用模型分别绑定检测场景。
- 已联网复核 ICH M10、CLSI EP06 以及 4PL/5PL 模型选择研究。默认自动拟合不再让全部函数按训练集
  `R²` 竞争，改为有限候选的加权线性、4PL、5PL，并把标准点反算浓度偏差、端点接受情况、有效
  浓度水平数量和模型复杂度作为主要裁决依据。
- 已确认当前回归：`DetectionModeSupport` 要求两个实际不存在的模式专用 PTL，导致旧96孔板链无法
  加载仓库中真实存在的共享模型；`WellResultRepository` 又对比色裁切孔逐孔执行灰世界白平衡，
  与新比色处理器“全阵列共享参考白增益、禁止逐 ROI 灰世界”的科学契约相冲突。
- 已从 Git 基线确认原始模型推理路径为 `128×128` RGB 裁切图加 ImageNet mean/std 归一化；新增的
  黑电平扣除、固定绿色增益和逐孔灰世界并非模型文件自带契约。本轮将恢复统一推理输入，保留比色
  与荧光在传统光度信号、背景处理、参考校正和 QC 层面的正确分流。
- 首页、历史、关于、Pager、底部导航和既有动画继续冻结，本工作包不修改这些页面。
- 第一阶段代码已经落地：`DetectionModeSupport`、`WellResultRepository` 和旧布局模型入口统一引用
  `improved_concentration_model_lite.ptl`；旧96孔板不再按模式逐孔执行灰世界、固定黑电平或绿色增益，
  共享模型恢复使用 Git 基线中的 `128×128 RGB + ImageNet mean/std` 输入。
- 新增 `CalibrationModelSelector`：支持无权重、`1/|y|`、`1/y²`，重复浓度水平充分时增加逆方差权重；
  4PL/5PL 使用正参数重参数化、多起点拟合、实验范围单调性检查和解析反函数。5PL 只有在不对称参数
  明确偏离1、同权重额外平方和检验显著且反算 RMSE 至少改善5%时，才允许优先于4PL。
- `FittingEngine.fit()` 已切换到新自动择优器；专家手动指定其他函数的能力继续保留。旧96孔板手动
  拟合对话框默认候选由“线性、二次、4PL”改为“线性、4PL、5PL”，结果排序优先使用反算验收指标。
- 数学专项最终覆盖共享模型路径、线性优先、对称4PL、非对称5PL、少量标准点防过拟合、重复标准孔
  逆方差权重候选及负R²保留；完整 `:app:testDebugUnitTest` 共 `216/216` 通过。
- 新增 `SharedConcentrationModelDeviceTest`，直接从 APK 的 main assets 提取真实
  `improved_concentration_model_lite.ptl`，构造确定性 128×128 RGB 输入，使用与生产代码一致的
  ImageNet mean/std 执行 PyTorch Mobile Lite 前向推理，并断言输出非空且全部为有限值；API 35
  模拟器专项最终 `1/1` 通过。
- 首次覆盖安装后启动检查发现 Debug 增量构建目录中的 Hilt ViewModel 聚合映射异常为空，表现为
  `UserViewModel` 无参构造反射失败。该问题不是业务源码回归；执行 `:app:clean` 后从零重建，生成映射
  恢复为 26 个 Hilt ViewModel，并包含 `UserViewModel`。最终 APK 再次覆盖安装后进程正常运行，
  AndroidRuntime 无崩溃记录。
- ADB 首轮函数选择截图发现专家公式直接显示 `\\cdot`、`\\frac` 等 LaTeX 源码，中文界面函数名也
  大量保留英文。已将当前页面所有专家函数名称中英文资源化，并用普通用户可读的 Unicode 数学文本
  显示公式；自动推荐继续固定在首项，4PL/5PL 分别明确显示为“四参数逻辑曲线”和“五参数逻辑曲线”。
- 最终截图保存为 `screenshots/calibration-model-auto-selector-final.png`、
  `screenshots/calibration-function-picker-localized-final.png`、
  `screenshots/calibration-function-picker-4pl-localized-final.png`、
  `screenshots/calibration-function-picker-5pl-localized-final.png`。复验未发现文字截断、按钮拥挤或
  LaTeX 控制符泄漏。
- 最终 `:app:assembleDebug`、完整 `:app:testDebugUnitTest` 和真实 PTL 设备专项均通过；最新 APK 已覆盖
  安装到 `emulator-5554` 并成功启动。本工作包没有修改首页、历史、关于、Pager、底部导航或动画。

### 2026-07-22：LaTeX 公式与拟合曲线可视化优化（进行中）

- 用户确认项目已集成 `jlatexmath-android`，要求函数选择器不再显示纯文本近似公式，而是直接渲染
  真正的 LaTeX；同时要求继续优化既有 `ui/components/charts` 拟合曲线组件。
- 已完整阅读项目 README、当前工作区状态、`LatexView`、`ScientificPickerSheet`、标准曲线创建页以及
  `CurveChart` 两个重载。确认库依赖和 Compose 封装都已存在，当前缺口是选择器仍用普通 `Text`
  渲染公式；图表还存在固定白底/黑轴、硬编码像素边距、数据区额外缩小、标准点部分入口不绘制，
  以及提示坐标按固定 250dp 宽度反算等问题。
- 本工作包采用科研仪器式精简视觉方向，保留原有点击、拖动、散点吸附和光谱/验证图能力；首页、
  历史、关于、Pager、底部导航和动画继续冻结，不在本次修改范围内。
- `LatexView` 已扩展字号、颜色和起始/居中/结束对齐参数，并给原生公式 View 补充无障碍描述；
  `ScientificPickerOption` 新增公式字段，标准曲线函数面板现直接渲染枚举中的 `latexFormula`。
- 已删除上一轮仅用于纯文本近似公式的中英文字串资源；函数名称继续本地化，自动推荐摘要继续使用
  普通文本，专业函数才使用数学排版，避免所有选择器被错误解释为公式。
- `CurveChart` 已改为 Material 3 主题自适应背景、边框、坐标轴、网格、曲线与散点；数据区不再
  额外缩小 10%，拟合曲线加粗，标定点增加描边和图例，Bland–Altman 上下限标签完成资源化。
- 标准曲线预览不再固定使用 0..100 横轴，而是围绕真实标定点增加合理留白；只有没有标定点时
  才使用函数安全默认域。历史仅写入 `standardPoints` 的模型也会补成可见、可点击的标定散点。
- 绘制、点击命中和十字线提示现在共享同一套 dp 绘图区几何，并用真实 Canvas 尺寸反算数值，
  修复旧实现按固定 250dp 宽度估算导致提示坐标偏移的问题；吸附阈值也改为密度无关的 24dp。
- 首轮 `:app:compileDebugKotlin` 发现两处当前 Compose 版本不支持的混合 `padding` 重载，修正后
  重新编译通过；jlatexmath 左/中/右对齐常量和新增公式参数均已通过真实编译验证。
- 完整 `:app:testDebugUnitTest` 最终 `216/216` 通过，无失败、无错误、无跳过；`:app:assembleDebug`
  同步通过，最新 APK 已覆盖安装到 `emulator-5554`。
- 在真实应用内从“设置 → 曲线模型库 → 新建标准曲线”打开函数面板，逐段检查线性、多项式、4PL、
  Gaussian、5PL 等公式。分式、指数和上下标均由 jlatexmath 正确排版，没有控制符源码泄漏、横向
  裁切或行高冲突；截图为 `calibration-function-picker-latex-final.png`、
  `calibration-function-picker-4pl-5pl-latex-final.png`、`calibration-function-picker-5pl-latex-final.png`。
- 使用 `(0, 10)`、`(1, 30)` 两组标定点执行自动拟合，正确选中线性函数并显示 `R²=1.0000`。
  新图表把横轴自动收敛到约 `-0.1..1.1`，没有继续固定铺满 `0..100`；两枚标定点、拟合线、图例、
  纵横轴标题和 LaTeX 参数公式均完整可见，截图为 `calibration-curve-chart-first.png`。
- 视觉自审结论：当前公式层级清楚，4PL/5PL 可直接理解；曲线数据区占比、网格对比度、标定点描边
  和拟合线粗细符合科研仪器式方向。ADB 复验期间 AndroidRuntime 无崩溃，`git diff --check` 无空白
  错误。本工作包仍未修改首页、历史、关于、Pager、底部导航或动画。

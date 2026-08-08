# 多分析物统一阵列工作流实施日志

> 开始日期：2026-07-23
> 实施分支：`codex/ux-rollback-implementation`
> 实施前检查点：`a8a7f07`
> 记录规则：只记录已经落地或已经验证的内容；每轮前端变更必须完成模拟器截图复验。

## 本轮目标

1. 新建项目支持多个分析物，并为每个分析物独立选择浓度单位。
2. 96 孔板和微流控阵列都能在布局页看到项目选择的全部分析物。
3. 微流控流程拆分为“定位确认、孔位布局、定量分析、最终结果”，删除检测完成中转卡。
4. 孔位布局支持模板预填和手动分配样本、标准品、空白、对照、参考及禁用位点。
5. 每个分析物可以独立使用标准曲线、深度学习模型或仅信号模式。
6. 历史记录能够读取新阵列运行，最终结果页返回首页，并以逐分析物浓度热力图为主视图。
7. 普通结果页删除帧级“高质量风险”恐吓提示，只保留几何摘要、具体位点问题和后台科研证据。

## 明确不修改

- 首页、历史列表、关于页的视觉设计。
- `HomeScreen` 的 Pager、底部导航和切换动画。
- 现有真实 PG-Grid 回归图片和算法金标准资产。

## 已完成

- 已核对当前 Git 基线 `a8a7f07`，工作区仅包含既有未跟踪的个人文件和临时目录。
- 已确认数据层的 `ProjectAnalyteJoin`、`TemplateAnalyteConfig`、`TemplateSiteAssignment` 与 `SiteMeasurement` 已具备多分析物和逐分析物单位基础。
- 已确认直接新建页、创建协调器、旧孔位模板适配、微流控一次性执行和历史运行查询是本轮连锁问题的主要来源。
- [x] 直接新建支持多分析物，并为每个分析物独立选择和冻结浓度单位。
- [x] 创建时原子保存全部 `ProjectAnalyteJoin`，不再把整板错误绑定到第一个分析物。
- [x] 微流控流程拆分为定位确认、孔位布局、定量保存和最终结果，不再显示完成中转卡。
- [x] 布局支持样本、标准品、空白、阴/阳性对照、参考位、禁用、单点清除、全部清除和批量填充。
- [x] 每个分析物分别执行现场标准曲线；至少两个浓度水平时自动拟合，标准不足时仅保留信号。
- [x] 历史记录优先读取 `detection_runs`，旧孔板项目继续回退 `well_results`。
- [x] 最终结果默认逐分析物显示浓度/信号热力图，曲线分析物显示运行冻结标准点、LaTeX 公式和拟合曲线。
- [x] 多分析物混合运行新增“部分定量”状态，避免把已生成浓度的运行误写为“仅信号”。
- [x] 整帧几何 QC 只作为复核提示，不再把全部真实观测位点一票否决；模型补位仍标记为不可靠。
- [x] 大阵列默认允许页面纵向滚动，用户显式点击缩放按钮后才捕获缩放和平移手势。
- [x] 新建项目为每个分析物增加最大浓度，最大浓度、浓度单位分别冻结到项目关联和模板快照。
- [x] 定位页删除“记录了 N 项保守的图像质量提示”，不再占用确认定位首屏空间。
- [x] 真实芯片预览改为从透视矫正图按 PG-Grid 定位中心裁切 10×10/15×15 单元，使用 A1～O15 与虚拟布局逐孔对应。
- [x] 15×15 及以下阵列完整适配手机宽度，任一维度达到 16 才启用浏览/涂抹模式切换。
- [x] 虚拟布局增加连续画笔，一次手势批量提交经过的所有孔位；清除模式复用相同批量路径。
- [x] 分析物与位点角色改为彩色卡片和矢量图标，并让工具条露出下一项作为横向滚动提示。
- [x] 删除结果质控页的整帧失败 Banner 和帧级问题列表；`frameQcJson`、处理证据与导出数据仍完整保留。
- [x] 将 Python `pg_unit_export.py` 的极性感知紧致裁切移植为 Android `OpenCvArrayUnitSegmenter`，包括 0.45×pitch 窗口、Otsu、开运算、连通域、边界拒绝、中位尺寸/中心偏移校验和中位几何兜底。
- [x] 新增通用阵列单元契约，以载体 `siteShape` 区分圆形、方形、点和自定义区域；禁止按 96/100/225 等数量猜测物理形状。
- [x] 微流控布局预览与比色/荧光 `PG-Quant v2` 共用同一组紧致边界和前景掩膜；背景环继续独立估计，不把收紧裁切错误等同于取消背景扣除。
- [x] 旧 96 孔板 YOLO＋霍夫圆识别链保持不变，圆形检测框适配到通用裁切器；像素提取支持形状/自定义掩膜，孔位裁切改为 PNG 无损保存。
- [x] 第 5 张处理证据由固定圆形 ROI 改为“真实单元边界 + 背景内外环”，用户可直接核对每个方块是否被正确包围。
- [x] 修复孔位布局多笔绘制覆盖：UI 每笔只提交位点集合与当前工具意图，ViewModel 在 `GridLayoutDraftStore` 的最新完整草稿上原子合并，不再让旧 Compose Map 覆盖前一笔。
- [x] 新增纯函数画笔合并契约：空孔位增量写入、相同分配幂等、已有分析物/角色禁止静默覆盖、清除后才允许重新分配。
- [x] 快速画笔增加相邻触点线段插值，Android 合并触摸事件时仍会补齐横划、竖划和跨行路径经过的孔位。
- [x] 最大浓度与浓度单位统一为外置标签、等宽两列和 64dp 控件本体，消除浮动标签与选择器混排造成的顶部/高度错位。
- [x] 一级孔位角色精简为样本、标准品、空白、清除、更多；阴性对照、阳性对照、参考位和禁用位保留在“更多角色”菜单。
- [x] 孔位布局页底部新增“使用实验模板 / 手动配置”两个来源入口；模板负责预填完整布局和逐分析物定量方案，标准曲线与深度学习模型保持为分析物级工具，不与模板二选一。
- [x] 手动配置为每个分析物分别提供现场标准品自动拟合、已有标准曲线、深度学习模型和仅查看信号；选择状态由 ViewModel 持有，页面重组、门控返回和模板切换不会丢失。
- [x] 现场拟合支持候选信号与线性、4PL、5PL 后台比较，展示标准点、曲线、LaTeX 公式、R²、RMSE、MAE 和接受率；拟合结果可保存到标准曲线库。
- [x] 当前完整孔位布局、分析物单位和逐分析物定量方式可另存为实验模板；深度学习只保存模型关联，不复制 PTL 文件，现场拟合会先固化为可复用标准曲线。
- [x] 微流控深度学习定量已经接入真实 PyTorch Lite 执行器：逐孔使用透视矫正图和紧致分割区域裁切，校验 SHA-256，按冻结输入契约推理；任一模型级失败会整分析物原子回退仅信号。
- [x] 内置 `improved_concentration_model_lite.ptl` 作为用户主动选择的“共享浓度模型”；普通界面只显示本地化名称、版本和主信号，不暴露文件名、校验和和输入尺寸。
- [x] 最终阵列结果页标签统一为“结果、曲线/统计、原图、过程、质控”；结果页默认逐分析物浓度热力图，曲线/统计页展示冻结曲线、拟合指标、重复孔 CV、回收率和样本浓度表。
- [x] “实验方案与定量方式”改为单分析物配置工作台：分析物下拉、逐项状态、完成数和线性进度条取代全部分析物同时展开。
- [x] 四种定量方式缩短为“现场拟合 / 标准曲线 / 深度学习 / 仅信号”，使用 2×2 等宽选择器；详细说明只在信息弹窗显示。
- [x] 标准曲线和深度学习资源改为卡内统一下拉字段，主行显示资源名，副行显示版本、主信号和可靠范围。
- [x] 标准品画笔不再要求预先输入统一浓度；标准孔可以先完成布局，现场标定面板再逐孔写入真实浓度。
- [x] 现场标定面板按物理坐标展示标准孔编号、真实紧致裁切图、浓度输入和固定单位，支持最大浓度校验、批量梯度、拟合函数下拉与候选信号高级选择。
- [x] 每个分析物增加显式确认状态；修改相关孔位、浓度、方式或资源后只撤销对应分析物确认，完成后自动切换到下一个未配置分析物。
- [x] 全部分析物完成后才开放“计算结果”和“保存当前方案为实验模板”；ViewModel 同步保留二次业务门控。

## 真实设备复验

- 设备：`emulator-5554`。
- 图片：Android Download 中用户实拍 15×15 芯片 `2.jpg`。
- 分析物：CA125、CEA，分别冻结独立单位。
- 布局恢复复现：先将 `225/225` 位点分配给 CA125 样本，不设置参考位并让 CEA 无位点；门控正确提示后点击“返回孔位布局修改”，直接回到布局页且仍为 `225/225`，未再清空。
- 最终定量复验：CA125 配置两个标准浓度 `0`、`10` 和样本位，CA125/CEA 分别配置空白位，CEA 配置样本位，其余位点批量填充为 CA125 样本。
- 运行结果：`225` 个物理位点、`225` 个测量、`218` 个质量通过位点；CA125 生成两点线性现场曲线，CEA 保持仅信号，运行状态显示“部分定量”。
- 曲线复验：结果页显示公式 `y = 2.044x + 1.605`、两个真实标定点和拟合直线；热力图默认可向下滚动到曲线区域。
- 截图（均位于未提交的 `tmp/`）：`grid-layout-recovery-225.png`、`grid-final-partial-quantified.png`、`grid-curve-scroll-fixed.png`。
- 追加 UI 复验项目：`Real15UI`、`Real15UI2`，均从系统 Files 的 Downloads 选择用户实拍 `2.jpg`，定位规格为 15×15。
- 最大浓度复验：CA125、CEA 均显示最大浓度 `100`，单位 `g/ml` 不再截断为 `g/...`。
- 真实裁切复验：225 个缩略图一次性完整显示，A1～O15 与虚拟板严格一致，未使用合成占位图。
- 画笔复验：从 A1 连续拖到 A15，一次手势后计数从 `0/225` 更新为 `15/225`，没有逐孔点击。
- 质控复验：最终结果首屏和质控页均不再显示“整帧图像存在较高质量风险”；质控页仅显示几何摘要和具体位点级问题。
- 本轮截图（均位于未提交的 `tmp/ui-review/`）：`max-fields-fixed.png`、`grid-layout-top.png`、`grid-virtual-layout-fixed.png`、`grid-brush-painted-fixed.png`、`grid-qc-tab.png`。
- 紧致裁切 UI 复验：新建 `CropReview15`，从 Android Files 的 Downloads 选择用户实拍 `1.jpg`；定位后布局页 225 个缩略图均只显示独立蓝色方块本体，A1～O15 编号与虚拟布局保持一致。
- 处理证据复验：`05_roi_background.png` 中绿色紧致框逐一贴合 225 个方块，橙色背景内/外环位于单元之外；截图为 `grid-layout-tight-crops.png`、`grid-unit-mask-evidence.png`。
- 6 张实拍裁切统计：10×10 两张分别为 `94/100`、`86/100` 真实分割；15×15 四张分别为 `225/225`、`225/225`、`225/225`、`197/225` 真实分割，其余全部由中位几何盒补齐，输出数量始终完整。
- 多笔画笔复验：新建 `BrushReview2`，第一笔 A1～A5 后为 `5/225`，第二笔 B1～B5 后稳定累计为 `10/225`，A/B 两行同时保留。
- 冲突保护复验：切换到 CEA 后划过 CA125 的 A1～A3，计数仍为 `10/225`，已有蓝色 CA125 位点没有变成绿色 CEA。
- 清除重分配复验：使用清除画笔删除 A1 后计数降为 `9/225`；切回样本画笔后，CEA 可重新分配 A1，计数恢复 `10/225`，A1 为绿色、其余九格保持蓝色。
- 角色分层复验：一级工具只显示样本、标准品、空白、清除孔位和更多角色；展开更多后显示阴性对照、阳性对照、参考位、禁用。
- 本轮新增截图：`analyte-fields-aligned.png`、`grid-role-tools-simplified.png`、`grid-brush-multi-stroke.png`、`grid-brush-conflict-protected.png`、`grid-brush-clear-reassign.png`、`grid-role-more-menu.png`。
- 最新流程复验项目：`WorkflowReview`，使用 Android Files 的 Downloads 中第一张用户实拍 15×15 芯片图；定位结果为 `225/225`、实拍支撑率 `100%`、平均置信度 `100%`。
- 配置来源复验：布局页底部同屏显示“使用实验模板 / 手动配置”，两张来源卡信息层级完整；CA125、CEA 均分别显示现场拟合、已有曲线、深度学习、仅信号四种方式。
- 深度学习选择器复验：CA125 主动切换深度学习后，选择器只显示“共享浓度模型 · v1 · 净荧光强度”，选择成功后卡片同步显示冻结摘要，不出现文件名、SHA-256 或输入宽高。
- 最新截图：`workflow-after-create.png`、`workflow-quant-source-cards.png`、`workflow-quant-section2.png`、`workflow-template-dialog.png`、`workflow-deep-learning-dialog.png`、`workflow-deep-learning-selected2.png`。
- 单分析物工作台实拍复验：新建 `QuantUIReview`/`QuantUIReviewFinal`，继续使用 Downloads 的 `2.jpg` 和 CA125、CEA；定位后工作台一次只展开 CA125，通过下拉切换分析物，四种方式在 2×2 网格内无截断。
- 现场标定实拍复验：先把 A1～A3 标记为标准品，再打开标定面板；三行均显示物理坐标、真实紧致裁切图、浓度输入和项目单位，拟合函数为下拉字段，空浓度不会在画笔阶段被阻止。
- 截图自审先后发现并修复三处问题：删除工作台外层重复标题；删除每行右侧内部全局索引；把最大浓度从 `100.0000` 改为 `100`，并将浓度单位从会隐藏的 `suffix` 改为始终可见的独立固定列。
- 本轮新增截图：`quant-section-first.png`、`gridf2.png`、`onsite-dialog-first.png`、`onsite-dialog-final.png`。最终固定单位由 Compose 设备测试逐行断言，避免 Material 3 空值状态再次隐藏单位。

## 验证状态

- [x] `:app:compileDebugKotlin`。
- [x] `:app:assembleDebug`。
- [x] `GridDetectionCoordinatorTest`。
- [x] `GridLayoutDraftStoreTest`。
- [x] 实拍 15×15 端到端流程和截图自审。
- [x] 完整 JVM 回归（`:app:testDebugUnitTest`）。
- [x] Debug APK 与 AndroidTest APK 重新构建（`:app:assembleDebug`、`:app:assembleDebugAndroidTest`）。
- [x] Compose 设备专项回归：`GridDetectionScreenTest`、`ArrayHeatmapTest`，共 `5/5` 通过。
- [x] 本轮最终设备专项回归：`GridDetectionScreenTest`、`ArrayResultDetailTest`、`ArrayHeatmapTest`，共 `11/11` 通过。
- [x] Room/Repository 设备专项回归：`ArrayResultRepositoryTest`、`GridDetectionDatabaseTest`，共 `7/7` 通过。
- [x] 模拟器测试后恢复原数据库，使用 `user / 123456` 登录成功，并将应用语言恢复为中文。
- [x] 最终 `git diff --check` 与工作区审计：无空白错误；首页、历史列表、关于页及 Pager/底栏文件未修改；XML 仅变更中英文字符串资源，资源 ID 完全一致；未引入原生 Toast 或用户可见硬编码文本。
- [x] 通用裁切纯 JVM 契约测试 `ArrayUnitSegmentationContractsTest`。
- [x] OpenCV 合成裁切测试 `OpenCvArrayUnitSegmenterTest`：暗方块零背景、弱对比中位兜底、亮圆孔形状掩膜，共 `3/3` 通过。
- [x] 实拍裁切回归 `PgUnitRealPhotoRegressionTest`：6 张 10×10/15×15 图片完整输出并达到分割率下限。
- [x] 科学采样回归 `PgQuantUnitSegmentationTest`：证明小方块信号由紧致前景读取，不再被固定圆形 ROI 的背景淹没。
- [x] 形状分流回归 `PixelExtractionShapeTest`：圆形孔板排除外接框四角，方形芯片保留完整紧致框。
- [x] `GridDetectionCoordinatorIntegrationTest` 共 `5/5` 通过，九步证据、持久化和现场定量链未因 PG-Quant v2 破坏。
- [x] `GridLayoutAssignmentMergeTest` 覆盖两笔/三笔累积、分析物切换、冲突保护、清除重分配、幂等和快速拖动补点，共 `9/9` 通过。
- [x] `GridDetectionScreenTest` 设备专项最终 `6/6` 通过，新增验证单分析物工作台、模板回传，以及现场标定三个空输入行仍显示三个固定浓度单位。
- [x] `emulator-5554` 实际完成两笔累积、跨分析物冲突保护、清除后重分配及字段像素边界核对；最大浓度/单位控件顶部同为 `811px`，底部分别为 `979/980px`。
- [x] 修正深度学习批次单测对最新 `primaryFeatureValue` 契约的旧参数调用，完整 `:app:testDebugUnitTest` 再次通过。
- [x] 最新 Debug APK 与 AndroidTest APK 构建通过：`:app:assembleDebug`、`:app:assembleDebugAndroidTest`。
- [x] `GridDetectionScreenTest` 扩展为 `5/5` 设备测试，新增验证模板/手动入口、四种定量方式、保存模板入口和模板选择回传。
- [x] `WorkflowReview` 使用用户实拍 15×15 图片完成定位与配置区截图自审；来源卡、两分析物定量卡、空模板提示、共享模型选择弹窗均无重叠、截断或底部遮挡。
- [x] 新增 `GridQuantitationWorkflowTest`，覆盖仅信号/资源/现场拟合完成门控，以及标准浓度非负、有限值和最大浓度边界。
- [x] 单分析物工作台完整 `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过，并在 `emulator-5554` 完成两轮实拍截图自审。

## 下一阶段（本轮未冒充完成）

- 自动识别 10×10/15×15 网格规格以及 C++/JNI parity 迁移仍是独立算法工作包，不属于本轮交互链修复。

## 2026-07-24 定量架构收口实施记录

- [x] 在现有安全检查点 `9fbaac7` 之后继续实现，没有回滚或覆盖用户已有改动。
- [x] Room 升级到 13，新增模板定量冻结绑定；模板可保存现场曲线完整摘要，不依赖曲线资源后续是否仍存在。
- [x] 新增独立“曲线拟合设置”，支持稳健推荐、R²优先、简单模型优先、候选函数、浓度水平门槛、权重和低质量处理策略。
- [x] 微流控现场拟合与标准曲线库在拟合启动时冻结策略快照，最终计算直接使用用户选择的运行曲线快照，不再重新拟合。
- [x] 标准曲线资源增加科学内容指纹，重复保存同一候选时复用资源，避免页面重建或重复点击生成内容相同的曲线。
- [x] 标准曲线库已接入统一推荐引擎，普通结果每个函数只保留最佳“信号+权重”组合。
- [x] 96孔板现场拟合已接入同一推荐引擎，并保留旧 `PixelType` 与专家函数兼容能力；普通自动候选最多展示线性、4PL、5PL三项。
- [x] 接通低质量曲线的允许、二次确认、仅查看三种应用行为。
- [x] 补齐模板冻结、资源指纹和 Room 12→13 迁移回归；固定业务闭环的 JVM/Compose 分层测试已完成。
- [x] 完成版本化信号特征目录与 V2 处理器修正：Legacy 键不变，新键修正 Hue、Lab、YCbCr 和低分母比率，并冻结到新建 96 孔板曲线。
- [x] 完成 JVM 全量测试、Debug/AndroidTest 构建、设备迁移与像素集成测试，并完成曲线拟合设置页截图自审；实拍 15×15 定位/裁切闭环沿用本日志前述固定回归结果。
- [x] 统一推荐器不再使用不同量纲的原始 MAE 做跨信号并列裁决；原始 RMSE/MAE 只在单个候选详情中展示。
- [x] 新增 `ReusableQuantitationWorkflowDatabaseTest` 固定设备闭环，在真实 Room 中验证“保存现场曲线 → 保存模板 → 新项目应用 → 冻结参数定量 → DetectionRun 保存 → 历史重开”，API 35 模拟器 `1/1` 通过。
- [x] 最终回归：`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 全部通过；迁移、V2 像素、定量界面和固定资源闭环设备专项合计 `12/12` 通过。

### 版本化信号特征与设备验证

- 新增静态 `SignalFeatureCatalog`，将 31 个旧 `PixelType` 分为推荐、扩展、兼容和实验等级，明确值域、聚合方式、空白扣除规则和处理器版本。
- `PixelExtractionUtils` 现在在同一孔位 JSON 中同时保存 Legacy 键与 `pixel.v2.*` 键；旧曲线读取旧键，新曲线只读取冻结 V2 编码，V2 无效时禁止静默回退。
- V2 Hue 使用低饱和度过滤和圆周均值；CIE Lab 使用 sRGB/D65 标准显示范围；YCbCr 明确输出 Y/Cb/Cr；通道比率增加分母噪声下限和最大稳定比率。
- Room 13 的 `curve_models` 新增可空 `signalFeatureCode`、`processorVersion`，旧记录迁移后保持空值并继续使用 Legacy 语义。
- 旧 96 孔板新拟合、模板应用、样本反算和曲线保存均读取版本化编码；曲线库加载改为直接恢复冻结参数，不再重新拟合。
- JVM 全量 `:app:testDebugUnitTest` 通过；设备 `AppDatabaseMigrationTest` 与 `PixelExtractionShapeTest` 共 5 项通过。
- 模拟器截图：`tmp/ui-review/calibration-settings-v2.png`、`tmp/ui-review/calibration-settings-signals.png`。英文长文案下无截断、重叠和底部遮挡，设备最终恢复中文与 `user` 登录状态。

### 实拍现场标定与共享 PTL 设备闭环

- [x] 新增 `RealPhotoOnsiteQuantitationWorkflowTest`，直接使用 `real_15x15_01.jpg` 串联生产定位、225 孔紧致裁切、真实净荧光信号、现场线性拟合、曲线发布、模板保存、新项目应用、冻结曲线定量、运行保存和历史重开。
- [x] 实拍现场曲线使用覆盖真实动态范围的 6 个标准孔，线性候选 `R² > 0.999999`；历史恢复 225 条测量，至少 220 个位点形成浓度，标准孔反算值与录入浓度一致。
- [x] 新增 `AndroidGridDeepLearningExecutorDeviceTest`，对同一张实拍 15×15 图执行 225 次真实 `improved_concentration_model_lite.ptl` 前向推理，并校验 128×128 RGB/ImageNet 输入、模型 SHA-256、百分比输出语义和每孔冻结模型快照。
- [x] 225 孔模型测试在 API 35 模拟器 `1/1` 通过，三次复验耗时约 `37.225～38.888 s`；输出键完整覆盖 `0..224`，没有部分失败、非有限浓度或模型快照漂移，批次映射满足原子契约。
- [x] 曾验证应用层 16 孔 NCHW 批量推理方案，实测 `38.507 s`，未优于原逐孔执行器；为避免无收益复杂度已完整撤回。后续性能优化应改从模型重新导出动态 batch、模型量化/NNAPI 或更轻量网络入手，并单独建立真机性能门槛。
- [x] 实拍定位、裁切、现场标定和 PTL 组合设备回归 `4/4` 通过。
- [x] 旧功能重点设备回归 `6/6` 通过：共享模型单次真实推理、96 孔板圆孔信号提取、旧 96 位布局 UI，以及复用 `pdf_cover_page.xml` 的阵列 PDF 导出均正常。
- [x] 完整 `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 再次通过。本轮只新增仪表测试和文档，没有新增生产 UI 变更，因此无需重复生成界面截图。

### 现场曲线保存后的冻结关系修复

- [x] 复现并定位阻断文案“冻结快照中的分析物、模板配置、分析模型或类型专用定义关系不一致”：现场曲线保存为资源后获得新模型 ID，但旧实现只替换 `analysisModel`，遗漏 `templateConfig.analysisModelId`，使预检同时看到旧配置 ID 和新模型 ID。
- [x] 新增 `withPersistedOnsiteCurveResource()` 统一领域入口，一次校验分析物、模型类型、曲线定义、标定点和定量方法，并原子同步模板配置模型 ID、单位、可靠范围、模型数据包及运行快照来源 ID。
- [x] `GridDetectionViewModel.applyOnsiteCalibration()` 改为调用统一同步入口；保存资源仍保持“现场拟合”语义，不会暗中切换为“已有标准曲线”。
- [x] `GridDetectionCoordinator.applyOnsiteCalibrationSelection()` 增加防御性同步，使任何调用入口冻结现场曲线时都明确写回实际模型 ID。
- [x] 新增 JVM 回归“保存现场曲线资源后模型ID完整同步且预检继续通过”，`GridDetectionCoordinatorTest` 共 `20/20` 通过；实拍设备闭环删除测试专用手工补 ID，改为使用生产同步入口并断言三处 ID 完全一致。
- [x] API 35 模拟器上的 `RealPhotoOnsiteQuantitationWorkflowTest` 再次 `1/1` 通过；随后全量 `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 均成功，并已执行 `:app:installDebug` 安装到 `Medium_Phone_API_35`。
- [x] 静态审计通过：中英文字符串 ID 一致、没有新增原生 Toast、`git diff --check` 无错误。本次没有生产 UI 变更，因此不新增截图；首页、历史、关于未修改。

# FluoColorQuant - 比色-荧光-光谱高通量传感检测软件

## 项目概述
FluoColorQuant 是一款专业的比色-荧光-光谱高通量传感检测软件,结合了专业的光学检测器件和智能手机的高分辨率摄像头、高性能处理能力以及丰富的计算资源,为生化材料的浓度定量测量提供高效、便捷的解决方案。本软件基于 Android 平台开发,使用 Jetpack Compose 构建现代化 UI,采用 MVVM 架构设计,支持**荧光检测模式(Fluorescence Mode)**、**比色检测模式(Colorimetric Mode)**和**光谱检测模式(Spectrum Mode)**三种检测方式。

软件支持对标准 96 孔板(12 列 × 8 行)进行自动检测和分析,通过深度学习模型实现高精度的孔位识别和浓度预测,同时提供手动调整功能,确保在复杂光照和成像条件下也能获得准确结果。光谱检测模式支持多通道光谱采集、自动/手动标定和智能寻峰分析。

## 最新开发进展

### 光谱检测模式完整实现
- **多通道光谱采集**:支持1-10个光谱通道的同时检测,自动识别多条光谱带
- **智能标定系统**:
  - 自动标定:基于参考光源的峰值检测自动完成波长标定
  - 手动标定:支持添加、拖动、删除标定点,实时计算拟合系数
  - 一键复制:将通道0的标定点自动复制到其他通道并调整偏移
- **线性拟合优化**:优先使用线性拟合(≤3个标定点),避免二次拟合的边缘异常
- **数据处理引擎**:
  - 波长范围裁剪:严格应用全局设置的最小/最大波长
  - 移动平均平滑:可配置平滑等级(0-5),窗口大小 = level×2+1
  - 智能寻峰:根据灵敏度(High/Medium/Low)自动调整阈值
- **分页结果展示**:
  - HorizontalPager 支持左右滑动查看多通道结果
  - 每页显示:分析物名称、光谱曲线、峰值信息、波长范围
  - 圆点指示器和通道页码显示
- **结果导出功能**:
  - CSV导出:支持导出单通道或全部通道的光谱原始数据(波长、强度)
  - PNG导出:导出各通道光谱曲线图像,包含峰值标注
  - PDF报告:生成包含项目信息、光谱图表和峰值分析的专业PDF报告
- **历史记录集成**:光谱项目自动导航到专用结果页面
- **完整国际化**:所有界面文本支持中英文切换

### 2026-03 光谱结果与标定体验优化
- **结果页快速调参**:结果页新增可折叠快速调参面板,可直接调整平滑等级与寻峰灵敏度,并基于缓存结果即时重算
- **多峰与峰值标注**:结果页支持多峰检测,曲线图新增峰值竖向标注线,峰值卡片可同时展示主峰与附加峰
- **多通道叠加对比**:新增叠加对比视图,可在单张图中查看多通道光谱曲线
- **空状态与导出反馈**:空状态页面补充返回标定引导; 导出期间显示进度对话框
- **标定进度总览**:手动标定底部新增整体进度条与通道完成状态指示,便于快速掌握剩余通道
- **设置页视觉统一**:光谱设置中的平滑度与灵敏度调整改为卡片化展示,与其它设置区域风格保持一致
- **内存与代码质量优化**:结果页错误提示改为资源化文案; 光谱结果加入内存缓存; 强度提取改为逐行读取像素以降低大图场景内存峰值

### PDF报告导出功能优化
- **多章节结构化报告**：实现了包含封面、总览页、分析物章节和附录的完整PDF报告结构
- **光谱总览页增强**：光谱完整报告的 Results Summary 页面新增多通道汇总光谱图，方便在摘要页先查看整体谱线分布
- **专业数据可视化**：优化图表展示，确保坐标轴和标签完整显示，增强数据可读性
- **统一页眉页脚设计**：标准化的页眉页脚设计，提升报告的专业性和一致性
- **多页附录支持**：支持原始数据的多页展示，根据数据量自动分页
- **图像优化显示**：在表格中展示实验孔位的实际图像，保持原始比例，避免畸变
- **验证数据优化**：分离展示回归分析和Bland-Altman分析，提高数据可读性

### 交互式曲线图表组件
- **实时数据可视化**：新增高级曲线图表组件，支持多种拟合函数展示
- **交互式探索**：用户可通过点击和拖动操作查看曲线上任意点的精确数值
- **散点和曲线集成**：同时展示实验数据点和拟合曲线，直观对比分析
- **自适应坐标轴**：智能调整坐标轴范围，确保所有数据点清晰可见
- **十字线定位**：精确显示所选点的浓度和像素值，支持数据精确读取

### 曲线拟合引擎增强
- **多函数拟合支持**：增加多种数学模型支持，包括线性、多项式、指数、对数、幂函数等
- **优化算法**：实现Levenberg-Marquardt优化算法，提高拟合精度
- **自动函数选择**：能够自动选择最佳拟合函数，根据R²等指标评估拟合质量
- **参数可视化**：以LaTeX格式展示函数表达式，便于科学记录和理解
- **拟合指标计算**：提供R²、RMSE、MSE等多种统计指标评估拟合质量

### 代码结构树状图

```
java
└── com
    └── muc
        └── fluocolorquant
            ├── data
            │   ├── converters
            │   │   └── Converters.kt
            │   ├── dao
            │   │   ├── AnalyteDao.kt
            │   │   ├── CurveModelDao.kt
            │   │   ├── DetectionRunDao.kt
            │   │   ├── ExperimentTemplateDao.kt
            │   │   ├── ProjectAnalyteJoinDao.kt
            │   │   ├── ProjectDao.kt
            │   │   ├── ReagentDao.kt
            │   │   ├── SpectrumDao.kt
            │   │   ├── UserDao.kt
            │   │   └── WellResultDao.kt
            │   ├── enums
            │   │   ├── FittingFunctions.kt
            │   │   ├── PixelTypes.kt
            │   │   ├── SpectrumCalibrationType.kt
            │   │   ├── SpectrumLightSource.kt
            │   │   └── WellRoleType.kt
            │   ├── model
            │   │   ├── Analyte.kt
            │   │   ├── AnalyteResultDetails.kt
            │   │   ├── AnalyteWellLayout.kt
            │   │   ├── CurveModel.kt
            │   │   ├── DetectionRun.kt
            │   │   ├── ExperimentTemplate.kt
            │   │   ├── Project.kt
            │   │   ├── ProjectAnalyteJoin.kt
            │   │   ├── Reagent.kt
            │   │   ├── SpectrumCalibration.kt
            │   │   ├── SpectrumExportData.kt
            │   │   ├── SpectrumResult.kt
            │   │   ├── User.kt
            │   │   └── WellResult.kt
            │   ├── repository
            │   │   ├── AnalyteRepository.kt
            │   │   ├── CurveModelRepository.kt
            │   │   ├── CurveModelRepositoryImpl.kt
            │   │   ├── DetectionRunRepository.kt
            │   │   ├── ExperimentTemplateRepository.kt
            │   │   ├── ExperimentTemplateRepositoryImpl.kt
            │   │   ├── ProjectAnalyteJoinRepository.kt
            │   │   ├── ProjectRepository.kt
            │   │   ├── ProjectRepositoryImpl.kt
            │   │   ├── ReagentRepository.kt
            │   │   ├── SettingsRepository.kt
            │   │   ├── SpectrumRepository.kt
            │   │   ├── SpectrumRepositoryImpl.kt
            │   │   ├── UserRepository.kt
            │   │   └── WellResultRepository.kt
            │   ├── AppDatabase.kt
            │   └── SessionManager.kt
            ├── di
            │   ├── DatabaseModule.kt
            │   └── RepositoryModule.kt
            ├── ui
            │   ├── components
            │   │   ├── AnimatedButtons.kt
            │   │   ├── Buttons.kt
            │   │   ├── CustomToast.kt
            │   │   ├── FlowRow.kt
            │   │   ├── InteractivePlateGrid.kt
            │   │   ├── LatexView.kt
            │   │   ├── ManualFittingDialog.kt
            │   │   ├── PrimaryButton.kt
            │   │   ├── TextFields.kt
            │   │   ├── WellLayoutComponents.kt
            │   │   ├── charts
            │   │   │   ├── ChartData.kt
            │   │   │   └── CurveChart.kt
            │   │   └── tables
            │   │       └── MetricsTable.kt
            │   ├── navigation
            │   │   ├── AppNavigation.kt
            │   │   └── Screen.kt
            │   ├── screens
            │   │   ├── auth
            │   │   │   ├── LoginScreen.kt
            │   │   │   └── RegisterScreen.kt
            │   │   ├── curvefitting
            │   │   │   ├── CurveFittingResultScreen.kt
            │   │   │   └── CurveFittingScreen.kt
            │   │   ├── detection
            │   │   │   └── WellDetectionScreen.kt
            │   │   ├── history
            │   │   │   └── HistoryScreen.kt
            │   │   ├── home
            │   │   │   └── HomeScreen.kt
            │   │   ├── image
            │   │   │   └── ImageCorrectionScreen.kt
            │   │   ├── imagecrop
            │   │   │   └── ImageCropScreen.kt
            │   │   ├── profile
            │   │   │   └── ProfileScreen.kt
            │   │   ├── project
            │   │   │   ├── AnalyteConfigItem.kt
            │   │   │   ├── AnalyteSelectionDialog.kt
            │   │   │   └── NewProjectScreen.kt
            │   │   ├── result
            │   │   │   ├── AnalysisPlanCard.kt
            │   │   │   ├── ExportComponents.kt
            │   │   │   ├── ModifiedCards.kt
            │   │   │   ├── NewResultScreen.kt
            │   │   │   ├── ProjectInfoCard.kt
            │   │   │   ├── ResultsDisplaySection.kt
            │   │   │   └── ValidationCard.kt
            │   │   ├── settings
            │   │   │   ├── AnalyteManagementScreen.kt
            │   │   │   ├── AppSettingsScreen.kt
            │   │   │   ├── CreateExperimentTemplateScreen.kt
            │   │   │   ├── CurveModelManagementScreen.kt
            │   │   │   ├── DetectionSettingsScreen.kt
            │   │   │   ├── ExperimentTemplateManagementScreen.kt
            │   │   │   ├── ManualCurveInputScreen.kt
            │   │   │   ├── ManualDataInputScreen.kt
            │   │   │   ├── ReagentLibraryScreen.kt
            │   │   │   ├── SettingsNavigationItem.kt
            │   │   │   ├── SettingsScreen.kt
            │   │   │   └── SpectrumSettingsScreen.kt
            │   │   ├── spectrum
            │   │   │   ├── SpectrumCalibrationScreen.kt
            │   │   │   ├── SpectrumExportBottomSheet.kt
            │   │   │   └── SpectrumResultScreen.kt
            │   │   └── splash
            │   │       └── SplashScreen.kt
            │   ├── theme
            │   │   ├── Color.kt
            │   │   ├── Theme.kt
            │   │   └── Type.kt
            │   └── viewmodels
            │       ├── AnalyteViewModel.kt
            │       ├── ConcentrationViewModel.kt
            │       ├── CurveFittingViewModel.kt
            │       ├── CurveModelViewModel.kt
            │       ├── DetectionViewModel.kt
            │       ├── ExperimentTemplateViewModel.kt
            │       ├── ExportViewModel.kt
            │       ├── HistoryViewModel.kt
            │       ├── ImageCorrectionViewModel.kt
            │       ├── ProjectViewModel.kt
            │       ├── ReagentViewModel.kt
            │       ├── ResultViewModel.kt
            │       ├── SettingsViewModel.kt
            │       ├── SpectrumCalibrationViewModel.kt
            │       ├── SpectrumResultViewModel.kt
            │       ├── UserViewModel.kt
            │       └── WellLayoutViewModel.kt
            ├── utils
            │   ├── math
            │   │   ├── FittingEngine.kt
            │   │   ├── FittingResult.kt
            │   │   ├── MetricsCalculator.kt
            │   │   ├── SpectrumCVUtils.kt
            │   │   └── WellMappingUtils.kt
            │   ├── AnimationUtils.kt
            │   ├── HeatmapColorUtil.kt
            │   ├── LocaleHelper.kt
            │   └── PixelExtractionUtils.kt
            ├── FluoColorApp.kt
            └── MainActivity.kt
```

## 核心目录功能简介

- **`data`**: 该目录负责应用的数据持久化和数据访问。
  - **`dao`**: 包含数据访问对象 (DAO)，用于与 Room 数据库进行交互。
  - **`model`**: 定义了应用的数据模型或实体类。
  - **`repository`**: 实现了仓库模式，用于抽象数据源。
- **`di`**: 包含依赖注入相关的模块。
  - `DatabaseModule.kt`: 提供数据库实例和 DAO 的注入。
  - `RepositoryModule.kt`: 提供仓库的注入。
- **`ui`**: 存放所有与用户界面相关的代码。
  - **`components`**: 包含通用的 UI 组件。
    - **`charts`**: 图表相关组件，包括曲线图表等数据可视化组件。
  - **`navigation`**: 负责应用的导航逻辑。
  - **`screens`**: 包含应用的所有屏幕或页面。
  - **`theme`**: 定义应用的主题、颜色和字体。
  - **`viewmodels`**: 存放为各个屏幕提供数据的 ViewModel。
- **`utils`**: 包含各种工具类。
  - **`math`**: 包含数学计算和数据拟合相关的工具类。
- `FluoColorApp.kt`: 应用的 Application 类。
- `MainActivity.kt`: 应用的主 Activity。

## 核心功能

### 1. 孔位检测与调整
- **自动检测**：使用 PyTorch Mobile 深度学习模型（model_final.pth）自动识别图像中的孔位
- **增强检测**：支持 Hough 圆检测，提高圆形孔位的识别准确度
- **手动调整**：用户可拖动调整单个孔位或整体孔阵位置和大小
- **单孔精确调整**：提供单个孔位放大预览和精确调整功能，支持拖拽四角精确调整孔位大小和位置

### 2. 浓度预测分析
- **自动分析**：使用深度学习模型（improved_concentration_model.pth）自动预测样品浓度
- **批量处理**：一次性处理所有检测到的孔位并生成浓度数据
- **背景处理**：支持在后台进行浓度计算，显示实时进度
- **数据持久化**：自动保存检测和分析结果到本地数据库

### 3. 结果可视化
- **热力图展示**：将预测浓度以热力图形式直观展示
- **散点图分析**：提供预测值与标准值的对比散点图，包含 R² 等统计指标
- **交互式曲线图**：支持对拟合曲线的交互式探索，可实时查看任意点的数值
- **数据导出**：支持将分析结果导出为标准格式数据
- **批次比较**：允许对不同批次的检测结果进行对比分析

### 4. 项目管理
- **多项目支持**：创建和管理多个检测项目，设置不同的检测参数
- **历史记录**：查看和管理历史检测记录和结果
- **数据同步**：支持本地数据的备份和恢复
- **参数设置**：可配置检测参数，如最大浓度值、检测模式等

### 5. 曲线拟合模型
- **多模型支持**：提供多种数学模型进行数据拟合，如线性、多项式、指数、对数等
- **自动选择**：根据数据特征自动选择最佳拟合函数
- **模型管理**：保存和重用拟合模型，应用于新的数据集
- **质量评估**：通过多种统计指标评估拟合质量
- **参数调优**：支持手动调整参数，优化拟合效果

### 6. 光谱检测分析
- **多通道支持**:同时采集和分析最多10个光谱通道
- **自动光谱识别**:使用OpenCV图像处理自动检测光谱条位置
- **灵活标定方式**:
  - 自动标定:基于参考波长的峰值自动匹配
  - 手动标定:可视化标定点管理,支持拖拽、删除和复制
- **波长-像素映射**:支持线性和二次多项式拟合
- **数据处理**:波长范围裁剪、移动平均平滑、智能寻峰
- **分析物关联**:支持为每个通道绑定分析物进行批量检测
- **结果可视化**:交互式光谱曲线图,峰值自动标注

### 7. 专业报告导出
- **多格式导出**:支持CSV、PNG和PDF三种格式的数据和结果导出
- **结构化PDF报告**:生成包含封面、总览页、分析物章节和附录的专业PDF报告
- **数据可视化**:在报告中包含热力图、浓度趋势图、标准曲线等多种图表
- **验证数据分析**:提供回归分析和Bland-Altman分析,评估预测准确性
- **原始数据附录**:包含完整的原始数据表格,支持多页显示
- **图像集成**:在报告中展示实际孔位图像,便于直观比对
- **统计指标**:提供R²、RMSE等多种统计指标,评估分析质量
- **自动分页**:根据数据量自动调整页数,确保内容完整展示

## 技术实现

### 架构设计
- **前端框架**: Jetpack Compose 构建响应式 UI
- **架构模式**: MVVM 架构，实现界面与业务逻辑分离
- **状态管理**: 使用 StateFlow 和 LiveData 进行状态管理
- **依赖注入**: 通过 Hilt 实现依赖注入，提高代码模块化程度
- **协程处理**: 使用 Kotlin Coroutines 处理异步任务和 IO 操作

### 数据处理
- **数据持久化**: Room 数据库存储检测结果和项目信息
- **图像处理**: 使用 OpenCV 进行图像预处理和特征提取
- **光谱数据处理**: OpenCV实现光谱条检测、强度提取和波长标定
- **机器学习**: 集成 PyTorch Mobile 进行模型推理
- **数学计算**: 使用 Apache Commons Math 实现高级数学计算和曲线拟合
- **结果分析**: 内置统计分析功能,计算浓度和相关指标
- **PDF生成**: 使用Android PDF生成API,创建专业的分析报告

### 用户体验优化
- **多语言支持**: 支持中英文切换，提供完整的国际化支持
- **自适应界面**: 适配不同尺寸和分辨率的设备屏幕
- **交互反馈**: 提供精确的操作反馈和状态提示
- **错误处理**: 完善的异常处理和用户友好的错误提示
- **自定义Toast**: 使用项目内置的自定义Toast组件，提供更好的用户体验

## 页面流程
1. **启动页**: 应用初始化和资源加载
2. **登录/注册**: 用户身份验证和账户管理
3. **主界面**: 项目列表和创建新项目入口
4. **新建项目**: 新建项目所需输入的信息,支持选择荧光/比色/光谱检测模式
5. **图像裁剪**: 裁剪目标区域,优化检测效果
6. **孔位检测**: 自动检测孔位,支持手动调整(荧光/比色模式)
7. **光谱标定**: 多通道光谱标定,自动/手动标定点管理(光谱模式)
8. **孔位布局**: 将裁切好的孔位进行展示,同时标注对应的分析物
9. **检测结果**: 以热力图和折线图的形式展示分析结果(荧光/比色模式)
10. **光谱结果**: 分页展示多通道光谱曲线和寻峰结果(光谱模式)
11. **结果导出**: 将分析结果导出为CSV、PNG或PDF格式
12. **历史记录**: 查看和管理历史检测记录
13. **系统设置**: 设置系统的检测设置等其余的默认信息

## 特色功能

### 孔位大小调整功能
为解决复杂光照和成像条件下孔位检测不准确的问题，软件提供了专业的孔位调整工具：
- **单孔预览窗口**: 放大显示单个孔位，便于精确观察
- **四角拖拽调整**: 通过拖拽四个角点精确调整孔位大小
- **整体移动**: 支持整体移动孔位位置
- **实时更新**: 调整过程中实时显示效果
- **状态保持**: 保存用户的调整，确保连续操作中不丢失之前的修改

### 增强型检测模式
针对不同类型的孔板和实验条件，提供增强型检测功能：
- **Hough 圆检测**: 精确识别圆形孔位边界
- **颜色分析**: 分析孔位中心颜色，用于荧光强度计算
- **噪声过滤**: 智能过滤背景噪声，提高识别准确度
- **批量优化**: 应用统一参数优化所有孔位检测结果

### 交互式数据可视化
为提升数据分析体验，软件提供了先进的数据可视化功能：
- **实时数据探索**: 用户可通过点击和拖动操作查看曲线上任意点的精确数值
- **自动坐标调整**: 根据数据特性自动调整坐标轴范围，确保数据点清晰可见
- **多曲线对比**: 支持在同一图表中显示多条拟合曲线进行对比分析
- **数据点高亮**: 交互时自动高亮显示最近的数据点，方便精确读取数值
- **参数可视化**: 以数学公式形式展示拟合函数及参数，增强科学性

### 专业PDF报告生成
为满足科研和实验记录需求，软件提供了专业的PDF报告生成功能：
- **标准化结构**: 包含封面、总览、分析章节和附录的完整科研报告结构
- **多章节组织**: 按分析物自动组织多个章节，便于查阅和比较
- **图表自适应**: 智能调整图表大小和位置，确保所有元素完整展示
- **数据完整性**: 支持多页附录，确保所有原始数据完整呈现
- **图像集成**: 在报告中展示实际孔位图像，保持原始比例
- **专业页眉页脚**: 统一的页眉页脚设计，包含项目信息和页码

### 光谱检测智能系统
为满足光谱分析需求,软件提供了完整的光谱检测解决方案:
- **自动光谱带识别**: 使用CLAHE对比度增强和形态学操作,精确定位多条光谱带
- **智能标定辅助**: 支持从通道0一键复制标定点到其他通道,自动调整偏移量
- **稳定拟合算法**: 自动选择线性/二次拟合,避免边缘异常和曲线翘曲
- **配置全局生效**: 波长范围、平滑度、灵敏度等设置统一管理
- **分页结果浏览**: HorizontalPager支持左右滑动查看多通道结果,直观对比
- **实时数据探索**: 点击光谱图可查看精确的波长和强度数值
- **峰值自动标注**: 根据灵敏度自动识别并标注主要峰值
- **多格式结果导出**: 支持CSV(原始数据)、PNG(图表)和PDF(完整报告)三种格式导出

## 未来计划
- **云端集成**: 实现云端存储和分析功能,支持跨设备数据共享
- **更多板型支持**: 添加对384孔板等更多类型孔板的支持
- **AI分析增强**: 开发更精确的浓度预测算法,应用最新的机器学习技术
- **批量分析工具**: 增加批量处理和比较多个实验结果的工具
- **自动报告生成**: 进一步优化PDF报告生成功能,支持更多自定义选项
- **3D可视化**: 实现检测结果的三维可视化展示
- **移动端优化**: 进一步优化移动端体验,提高应用流畅度和响应速度
- **多光谱对比**: 支持多通道光谱曲线叠加对比分析
## 2026-03-12 本轮调整记录
- `UiText.kt` 已迁移到 `app/src/main/java/com/muc/fluocolorquant/utils/UiText.kt`，避免该通用文案封装继续停留在 `viewmodels` 包中。
- 光谱标定页底部弱化了重复卡片样式，保留一套进度主卡片并压缩当前通道信息区，解决“标定进度”区域视觉重叠的问题。
- 光谱分析结果页将多通道叠加对比并入通道详情的纵向滚动区域，叠加图注改为左右双列显示，便于继续查看下方单通道详情。
## 2026-03-13 调整记录
- 光谱标定页底部控制区改为仅保留外层底部面板背景，移除了内部白色圆角背景，避免双重背景框。
- 光谱分析结果页为多通道叠加对比补充了高区分度的 10 色配色，并给图例区域增加左侧留白。
- 修复了光谱曲线点击提示仍显示“浓度 / 像素值”的问题，现已按光谱模式切换为“波长 / 强度”提示，并完成中英文资源化。
- 应用设置页新增主题模式选择，支持浅色、深色与跟随系统三种全局主题，并接入 DataStore 持久化与应用级主题切换。
- 新建项目页与光谱分析结果页改为全面跟随 `MaterialTheme` 的背景、卡片和文字配色；同时为检测模式补充英文短标签，并将 96 孔结果页的卡片层级、分析物切换条和结果展示区样式统一向光谱结果页靠拢。

## 2026-03-19 ??????????
- ?? CameraX ?????????????????????????????????
- ?????? ISO????????????????????????????????????? AE/AWB ?????
- ???????????????? JSON??????????????? EXIF ?????????????????
- ?????????????????????????????????????????????
- ?????????????????????????? ISO?????????????????????????????

## 2026-03-19 相机拍摄页修复记录
- 补回 `CameraCaptureScreen.kt`，修复因页面文件缺失导致的导航引用爆红和 Kotlin 编译失败问题。
- 相机拍摄页改为“底部简洁参数条 + 高级设置底部面板”，主界面不再堆叠大段说明文字。
- ISO、曝光时间、曝光补偿和白平衡锁定保留默认实验预设，但支持用户在高级面板中通过刻度拨盘样式进行调整。
- 设备支持手动传感器控制时优先锁定 ISO 与曝光时间；不支持时自动回退到 EV 补偿与 AE/AWB 锁定策略，并继续保存 EXIF 与相机能力元数据。
- 紧凑参数区改为 2x2 等宽信息卡片，并压缩文案长度；同时重新分配预览区与底部控件高度，让实时取景区域更大、更整洁。
- 相机拍摄页的“固定拍摄参数”面板已整体重建为标题区 + 高级设置入口 + 2x2 参数网格，去掉原先自适应拼接布局，视觉层次和对齐稳定性更自然。

## 2026-03-20 相机拍摄页第一阶段重构
- 相机拍摄主界面改为“全屏实时取景 + 顶部悬浮栏 + 中央辅助对准框 + 底部悬浮操作层”，不再在主界面常驻大面积参数卡片。
- 顶部悬浮栏仅保留返回、标题和说明入口，减少对取景区域的占用，并为后续接入锁定状态徽标与专业模式入口预留空间。
- 底部操作层拆分为状态徽标、模式说明与拍摄按钮三部分，使用半透明深色悬浮样式，提升深浅主题下的可读性和整体一致性。
- 固定拍摄参数从主视图移出，主界面只保留 ISO、曝光、白平衡和锁定模式的极简状态徽标，详细说明继续通过信息弹窗和高级设置面板按需展示。
- 本轮重构仍保留现有 CameraX + Camera2Interop 参数注入与元数据写入链路，下一阶段将继续拆分 `CameraCaptureViewModel` 与 `CameraEngine`，把 UI 渲染和相机硬件控制彻底解耦。

## 2026-03-20 相机拍摄页第二阶段解耦
- 新增 `CameraCaptureViewModel`，统一管理输出路径校验、参数状态、弹窗显隐、拍照保存状态、错误提示与跳转副作用，页面改为只消费 `uiState` 和 `effects`。
- 新增 `CameraEngine` 抽象接口及 `CameraXCameraEngine` 实现，集中封装 CameraX 绑定、Camera2Interop 固定参数注入、拍照保存与元数据写入，Compose 页面不再直接持有 `ImageCapture`、`ProcessCameraProvider` 等硬件对象。
- 原先散落在 `CameraCaptureScreen.kt` 中的 Camera2 能力探测、固定参数裁剪、UseCase 构建与拍照回调逻辑已迁移到 `utils/camera/CameraEngine.kt`，屏幕层仅保留取景预览承载与界面渲染。
- 固定拍摄请求与能力快照模型拆分到 `utils/camera/CameraCaptureModels.kt`，并将 JSON 元数据写入逻辑保持在 `CameraCaptureMetadataStore.kt`，形成“模型 / 引擎 / 存储”三层职责边界。
- Hilt 依赖注入现已为相机页绑定 `CameraEngine`，后续可以在不改 UI 的情况下继续替换为更细粒度的专业控制实现，例如运行时 `Camera2CameraControl` 动态更新、点击对焦和双指缩放。

## 2026-03-20 相机拍摄页第三阶段专业控制
- 相机固定参数更新已从“参数变化后重绑 UseCase”切换为 `Camera2CameraControl.setCaptureRequestOptions` 运行时下发，ISO、曝光时间、曝光补偿与白平衡锁定在相机已绑定后可直接作用于当前会话。
- `CameraEngine` 新增运行时参数更新、缩放倍率控制与点击对焦能力；`CameraCaptureViewModel` 进一步接入缩放状态、参数同步任务和手势驱动的状态更新。
- 预览页现已支持双指缩放与单击对焦，手势直接作用于 `PreviewView`，同时将当前缩放倍率同步展示到底部状态徽标与高级设置面板中。
- 高级设置面板拆分为“基础成像”和“实验锁定”两个分组：前者集中管理缩放与曝光补偿，后者集中管理 ISO、曝光时间和白平衡锁定，避免不同层级参数混杂在同一列中。
- 本轮仍保留当前的悬浮式取景界面与元数据写入策略，后续若继续增强，可在现有 `CameraEngine` 上继续接入更细粒度的对焦模式、镜头切换和实时硬件状态回读。

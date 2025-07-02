# FluoColorQuant - 比色-荧光高通量传感检测软件

## 项目概述
FluoColorQuant 是一款专业的比色-荧光高通量传感检测软件，结合了专业的光学检测器件和智能手机的高分辨率摄像头、高性能处理能力以及丰富的计算资源，为生化材料的浓度定量测量提供高效、便捷的解决方案。本软件基于 Android 平台开发，使用 Jetpack Compose 构建现代化 UI，采用 MVVM 架构设计，支持荧光检测模式（Fluorescence Mode）和比色检测模式（Colorimetric Mode）两种检测方式。

软件支持对标准 96 孔板（12 列 × 8 行）进行自动检测和分析，通过深度学习模型实现高精度的孔位识别和浓度预测，同时提供手动调整功能，确保在复杂光照和成像条件下也能获得准确结果。

## 最新开发进展

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
            │   ├── dao
            │   │   ├── AnalyteDao.kt
            │   │   ├── CurveModelDao.kt
            │   │   ├── DetectionRunDao.kt
            │   │   ├── PlateLayoutDao.kt
            │   │   ├── ProjectDao.kt
            │   │   ├── ReagentDao.kt
            │   │   ├── UserDao.kt
            │   │   └── WellResultDao.kt
            │   ├── enums
            │   │   ├── FittingFunction.kt
            │   │   └── PixelTypes.kt
            │   ├── model
            │   │   ├── Analyte.kt
            │   │   ├── CurveModel.kt
            │   │   ├── DetectionRun.kt
            │   │   ├── PlateLayout.kt
            │   │   ├── Project.kt
            │   │   ├── Reagent.kt
            │   │   ├── User.kt
            │   │   └── WellResult.kt
            │   ├── repository
            │   │   ├── AnalyteRepository.kt
            │   │   ├── CurveModelRepository.kt
            │   │   ├── PlateLayoutRepository.kt
            │   │   ├── ProjectRepository.kt
            │   │   ├── ProjectRepositoryImpl.kt
            │   │   ├── ReagentRepository.kt
            │   │   ├── SettingsRepository.kt
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
            │   │   ├── PrimaryButton.kt
            │   │   ├── TextFields.kt
            │   │   └── charts
            │   │       ├── ChartData.kt
            │   │       └── CurveChart.kt
            │   ├── navigation
            │   │   ├── AppNavigation.kt
            │   │   └── Screen.kt
            │   ├── screens
            │   │   ├── auth
            │   │   │   ├── LoginScreen.kt
            │   │   │   └── RegisterScreen.kt
            │   │   ├── curvefitting
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
            │   │   │   └── NewProjectScreen.kt
            │   │   ├── result
            │   │   │   ├── ExportComponents.kt
            │   │   │   └── ResultScreen.kt
            │   │   ├── settings
            │   │   │   ├── AnalyteManagementScreen.kt
            │   │   │   ├── AppSettingsScreen.kt
            │   │   │   ├── CurveModelManagementScreen.kt
            │   │   │   ├── DetectionSettingsScreen.kt
            │   │   │   ├── LibraryManagementScreen.kt
            │   │   │   ├── ReagentLibraryScreen.kt
            │   │   │   ├── SettingsNavigationItem.kt
            │   │   │   └── SettingsScreen.kt
            │   │   └── splash
            │   │       └── SplashScreen.kt
            │   ├── theme
            │   │   ├── Color.kt
            │   │   ├── Theme.kt
            │   │   └── Type.kt
            │   └── viewmodels
            │       ├── AnalyteViewModel.kt
            │       ├── ConcentrationViewModel.kt
            │       ├── CurveModelViewModel.kt
            │       ├── DetectionViewModel.kt
            │       ├── HistoryViewModel.kt
            │       ├── ImageCorrectionViewModel.kt
            │       ├── ProjectViewModel.kt
            │       ├── ReagentViewModel.kt
            │       ├── ResultViewModel.kt
            │       ├── SettingsViewModel.kt
            │       └── UserViewModel.kt
            ├── utils
            │   ├── math
            │   │   ├── FittingEngine.kt
            │   │   ├── FittingResult.kt
            │   │   └── MetricsCalculator.kt
            │   ├── AnimationUtils.kt
            │   ├── HeatmapColorUtil.kt
            │   └── LocaleHelper.kt
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
- **机器学习**: 集成 PyTorch Mobile 进行模型推理
- **数学计算**: 使用 Apache Commons Math 实现高级数学计算和曲线拟合
- **结果分析**: 内置统计分析功能，计算浓度和相关指标

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
4. **图像获取**: 拍照或从相册选择孔板图像
5. **图像裁剪**: 裁剪目标区域，优化检测效果
6. **图像矫正**: 对图像进行预处理和颜色校正
7. **孔位检测**: 自动检测孔位，支持手动调整
8. **浓度预测**: 进行浓度预测和数据分析
9. **结果展示**: 以图表和数据形式展示分析结果
10. **历史记录**: 查看和管理历史检测记录

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

## 未来计划
- **云端集成**: 实现云端存储和分析功能，支持跨设备数据共享
- **更多板型支持**: 添加对384孔板等更多类型孔板的支持
- **AI分析增强**: 开发更精确的浓度预测算法，应用最新的机器学习技术
- **批量分析工具**: 增加批量处理和比较多个实验结果的工具
- **自动报告生成**: 添加根据检测结果自动生成标准实验报告的功能
- **3D可视化**: 实现检测结果的三维可视化展示
- **移动端优化**: 进一步优化移动端体验，提高应用流畅度和响应速度 
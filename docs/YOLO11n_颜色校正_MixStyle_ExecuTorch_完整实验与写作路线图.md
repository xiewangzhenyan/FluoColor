# YOLO11n + 颜色校正 + MixStyle + ExecuTorch 完整实验与写作路线图

## 1. 文档定位

本文档用于把当前论文主线收敛成一条可执行、可复现、可端侧落地的路线，供后续在新的 Codex 窗口中直接继续推进。

本文档不重复展开公开数据集的完整列表与许可细节，而是直接复用现有文档：

- [基于公开智能手机生化传感图像数据的可投稿小论文设计与可复现实验方案.md](./基于公开智能手机生化传感图像数据的可投稿小论文设计与可复现实验方案.md)
- [基于公开数据的智能手机端侧深度学习生化传感检测系统：可投稿小论文设计与可复现实验方案.md](./基于公开数据的智能手机端侧深度学习生化传感检测系统：可投稿小论文设计与可复现实验方案.md)

后续如需补充数据集来源、样本量、许可和可投稿期刊讨论，优先回到上述两份文档，而不是在本文重复维护。

## 2. 最终论文主线

### 2.1 研究目标

论文主线聚焦于：

**基于公开智能手机生化传感图像的轻量 ROI 检测与端侧可部署分析框架**

主线关键词：

- YOLO11n
- Reference-aware Color Correction
- MixStyle
- ExecuTorch
- Android 端侧部署
- 公开数据集
- 跨数据集泛化
- 鲁棒性评估

### 2.2 主线必须控制的边界

这篇主稿不要混入下列方向作为主实验：

- 光谱检测
- 微流控芯片规则阵列恢复
- 四角点深度学习检测
- 强透视几何网络
- GroundingDINO / MaskDINO 等重模型
- 多检测模式全部展开

这些方向可以作为系统扩展能力或未来工作，但不进入主稿核心实验链。

## 3. 核心技术路线

### 3.1 模块拆分

主链路按以下方式组织：

1. YOLO11n 负责检测读数区域或关键 ROI
2. 对 ROI 做参考区感知颜色校正
3. 在校正后的 ROI 上做定量回归或分类
4. 使用 MixStyle 提升训练阶段域泛化
5. 最终在 Android 端使用 ExecuTorch 部署检测模型

### 3.2 各模块职责

#### YOLO11n

职责：

- 检测 `reading_window` 或关键读数窗口
- 作为轻量 ROI 检测器
- 不直接承担浓度回归任务

不建议在第一篇主稿中让 YOLO11n 负责：

- 四角点检测
- 复杂透视校正
- 规则阵列点位恢复

#### Reference-aware Color Correction

目标：

- 缓解不同手机、不同白平衡、不同光照条件下的颜色漂移
- 利用 control 区、背景参考区或固定参考色块进行归一化

实现原则：

- 可先用传统 CV 方案实现
- 可借鉴 FC4 的颜色恒常性思路
- 但不要直接照搬 FC4 原模型

#### MixStyle

目标：

- 在训练阶段提升跨数据集、跨设备、跨场景泛化能力

特点：

- 仅作用于训练阶段
- 推理几乎不增加额外成本
- 适合作为论文主要创新模块之一

#### Tent

定位：

- 仅作为 Python 论文实验中的增强项
- 不作为 Android 首版落地模块

原因：

- 需要测试时在线自适应
- 端侧功耗、延迟和工程复杂度都较高

## 4. Python 侧必须完成的工作

Python 是论文实验主战场，目标是先把算法、表格、图和统计检验跑通，再考虑 Android 移植。

### 4.1 先做导出可行性验证

这是第一步，不是最后一步。

需要首先验证：

1. `yolo11n.pt` 能否顺利进入可用于 ExecuTorch 的导出链路
2. 哪些算子会在导出时出问题
3. 是否需要把 NMS 和结果解码放到 Android 端自行实现
4. Android 端是否能完成最小推理闭环

如果这一步不通过，不要急着大规模训练。

### 4.2 推荐实验目录结构

建议在仓库根目录新增：

`F:/Code/Android/FluoColor/experiments/`

推荐结构：

```text
experiments/
├─ configs/
│  ├─ datasets/
│  ├─ models/
│  └─ train/
├─ data/
│  ├─ raw/
│  ├─ interim/
│  ├─ processed/
│  └─ splits/
├─ tools/
│  ├─ download_*.py
│  ├─ convert_annotations.py
│  ├─ build_splits.py
│  └─ export_executorch.py
├─ detection/
│  ├─ train_yolo11n.py
│  ├─ eval_yolo11n.py
│  └─ infer_yolo11n.py
├─ quantification/
│  ├─ color_correction.py
│  ├─ feature_extract.py
│  ├─ train_regressor.py
│  └─ train_classifier.py
├─ evaluation/
│  ├─ metrics.py
│  ├─ robustness.py
│  ├─ statistics.py
│  └─ benchmark_tables.py
├─ export/
│  ├─ to_torch_export.py
│  ├─ to_executorch.py
│  └─ sample_inputs/
└─ notebooks/
```

### 4.3 数据集与任务组织原则

本主稿建议只保留：

- 一个比色主任务
- 一个 LFA 主任务
- 一个跨数据集泛化验证集

不要把太多任务同时塞进第一篇论文。

公开数据集的具体来源、许可和推荐用法，直接参考：

- [基于公开智能手机生化传感图像数据的可投稿小论文设计与可复现实验方案.md](./基于公开智能手机生化传感图像数据的可投稿小论文设计与可复现实验方案.md)

### 4.4 标注策略

第一版标注建议尽量保守，优先降低工作量并保证跨数据集一致性。

#### 首选标注方案

只标一个检测类别：

- `reading_window`

优点：

- 标注成本低
- 跨不同公开数据集更容易统一
- 对 YOLO11n 训练更稳定

#### 第二阶段增强标注

如果单类 ROI 检测已经很稳，可再扩展为：

- `reading_window`
- `control_zone`
- `target_zone`

但这不是第一优先级。

### 4.5 训练流程

#### 阶段 A：YOLO11n ROI 检测

任务：

- 训练 `reading_window` 检测器
- 输出标准检测指标

输出：

- mAP50
- mAP50-95
- Precision
- Recall

#### 阶段 B：颜色校正与定量/分类

在 ROI 裁切结果上继续做：

- 参考区感知颜色校正
- 特征提取或轻量 CNN
- 定量回归或分类

候选方法：

- Ridge / XGBoost / RandomForest
- MobileNetV3-Small
- 很小的自定义 CNN

#### 阶段 C：鲁棒性与跨数据集实验

必须做：

- 跨数据集训练/测试
- 光照与压缩扰动
- 模糊与噪声扰动
- 统计检验

## 5. 实验表格设计

### 5.1 检测器比较表

这张表不是消融表，而是检测器/骨干对比表。

建议比较：

- YOLOv5s
- YOLOv8n
- YOLO11n
- 可选：YOLOv8s

表头建议：

| Model | Params | Size | mAP50 | mAP50-95 | Precision | Recall | Export Feasibility | Android Latency |
|---|---:|---:|---:|---:|---:|---:|---|---:|

### 5.2 方法消融表

固定 YOLO11n 后再做真正的 ablation。

建议行设置：

1. Baseline
2. Baseline + Color Correction
3. Baseline + MixStyle
4. Baseline + Color Correction + MixStyle
5. 可选：+ Tent
6. Full model

检测任务指标可用：

- mAP50
- mAP50-95
- Precision
- Recall

定量或分类任务指标按任务类型替换为：

- MAE
- RMSE
- R²
- Accuracy
- F1
- AUC

### 5.3 鲁棒性实验表

至少包含：

- Clean
- Brightness Shift
- Color Shift
- Gaussian Noise
- JPEG Compression
- Blur
- Shadow / Partial Occlusion

### 5.4 跨数据集泛化表

建议至少包括：

- Dataset A train -> Dataset A test
- Dataset A train -> Dataset B test
- Dataset B train -> Dataset A test

### 5.5 安卓部署表

建议字段：

| Model | Runtime | Device | Size (MB) | Avg Latency (ms) | Peak Memory (MB) | Notes |
|---|---|---|---:|---:|---:|---|

## 6. Android 侧最终保留的模块

### 6.1 建议最终保留到安卓端

- YOLO11n ROI 检测
- 轻量颜色校正
- 必要的后处理
- Android 端解码/NMS（如果导出链路需要）
- 结果追溯与导出

### 6.2 不建议首版直接保留到安卓端

- 完整 Tent
- 复杂测试时在线自适应
- 四角点深度学习检测
- 重型分割器或大 Transformer 检测器

## 7. ExecuTorch 落地策略

### 7.1 平台原则

运行时从 PyTorch Mobile 迁移到 ExecuTorch 是既定方向，但必须采用保守落地策略。

策略如下：

1. 先做导出 POC
2. 优先验证最小可运行闭环
3. 若模型后处理在导出链路中不稳定，优先改为 Android 侧实现
4. 不要把训练代码和导出代码混写在一起

### 7.2 Android 端验证目标

至少测试两台设备：

- 一台中端安卓机
- 一台普通安卓机

至少输出：

- 模型大小
- 加载时间
- 平均推理延迟
- 峰值内存
- 连续推理稳定性

## 8. 论文中的创新点该怎么写

不要写：

- “把 YOLOv5s 升级成 YOLO11n”

应该写成：

1. **提出一个面向公开智能手机生化传感图像的轻量 ROI 检测与端侧可部署分析框架**
2. **提出参考区感知颜色校正策略，提升跨光照和跨设备鲁棒性**
3. **结合 MixStyle 提升跨公开数据集泛化能力**
4. **在 Android 端通过 ExecuTorch 实现低成本可复现部署与可追溯归档**

## 9. 时间规划

### Week 1

- 整理公开数据集
- 核查许可
- 创建 `experiments/` 目录
- 做 YOLO11n -> ExecuTorch 导出 POC

### Week 2–3

- 制定 ROI 标注规范
- 完成 `reading_window` 标注
- 跑通第一版 YOLO11n 训练与验证

### Week 4–5

- 实现颜色校正模块
- 接入 MixStyle
- 形成第一版消融结果

### Week 6

- 做鲁棒性实验
- 做跨数据集泛化实验
- 生成第一版主结果表

### Week 7

- 完成 ExecuTorch Android 集成
- 跑端侧 benchmark

### Week 8

- 整理图表
- 写摘要、方法、实验、结果
- 收敛论文初稿

## 10. 明确不做的事情

当前主稿中，不做以下事情作为主任务：

- 规则阵列四角深度学习检测
- 微流控芯片网格恢复
- 光谱算法实验
- 多条完全不同生化检测链路同时展开

这些方向可以在系统介绍或未来工作里提及，但不应进入当前主稿主实验闭环。

## 11. 当前最优先要推进的事项

后续新的 Codex 窗口应优先协助我完成以下几件事：

1. 创建 `experiments/` 目录骨架
2. 设计并落地 `reading_window` 标注规范
3. 跑通 YOLO11n 训练、验证与最小导出流程
4. 定义颜色校正模块接口
5. 设计 MixStyle 接入方案
6. 产出论文实验表格模板与自动汇总脚本

## 12. 一句话总结

当前论文主线是：

**围绕公开智能手机生化传感图像，构建一条以 YOLO11n 进行轻量 ROI 检测、以参考区感知颜色校正和 MixStyle 提升鲁棒性、并最终通过 ExecuTorch 落地到 Android 端的可复现实验与部署路线。**

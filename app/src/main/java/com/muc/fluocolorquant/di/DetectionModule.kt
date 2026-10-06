package com.muc.fluocolorquant.di

import com.muc.fluocolorquant.domain.detection.quantification.AndroidGridDeepLearningExecutor
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningExecutor
import com.muc.fluocolorquant.domain.detection.plate96.Plate96ObjectDetector
import com.muc.fluocolorquant.domain.detection.plate96.Plate96YoloDetector
import com.muc.fluocolorquant.domain.result.dualmodal.network.AndroidDualNetCropSource
import com.muc.fluocolorquant.domain.result.dualmodal.network.AndroidDualNetModelRunner
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetCropSource
import com.muc.fluocolorquant.domain.result.dualmodal.network.DualNetModelRunner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 检测算法实现绑定；页面和协调器只依赖稳定领域接口。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DetectionModule {

    /** 生产环境使用真实 PyTorch Lite 逐孔执行器，测试可以直接注入假实现。 */
    @Binds
    @Singleton
    abstract fun bindGridDeepLearningExecutor(
        implementation: AndroidGridDeepLearningExecutor
    ): GridDeepLearningExecutor

    /** 新96孔板定位链通过领域接口复用YOLO，不再依赖旧DetectionViewModel。 */
    @Binds
    @Singleton
    abstract fun bindPlate96ObjectDetector(
        implementation: Plate96YoloDetector
    ): Plate96ObjectDetector

    /** DualNet 从冻结原图与 PG-Grid 几何裁切；JVM 测试注入假裁切。 */
    @Binds
    @Singleton
    abstract fun bindDualNetCropSource(
        implementation: AndroidDualNetCropSource
    ): DualNetCropSource

    /** DualNet 的 PyTorch Lite 执行器；模型摘要不一致时拒绝加载。 */
    @Binds
    @Singleton
    abstract fun bindDualNetModelRunner(
        implementation: AndroidDualNetModelRunner
    ): DualNetModelRunner
}

package com.muc.fluocolorquant.di

import com.muc.fluocolorquant.domain.detection.quantification.AndroidGridDeepLearningExecutor
import com.muc.fluocolorquant.domain.detection.quantification.GridDeepLearningExecutor
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
}

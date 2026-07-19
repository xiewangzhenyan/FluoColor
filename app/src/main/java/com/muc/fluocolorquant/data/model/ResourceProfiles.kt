package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.muc.fluocolorquant.data.enums.ResourceStatus
import java.util.Date
import java.util.UUID

/**
 * 实验载体档案。
 *
 * 该实体只描述孔板、微流控芯片等物理承载对象，不包含比色、荧光或光谱算法参数。
 * 定位器的具体参数通过版本化 JSON 保存，后续可以在不改表结构的前提下演进 PG-Grid 契约。
 */
@Entity(
    tableName = "carrier_profiles",
    indices = [Index(value = ["name", "version"], unique = true)]
)
data class CarrierProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val carrierType: String,
    val rows: Int,
    val columns: Int,
    val siteShape: String,
    val orientationMarkerJson: String? = null,
    val roiConfigJson: String? = null,
    val locatorConfigJson: String? = null,
    val status: String = ResourceStatus.ACTIVE.code,
    val version: Int = 1,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

/**
 * 采集设备档案。
 *
 * 手机型号、Camera ID、光学模块、固定装置和相机控制策略共同决定图像是否可比较。
 * 复杂兼容规则以 JSON 保存，普通用户只选择已命名并发布的设备档案。
 */
@Entity(
    tableName = "acquisition_profiles",
    indices = [Index(value = ["name", "version"], unique = true)]
)
data class AcquisitionProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val supportedModesJson: String,
    val compatibleCarrierTypesJson: String,
    val deviceMatcherJson: String? = null,
    val opticalModuleName: String? = null,
    val fixtureId: String? = null,
    val cameraControlStrategy: String,
    val cameraConstraintsJson: String? = null,
    val imageQcProfileJson: String? = null,
    val status: String = ResourceStatus.ACTIVE.code,
    val version: Int = 1,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

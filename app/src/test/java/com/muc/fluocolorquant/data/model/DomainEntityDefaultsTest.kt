package com.muc.fluocolorquant.data.model

import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

/**
 * 新领域实体的兼容默认值测试。
 *
 * 旧业务代码仍会使用原有构造参数创建项目、检测运行和实验模板，新增字段必须提供
 * 明确默认值，避免一次数据库升级迫使所有旧页面同时重写。
 */
class DomainEntityDefaultsTest {

    @Test
    fun `载体资源默认启用且从版本一开始`() {
        val carrier = CarrierProfile(
            name = "10×10 微流控芯片",
            carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
            rows = 10,
            columns = 10,
            siteShape = SiteShape.SQUARE.code
        )

        assertEquals(ResourceStatus.ACTIVE.code, carrier.status)
        assertEquals(1, carrier.version)
    }

    @Test
    fun `旧构造方式创建的模板项目和运行保留兼容默认值`() {
        val template = ExperimentTemplate(
            templateName = "兼容模板",
            analyteId = "analyte",
            reagentAntigenId = null,
            reagentAntibodyId = null,
            fkCurveModelId = "curve",
            reliableRangeMin = 0.0,
            reliableRangeMax = 10.0,
            concentrationUnit = "ng/mL",
            defaultLayoutJson = null
        )
        val project = Project(
            id = "project",
            name = "兼容项目",
            detectionMode = "COLORIMETRIC",
            recognitionType = "AUTO",
            imageUri = "content://image",
            rows = 8,
            columns = 12,
            createTime = Date(1L),
            userId = "user",
            lastRunTimestamp = null,
            analysisMethod = "CURVE_FIT"
        )
        val run = DetectionRun(
            runId = "run",
            projectId = project.id,
            timestamp = Date(2L),
            detectionModelUsed = null,
            concentrationModelUsed = null,
            status = "Processing",
            errorMessage = null,
            confThreshold = null,
            iouThreshold = null,
            wellsDetected = null
        )

        assertEquals(TemplateLifecycleStatus.DRAFT.code, template.status)
        assertNull(project.templateSnapshotJson)
        assertNull(run.effectiveConfigSnapshotJson)
    }

    @Test
    fun `采集附件默认未锁定且保留端点角色编码`() {
        val artifact = CaptureArtifact(
            runId = "run",
            captureRole = CaptureRole.ENDPOINT.code,
            originalPath = "/data/end.png",
            capturedAt = Date(3L)
        )

        assertEquals(CaptureRole.ENDPOINT.code, artifact.captureRole)
        assertFalse(artifact.locked)
        assertEquals(1, artifact.revision)
    }
}

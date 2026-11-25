package com.muc.fluocolorquant.data.storage

import com.muc.fluocolorquant.data.dao.ProjectDeletionSnapshot
import com.muc.fluocolorquant.data.dao.ProjectFileReference
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 项目文件清理的边界测试，重点防止公共图片、共享证据和越界路径被误删。 */
class ProjectFileCleanerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `删除项目会清理私有直接路径运行目录和光谱标定裁片`() {
        val filesRoot = temporaryFolder.newFolder("files")
        val cacheRoot = temporaryFolder.newFolder("cache")
        val source = File(filesRoot, "projects/source.jpg").createFileWithParent()
        val runInput = File(filesRoot, "run_inputs/run-1/input.jpg").createFileWithParent()
        val evidence = File(filesRoot, "processing_evidence/run-1/grid.png").createFileWithParent()
        val calibrationCrop = File(cacheRoot, "spectrum/calibration.png").createFileWithParent()
        val cleaner = ProjectFileCleaner(filesRoot, listOf(cacheRoot))

        val report = cleaner.clean(
            snapshot = ProjectDeletionSnapshot(
                projectId = "project-1",
                references = listOf(
                    reference("PROJECT_IMAGE", source.path),
                    reference("RUN_ID", "run-1"),
                    reference(
                        "SPECTRUM_CALIBRATION_JSON",
                        """{"autoDebug":{"calibrationCropPath":"${calibrationCrop.invariantSeparatorsPath}"}}"""
                    )
                )
            ),
            remainingReferences = emptyList()
        )

        assertFalse(source.exists())
        assertFalse(runInput.exists())
        assertFalse(evidence.exists())
        assertFalse(calibrationCrop.exists())
        assertTrue(report.deletedCount >= 4)
        assertTrue(report.failedCount == 0)
    }

    @Test
    fun `公共路径content引用和带目录穿越的运行ID永不删除`() {
        val filesRoot = temporaryFolder.newFolder("private-files")
        val publicFile = temporaryFolder.newFile("public.jpg").apply { writeText("public") }
        val traversalTarget = File(filesRoot, "must-stay.txt").createFileWithParent()
        val cleaner = ProjectFileCleaner(filesRoot, emptyList())

        cleaner.clean(
            snapshot = ProjectDeletionSnapshot(
                projectId = "project-unsafe",
                references = listOf(
                    reference("PROJECT_IMAGE", publicFile.path),
                    reference("PROJECT_IMAGE", "content://media/external/images/42"),
                    reference("RUN_ID", "../must-stay.txt")
                )
            ),
            remainingReferences = emptyList()
        )

        assertTrue(publicFile.exists())
        assertTrue(traversalTarget.exists())
    }

    @Test
    fun `剩余项目引用运行目录内文件时保守保留整个目录`() {
        val filesRoot = temporaryFolder.newFolder("shared-files")
        val shared = File(filesRoot, "run_inputs/run-shared/shared.jpg").createFileWithParent()
        val unsharedSibling = File(filesRoot, "run_inputs/run-shared/diagnostic.png").createFileWithParent()
        val cleaner = ProjectFileCleaner(filesRoot, emptyList())

        cleaner.clean(
            snapshot = ProjectDeletionSnapshot(
                projectId = "project-old",
                references = listOf(reference("RUN_ID", "run-shared"))
            ),
            remainingReferences = listOf(reference("PROJECT_IMAGE", shared.path))
        )

        // 共享引用位于运行目录内时，不逐个猜测所有权，整目录保留可避免证据链断裂。
        assertTrue(shared.exists())
        assertTrue(unsharedSibling.exists())
    }

    @Test
    fun `V2代际只清理明确科研目录并保留模型设置和无关文件`() {
        val filesRoot = temporaryFolder.newFolder("generation-files")
        val cleaner = ProjectFileCleaner(filesRoot, emptyList())
        val runInput = File(filesRoot, "run_inputs/run-old/input.jpg").createFileWithParent()
        val evidence = File(filesRoot, "processing_evidence/run-old/grid.png").createFileWithParent()
        val well = File(filesRoot, "wells/run-old/A1.png").createFileWithParent()
        val spectrumChannel = File(filesRoot, "spectrum_channels/channel.png").createFileWithParent()
        val spectrumCalibration = File(
            filesRoot,
            "spectrum_calibrations/reference.png"
        ).createFileWithParent()
        val model = File(filesRoot, "models/quantitation.ptl").createFileWithParent()
        val settings = File(filesRoot, "unrelated/user-note.txt").createFileWithParent()

        val report = cleaner.cleanLegacyScientificGeneration()

        assertFalse(runInput.exists())
        assertFalse(evidence.exists())
        assertFalse(well.exists())
        assertFalse(spectrumChannel.exists())
        assertFalse(spectrumCalibration.exists())
        assertTrue(model.exists())
        assertTrue(settings.exists())
        assertTrue(report.failedCount == 0)
    }

    private fun reference(kind: String, value: String) = ProjectFileReference(kind, value)

    private fun File.createFileWithParent(): File = apply {
        parentFile?.mkdirs()
        writeText("test")
    }
}

package com.muc.fluocolorquant.data.storage

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.muc.fluocolorquant.data.dao.ProjectDeletionSnapshot
import com.muc.fluocolorquant.data.dao.ProjectFileReference
import com.muc.fluocolorquant.data.model.SpectrumCalibrationReferenceData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** 项目删除后的文件清理结果，主要供测试、日志和后续受限垃圾回收使用。 */
data class ProjectFileCleanupReport(
    val deletedCount: Int,
    val failedCount: Int
)

/**
 * 只清理项目在应用专属目录中拥有的图片和过程证据。
 *
 * 数据库中的路径属于历史输入，可能来自公共相册、`content://`、旧版本绝对路径，甚至
 * 损坏数据。因此本类遵循“默认不删除”：只有 canonical path 严格位于 files/cache/
 * externalFiles/externalCache 之一、且未被剩余项目引用时才执行删除。
 */
@Singleton
class ProjectFileCleaner private constructor(
    roots: PrivateStorageRoots
) {
    private val internalFilesRoot: File? = roots.internalFilesRoot.canonicalOrNull()
    private val allowedRoots: List<File> = (
        listOfNotNull(internalFilesRoot) + roots.additionalRoots.mapNotNull { it.canonicalOrNull() }
        ).distinctBy { it.path }

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        PrivateStorageRoots(
            internalFilesRoot = context.filesDir,
            additionalRoots = listOfNotNull(
                context.cacheDir,
                context.getExternalFilesDir(null),
                context.externalCacheDir
            )
        )
    )

    /** JVM 测试专用入口；生产代码必须使用 Context 构造出的真实应用专属目录。 */
    internal constructor(
        internalFilesRoot: File,
        additionalAllowedRoots: List<File>
    ) : this(
        PrivateStorageRoots(
            internalFilesRoot = internalFilesRoot,
            additionalRoots = additionalAllowedRoots
        )
    )

    /**
     * 清理已经从数据库成功删除的项目文件。
     *
     * [remainingReferences] 必须在项目删除提交后读取。两个项目引用同一路径时，该路径会
     * 进入保护集合；即使它位于待删除运行目录内部，也会保守地保留整个目录。
     */
    fun clean(
        snapshot: ProjectDeletionSnapshot,
        remainingReferences: List<ProjectFileReference>
    ): ProjectFileCleanupReport {
        if (allowedRoots.isEmpty()) return ProjectFileCleanupReport(0, 0)

        val protectedPaths = remainingReferences
            .asSequence()
            .flatMap(::pathsFromReference)
            .mapNotNull(::canonicalPrivateFileOrNull)
            .toSet()

        val candidates = snapshot.references
            .asSequence()
            .flatMap(::pathsFromReference)
            .mapNotNull(::canonicalPrivateFileOrNull)
            .distinctBy { it.path }
            .toList()

        var deletedCount = 0
        var failedCount = 0
        candidates.forEach { candidate ->
            val outcome = deletePrivateTree(
                candidate = candidate,
                protectedPaths = protectedPaths
            )
            deletedCount += outcome.deletedCount
            failedCount += outcome.failedCount
        }
        if (failedCount > 0) {
            // 数据库删除已经提交，文件失败不能反向伪造项目仍存在；记录后可由受限 GC 重试。
            Log.w(TAG, "项目 ${snapshot.projectId} 已删除，但有 $failedCount 个私有文件未能清理")
        }
        return ProjectFileCleanupReport(deletedCount, failedCount)
    }

    /**
     * 清理 V2 科研数据代际之前生成的、目录所有权明确的应用私有文件。
     *
     * 这里只枚举项目运行专用目录，绝不扫描或删除 filesDir 本身，也不触碰模型、账户、
     * DataStore、公共相册或导出目录。每个目标仍会经过 canonical 边界和逐层删除校验；
     * 任一失败会由启动协调器保留代际标记并在下次启动重试。
     */
    fun cleanLegacyScientificGeneration(): ProjectFileCleanupReport {
        val root = internalFilesRoot ?: return ProjectFileCleanupReport(0, 0)
        var outcome = DeleteOutcome()
        LEGACY_SCIENTIFIC_DIRECTORIES.forEach { directoryName ->
            outcome += deletePrivateTree(
                candidate = File(root, directoryName),
                protectedPaths = emptySet()
            )
        }
        if (outcome.failedCount > 0) {
            Log.w(TAG, "V2 科研数据目录清理仍有 ${outcome.failedCount} 个文件待重试")
        }
        return ProjectFileCleanupReport(outcome.deletedCount, outcome.failedCount)
    }

    /** 将不同数据库引用类型恢复为实际文件路径；运行目录只接受单段安全 ID。 */
    private fun pathsFromReference(reference: ProjectFileReference): Sequence<String> {
        return when (reference.kind) {
            KIND_RUN_ID -> {
                val runId = reference.value.takeIf(::isSafeRunId) ?: return emptySequence()
                val root = internalFilesRoot ?: return emptySequence()
                sequenceOf(
                    File(root, "$RUN_INPUT_DIRECTORY/$runId").path,
                    File(root, "$PROCESSING_EVIDENCE_DIRECTORY/$runId").path
                )
            }

            KIND_SPECTRUM_CALIBRATION_JSON -> listOfNotNull(
                parseCalibrationCropPath(reference.value)
            ).asSequence()

            else -> sequenceOf(reference.value)
        }
    }

    /**
     * 只接受 UUID/普通标识符允许的单段字符，阻止 `../` 或路径分隔符把运行目录路由到
     * 项目证据根目录以外。点号可以出现在旧 ID 中，但 `.` 和 `..` 本身明确拒绝。
     */
    private fun isSafeRunId(value: String): Boolean {
        return value.length in 1..128 &&
            value != "." &&
            value != ".." &&
            SAFE_RUN_ID.matches(value)
    }

    private fun parseCalibrationCropPath(json: String): String? {
        return runCatching {
            Gson().fromJson(json, SpectrumCalibrationReferenceData::class.java)
                ?.autoDebug
                ?.calibrationCropPath
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    /**
     * URI 与文件路径必须先分流。公共 `content://`、网络资源和未知 scheme 永远不删除；
     * `file://` 只有在随后通过应用私有 canonical 边界检查时才会被接受。
     */
    private fun canonicalPrivateFileOrNull(rawValue: String): File? {
        val value = rawValue.trim()
        if (value.isEmpty()) return null
        val file = when {
            WINDOWS_ABSOLUTE_PATH.containsMatchIn(value) -> File(value)
            URI_SCHEME.containsMatchIn(value) -> {
                if (!value.startsWith("file:", ignoreCase = true)) return null
                runCatching { File(URI(value)) }.getOrNull() ?: return null
            }

            else -> File(value)
        }
        val canonical = file.canonicalOrNull() ?: return null
        return canonical.takeIf(::isStrictlyInsideAllowedRoot)
    }

    /** 绝不允许删除专属根目录本身，只允许其严格后代。 */
    private fun isStrictlyInsideAllowedRoot(candidate: File): Boolean {
        return allowedRoots.any { root -> candidate.isStrictDescendantOf(root) }
    }

    /**
     * 逐层校验后删除，避免符号链接或损坏目录让递归操作越过专属根目录。
     * 对包含受保护文件的目录采取整目录保留，安全性优先于立即回收少量空间。
     */
    private fun deletePrivateTree(
        candidate: File,
        protectedPaths: Set<File>
    ): DeleteOutcome {
        val canonical = candidate.canonicalOrNull() ?: return DeleteOutcome()
        if (!isStrictlyInsideAllowedRoot(canonical)) return DeleteOutcome()
        if (protectedPaths.any { protected ->
                canonical == protected ||
                    canonical.isStrictDescendantOf(protected) ||
                    protected.isStrictDescendantOf(canonical)
            }
        ) {
            return DeleteOutcome()
        }
        if (!candidate.exists()) return DeleteOutcome()

        var outcome = DeleteOutcome()
        if (candidate.isDirectory) {
            // 每个子项都会重新 canonical 化和校验；指向专属目录外的符号链接不会被跟随删除。
            candidate.listFiles()?.forEach { child ->
                outcome += deletePrivateTree(child, protectedPaths)
            }
            if (candidate.listFiles()?.isNotEmpty() == true) return outcome
        }

        return if (candidate.delete()) {
            outcome + DeleteOutcome(deletedCount = 1)
        } else {
            outcome + DeleteOutcome(failedCount = 1)
        }
    }

    private data class DeleteOutcome(
        val deletedCount: Int = 0,
        val failedCount: Int = 0
    ) {
        operator fun plus(other: DeleteOutcome): DeleteOutcome = DeleteOutcome(
            deletedCount = deletedCount + other.deletedCount,
            failedCount = failedCount + other.failedCount
        )
    }

    private data class PrivateStorageRoots(
        val internalFilesRoot: File,
        val additionalRoots: List<File>
    )

    private companion object {
        const val TAG = "ProjectFileCleaner"
        const val KIND_RUN_ID = "RUN_ID"
        const val KIND_SPECTRUM_CALIBRATION_JSON = "SPECTRUM_CALIBRATION_JSON"
        const val RUN_INPUT_DIRECTORY = "run_inputs"
        const val PROCESSING_EVIDENCE_DIRECTORY = "processing_evidence"

        // 这些目录只保存项目、运行或光谱裁片。模型目录和用户导出目录明确不在清单中。
        val LEGACY_SCIENTIFIC_DIRECTORIES: Set<String> = linkedSetOf(
            RUN_INPUT_DIRECTORY,
            PROCESSING_EVIDENCE_DIRECTORY,
            "wells",
            "spectrum_channels",
            "spectrum_calibrations"
        )

        val SAFE_RUN_ID = Regex("[A-Za-z0-9._-]+")
        val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/]")

        fun File.canonicalOrNull(): File? = runCatching { canonicalFile }.getOrNull()

        fun File.isStrictDescendantOf(parent: File): Boolean {
            val parentPrefix = parent.path.trimEnd(File.separatorChar) + File.separator
            return path.startsWith(parentPrefix)
        }
    }
}

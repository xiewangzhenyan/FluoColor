package com.muc.fluocolorquant.domain.result.dualmodal.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.JsonParser
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.domain.detection.grid.PgGridImageRectifier
import com.muc.fluocolorquant.domain.detection.grid.PgGridJsonCodec
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * 从一次运行的冻结证据生成 DualNet 裁切。
 *
 * 输入只取两样冻结证据：无损保存的端点原图（校验 SHA-256），以及运行快照里的 PG-Grid 几何。
 * 处理与 W3 训练数据逐步对应：
 * 1. 用与光度处理相同的 [PgGridImageRectifier] 生成矫正图；
 * 2. 按 PG-Grid 参考实现保存 `rectified_chip.jpg` 的方式做一次 JPEG（质量 95）编解码——训练裁切
 *    正是从这张 JPEG 上截取的，省掉这一步会让输入分布与训练不同；
 * 3. 以格点为中心、边长一个孔距截取正方形（`getRectSubPix`，亚像素中心），再用 INTER_AREA
 *    缩放到 32×32。孔距取行内、列内相邻格点距离中位数的均值，与参考实现的估计方式相同。
 */
@Singleton
class AndroidDualNetCropSource @Inject constructor() : DualNetCropSource {

    override fun crops(run: ArrayResultSnapshot, siteIndices: Set<Int>): Map<Int, ByteArray> {
        if (siteIndices.isEmpty()) return emptyMap()
        if (!OpenCVLoader.initDebug()) evidenceFailure("OpenCV 未能加载")
        val grid = decodeGrid(run)
        val source = loadVerifiedEndpoint(run)
        val rectified = try {
            PgGridImageRectifier.rectify(source, grid)
        } catch (error: RuntimeException) {
            evidenceFailure("矫正失败", error)
        } finally {
            source.recycle()
        }
        val rgba = Mat()
        val bgr = Mat()
        val decoded = Mat()
        val rgb = Mat()
        val encoded = MatOfByte()
        try {
            Utils.bitmapToMat(rectified, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, DualNetSpec.RECTIFIED_JPEG_QUALITY)
            if (!Imgcodecs.imencode(".jpg", bgr, encoded, params)) evidenceFailure("矫正图 JPEG 编码失败")
            params.release()
            val jpeg = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
            jpeg.copyTo(decoded)
            jpeg.release()
            Imgproc.cvtColor(decoded, rgb, Imgproc.COLOR_BGR2RGB)
            val side = windowSide(grid).toDouble()
            val byIndex = grid.sites.associateBy { it.siteIndex }
            return siteIndices.associateWith { index ->
                val site = byIndex[index] ?: evidenceFailure("几何中没有位点 $index")
                cropAt(rgb, site.rectified.x, site.rectified.y, side)
            }
        } finally {
            listOf(rgba, bgr, decoded, rgb, encoded).forEach(Mat::release)
            rectified.recycle()
        }
    }

    private fun cropAt(image: Mat, x: Double, y: Double, side: Double): ByteArray {
        val patch = Mat()
        val resized = Mat()
        try {
            Imgproc.getRectSubPix(image, Size(side, side), Point(x, y), patch)
            Imgproc.resize(
                patch,
                resized,
                Size(DualNetSpec.CROP_SIZE.toDouble(), DualNetSpec.CROP_SIZE.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA
            )
            val bytes = ByteArray(DualNetSpec.CROP_BYTES)
            val continuous = if (resized.isContinuous) resized else resized.clone()
            continuous.get(0, 0, bytes)
            if (continuous !== resized) continuous.release()
            return bytes
        } finally {
            patch.release()
            resized.release()
        }
    }

    /**
     * 窗口边长 = round(孔距)，至少 8 像素；与参考实现一样用"四舍六入五成双"取整。
     * 孔距由最终格点估计，参考实现用的是平差前的候选点，两者在规则网格上相差远小于 1 像素。
     */
    private fun windowSide(grid: PgGridResult): Int {
        val points = grid.sites.associateBy { it.key.rowIndex to it.key.columnIndex }
        val dx = buildList {
            for (r in 0 until grid.rows) for (c in 0 until grid.columns - 1) {
                val a = points[r to c] ?: continue
                val b = points[r to c + 1] ?: continue
                add(abs(b.rectified.x - a.rectified.x))
            }
        }
        val dy = buildList {
            for (r in 0 until grid.rows - 1) for (c in 0 until grid.columns) {
                val a = points[r to c] ?: continue
                val b = points[r + 1 to c] ?: continue
                add(abs(b.rectified.y - a.rectified.y))
            }
        }
        if (dx.isEmpty() || dy.isEmpty()) evidenceFailure("格点不足以估计孔距")
        val pitch = (median(dx) + median(dy)) / 2.0
        if (!pitch.isFinite() || pitch <= 2.0) evidenceFailure("孔距无效：$pitch")
        return max(8, Math.rint(pitch).toInt())
    }

    private fun decodeGrid(run: ArrayResultSnapshot): PgGridResult = try {
        val root = JsonParser.parseString(run.frame.frameQcJson).asJsonObject
        val pgGrid = root.get("pgGrid") ?: evidenceFailure("运行快照中没有 PG-Grid 几何")
        PgGridJsonCodec.decode(pgGrid.toString())
    } catch (error: DualNetUnavailableException) {
        throw error
    } catch (error: RuntimeException) {
        evidenceFailure("PG-Grid 几何无法解析", error)
    }

    private fun loadVerifiedEndpoint(run: ArrayResultSnapshot): Bitmap {
        val artifact = run.artifacts.firstOrNull { it.captureRole == CaptureRole.ENDPOINT.code }
            ?: evidenceFailure("没有端点原图")
        val file = File(artifact.originalPath)
        if (!file.isFile) evidenceFailure("端点原图不存在")
        val expected = artifact.checksumSha256?.trim()?.lowercase()
            ?: evidenceFailure("端点原图没有冻结摘要")
        if (sha256(file) != expected) evidenceFailure("端点原图摘要不一致")
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeFile(file.absolutePath, options) ?: evidenceFailure("端点原图无法解码")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun evidenceFailure(message: String, cause: Throwable? = null): Nothing =
        throw DualNetUnavailableException(DualNetUnavailableReason.EVIDENCE_UNAVAILABLE, message, cause)
}

package com.muc.fluocolorquant.domain.export.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.muc.fluocolorquant.R
import java.io.File
import java.util.Locale

/**
 * PDF 报告的共享绘制原语。
 *
 * 旧孔板报告、阵列报告与光谱报告用的是同一套版式（A4 竖版、绿色页眉、页码页脚、三线
 * 风格表格），此前这套原语只存在于 `ExportViewModel` 内部，导致光谱导出想要复用就只能
 * 继续待在那个 3000 行的 ViewModel 里。这里把实现收敛成唯一一份，调用方各自组织页面内容。
 *
 * 页面几何常量同样收敛于此：页宽页高一旦在不同链路各写一份，报告版式就会悄悄分叉。
 */
object PdfPageCanvas {

    /** A4 @72dpi，与 `PdfDocument.PageInfo` 的点单位一致。 */
    const val PAGE_WIDTH: Int = 595
    const val PAGE_HEIGHT: Int = 842
    const val MARGIN: Float = 40f
    const val HEADER_HEIGHT: Float = 80f
    const val FOOTER_HEIGHT: Float = 40f
    const val CONTENT_WIDTH: Float = PAGE_WIDTH - 2 * MARGIN

    /** 正文起始 Y：页眉之下再留一个页边距。 */
    const val CONTENT_START_Y: Float = MARGIN + HEADER_HEIGHT

    /** 正文可用高度：扣掉页眉、页脚与上下页边距后剩余的纵向空间。 */
    const val CONTENT_HEIGHT: Float = PAGE_HEIGHT - CONTENT_START_Y - MARGIN - FOOTER_HEIGHT

    const val TABLE_CELL_HEIGHT: Float = 30f

    /** 页眉底色，与应用主色一致。 */
    private const val HEADER_BACKGROUND: String = "#006E1C"
    private const val TABLE_HEADER_BACKGROUND: String = "#E0E0E0"
    private const val TABLE_ALTERNATE_ROW: String = "#F5F5F5"

    /** 报告内嵌缩略图的目标尺寸；超过即按 2 的幂降采样，避免整图解码撑爆内存。 */
    private const val THUMBNAIL_TARGET_PX: Int = 200

    private const val LOG_TAG: String = "PdfPageCanvas"

    /**
     * 绘制统一页眉与页脚。
     *
     * 需要 [context] 的原因只有两个：取应用图标资源，以及解析页脚的应用名与页码文案。
     * 这两项是全局品牌信息而非某条链路的领域文案，因此不走 Labels 传参。
     */
    fun drawPageHeaderFooter(
        canvas: Canvas,
        context: Context,
        projectName: String,
        chapterTitle: String,
        pageNumber: Int,
        totalPages: Int
    ) {
        val headerBackground = Paint().apply { color = Color.parseColor(HEADER_BACKGROUND) }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), HEADER_HEIGHT, headerBackground)

        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.icon2)
        var brandTextX = MARGIN
        if (logo != null && logo.height > 0) {
            val scale = 48f / logo.height
            val scaledWidth = logo.width * scale
            canvas.drawBitmap(
                logo,
                null,
                Rect(
                    MARGIN.toInt(),
                    ((HEADER_HEIGHT - 48f) / 2).toInt(),
                    (MARGIN + scaledWidth).toInt(),
                    ((HEADER_HEIGHT + 48f) / 2).toInt()
                ),
                null
            )
            brandTextX = MARGIN + scaledWidth + 16f
        }

        canvas.drawText(
            "FluoColorQuant",
            brandTextX,
            HEADER_HEIGHT / 2 + 8f,
            textPaint(20f, Color.WHITE)
        )
        canvas.drawText(
            "$projectName - $chapterTitle",
            MARGIN,
            HEADER_HEIGHT + 20f,
            textPaint(12f)
        )

        val footerPaint = textPaint(10f, Color.GRAY)
        canvas.drawText(
            context.getString(R.string.pdf_footer_app_name),
            MARGIN,
            PAGE_HEIGHT - 20f,
            footerPaint
        )
        footerPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            context.getString(R.string.pdf_page_number, pageNumber, totalPages),
            PAGE_WIDTH - MARGIN,
            PAGE_HEIGHT - 20f,
            footerPaint
        )
    }

    /** 统一文本画笔，保证全报告字体族与抗锯齿设置一致。 */
    fun textPaint(
        size: Float,
        color: Int = Color.BLACK,
        isBold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT
    ): TextPaint = TextPaint().apply {
        this.textSize = size
        this.color = color
        this.isAntiAlias = true
        this.textAlign = align
        this.typeface = Typeface.create(
            Typeface.DEFAULT,
            if (isBold) Typeface.BOLD else Typeface.NORMAL
        )
    }

    /**
     * 在 [maxWidth] 内自动折行绘制文本，返回文本结束的 Y 坐标。
     *
     * 用 [StaticLayout] 而不是 `drawText`：中英文混排、长分析物名和长单位都需要真实折行，
     * 直接 `drawText` 会让文字溢出页面右边界。
     */
    fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: TextPaint,
        maxWidth: Float
    ): Float {
        val layout = createStaticLayout(
            text = text,
            paint = paint,
            width = maxWidth.toInt().coerceAtLeast(1)
        )
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return y + layout.height
    }

    /**
     * 创建与系统版本匹配的折行布局。
     *
     * `StaticLayout.Builder` 从 API 23 才存在，而项目仍明确支持 API 22。两条分支使用相同的
     * 对齐、行距和字体上下留白语义，低版本分支只解决平台接口差异，不改变 PDF 文本内容、
     * 分页数据或科研数值。等未来最低版本统一提升到 API 23 以上后才可删除兼容构造。
     */
    @Suppress("DEPRECATION")
    private fun createStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width)
                .build()
        } else {
            StaticLayout(
                text,
                paint,
                width,
                Layout.Alignment.ALIGN_NORMAL,
                1f,
                0f,
                true
            )
        }

    /**
     * 绘制带表头、隔行底色和边框的表格，返回表格下方的 Y 坐标。
     *
     * 不做自动分页：调用方掌握章节结构，才知道该在哪一行断页。
     */
    fun drawTable(
        canvas: Canvas,
        startY: Float,
        headers: List<String>,
        data: List<List<String>>,
        columnWidths: FloatArray,
        startX: Float = MARGIN
    ): Float {
        var currentY = startY
        val totalWidth = columnWidths.sum()
        val headerBackground = Paint().apply {
            color = Color.parseColor(TABLE_HEADER_BACKGROUND)
            style = Paint.Style.FILL
        }
        val alternateRow = Paint().apply {
            color = Color.parseColor(TABLE_ALTERNATE_ROW)
            style = Paint.Style.FILL
        }
        val border = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val cellPaint = textPaint(10f)
        val headerPaint = textPaint(10f, isBold = true)

        canvas.drawRect(startX, currentY, startX + totalWidth, currentY + TABLE_CELL_HEIGHT, headerBackground)
        var currentX = startX
        headers.forEachIndexed { index, header ->
            canvas.drawText(header, currentX + 5f, currentY + TABLE_CELL_HEIGHT - 10f, headerPaint)
            currentX += columnWidths.getOrElse(index) { 0f }
        }
        currentY += TABLE_CELL_HEIGHT

        data.forEachIndexed { rowIndex, row ->
            if (rowIndex % 2 != 0) {
                canvas.drawRect(startX, currentY, startX + totalWidth, currentY + TABLE_CELL_HEIGHT, alternateRow)
            }
            currentX = startX
            row.forEachIndexed { index, cell ->
                canvas.drawText(cell, currentX + 5f, currentY + TABLE_CELL_HEIGHT - 10f, cellPaint)
                currentX += columnWidths.getOrElse(index) { 0f }
            }
            currentY += TABLE_CELL_HEIGHT
        }

        canvas.drawRect(startX, startY, startX + totalWidth, currentY, border)
        currentX = startX
        columnWidths.forEach { width ->
            canvas.drawLine(currentX, startY, currentX, currentY, border)
            currentX += width
        }
        canvas.drawLine(currentX, startY, currentX, currentY, border)
        return currentY + 10f
    }

    /** 整数值省略小数位，其余保留两位；报告里 `12` 比 `12.00` 更易读且不会误导精度。 */
    fun formatDecimal(value: Double): String {
        if (!value.isFinite()) return "-"
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }

    /**
     * 按缩略图尺寸降采样加载图片，支持文件路径与 content URI 两种来源。
     *
     * 报告里的插图只有约 200px 显示尺寸，整图解码一张手机原图会瞬间占用数十 MB。
     */
    fun loadThumbnail(context: Context, imagePath: String): Bitmap? {
        if (imagePath.isBlank()) return null
        return runCatching {
            val file = File(imagePath)
            if (file.exists() && file.canRead()) {
                decodeFileScaled(file)
            } else {
                decodeUriScaled(context, imagePath)
            }
        }.onFailure { error ->
            Log.e(LOG_TAG, "加载报告插图失败: $imagePath", error)
        }.getOrNull()
    }

    private fun decodeFileScaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, THUMBNAIL_TARGET_PX, THUMBNAIL_TARGET_PX)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun decodeUriScaled(context: Context, imagePath: String): Bitmap? {
        val uri = Uri.parse(imagePath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, THUMBNAIL_TARGET_PX, THUMBNAIL_TARGET_PX)
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    /** 取满足目标尺寸的最大 2 的幂降采样比例，与 Android 官方建议一致。 */
    fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

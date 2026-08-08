package com.muc.fluocolorquant.utils.pdf

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.muc.fluocolorquant.R

/**
 * PDF 封面模板需要展示的动态内容。
 *
 * 所有文字必须由调用方使用 strings.xml 预先完成本地化，本渲染器只负责把内容写入
 * [R.layout.pdf_cover_page]，避免96孔板和规则阵列各自维护一套不同的封面样式。
 */
data class PdfCoverPageContent(
    val title: String,
    val projectLine: String,
    val dateLine: String,
    val detectionModeLine: String,
    val overviewLines: List<String>,
    val generatedAtLine: String,
    val pageNumberLine: String
)

/**
 * 将既有 XML 封面模板渲染到 [android.graphics.pdf.PdfDocument] 的页面画布。
 *
 * Android 设备密度会把 XML 中的 dp 放大到不同像素，如果直接按设备密度绘制到
 * 595 × 842 的 PDF 页面，80dp 页眉在高密度手机上可能膨胀到两百多像素。这里创建
 * 固定 160dpi 的资源上下文，让 1dp 稳定对应 1 个 PDF 布局像素，再以 2 倍位图采样
 * 输出，兼顾模板比例一致性和文字、Logo 的清晰度。
 */
object PdfCoverPageRenderer {
    private const val RENDER_SCALE = 2
    private const val MAX_OVERVIEW_LINES = 8

    fun draw(
        context: Context,
        targetCanvas: Canvas,
        pageWidth: Int,
        pageHeight: Int,
        content: PdfCoverPageContent
    ) {
        require(pageWidth > 0 && pageHeight > 0) { "PDF_PAGE_SIZE_INVALID" }

        val layoutContext = createFixedDensityContext(context)
        val root = LayoutInflater.from(layoutContext)
            .inflate(R.layout.pdf_cover_page, null, false)

        root.findViewById<TextView>(R.id.main_title).text = content.title
        root.findViewById<TextView>(R.id.project_name_value).text = content.projectLine
        root.findViewById<TextView>(R.id.creation_date_value).text = content.dateLine
        root.findViewById<TextView>(R.id.detection_mode_value).text = content.detectionModeLine
        root.findViewById<TextView>(R.id.generation_date_footer).text = content.generatedAtLine
        root.findViewById<TextView>(R.id.page_number_footer).text = content.pageNumberLine
        bindOverviewLines(
            context = layoutContext,
            container = root.findViewById(R.id.content_overview_container),
            lines = content.overviewLines
        )

        val widthSpec = View.MeasureSpec.makeMeasureSpec(pageWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(pageHeight, View.MeasureSpec.EXACTLY)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, pageWidth, pageHeight)

        // 使用两倍尺寸中间位图渲染 XML，缩回 PDF 页面时能保留更清晰的文字边缘。
        val renderedCover = Bitmap.createBitmap(
            pageWidth * RENDER_SCALE,
            pageHeight * RENDER_SCALE,
            Bitmap.Config.ARGB_8888
        )
        try {
            val bitmapCanvas = Canvas(renderedCover).apply {
                scale(RENDER_SCALE.toFloat(), RENDER_SCALE.toFloat())
            }
            root.draw(bitmapCanvas)
            targetCanvas.drawBitmap(
                renderedCover,
                null,
                Rect(0, 0, pageWidth, pageHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
        } finally {
            renderedCover.recycle()
        }
    }

    private fun bindOverviewLines(
        context: Context,
        container: LinearLayout,
        lines: List<String>
    ) {
        container.removeAllViews()
        // 封面只承担目录作用；过多分析物的完整内容仍在后续章节中展示，避免目录压住页脚。
        lines.filter(String::isNotBlank).take(MAX_OVERVIEW_LINES).forEachIndexed { index, line ->
            container.addView(
                TextView(context).apply {
                    text = line
                    setTextColor(android.graphics.Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    includeFontPadding = false
                    setPadding(0, if (index == 0) 0 else 6, 0, 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun createFixedDensityContext(context: Context): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_DEFAULT
            // PDF 是固定版式文档，不随系统“字体大小”设置改变分页和卡片高度。
            fontScale = 1f
        }
        return context.createConfigurationContext(configuration)
    }
}

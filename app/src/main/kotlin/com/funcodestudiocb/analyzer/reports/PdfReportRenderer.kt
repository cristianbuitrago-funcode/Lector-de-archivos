package com.funcodestudiocb.analyzer.reports

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import com.funcodestudiocb.analyzer.engine.api.Diagram
import com.funcodestudiocb.analyzer.engine.api.DiagramNodeKind
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.report.Block
import com.funcodestudiocb.analyzer.engine.report.HtmlRenderer
import com.funcodestudiocb.analyzer.engine.report.Labels
import com.funcodestudiocb.analyzer.engine.report.ReportDocument
import com.funcodestudiocb.analyzer.engine.report.ReportSection
import com.funcodestudiocb.analyzer.engine.report.Tone
import java.io.OutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Informe en PDF dibujado con el lienzo nativo de Android (`PdfDocument`): texto y diagramas vectoriales, portada con la
 * marca Funcode, índice con el número de página de cada sección y numeración «Página X de Y». Se compone en dos
 * pasadas: la primera solo mide (para saber en qué página empieza cada sección y cuántas hay) y la segunda dibuja.
 */
object PdfReportRenderer {

    private const val PAGE_W = 595 // A4 en puntos
    private const val PAGE_H = 842
    private const val LEFT = 48f
    private const val RIGHT = PAGE_W - 48f
    private const val CONTENT_W = RIGHT - LEFT
    private const val TOP = 58f
    private const val BOTTOM = PAGE_H - 60f

    private val NAVY = Color.rgb(0x0A, 0x1A, 0x33)
    private val GOLD = Color.rgb(0xC9, 0xA2, 0x27)
    private val TEXT = Color.rgb(0x1B, 0x24, 0x33)
    private val MUTED = Color.rgb(0x5B, 0x6B, 0x82)
    private val LINE = Color.rgb(0xDC, 0xE3, 0xEC)
    private val PANEL = Color.rgb(0xF5, 0xF7, 0xFA)

    fun render(doc: ReportDocument, out: OutputStream) {
        val measure = Writer(doc, null, emptyMap(), 0)
        measure.run()
        val pdf = PdfDocument()
        try {
            Writer(doc, pdf, measure.sectionPages, measure.pages).run()
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private fun tone(t: Tone) = when (t) {
        Tone.GOOD -> Color.rgb(0x2E, 0x9E, 0x5B)
        Tone.INFO -> Color.rgb(0x1E, 0x90, 0xFF)
        Tone.WARNING -> Color.rgb(0xE8, 0xA3, 0x3D)
        Tone.DANGER -> Color.rgb(0xE5, 0x48, 0x4D)
        Tone.NEUTRAL -> Color.rgb(0x8A, 0x9B, 0xB5)
    }

    private fun paint(size: Float, color: Int = TEXT, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Compone el documento; con [pdf] nulo solo mide. */
    private class Writer(val doc: ReportDocument, val pdf: PdfDocument?, val tocPages: Map<String, Int>, val totalPages: Int) {
        val sectionPages = LinkedHashMap<String, Int>()
        var pages = 0
            private set
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = TOP

        private val body = paint(9.5f)
        private val small = paint(8f, MUTED)
        private val cellPaint = paint(8f)
        private val headPaint = paint(8f, MUTED, bold = true)
        private val subheading = paint(9f, MUTED, bold = true).apply { letterSpacing = 0.06f }
        private val sectionPaint = paint(16f, NAVY, bold = true)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        fun run() {
            cover()
            contents()
            doc.sections.forEachIndexed { i, s -> section(s, first = i == 0) }
            finishPage()
        }

        // ------------------------------------------------------------ páginas

        private fun newPage() {
            finishPage()
            pages++
            if (pdf != null) {
                val p = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pages).create())
                page = p
                canvas = p.canvas
            }
            y = TOP
            if (pages > 1) footer()
        }

        private fun finishPage() {
            page?.let { pdf?.finishPage(it) }
            page = null
            canvas = null
        }

        private fun ensure(height: Float) {
            if (page == null && pdf != null || y + height > BOTTOM) newPage()
        }

        private fun footer() {
            val c = canvas ?: return
            stroke.color = LINE
            stroke.strokeWidth = 0.6f
            c.drawLine(LEFT, PAGE_H - 44f, RIGHT, PAGE_H - 44f, stroke)
            val p = paint(7.5f, MUTED)
            c.drawText(TextUtils.ellipsize("Funcode Code Analyzer · ${doc.subtitle}", p, CONTENT_W * 0.7f, TextUtils.TruncateAt.MIDDLE).toString(), LEFT, PAGE_H - 30f, p)
            if (totalPages > 0) {
                val label = "Página $pages de $totalPages"
                c.drawText(label, RIGHT - p.measureText(label), PAGE_H - 30f, p)
            }
        }

        // ------------------------------------------------------------ texto

        private fun layout(text: CharSequence, paint: TextPaint, width: Float): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(10))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(1f, 1.12f)
                .setIncludePad(false)
                .build()

        /** Texto que puede ocupar varias páginas: se parte por líneas. */
        private fun text(content: CharSequence, paint: TextPaint, x: Float = LEFT, width: Float = CONTENT_W, after: Float = 6f) {
            val l = layout(content, paint, width)
            var line = 0
            while (line < l.lineCount) {
                ensure(l.getLineBottom(line) - l.getLineTop(line).toFloat())
                var last = line
                while (last + 1 < l.lineCount && y + (l.getLineBottom(last + 1) - l.getLineTop(line)) <= BOTTOM) last++
                val top = l.getLineTop(line).toFloat()
                val bottom = l.getLineBottom(last).toFloat()
                canvas?.withTranslation(x, y - top) {
                    clipRect(0f, top, width, bottom)
                    l.draw(this)
                }
                y += bottom - top
                line = last + 1
            }
            y += after
        }

        private fun drawLayout(l: StaticLayout, x: Float, top: Float) {
            canvas?.withTranslation(x, top) { l.draw(this) }
        }

        // ------------------------------------------------------------ portada e índice

        private fun cover() {
            newPage()
            val c = canvas
            if (c != null) {
                fill.color = NAVY
                c.drawRect(0f, 0f, PAGE_W.toFloat(), 360f, fill)
                fill.color = GOLD
                c.drawRect(0f, 360f, PAGE_W.toFloat(), 365f, fill)
                val brand = paint(10f, GOLD, bold = true).apply { letterSpacing = 0.3f }
                c.drawText("FUNCODE STUDIO CB", LEFT, 120f, brand)
                drawLayout(layout(doc.title, paint(26f, Color.WHITE, bold = true), CONTENT_W), LEFT, 140f)
                drawLayout(layout(doc.subtitle, paint(16f, Color.rgb(0xE8, 0xEE, 0xF8)), CONTENT_W), LEFT, 200f)
                val dates = buildString {
                    doc.meta.analyzedAtEpochMs?.let { append("Análisis del ").append(Labels.dateTime(it, doc.meta.zone)).append('\n') }
                    append("Informe del ").append(Labels.dateTime(doc.meta.generatedAtEpochMs, doc.meta.zone))
                }
                drawLayout(layout(dates, paint(9f, Color.rgb(0x9F, 0xB0, 0xC8)), CONTENT_W), LEFT, 250f)
                doc.sections.flatMap { it.blocks }.filterIsInstance<Block.Gauge>().firstOrNull()?.let { g ->
                    val label = "Índice de mantenimiento: " + (g.score?.let { "$it/100 · ${g.label}" } ?: g.label)
                    val p = paint(10.5f, Color.WHITE, bold = true)
                    val w = p.measureText(label) + 28f
                    fill.color = Color.rgb(0x10, 0x28, 0x4D)
                    c.drawRoundRect(RectF(LEFT, 300f, LEFT + w, 328f), 14f, 14f, fill)
                    fill.color = tone(g.tone)
                    c.drawCircle(LEFT + 12f, 314f, 4f, fill)
                    c.drawText(label, LEFT + 22f, 318f, p)
                }
                val sections = doc.sections.size
                drawLayout(
                    layout(
                        "Contenido: ${sections} apartados. Análisis estático hecho en el propio teléfono: el código no se ejecutó ni se envió a ningún servicio. " +
                            "Los posibles secretos aparecen enmascarados.",
                        paint(9f, MUTED), CONTENT_W,
                    ),
                    LEFT, PAGE_H - 140f,
                )
                c.drawText("Generado por Funcode Code Analyzer · versión ${doc.meta.appVersion}", LEFT, PAGE_H - 70f, paint(8f, MUTED))
            }
        }

        private fun contents() {
            newPage()
            text("Índice", paint(18f, NAVY, bold = true), after = 14f)
            val left = paint(10f)
            val right = paint(10f, MUTED)
            doc.sections.forEach { s ->
                ensure(20f)
                val label = if (s.number.all { it.isDigit() }) "${s.number}. ${s.title}" else "Anexo ${s.number}. ${s.title}"
                val number = tocPages[s.id]?.toString() ?: ""
                canvas?.let { c ->
                    c.drawText(label, LEFT, y + 12f, left)
                    val nw = right.measureText(number)
                    c.drawText(number, RIGHT - nw, y + 12f, right)
                    stroke.color = LINE
                    stroke.strokeWidth = 0.8f
                    stroke.pathEffect = DashPathEffect(floatArrayOf(1.5f, 3f), 0f)
                    c.drawLine(LEFT + left.measureText(label) + 6f, y + 12f, RIGHT - nw - 6f, y + 12f, stroke)
                    stroke.pathEffect = null
                }
                y += 20f
            }
        }

        // ------------------------------------------------------------ secciones y bloques

        private fun section(s: ReportSection, first: Boolean) {
            // El título no se queda solo al pie de una página: va con el principio de su primer bloque.
            if (first) newPage() else ensure(14f + TITLE_H + keepWith(s.blocks.firstOrNull()))
            if (!first) y += 14f
            sectionPages[s.id] = pages
            val title = if (s.number.all { it.isDigit() }) "${s.number}. ${s.title}" else "Anexo ${s.number}. ${s.title}"
            text(title, sectionPaint, after = 3f)
            canvas?.let { c ->
                fill.color = GOLD
                c.drawRect(LEFT, y, LEFT + 42f, y + 2.5f, fill)
            }
            y += 12f
            s.blocks.forEachIndexed { i, b -> block(b, s.blocks.getOrNull(i + 1)) }
        }

        /** Alto mínimo para empezar [b] en la página actual (lo que un título necesita debajo para no quedarse solo). */
        private fun keepWith(b: Block?): Float = when (b) {
            null -> 0f
            is Block.Gauge -> GAUGE_H
            is Block.Figure -> figureHeight(b.diagram) + 34f
            is Block.Table -> 44f
            is Block.Callout -> layout(b.text, body, CONTENT_W - 28f).height + 24f
            is Block.Heading -> 30f + MIN_LINES
            else -> MIN_LINES
        }.coerceAtMost(BOTTOM - TOP - TITLE_H - 14f)

        private fun block(b: Block, next: Block?) {
            when (b) {
                is Block.Heading -> {
                    ensure(30f + keepWith(next))
                    y += 6f
                    text(b.text.uppercase(), subheading, after = 4f)
                }
                is Block.Paragraph -> text(b.text, body)
                is Block.Statements -> b.items.forEachIndexed { i, st ->
                    val sb = SpannableStringBuilder(st.text).append("  ")
                    val start = sb.length
                    sb.append(st.ref)
                    sb.setSpan(ForegroundColorSpan(GOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.85f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val marker = if (b.numbered) "${i + 1}." else "•"
                    ensure(14f)
                    canvas?.drawText(marker, LEFT + 2f, y + 9.5f, body)
                    text(sb, body, LEFT + 18f, CONTENT_W - 18f, after = 4f)
                }
                is Block.Facts -> b.items.forEach { (k, v) ->
                    val kl = layout(k, paint(9f, MUTED), CONTENT_W * 0.34f)
                    val vl = layout(v, paint(9f), CONTENT_W * 0.64f)
                    val h = maxOf(kl.height, vl.height) + 5f
                    ensure(h)
                    drawLayout(kl, LEFT, y)
                    drawLayout(vl, LEFT + CONTENT_W * 0.36f, y)
                    y += h
                }
                is Block.Table -> table(b)
                is Block.Bullets -> b.items.forEach {
                    ensure(14f)
                    canvas?.drawText("•", LEFT + 4f, y + 9.5f, body)
                    text(it, body, LEFT + 16f, CONTENT_W - 16f, after = 3f)
                }
                is Block.Callout -> {
                    val l = layout(b.text, body, CONTENT_W - 28f)
                    val h = l.height + 16f
                    ensure(h)
                    canvas?.let { c ->
                        fill.color = PANEL
                        c.drawRoundRect(RectF(LEFT, y, RIGHT, y + h), 6f, 6f, fill)
                        fill.color = tone(b.tone)
                        c.drawRect(LEFT, y, LEFT + 4f, y + h, fill)
                    }
                    drawLayout(l, LEFT + 16f, y + 8f)
                    y += h + 8f
                }
                is Block.Bars -> b.items.forEach { bar ->
                    ensure(16f)
                    canvas?.let { c ->
                        val label = TextUtils.ellipsize(bar.label, cellPaint, 140f, TextUtils.TruncateAt.END).toString()
                        c.drawText(label, LEFT, y + 10f, cellPaint)
                        fill.color = LINE
                        c.drawRoundRect(RectF(LEFT + 150f, y + 2f, LEFT + 400f, y + 12f), 3f, 3f, fill)
                        fill.color = tone(bar.tone)
                        val w = if (bar.max <= 0) 0f else 250f * bar.value / bar.max
                        c.drawRoundRect(RectF(LEFT + 150f, y + 2f, LEFT + 150f + maxOf(w, 2f), y + 12f), 3f, 3f, fill)
                        c.drawText(bar.display, LEFT + 408f, y + 10f, cellPaint)
                    }
                    y += 16f
                }
                is Block.Gauge -> gauge(b)
                is Block.Figure -> figure(b.diagram, b.caption)
            }
        }

        private fun table(t: Block.Table) {
            val cols = t.headers.size
            if (cols == 0) return
            val w = TableColumns.widths(t.headers, t.rows, CONTENT_W, headPaint::measureText, cellPaint::measureText)

            fun row(cells: List<String>, paint: TextPaint): Pair<List<StaticLayout>, Float> {
                val ls = (0 until cols).map { i -> layout(cells.getOrElse(i) { "" }.take(MAX_CELL), paint, w[i] - TableColumns.PADDING) }
                return ls to (ls.maxOf { it.height } + 7f)
            }

            fun header() {
                val (ls, h) = row(t.headers, headPaint)
                ensure(h + 14f)
                canvas?.let { c ->
                    fill.color = PANEL
                    c.drawRect(LEFT, y, RIGHT, y + h, fill)
                }
                var x = LEFT
                ls.forEachIndexed { i, l -> drawLayout(l, x + 4f, y + 3.5f); x += w[i] }
                y += h
            }

            header()
            t.rows.forEachIndexed { r, cells ->
                val (ls, h) = row(cells, cellPaint)
                if (y + h > BOTTOM) {
                    newPage()
                    header()
                }
                canvas?.let { c ->
                    t.tones?.getOrNull(r)?.takeIf { it != Tone.NEUTRAL }?.let { tone ->
                        fill.color = tone(tone)
                        c.drawRect(LEFT, y, LEFT + 2.5f, y + h, fill)
                    }
                    stroke.color = LINE
                    stroke.strokeWidth = 0.6f
                    c.drawLine(LEFT, y + h, RIGHT, y + h, stroke)
                }
                var x = LEFT
                ls.forEachIndexed { i, l -> drawLayout(l, x + 4f, y + 3.5f); x += w[i] }
                y += h
            }
            y += 4f
            t.note?.let { text(it, small) }
            y += 4f
        }

        private fun gauge(g: Block.Gauge) {
            ensure(GAUGE_H)
            canvas?.let { c ->
                val box = RectF(LEFT + 6f, y + 6f, LEFT + 80f, y + 80f)
                stroke.strokeWidth = 9f
                stroke.color = LINE
                c.drawArc(box, 0f, 360f, false, stroke)
                g.score?.let { s ->
                    stroke.color = tone(g.tone)
                    c.drawArc(box, -90f, 360f * s / 100, false, stroke)
                }
                val p = paint(22f, TEXT, bold = true)
                val label = g.score?.toString() ?: "—"
                c.drawText(label, box.centerX() - p.measureText(label) / 2, box.centerY() + 8f, p)
                c.drawText(g.label, LEFT + 100f, y + 34f, paint(15f, tone(g.tone), bold = true))
                drawLayout(layout(g.caption, small, CONTENT_W - 100f), LEFT + 100f, y + 44f)
            }
            y += 94f
        }

        private fun figureScale(d: Diagram) = minOf(CONTENT_W / d.width, (BOTTOM - TOP - 60f) / d.height, 1.15f)

        private fun figureHeight(d: Diagram) = d.height * figureScale(d)

        private fun figure(d: Diagram, caption: String) {
            val scale = figureScale(d)
            val h = figureHeight(d)
            ensure(h + 34f)
            canvas?.let { c ->
                fill.color = PANEL
                c.drawRoundRect(RectF(LEFT, y, LEFT + d.width * scale, y + h), 6f, 6f, fill)
                c.withTranslation(LEFT, y) {
                    scale(scale, scale)
                    drawDiagram(this, d)
                }
            }
            y += h + 4f
            d.note?.let { text(it, small, after = 2f) }
            text(caption, small, after = 8f)
        }

        private fun drawDiagram(c: Canvas, d: Diagram) {
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f }
            val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            d.edges.forEach { e ->
                val color = when {
                    e.inverted -> Color.rgb(0xE8, 0x71, 0x1C)
                    e.cycle -> GOLD
                    else -> MUTED
                }
                edge.color = color
                head.color = color
                edge.strokeWidth = if (e.count > 5) 2f else 1.4f
                edge.pathEffect = when (e.confidence) {
                    RelationConfidence.VERIFIED -> null
                    RelationConfidence.PROBABLE -> DashPathEffect(floatArrayOf(7f, 4f), 0f)
                    else -> DashPathEffect(floatArrayOf(2f, 4f), 0f)
                }
                val pts = e.points
                if (pts.size < 4) return@forEach
                val path = Path().apply {
                    moveTo(pts[0], pts[1])
                    var i = 2
                    while (i + 1 < pts.size) {
                        lineTo(pts[i], pts[i + 1])
                        i += 2
                    }
                }
                c.drawPath(path, edge)
                val n = pts.size
                val angle = atan2(pts[n - 1] - pts[n - 3], pts[n - 2] - pts[n - 4])
                val tipX = pts[n - 2]
                val tipY = pts[n - 1]
                val arrow = Path().apply {
                    moveTo(tipX, tipY)
                    lineTo(tipX - 9f * cos(angle - 0.4f), tipY - 9f * sin(angle - 0.4f))
                    lineTo(tipX - 9f * cos(angle + 0.4f), tipY - 9f * sin(angle + 0.4f))
                    close()
                }
                c.drawPath(arrow, head)
            }
            val label = paint(12f, TEXT, bold = true)
            val detail = paint(10f, MUTED)
            d.nodes.forEach { n ->
                val color = n.layer?.let { HtmlRenderer.LAYER_COLORS.getValue(it).toColorInt() } ?: when (n.kind) {
                    DiagramNodeKind.EXTERNAL_SERVICE -> Color.rgb(0x2B, 0xA8, 0xD8)
                    DiagramNodeKind.PACKAGE -> n.severity?.let { if (it.ordinal <= Severity.HIGH.ordinal) tone(Tone.DANGER) else if (it == Severity.MEDIUM) tone(Tone.WARNING) else tone(Tone.NEUTRAL) }
                        ?: tone(Tone.NEUTRAL)
                    DiagramNodeKind.PROJECT -> NAVY
                    else -> MUTED
                }
                val box = RectF(n.x, n.y, n.x + n.width, n.y + n.height)
                fill.color = Color.WHITE
                c.drawRoundRect(box, 8f, 8f, fill)
                stroke.color = color
                stroke.strokeWidth = 2f
                c.drawRoundRect(box, 8f, 8f, stroke)
                fill.color = color
                c.drawRoundRect(RectF(n.x, n.y, n.x + 6f, n.y + n.height), 3f, 3f, fill)
                val width = n.width - 20f
                c.drawText(TextUtils.ellipsize(n.label, label, width, TextUtils.TruncateAt.END).toString(), n.x + 12f, n.y + 19f, label)
                if (n.height >= 40f) n.detail?.let { c.drawText(TextUtils.ellipsize(it, detail, width, TextUtils.TruncateAt.END).toString(), n.x + 12f, n.y + 35f, detail) }
            }
        }
    }

    private const val MAX_CELL = 600
    private const val TITLE_H = 40f // título de sección con su subrayado
    private const val GAUGE_H = 94f
    private const val MIN_LINES = 30f // dos líneas de texto
}

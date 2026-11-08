package com.funcodestudiocb.analyzer.engine.report

import com.funcodestudiocb.analyzer.engine.api.ArchLayer
import com.funcodestudiocb.analyzer.engine.api.Diagram
import com.funcodestudiocb.analyzer.engine.api.DiagramNode
import com.funcodestudiocb.analyzer.engine.api.DiagramNodeKind
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.Severity
import java.security.MessageDigest
import java.util.Base64

/**
 * Informe en HTML: un único archivo autocontenido, **sin JavaScript** y sin recursos externos, con una política de
 * seguridad de contenidos que solo permite su propia hoja de estilos (por su huella SHA-256). Todo el texto del proyecto
 * se escapa; los diagramas son SVG en línea con atributos de presentación (sin atributos `style`, que la política
 * bloquearía). Las secciones se pliegan con `<details>`.
 */
object HtmlRenderer {

    fun render(doc: ReportDocument): String {
        val css = CSS.trimIndent()
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(css.toByteArray(Charsets.UTF_8)))
        val sb = StringBuilder(64 * 1024)
        sb.append("<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'sha256-").append(hash)
            .append("'; img-src data:; base-uri 'none'; form-action 'none'\">\n")
        sb.append("<meta name=\"referrer\" content=\"no-referrer\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<meta name=\"generator\" content=\"Funcode Code Analyzer\">\n")
        sb.append("<title>").append(esc("${doc.title} · ${doc.subtitle}")).append("</title>\n")
        sb.append("<style>").append(css).append("</style>\n</head>\n<body>\n")
        cover(sb, doc)
        toc(sb, doc)
        sb.append("<main>\n")
        doc.sections.forEach { section(sb, it) }
        sb.append("</main>\n<footer>Generado sin conexión por Funcode Code Analyzer · Funcode Studio CB. ")
            .append("Los posibles secretos aparecen enmascarados. Análisis estático: el código no se ejecutó.</footer>\n")
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    private fun cover(sb: StringBuilder, doc: ReportDocument) {
        sb.append("<header class=\"cover\">\n<p class=\"brand\">FUNCODE STUDIO CB</p>\n<h1>").append(esc(doc.title)).append("</h1>\n")
        sb.append("<p class=\"project\">").append(esc(doc.subtitle)).append("</p>\n<p class=\"when\">")
        doc.meta.analyzedAtEpochMs?.let { sb.append("Análisis del ").append(esc(Labels.dateTime(it, doc.meta.zone))).append(" · ") }
        sb.append("Informe del ").append(esc(Labels.dateTime(doc.meta.generatedAtEpochMs, doc.meta.zone))).append("</p>\n")
        doc.sections.flatMap { it.blocks }.filterIsInstance<Block.Gauge>().firstOrNull()?.let { g ->
            sb.append("<p class=\"badge ").append(tone(g.tone)).append("\">Índice de mantenimiento: ")
                .append(g.score?.let { "$it/100 · ${esc(g.label)}" } ?: esc(g.label)).append("</p>\n")
        }
        sb.append("</header>\n")
    }

    private fun toc(sb: StringBuilder, doc: ReportDocument) {
        sb.append("<nav class=\"toc\"><h2>Índice</h2><ul>\n")
        doc.sections.forEach { s ->
            sb.append("<li><a href=\"#").append(s.id).append("\">").append(esc(if (s.number.all { it.isDigit() }) "${s.number}. ${s.title}" else "Anexo ${s.number}. ${s.title}"))
                .append("</a></li>\n")
        }
        sb.append("</ul></nav>\n")
    }

    private fun section(sb: StringBuilder, s: ReportSection) {
        val heading = if (s.number.all { it.isDigit() }) "${s.number}. ${s.title}" else "Anexo ${s.number}. ${s.title}"
        sb.append("<section id=\"").append(s.id).append("\">\n<details open>\n<summary><h2>").append(esc(heading)).append("</h2></summary>\n")
        s.blocks.forEach { block(sb, it) }
        sb.append("</details>\n</section>\n")
    }

    private fun block(sb: StringBuilder, b: Block) {
        when (b) {
            is Block.Heading -> sb.append("<h3>").append(esc(b.text)).append("</h3>\n")
            is Block.Paragraph -> sb.append("<p>").append(esc(b.text)).append("</p>\n")
            is Block.Statements -> {
                sb.append(if (b.numbered) "<ol class=\"statements\">\n" else "<ul class=\"statements\">\n")
                b.items.forEach { st ->
                    sb.append("<li>").append(esc(st.text)).append(" <a class=\"ref\" href=\"#").append(anchor(st.ref)).append("\">")
                        .append(esc(st.ref)).append("</a></li>\n")
                }
                sb.append(if (b.numbered) "</ol>\n" else "</ul>\n")
            }
            is Block.Facts -> {
                sb.append("<dl class=\"facts\">\n")
                b.items.forEach { (k, v) -> sb.append("<dt>").append(esc(k)).append("</dt><dd>").append(esc(v)).append("</dd>\n") }
                sb.append("</dl>\n")
            }
            is Block.Table -> {
                sb.append("<div class=\"scroll\"><table>\n<thead><tr>")
                b.headers.forEach { sb.append("<th>").append(esc(it)).append("</th>") }
                sb.append("</tr></thead>\n<tbody>\n")
                b.rows.forEachIndexed { i, row ->
                    val t = b.tones?.getOrNull(i)
                    sb.append(if (t != null && t != Tone.NEUTRAL) "<tr class=\"${tone(t)}\">" else "<tr>")
                    row.forEach { sb.append("<td>").append(cell(it)).append("</td>") }
                    sb.append("</tr>\n")
                }
                sb.append("</tbody></table></div>\n")
                b.note?.let { sb.append("<p class=\"note\">").append(esc(it)).append("</p>\n") }
            }
            is Block.Bullets -> {
                sb.append("<ul>\n")
                b.items.forEach { sb.append("<li>").append(esc(it)).append("</li>\n") }
                sb.append("</ul>\n")
            }
            is Block.Callout -> sb.append("<p class=\"callout ").append(tone(b.tone)).append("\">").append(esc(b.text)).append("</p>\n")
            is Block.Bars -> bars(sb, b)
            is Block.Gauge -> gauge(sb, b)
            is Block.Figure -> {
                sb.append("<figure>\n")
                svg(sb, b.diagram)
                sb.append("<figcaption>").append(esc(b.caption)).append("</figcaption>\n</figure>\n")
            }
        }
    }

    private fun bars(sb: StringBuilder, b: Block.Bars) {
        val rowH = 26
        val height = b.items.size * rowH
        sb.append("<svg class=\"bars\" viewBox=\"0 0 720 ").append(height).append("\" width=\"100%\" role=\"img\" aria-label=\"Gráfico de barras\">\n")
        b.items.forEachIndexed { i, bar ->
            val y = i * rowH
            val w = if (bar.max <= 0) 0.0 else 300.0 * bar.value / bar.max
            sb.append("<text x=\"0\" y=\"").append(y + 17).append("\" class=\"bar-label\">").append(esc(bar.label.take(32))).append("</text>")
            sb.append("<rect x=\"170\" y=\"").append(y + 5).append("\" width=\"300\" height=\"16\" rx=\"3\" fill=\"#E6EBF2\"/>")
            sb.append("<rect x=\"170\" y=\"").append(y + 5).append("\" width=\"").append(fmt(maxOf(w, 2.0))).append("\" height=\"16\" rx=\"3\" fill=\"")
                .append(fill(bar.tone)).append("\"/>")
            sb.append("<text x=\"480\" y=\"").append(y + 17).append("\" class=\"bar-value\">").append(esc(bar.display)).append("</text>\n")
        }
        sb.append("</svg>\n")
    }

    private fun gauge(sb: StringBuilder, g: Block.Gauge) {
        val score = g.score
        sb.append("<div class=\"gauge\"><svg viewBox=\"0 0 120 120\" width=\"120\" height=\"120\" role=\"img\" aria-label=\"Índice ")
            .append(score?.toString() ?: "no evaluado").append("\">")
        sb.append("<circle cx=\"60\" cy=\"60\" r=\"50\" fill=\"none\" stroke=\"#E6EBF2\" stroke-width=\"12\"/>")
        if (score != null) {
            val circumference = 2 * Math.PI * 50
            sb.append("<circle cx=\"60\" cy=\"60\" r=\"50\" fill=\"none\" stroke=\"").append(fill(g.tone)).append("\" stroke-width=\"12\" stroke-dasharray=\"")
                .append(fmt(circumference * score / 100)).append(' ').append(fmt(circumference)).append("\" transform=\"rotate(-90 60 60)\"/>")
        }
        sb.append("<text x=\"60\" y=\"68\" text-anchor=\"middle\" class=\"gauge-value\">").append(score?.toString() ?: "—").append("</text></svg>")
        sb.append("<div><p class=\"gauge-label ").append(tone(g.tone)).append("\">").append(esc(g.label)).append("</p><p class=\"note\">")
            .append(esc(g.caption)).append("</p></div></div>\n")
    }

    // ------------------------------------------------------------------ diagramas

    private fun svg(sb: StringBuilder, d: Diagram) {
        sb.append("<div class=\"scroll\"><svg class=\"diagram\" viewBox=\"0 0 ").append(fmt(d.width.toDouble())).append(' ').append(fmt(d.height.toDouble()))
            .append("\" width=\"").append(fmt(minOf(d.width.toDouble(), 1600.0))).append("\" role=\"img\" aria-label=\"").append(esc(d.title)).append("\">\n")
        sb.append("<defs>")
        listOf("n" to "#5B6B82", "i" to "#E8711C", "c" to "#C9A227").forEach { (id, color) ->
            sb.append("<marker id=\"a-").append(id).append("\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\">")
                .append("<path d=\"M0,0 L10,5 L0,10 z\" fill=\"").append(color).append("\"/></marker>")
        }
        sb.append("</defs>\n")
        d.edges.forEach { e ->
            val (id, color) = when {
                e.inverted -> "i" to "#E8711C"
                e.cycle -> "c" to "#C9A227"
                else -> "n" to "#5B6B82"
            }
            sb.append("<polyline fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"").append(if (e.count > 5) "2" else "1.4").append('"')
            when (e.confidence) {
                RelationConfidence.VERIFIED -> {}
                RelationConfidence.PROBABLE -> sb.append(" stroke-dasharray=\"7 4\"")
                else -> sb.append(" stroke-dasharray=\"2 4\"")
            }
            sb.append(" marker-end=\"url(#a-").append(id).append(")\" points=\"")
            var i = 0
            while (i + 1 < e.points.size) {
                if (i > 0) sb.append(' ')
                sb.append(fmt(e.points[i].toDouble())).append(',').append(fmt(e.points[i + 1].toDouble()))
                i += 2
            }
            sb.append("\"><title>").append(esc("${e.from} → ${e.to}: ${Labels.number(e.count)} conexiones")).append("</title></polyline>\n")
        }
        d.nodes.forEach { n ->
            val color = nodeColor(n)
            sb.append("<g><title>").append(esc(n.detail?.let { "${n.label} · $it" } ?: n.label)).append("</title>")
            sb.append("<rect x=\"").append(fmt(n.x.toDouble())).append("\" y=\"").append(fmt(n.y.toDouble())).append("\" width=\"").append(fmt(n.width.toDouble()))
                .append("\" height=\"").append(fmt(n.height.toDouble())).append("\" rx=\"8\" fill=\"#FFFFFF\" stroke=\"").append(color).append("\" stroke-width=\"2\"/>")
            sb.append("<rect x=\"").append(fmt(n.x.toDouble())).append("\" y=\"").append(fmt(n.y.toDouble())).append("\" width=\"6\" height=\"")
                .append(fmt(n.height.toDouble())).append("\" rx=\"3\" fill=\"").append(color).append("\"/>")
            val maxChars = ((n.width - 20) / 7).toInt().coerceAtLeast(4)
            sb.append("<text x=\"").append(fmt(n.x + 12.0)).append("\" y=\"").append(fmt(n.y + 20.0)).append("\" class=\"node-label\">")
                .append(esc(ellipsize(n.label, maxChars))).append("</text>")
            n.detail?.let { detail ->
                if (n.height >= 40) {
                    sb.append("<text x=\"").append(fmt(n.x + 12.0)).append("\" y=\"").append(fmt(n.y + 36.0)).append("\" class=\"node-detail\">")
                        .append(esc(ellipsize(detail, maxChars + 4))).append("</text>")
                }
            }
            sb.append("</g>\n")
        }
        sb.append("</svg></div>\n")
        d.note?.let { sb.append("<p class=\"note\">").append(esc(it)).append("</p>\n") }
    }

    private fun nodeColor(n: DiagramNode): String = n.layer?.let { LAYER_COLORS.getValue(it) } ?: when (n.kind) {
        DiagramNodeKind.EXTERNAL_SERVICE -> "#4FC3F7"
        DiagramNodeKind.PACKAGE -> n.severity?.let { if (it.ordinal <= Severity.HIGH.ordinal) "#E5484D" else if (it == Severity.MEDIUM) "#E8A33D" else "#8A9BB5" } ?: "#8A9BB5"
        DiagramNodeKind.PROJECT -> "#0A1A33"
        else -> "#5B6B82"
    }

    /** Los mismos colores de capa que la pantalla Arquitectura. */
    val LAYER_COLORS = mapOf(
        ArchLayer.FRONTEND to "#1E90FF",
        ArchLayer.API to "#2BA8D8",
        ArchLayer.CONTROLLER to "#C9A227",
        ArchLayer.SERVICE to "#2E9E5B",
        ArchLayer.MODEL to "#8B5CF6",
        ArchLayer.DATABASE to "#E0479E",
        ArchLayer.SHARED to "#8A9BB5",
        ArchLayer.CONFIG to "#9FB0C8",
        ArchLayer.TEST to "#8A9BB5",
    )

    private fun ellipsize(text: String, max: Int) = if (text.length <= max) text else text.take(maxOf(1, max - 1)) + "…"

    // ------------------------------------------------------------------ utilidades

    /** Celda de tabla: las rutas pueden partirse después de cada `/` en lugar de en mitad de una palabra. */
    private fun cell(text: String) = esc(text).replace("/", "/<wbr>")

    /** Escapa texto para HTML y atributos (y pone un espacio duro antes de `%`, como se escribe en español). */
    fun esc(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text.replace(" %", "\u00A0%")) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> if (c.isISOControl() && c != '\n' && c != '\t') sb.append('�') else sb.append(c)
            }
        }
        return sb.toString()
    }

    /** `§11` → `s11`; `Anexo A` → `anexo-a`. */
    fun anchor(ref: String): String = if (ref.startsWith("§")) "s" + ref.removePrefix("§") else ref.lowercase().replace(' ', '-')

    private fun tone(t: Tone) = "tone-" + t.name.lowercase()

    private fun fill(t: Tone) = when (t) {
        Tone.GOOD -> "#2E9E5B"
        Tone.INFO -> "#1E90FF"
        Tone.WARNING -> "#E8A33D"
        Tone.DANGER -> "#E5484D"
        Tone.NEUTRAL -> "#8A9BB5"
    }

    private fun fmt(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }

    private const val CSS = """
        :root { --navy: #0A1A33; --gold: #C9A227; --electric: #1E90FF; --text: #1B2433; --muted: #5B6B82; --line: #DCE3EC; --bg: #FFFFFF; --panel: #F5F7FA; }
        @media (prefers-color-scheme: dark) { :root { --text: #E8EEF8; --muted: #9FB0C8; --line: #26395A; --bg: #05070D; --panel: #0A1A33; } }
        * { box-sizing: border-box; }
        body { margin: 0; font: 15px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; color: var(--text); background: var(--bg); }
        header.cover { background: var(--navy); color: #E8EEF8; padding: 40px 24px 32px; border-bottom: 4px solid var(--gold); }
        .brand { letter-spacing: .3em; color: var(--gold); font-weight: 700; font-size: 13px; margin: 0 0 12px; }
        header.cover h1 { margin: 0 0 8px; font-size: 30px; }
        .project { font-size: 20px; margin: 0 0 8px; word-break: break-all; }
        .when { color: #9FB0C8; margin: 0 0 16px; font-size: 13px; }
        .badge { display: inline-block; padding: 6px 12px; border-radius: 16px; background: #10284D; font-weight: 600; margin: 0; }
        nav.toc, main, footer { max-width: 1100px; margin: 0 auto; padding: 0 24px; }
        nav.toc { padding-top: 24px; }
        nav.toc ul { columns: 2; column-gap: 32px; padding-left: 0; list-style: none; }
        nav.toc li { margin: 2px 0; }
        nav.toc a { color: var(--electric); text-decoration: none; }
        section { border-top: 1px solid var(--line); padding: 8px 0 16px; }
        summary { cursor: pointer; list-style-position: outside; }
        summary h2 { display: inline; font-size: 22px; color: var(--text); }
        summary::marker { color: var(--gold); }
        h3 { font-size: 16px; margin: 20px 0 8px; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; }
        .facts { display: grid; grid-template-columns: minmax(160px, 30%) 1fr; gap: 4px 16px; margin: 12px 0; }
        .facts dt { color: var(--muted); }
        .facts dd { margin: 0; word-break: break-word; }
        .scroll { overflow-x: auto; }
        table { border-collapse: collapse; width: 100%; margin: 8px 0; font-size: 14px; }
        th { text-align: left; background: var(--panel); color: var(--muted); font-weight: 600; white-space: nowrap; }
        th, td { padding: 6px 8px; border-bottom: 1px solid var(--line); vertical-align: top; overflow-wrap: break-word; }
        tr.tone-danger td:first-child { border-left: 4px solid #E5484D; }
        tr.tone-warning td:first-child { border-left: 4px solid #E8A33D; }
        tr.tone-good td:first-child { border-left: 4px solid #2E9E5B; }
        tr.tone-info td:first-child { border-left: 4px solid #1E90FF; }
        .note { color: var(--muted); font-size: 13px; }
        .callout { padding: 10px 14px; border-radius: 8px; background: var(--panel); border-left: 4px solid var(--muted); }
        .callout.tone-danger { border-left-color: #E5484D; } .callout.tone-warning { border-left-color: #E8A33D; }
        .callout.tone-good { border-left-color: #2E9E5B; } .callout.tone-info { border-left-color: #1E90FF; }
        .statements li { margin: 6px 0; }
        a.ref { font-size: 12px; color: var(--gold); text-decoration: none; font-weight: 600; white-space: nowrap; }
        svg text { fill: var(--text); }
        .bars { max-width: 720px; }
        .bar-label, .bar-value { font-size: 13px; }
        .gauge { display: flex; gap: 20px; align-items: center; margin: 12px 0; }
        .gauge-value { font-size: 34px; font-weight: 700; }
        .gauge-label { font-size: 20px; font-weight: 700; margin: 0; }
        .tone-good.gauge-label { color: #2E9E5B; } .tone-warning.gauge-label { color: #E8A33D; } .tone-danger.gauge-label { color: #E5484D; }
        figure { margin: 16px 0; }
        figcaption { color: var(--muted); font-size: 13px; }
        .diagram { max-width: 100%; height: auto; background: var(--panel); border-radius: 8px; }
        .diagram text { fill: #1B2433; }
        .node-label { font-size: 13px; font-weight: 600; }
        .node-detail { font-size: 11px; }
        footer { color: var(--muted); font-size: 12px; padding: 24px; border-top: 1px solid var(--line); margin-top: 24px; }
        @media print { header.cover { background: #FFFFFF; color: var(--navy); } .when { color: var(--muted); } details > summary { list-style: none; } section { break-inside: auto; } }
    """
}

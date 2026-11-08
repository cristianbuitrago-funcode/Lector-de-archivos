package com.funcodestudiocb.analyzer.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.funcodestudiocb.analyzer.engine.report.Block
import com.funcodestudiocb.analyzer.engine.report.ReportDocument
import com.funcodestudiocb.analyzer.engine.report.Tone
import com.funcodestudiocb.analyzer.ui.architecture.DiagramCanvas
import com.funcodestudiocb.analyzer.ui.architecture.DiagramSelection
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

fun toneColor(t: Tone): Color = when (t) {
    Tone.GOOD -> FuncodeColors.Success
    Tone.INFO -> FuncodeColors.Electric
    Tone.WARNING -> FuncodeColors.Medium
    Tone.DANGER -> FuncodeColors.Critical
    Tone.NEUTRAL -> FuncodeColors.Info
}

/**
 * El informe en la pantalla con el mismo modelo que el PDF y el HTML: una entrada de la lista por sección y por bloque
 * (así un informe largo no se compone entero de golpe). Las tablas se muestran fila a fila, como fichas.
 */
fun LazyListScope.reportItems(doc: ReportDocument) {
    doc.sections.forEach { s ->
        item(key = "sec-${s.id}") {
            val title = if (s.number.all { it.isDigit() }) "${s.number}. ${s.title}" else "Anexo ${s.number}. ${s.title}"
            Column(Modifier.padding(top = 16.dp).testTag("report_section_${s.id}")) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Box(Modifier.padding(top = 4.dp).width(42.dp).height(3.dp).background(FuncodeColors.Gold))
            }
        }
        s.blocks.forEachIndexed { i, b ->
            item(key = "blk-${s.id}-$i") { ReportBlock(b) }
        }
    }
}

@Composable
fun ReportBlock(b: Block) {
    when (b) {
        is Block.Heading -> Text(
            b.text.uppercase(), style = MaterialTheme.typography.labelMedium, color = FuncodeColors.TextSecondary,
            modifier = Modifier.padding(top = 12.dp),
        )
        is Block.Paragraph -> Text(b.text.revealControlCharacters(), style = MaterialTheme.typography.bodyMedium)
        is Block.Statements -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            b.items.forEachIndexed { i, st ->
                Row {
                    Text(if (b.numbered) "${i + 1}." else "•", modifier = Modifier.width(22.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildAnnotatedString {
                            append(st.text.revealControlCharacters())
                            append("  ")
                            withStyle(SpanStyle(color = FuncodeColors.Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)) { append(st.ref) }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        is Block.Facts -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            b.items.forEach { (k, v) ->
                Row {
                    Text(k, color = FuncodeColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.42f))
                    Text(v.revealControlCharacters(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.58f))
                }
            }
        }
        is Block.Table -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            b.rows.forEachIndexed { r, row ->
                val tone = b.tones?.getOrNull(r) ?: Tone.NEUTRAL
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)),
                ) {
                    Box(Modifier.width(4.dp).height(44.dp).background(if (tone == Tone.NEUTRAL) Color.Transparent else toneColor(tone), RoundedCornerShape(2.dp)))
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.firstOrNull().orEmpty().revealControlCharacters(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        row.drop(1).forEachIndexed { c, cell ->
                            if (cell.isNotBlank() && cell != "—") {
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = FuncodeColors.TextSecondary)) { append("${b.headers.getOrNull(c + 1).orEmpty()}: ") }
                                        append(cell.revealControlCharacters())
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            b.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = FuncodeColors.TextSecondary) }
        }
        is Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            b.items.forEach { Row { Text("•", modifier = Modifier.width(16.dp)); Text(it.revealControlCharacters(), style = MaterialTheme.typography.bodySmall) } }
        }
        is Block.Callout -> FuncodePanel(accent = toneColor(b.tone)) { Text(b.text, style = MaterialTheme.typography.bodyMedium) }
        is Block.Bars -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            b.items.forEach { bar ->
                Column {
                    Row {
                        Text(bar.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(bar.display, style = MaterialTheme.typography.bodySmall, color = FuncodeColors.TextSecondary)
                    }
                    LinearProgressIndicator(
                        progress = { if (bar.max <= 0) 0f else (bar.value.toFloat() / bar.max).coerceIn(0f, 1f) },
                        color = toneColor(bar.tone),
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }
        }
        is Block.Gauge -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("report_gauge")) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { (b.score ?: 0) / 100f },
                    color = toneColor(b.tone),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeWidth = 8.dp,
                    modifier = Modifier.size(84.dp),
                )
                Text(b.score?.toString() ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(b.label, style = MaterialTheme.typography.titleMedium, color = toneColor(b.tone), fontWeight = FontWeight.Bold)
                Text(b.caption, style = MaterialTheme.typography.bodySmall, color = FuncodeColors.TextSecondary)
            }
        }
        is Block.Figure -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DiagramCanvas(b.diagram, DiagramSelection(), onSelectNode = {}, onSelectEdge = { _, _ -> }, modifier = Modifier.fillMaxWidth().height(320.dp))
            b.diagram.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = FuncodeColors.TextSecondary) }
            Text(b.caption, style = MaterialTheme.typography.labelSmall, color = FuncodeColors.TextSecondary)
            HorizontalDivider()
        }
    }
}

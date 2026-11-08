package com.funcodestudiocb.analyzer.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.engine.report.AnalysisReport
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

@Composable
private fun Heading(text: String) {
    Text(text, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun PathLine(path: String, detail: String?, onOpenFile: ((String) -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onOpenFile != null) Modifier.clickable { onOpenFile(path) } else Modifier)
            .padding(vertical = 3.dp),
    ) {
        Text(
            path.revealControlCharacters(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = if (onOpenFile != null) FuncodeColors.ElectricSoft else MaterialTheme.colorScheme.onSurface,
        )
        detail?.let { Text(it.revealControlCharacters(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Relaciones del proyecto: totales, posibles errores, archivos más importantes, ciclos y archivos sin referencias. */
@Composable
fun RelationsReportPanel(report: AnalysisReport, onOpenFile: ((String) -> Unit)?) {
    val summary = report.relationsSummary ?: return
    FuncodePanel(accent = FuncodeColors.Electric, modifier = Modifier.testTag("report_relations")) {
        Text(stringResource(R.string.report_relations).uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
        Text(stringResource(R.string.report_relations_summary, summary.internal, summary.external), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.report_relations_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val broken = report.relations.filter { it.confidence == RelationConfidence.BROKEN }
        if (broken.isNotEmpty()) {
            Heading(stringResource(R.string.report_relations_broken, summary.broken))
            Text(stringResource(R.string.report_relations_broken_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            broken.take(MAX).forEach { r ->
                PathLine(r.source + (r.line?.let { ":$it" } ?: ""), "«${r.reference}» — ${r.evidence.first().detail.substringAfter(": ")}", onOpenFile?.let { open -> { _: String -> open(r.source) } })
            }
            if (broken.size > MAX) Text(stringResource(R.string.report_and_more, broken.size - MAX), style = MaterialTheme.typography.labelSmall)
        }

        val important = report.fileGraphs.filter { it.incoming > 0 }.sortedBy { it.rank }.take(IMPORTANT)
        if (important.isNotEmpty()) {
            Heading(stringResource(R.string.report_relations_important))
            important.forEach { g -> PathLine(g.path, pluralStringResource(R.plurals.report_relations_important_item, g.incoming, g.incoming, g.transitiveDependents), onOpenFile) }
        }

        if (report.cycles.isNotEmpty()) {
            Heading(stringResource(R.string.report_relations_cycles, summary.cycles))
            report.cycles.take(MAX_CYCLES).forEach { c ->
                Text(
                    pluralStringResource(R.plurals.report_relations_cycle_item, c.size, c.size) + ": " + c.paths.take(4).joinToString(", ") { it.substringAfterLast('/') } + if (c.size > 4) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        val unreferenced = report.fileGraphs.filter { it.unreferenced }
        if (unreferenced.isNotEmpty()) {
            Heading(stringResource(R.string.report_relations_unreferenced, summary.unreferencedFiles))
            Text(stringResource(R.string.report_relations_unreferenced_intro), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Medium)
            unreferenced.take(MAX).forEach { PathLine(it.path, null, onOpenFile) }
            if (unreferenced.size > MAX) Text(stringResource(R.string.report_and_more, unreferenced.size - MAX), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val MAX = 20
private const val IMPORTANT = 10
private const val MAX_CYCLES = 10

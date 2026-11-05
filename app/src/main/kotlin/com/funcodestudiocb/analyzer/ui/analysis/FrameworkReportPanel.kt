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
import com.funcodestudiocb.analyzer.analysis.AnalysisReport
import com.funcodestudiocb.analyzer.engine.api.EndpointKind
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

@Composable
private fun Heading(text: String, tag: String? = null) {
    Text(text, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier))
}

/** Línea con un texto (ruta, tabla, clave) que abre un archivo al tocarla. */
@Composable
private fun LinkLine(label: String, detail: String?, file: String?, onOpenFile: ((String) -> Unit)?, color: androidx.compose.ui.graphics.Color? = null) {
    val open = if (file != null && onOpenFile != null) Modifier.clickable { onOpenFile(file) } else Modifier
    Column(Modifier.fillMaxWidth().then(open).padding(vertical = 3.dp)) {
        Text(
            label.revealControlCharacters(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = color ?: if (file != null && onOpenFile != null) FuncodeColors.ElectricSoft else MaterialTheme.colorScheme.onSurface,
        )
        detail?.let { Text(it.revealControlCharacters(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private val METHOD_PREFIX = listOf("GET ", "POST ", "PUT ", "PATCH ", "DELETE ", "HEAD ", "OPTIONS ", "ANY ")

/**
 * Fase 5B: rutas del backend y páginas, llamadas HTTP sin ruta en el proyecto, tablas y colecciones, y claves de
 * configuración (solo sus nombres) que se leen sin definirse en el comprimido.
 */
@Composable
fun FrameworkReportPanel(report: AnalysisReport, onOpenFile: ((String) -> Unit)?) {
    val summary = report.relationsSummary ?: return
    if (report.endpoints.isEmpty() && report.dataEntities.isEmpty() && report.configKeys.isEmpty() && summary.httpCalls == 0) return
    FuncodePanel(accent = FuncodeColors.Gold, modifier = Modifier.testTag("report_framework")) {
        Text(stringResource(R.string.report_framework).uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
        Text(stringResource(R.string.report_framework_summary, summary.endpoints, summary.pages, summary.httpCalls, summary.httpMatched), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.report_framework_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val apis = report.endpoints.filter { it.kind == EndpointKind.API }.sortedWith(compareBy({ it.path }, { it.method }))
        if (apis.isNotEmpty()) {
            Heading(stringResource(R.string.report_routes), "report_routes")
            apis.take(MAX).forEach { e ->
                val detail = listOfNotNull(
                    e.handler?.let { "→ $it" },
                    e.framework,
                    pluralStringResource(R.plurals.report_route_callers, e.callerCount, e.callerCount),
                    if (!e.prefixKnown) stringResource(R.string.report_route_unknown_prefix) else null,
                ).joinToString(" · ")
                LinkLine("${e.method} ${e.path}", detail, e.handlerFile ?: e.file, onOpenFile)
            }
            if (apis.size > MAX) Text(stringResource(R.string.report_and_more, apis.size - MAX), style = MaterialTheme.typography.labelSmall)
        }

        val pages = report.endpoints.filter { it.kind == EndpointKind.PAGE }.sortedBy { it.path }
        if (pages.isNotEmpty()) {
            Heading(stringResource(R.string.report_pages))
            pages.take(MAX).forEach { e -> LinkLine(e.path, listOfNotNull(e.handler?.let { "→ $it" }, e.framework).joinToString(" · "), e.handlerFile ?: e.file, onOpenFile) }
            if (pages.size > MAX) Text(stringResource(R.string.report_and_more, pages.size - MAX), style = MaterialTheme.typography.labelSmall)
        }

        // Llamadas a una ruta relativa que ningún backend del proyecto atiende.
        val unmatched = report.relations.filter { r -> r.kind == RelationKind.HTTP && r.external != null && METHOD_PREFIX.any { r.external!!.startsWith(it) } && apis.isNotEmpty() }
        if (unmatched.isNotEmpty()) {
            Heading(stringResource(R.string.report_unmatched_calls, unmatched.size), "report_unmatched")
            unmatched.take(MAX).forEach { r -> LinkLine(r.source + (r.line?.let { ":$it" } ?: ""), r.external, r.source, onOpenFile, FuncodeColors.Medium) }
            if (unmatched.size > MAX) Text(stringResource(R.string.report_and_more, unmatched.size - MAX), style = MaterialTheme.typography.labelSmall)
        }

        if (report.dataEntities.isNotEmpty()) {
            Heading(stringResource(R.string.report_data, report.dataEntities.size), "report_data")
            report.dataEntities.take(MAX).forEach { d ->
                val where = if (d.file != null) "${d.file!!.substringAfterLast('/')}:${d.line} (${d.definedBy})" else stringResource(R.string.report_data_undefined)
                val detail = pluralStringResource(R.plurals.report_data_item, d.accessedBy, where, d.accessedBy) + if (d.operations.isNotEmpty()) " · " + d.operations.joinToString(", ") else ""
                LinkLine(d.name, detail, d.file, onOpenFile)
            }
            if (report.dataEntities.size > MAX) Text(stringResource(R.string.report_and_more, report.dataEntities.size - MAX), style = MaterialTheme.typography.labelSmall)
        }

        if (report.configKeys.isNotEmpty()) {
            Heading(stringResource(R.string.report_config, report.configKeys.size), "report_config")
            val undefined = report.configKeys.filter { it.definedIn.isEmpty() }
            if (undefined.isNotEmpty()) {
                Text(stringResource(R.string.report_config_undefined, undefined.size), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Medium)
                undefined.take(MAX).forEach { k ->
                    LinkLine(k.name, pluralStringResource(R.plurals.report_config_item, k.readCount, k.readCount), k.readers.firstOrNull()?.substringBeforeLast(':'), onOpenFile)
                }
            }
        }
    }
}

private const val MAX = 30

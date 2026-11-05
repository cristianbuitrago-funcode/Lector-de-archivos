package com.funcodestudiocb.analyzer.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.analysis.AnalysisReport
import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory
import com.funcodestudiocb.analyzer.engine.api.SecurityFinding
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.ui.analysis.severityColor
import com.funcodestudiocb.analyzer.ui.analysis.severityLabel
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.explorer.secretTypeLabel
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

@Composable
fun certaintyLabel(certainty: Certainty): String = stringResource(
    when (certainty) {
        Certainty.CONFIRMED -> R.string.certainty_confirmed
        Certainty.PROBABLE -> R.string.certainty_probable
        Certainty.POSSIBLE -> R.string.certainty_possible
        Certainty.DYNAMIC -> R.string.certainty_dynamic
    },
)

@Composable
fun certaintyMeaning(certainty: Certainty): String = stringResource(
    when (certainty) {
        Certainty.CONFIRMED -> R.string.certainty_confirmed_meaning
        Certainty.PROBABLE -> R.string.certainty_probable_meaning
        Certainty.POSSIBLE -> R.string.certainty_possible_meaning
        Certainty.DYNAMIC -> R.string.certainty_dynamic_meaning
    },
)

fun certaintyColor(certainty: Certainty): Color = when (certainty) {
    Certainty.CONFIRMED -> FuncodeColors.Electric
    Certainty.PROBABLE -> FuncodeColors.ElectricSoft
    Certainty.POSSIBLE -> FuncodeColors.Info
    Certainty.DYNAMIC -> FuncodeColors.Gold
}

@Composable
fun categoryLabel(category: SecurityCategory): String = stringResource(
    when (category) {
        SecurityCategory.SQL_INJECTION -> R.string.category_sql_injection
        SecurityCategory.XSS -> R.string.category_xss
        SecurityCategory.CSRF -> R.string.category_csrf
        SecurityCategory.PATH_TRAVERSAL -> R.string.category_path_traversal
        SecurityCategory.COMMAND_EXECUTION -> R.string.category_command_execution
        SecurityCategory.HARDCODED_PASSWORD -> R.string.category_hardcoded_password
        SecurityCategory.EXPOSED_SECRET -> R.string.category_exposed_secret
        SecurityCategory.INSECURE_CONFIGURATION -> R.string.category_insecure_configuration
        SecurityCategory.EXCESSIVE_PERMISSIONS -> R.string.category_excessive_permissions
        SecurityCategory.WEAK_AUTHENTICATION -> R.string.category_weak_authentication
        SecurityCategory.INSUFFICIENT_VALIDATION -> R.string.category_insufficient_validation
        SecurityCategory.VULNERABLE_DEPENDENCY -> R.string.category_vulnerable_dependency
        SecurityCategory.OUTDATED_LIBRARY -> R.string.category_outdated_library
        SecurityCategory.CORS -> R.string.category_cors
        SecurityCategory.SENSITIVE_DATA_EXPOSURE -> R.string.category_sensitive_data_exposure
        SecurityCategory.SESSION -> R.string.category_session
        SecurityCategory.ACCESS_CONTROL -> R.string.category_access_control
        SecurityCategory.SUPPRESSION -> R.string.category_suppression
    },
)

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
}

/** Fila compacta de un hallazgo: severidad, nombre, ubicación y certeza. */
@Composable
fun FindingRow(finding: SecurityFinding, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(severityColor(finding.severity), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(finding.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    finding.path?.let { p -> p.revealControlCharacters() + (finding.line?.let { ":$it" } ?: "") } ?: stringResource(R.string.security_project_wide),
                    finding.ruleId,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusChip(severityLabel(finding.severity), severityColor(finding.severity))
            Text(certaintyLabel(finding.certainty), style = MaterialTheme.typography.labelSmall, color = certaintyColor(finding.certainty))
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 104.dp, max = 140.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.weight(1f))
    }
}

/** Bloque de código de la ficha (fragmento o ejemplo corregido), con números de línea si se conocen. */
@Composable
private fun CodeBlock(text: String, firstLine: Int?, highlight: Int?, tag: String) {
    val lines = text.split('\n')
    val gutter = ((firstLine ?: 1) + lines.size).toString().length
    SelectionContainer {
        Column(
            Modifier
                .fillMaxWidth()
                .background(FuncodeColors.NavyDeep, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
                .padding(8.dp)
                .testTag(tag),
        ) {
            lines.forEachIndexed { i, line ->
                val number = firstLine?.let { it + i }
                val marked = number != null && number == highlight
                Row(Modifier.background(if (marked) FuncodeColors.Critical.copy(alpha = 0.16f) else Color.Transparent)) {
                    if (number != null) {
                        Text(number.toString().padStart(gutter) + "  ", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FuncodeColors.Info)
                    }
                    Text(line.revealControlCharacters().replace("\t", "    "), fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                }
            }
        }
    }
}

/** Ficha completa de un hallazgo (§8.3): todo lo que el motor envía, en español y sin ningún secreto completo. */
@Composable
fun FindingDetail(finding: SecurityFinding, onOpenFile: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.testTag("finding_detail"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(finding.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("finding_title"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(severityLabel(finding.severity), severityColor(finding.severity))
            StatusChip(certaintyLabel(finding.certainty), certaintyColor(finding.certainty))
        }
        Text(certaintyMeaning(finding.certainty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        finding.downgraded?.let {
            Text(stringResource(R.string.finding_downgraded, it), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Gold, modifier = Modifier.testTag("finding_downgraded"))
        }
        FuncodePanel(accent = severityColor(finding.severity)) {
            KeyValue(stringResource(R.string.finding_rule), stringResource(R.string.finding_rule_value, finding.ruleId, finding.ruleVersion), mono = true)
            KeyValue(stringResource(R.string.finding_category), categoryLabel(finding.category))
            finding.cwe?.let { KeyValue(stringResource(R.string.finding_cwe), "CWE-$it") }
            finding.owasp?.let { KeyValue(stringResource(R.string.finding_owasp), it) }
            KeyValue(
                stringResource(R.string.finding_location),
                finding.path?.let { p ->
                    val shown = p.revealControlCharacters()
                    finding.line?.let { stringResource(R.string.finding_location_value, shown, it) } ?: shown
                } ?: stringResource(R.string.security_project_wide),
            )
            finding.secretType?.let { type ->
                KeyValue(stringResource(R.string.finding_secret), stringResource(R.string.finding_secret_value, secretTypeLabel(type), finding.masked.orEmpty()), mono = true)
            }
            finding.fingerprint?.let { KeyValue(stringResource(R.string.finding_fingerprint), it, mono = true) }
            if (finding.fingerprint != null) {
                Text(stringResource(R.string.finding_fingerprint_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val path = finding.path
            if (path != null && onOpenFile != null) {
                TextButton(onClick = { onOpenFile(path) }, modifier = Modifier.testTag("finding_open_file")) { Text(stringResource(R.string.security_open_file)) }
            }
        }
        FuncodePanel {
            SectionTitle(stringResource(R.string.finding_explanation))
            Text(finding.explanation, style = MaterialTheme.typography.bodyMedium)
            SectionTitle(stringResource(R.string.finding_consequences))
            Text(finding.consequences, style = MaterialTheme.typography.bodyMedium)
        }
        finding.snippet?.let { snippet ->
            FuncodePanel {
                SectionTitle(stringResource(R.string.finding_snippet))
                CodeBlock(snippet, finding.snippetLine, finding.line, "finding_snippet")
            }
        }
        if (finding.flow.isNotEmpty()) {
            FuncodePanel(accent = FuncodeColors.Electric) {
                SectionTitle(stringResource(R.string.finding_flow))
                finding.flow.forEach { step ->
                    Text(stringResource(R.string.finding_flow_step, step.line, step.description), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        FuncodePanel(accent = FuncodeColors.Success) {
            SectionTitle(stringResource(R.string.finding_recommendation))
            Text(finding.recommendation, style = MaterialTheme.typography.bodyMedium)
            finding.fixedExample?.let {
                SectionTitle(stringResource(R.string.finding_example))
                CodeBlock(it, null, null, "finding_example")
            }
        }
        if (finding.references.isNotEmpty()) {
            FuncodePanel {
                SectionTitle(stringResource(R.string.finding_references))
                // Solo texto: la app no abre enlaces ni tiene permiso de red.
                SelectionContainer { Column { finding.references.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = FuncodeColors.ElectricSoft) } } }
            }
        }
    }
}

/** Resumen de la auditoría dentro del informe de un análisis. */
@Composable
fun SecurityReportPanel(report: AnalysisReport, onOpenFile: ((String) -> Unit)?) {
    val summary = report.securitySummary ?: return
    FuncodePanel(accent = if (summary.findings > 0) severityColor(report.security.minOfOrNull { it.severity } ?: Severity.INFO) else FuncodeColors.Success) {
        SectionTitle(stringResource(R.string.report_security))
        Text(pluralStringResource(R.plurals.security_findings_count, summary.findings, summary.findings), fontWeight = FontWeight.Medium, modifier = Modifier.testTag("report_security_count"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Severity.entries.forEach { s ->
                val n = summary.bySeverity[s] ?: 0
                if (n > 0) StatusChip("${severityLabel(s)} $n", severityColor(s))
            }
        }
        Text(stringResource(R.string.security_disclaimer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val top = report.security.sortedWith(compareBy({ it.severity.ordinal }, { it.certainty.ordinal })).take(5)
        if (top.isNotEmpty()) {
            SectionTitle(stringResource(R.string.report_security_top))
            top.forEach { f -> FindingRow(f, onClick = { f.path?.let { p -> onOpenFile?.invoke(p) } }) }
            Text(stringResource(R.string.report_security_open), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        summary.notEvaluated.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

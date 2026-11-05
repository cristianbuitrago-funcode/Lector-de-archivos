package com.funcodestudiocb.analyzer.ui.dependencies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.analysis.AnalysisReport
import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.Criticality
import com.funcodestudiocb.analyzer.engine.api.DependencyScope
import com.funcodestudiocb.analyzer.engine.api.EndOfLife
import com.funcodestudiocb.analyzer.engine.api.EolStatus
import com.funcodestudiocb.analyzer.engine.api.ResolvedDependency
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.UsageStatus
import com.funcodestudiocb.analyzer.engine.api.VersionSource
import com.funcodestudiocb.analyzer.engine.api.VulnerabilityRef
import com.funcodestudiocb.analyzer.ui.analysis.severityColor
import com.funcodestudiocb.analyzer.ui.analysis.severityLabel
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.explorer.ecosystemLabel
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

@Composable
fun usageLabel(usage: UsageStatus): String = stringResource(
    when (usage) {
        UsageStatus.USED -> R.string.dep_usage_used
        UsageStatus.NOT_SEEN -> R.string.dep_usage_not_seen
        UsageStatus.DEVELOPMENT_TOOL -> R.string.dep_usage_dev
        UsageStatus.INDIRECT -> R.string.dep_usage_indirect
        UsageStatus.NOT_ANALYZED -> R.string.dep_usage_not_analyzed
    },
)

@Composable
fun usageMeaning(usage: UsageStatus): String = stringResource(
    when (usage) {
        UsageStatus.USED -> R.string.dep_usage_used_desc
        UsageStatus.NOT_SEEN -> R.string.dep_usage_not_seen_desc
        UsageStatus.DEVELOPMENT_TOOL -> R.string.dep_usage_dev_desc
        UsageStatus.INDIRECT -> R.string.dep_usage_indirect_desc
        UsageStatus.NOT_ANALYZED -> R.string.dep_usage_not_analyzed_desc
    },
)

@Composable
fun versionSourceLabel(source: VersionSource): String = stringResource(
    when (source) {
        VersionSource.LOCKFILE -> R.string.dep_version_lockfile
        VersionSource.EXACT_IN_MANIFEST -> R.string.dep_version_exact
        VersionSource.RANGE_IN_MANIFEST -> R.string.dep_version_range
        VersionSource.UNKNOWN -> R.string.dep_version_unknown
    },
)

@Composable
fun criticalityLabel(criticality: Criticality): String = stringResource(
    when (criticality) {
        Criticality.HIGH -> R.string.dep_criticality_high
        Criticality.MEDIUM -> R.string.dep_criticality_medium
        Criticality.LOW -> R.string.dep_criticality_low
    },
)

fun criticalityColor(criticality: Criticality): Color = when (criticality) {
    Criticality.HIGH -> FuncodeColors.High
    Criticality.MEDIUM -> FuncodeColors.Medium
    Criticality.LOW -> FuncodeColors.Info
}

@Composable
fun scopeLabel(scope: DependencyScope): String = stringResource(
    when (scope) {
        DependencyScope.RUNTIME -> R.string.dep_scope_runtime
        DependencyScope.DEVELOPMENT -> R.string.dep_scope_development
        DependencyScope.TEST -> R.string.dep_scope_test
        DependencyScope.BUILD -> R.string.dep_scope_build
        DependencyScope.PEER -> R.string.dep_scope_peer
        DependencyScope.OPTIONAL -> R.string.dep_scope_optional
        DependencyScope.PLATFORM -> R.string.dep_scope_platform
    },
)

@Composable
fun eolStatusLabel(status: EolStatus): String = stringResource(
    when (status) {
        EolStatus.UNSUPPORTED -> R.string.eol_unsupported
        EolStatus.ENDING_SOON -> R.string.eol_ending_soon
        EolStatus.SUPPORTED -> R.string.eol_supported
        EolStatus.UNKNOWN_CYCLE -> R.string.eol_unknown_cycle
    },
)

fun eolStatusColor(status: EolStatus): Color = when (status) {
    EolStatus.UNSUPPORTED -> FuncodeColors.High
    EolStatus.ENDING_SOON -> FuncodeColors.Medium
    EolStatus.SUPPORTED -> FuncodeColors.Success
    EolStatus.UNKNOWN_CYCLE -> FuncodeColors.Info
}

/** La app solo está en español: fechas y números con formato español, sea cual sea el idioma del teléfono. */
private val SPANISH: Locale = Locale.forLanguageTag("es-ES")

internal fun formatDay(epochMs: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, SPANISH).format(Date(epochMs))

internal fun formatCount(n: Int): String = NumberFormat.getIntegerInstance(SPANISH).format(n)

/** Peor severidad de las vulnerabilidades de una dependencia, o `null` si no tiene. */
internal val ResolvedDependency.worst: Severity? get() = vulnerabilities.minByOrNull { it.severity.ordinal }?.severity

/** "archivo:línea" (o solo el archivo). */
internal fun location(path: String?, line: Int?): String? = path?.let { it + (line?.let { l -> ":$l" } ?: "") }

/** Ruta de un "archivo:línea" para abrirla en el Explorador. */
internal fun pathOf(location: String): String {
    val i = location.lastIndexOf(':')
    return if (i > 0 && location.substring(i + 1).all { it.isDigit() }) location.substring(0, i) else location
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
}

/** Fila de la lista: nombre y versión, tipo, uso y la peor vulnerabilidad o la criticidad. */
@Composable
fun DependencyRow(d: ResolvedDependency, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val worst = d.worst
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(worst?.let { severityColor(it) } ?: criticalityColor(d.criticality).copy(alpha = 0.5f), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(
                d.name.revealControlCharacters() + (d.version?.let { " " + it.revealControlCharacters() } ?: ""),
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    ecosystemLabel(d.ecosystem),
                    stringResource(if (d.direct) R.string.dep_direct else R.string.dep_indirect),
                    usageLabel(d.usage),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (worst != null) {
                val malicious = d.vulnerabilities.any { it.malicious }
                StatusChip(
                    if (malicious) stringResource(R.string.dep_malicious_chip) else pluralStringResource(R.plurals.dep_vulns_chip, d.vulnerabilityCount, d.vulnerabilityCount),
                    severityColor(worst),
                )
            }
            Text(criticalityLabel(d.criticality), style = MaterialTheme.typography.labelSmall, color = criticalityColor(d.criticality))
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String, onClick: (() -> Unit)? = null, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 104.dp, max = 140.dp).padding(end = 8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = if (onClick != null) FuncodeColors.ElectricSoft else Color.Unspecified,
            modifier = Modifier.weight(1f).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        )
    }
}

@Composable
private fun LinkLine(text: String, onClick: (() -> Unit)?, tag: String? = null) {
    Text(
        "• " + text.revealControlCharacters(),
        style = MaterialTheme.typography.bodySmall,
        color = if (onClick != null) FuncodeColors.ElectricSoft else Color.Unspecified,
        modifier = Modifier.padding(vertical = 2.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/** Ficha completa de una dependencia: todo lo que envía el motor, en español. */
@Composable
fun DependencyDetail(d: ResolvedDependency, onOpenFile: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val open = { path: String -> onOpenFile?.invoke(path) }
    Column(modifier.testTag("dependency_detail"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            d.name.revealControlCharacters() + (d.version?.let { " " + it.revealControlCharacters() } ?: ""),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("dependency_title"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusChip(ecosystemLabel(d.ecosystem), FuncodeColors.Electric)
            StatusChip(stringResource(if (d.direct) R.string.dep_direct else R.string.dep_indirect), FuncodeColors.Gold)
            StatusChip(scopeLabel(d.scope), FuncodeColors.Info)
            StatusChip(stringResource(R.string.dep_criticality_chip, criticalityLabel(d.criticality)), criticalityColor(d.criticality))
        }

        FuncodePanel(accent = FuncodeColors.Electric) {
            SectionTitle(stringResource(R.string.dep_section_version))
            d.declared?.let { KeyValue(stringResource(R.string.dep_declared), it.revealControlCharacters(), mono = true) }
            KeyValue(stringResource(R.string.dep_evaluated), d.version?.revealControlCharacters() ?: stringResource(R.string.dep_no_version), mono = true)
            KeyValue(stringResource(R.string.dep_version_source), versionSourceLabel(d.versionSource))
            d.projectRoot?.let { KeyValue(stringResource(R.string.dep_project), it.ifEmpty { stringResource(R.string.explorer_root_dir) }.revealControlCharacters()) }
            location(d.manifest, d.manifestLine)?.let { loc ->
                KeyValue(stringResource(R.string.dep_manifest), loc.revealControlCharacters(), onClick = if (onOpenFile != null) ({ open(d.manifest!!) }) else null)
            }
            location(d.lockfile, d.lockfileLine)?.let { loc ->
                KeyValue(stringResource(R.string.dep_lockfile), loc.revealControlCharacters(), onClick = if (onOpenFile != null) ({ open(d.lockfile!!) }) else null)
            }
            if (d.alsoIn.isNotEmpty()) {
                Text(stringResource(R.string.dep_also_in), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                d.alsoIn.forEach { LinkLine(it, if (onOpenFile != null) ({ open(pathOf(it)) }) else null) }
                if (d.alsoInCount > d.alsoIn.size) Text(stringResource(R.string.dep_more, d.alsoInCount - d.alsoIn.size), style = MaterialTheme.typography.labelSmall)
            }
        }

        if (!d.direct) {
            FuncodePanel(accent = FuncodeColors.Gold) {
                SectionTitle(stringResource(R.string.dep_section_chain))
                if (d.introducedBy.isEmpty()) Text(stringResource(R.string.dep_chain_unknown), style = MaterialTheme.typography.bodySmall)
                d.introducedBy.forEach { Text("• " + it.revealControlCharacters(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("dependency_chain")) }
                if (d.introducedByCount > d.introducedBy.size) Text(stringResource(R.string.dep_more_chains, d.introducedByCount - d.introducedBy.size), style = MaterialTheme.typography.labelSmall)
            }
        }

        FuncodePanel(accent = if (d.usage == UsageStatus.NOT_SEEN) FuncodeColors.Medium else FuncodeColors.Electric) {
            SectionTitle(stringResource(R.string.dep_section_usage))
            Text(usageLabel(d.usage), fontWeight = FontWeight.Medium, modifier = Modifier.testTag("dependency_usage"))
            Text(usageMeaning(d.usage), style = MaterialTheme.typography.bodySmall)
            d.usedIn.forEach { LinkLine(it, if (onOpenFile != null) ({ open(pathOf(it)) }) else null, "dependency_used_in") }
            if (d.usedCount > d.usedIn.size) Text(stringResource(R.string.dep_more_files, d.usedCount - d.usedIn.size), style = MaterialTheme.typography.labelSmall)
        }

        FuncodePanel(accent = criticalityColor(d.criticality)) {
            SectionTitle(stringResource(R.string.dep_section_criticality))
            Text(criticalityLabel(d.criticality), fontWeight = FontWeight.Medium)
            d.criticalityReasons.forEach { Text("• " + it.revealControlCharacters(), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.dep_criticality_desc), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        FuncodePanel(accent = d.worst?.let { severityColor(it) } ?: FuncodeColors.Success, modifier = Modifier.testTag("dependency_vulns")) {
            SectionTitle(stringResource(R.string.dep_section_vulns))
            if (d.vulnerabilities.isEmpty()) {
                Text(
                    stringResource(if (d.version == null) R.string.dep_vulns_not_checked else R.string.dep_vulns_none),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("dependency_no_vulns"),
                )
            }
            d.vulnerabilities.forEach { VulnerabilityItem(it) }
            if (d.vulnerabilityCount > d.vulnerabilities.size) {
                Text(stringResource(R.string.dep_more_vulns, d.vulnerabilityCount - d.vulnerabilities.size), style = MaterialTheme.typography.labelSmall)
            }
            if (d.vulnerabilities.isNotEmpty()) {
                Text(stringResource(R.string.dep_vulns_fix_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VulnerabilityItem(v: VulnerabilityRef) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("vulnerability_item"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(v.id.revealControlCharacters(), fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            }
            StatusChip(severityLabel(v.severity) + (v.score?.let { " · " + String.format(java.util.Locale.ROOT, "%.1f", it) } ?: ""), severityColor(v.severity))
        }
        if (v.malicious) {
            Text(stringResource(R.string.dep_malicious), color = FuncodeColors.Critical, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
        }
        if (v.aliases.isNotEmpty()) Text(v.aliases.joinToString(" · ") { it.revealControlCharacters() }, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        v.summary?.let { Text(it.revealControlCharacters(), style = MaterialTheme.typography.bodySmall) }
        Text(
            stringResource(if (v.certainty == Certainty.CONFIRMED) R.string.dep_vuln_confirmed else R.string.dep_vuln_possible),
            style = MaterialTheme.typography.labelSmall,
            color = if (v.certainty == Certainty.CONFIRMED) FuncodeColors.High else FuncodeColors.Medium,
        )
        Text(
            if (v.fixedIn.isEmpty()) stringResource(R.string.dep_vuln_no_fix) else stringResource(R.string.dep_vuln_fixed_in, v.fixedIn.joinToString(", ") { it.revealControlCharacters() }),
            style = MaterialTheme.typography.bodySmall,
        )
        v.published?.let { Text(stringResource(R.string.dep_vuln_published, it.take(10)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(stringResource(R.string.dep_vuln_reference, v.id.revealControlCharacters()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Una versión de un entorno, framework o base de datos y su fin de soporte. */
@Composable
fun EndOfLifeItem(e: EndOfLife, onOpenFile: ((String) -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("eol_item"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${e.label.revealControlCharacters()} ${e.version.revealControlCharacters()}" + (e.cycle?.let { c -> " " + stringResource(R.string.eol_cycle, c.revealControlCharacters()) } ?: ""),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            StatusChip(eolStatusLabel(e.status), eolStatusColor(e.status))
        }
        val detail = when (e.status) {
            EolStatus.UNSUPPORTED -> e.eolDate?.let { stringResource(R.string.eol_unsupported_since, it) } ?: stringResource(R.string.eol_unsupported_no_date)
            EolStatus.ENDING_SOON -> stringResource(R.string.eol_ending_on, e.eolDate.orEmpty())
            EolStatus.SUPPORTED -> e.eolDate?.let { stringResource(R.string.eol_supported_until, it) } ?: stringResource(R.string.eol_supported_no_date)
            EolStatus.UNKNOWN_CYCLE -> stringResource(R.string.eol_unknown_cycle_desc)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall)
        e.latestInCycle?.let { Text(stringResource(R.string.eol_latest, it.revealControlCharacters()), style = MaterialTheme.typography.labelSmall) }
        if (e.minimumOfRange) Text(stringResource(R.string.eol_minimum_of_range), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Medium)
        val where = location(e.path, e.line)
        Text(
            stringResource(R.string.eol_source, e.source.revealControlCharacters(), where?.revealControlCharacters() ?: "—"),
            style = MaterialTheme.typography.labelSmall,
            color = if (where != null && onOpenFile != null) FuncodeColors.ElectricSoft else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (e.path != null && onOpenFile != null) Modifier.clickable { onOpenFile(e.path!!) } else Modifier,
        )
        if (e.alsoInCount > 0) Text(stringResource(R.string.eol_also_in, e.alsoInCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Panel del informe: totales de dependencias, vulnerabilidades, fin de soporte y estado de la base. */
@Composable
fun DependencyReportPanel(report: AnalysisReport, onOpenDependencies: (() -> Unit)?) {
    val summary = report.dependencySummary ?: return
    val worst = report.resolvedDependencies.mapNotNull { it.worst }.minByOrNull { it.ordinal }
    FuncodePanel(accent = worst?.let { severityColor(it) } ?: if (summary.endOfLife > 0) FuncodeColors.High else FuncodeColors.Success, modifier = Modifier.testTag("report_dependencies")) {
        SectionTitle(stringResource(R.string.report_dependencies))
        Text(stringResource(R.string.dep_totals, summary.direct, summary.indirect, summary.lockfiles), fontWeight = FontWeight.Medium, modifier = Modifier.testTag("report_dependencies_totals"))
        Text(
            if (summary.vulnerabilities == 0) stringResource(R.string.dep_summary_no_vulns)
            else stringResource(R.string.dep_summary_vulns, summary.vulnerablePackages, summary.vulnerabilities),
            modifier = Modifier.testTag("report_dependencies_vulns"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Severity.entries.forEach { s ->
                val n = summary.bySeverity[s] ?: 0
                if (n > 0) StatusChip("${severityLabel(s)} $n", severityColor(s))
            }
        }
        if (summary.malicious > 0) Text(stringResource(R.string.dep_summary_malicious, summary.malicious), color = FuncodeColors.Critical, fontWeight = FontWeight.Medium)
        if (summary.notSeen > 0) Text(stringResource(R.string.dep_summary_not_seen, summary.notSeen), style = MaterialTheme.typography.bodySmall)
        if (summary.endOfLife > 0 || summary.endingSoon > 0) {
            Text(stringResource(R.string.dep_summary_eol, summary.endOfLife, summary.endingSoon), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.High)
        }
        DatabaseState(report)
        if (onOpenDependencies != null && (report.resolvedDependencies.isNotEmpty() || report.endOfLife.isNotEmpty())) {
            Button(onClick = onOpenDependencies, modifier = Modifier.testTag("report_open_dependencies")) { Text(stringResource(R.string.dep_open)) }
        }
    }
}

/** Qué base se usó en el análisis y lo que no se pudo evaluar. */
@Composable
fun DatabaseState(report: AnalysisReport) {
    val summary = report.dependencySummary ?: return
    summary.databases.forEach { db ->
        Text(
            if (db.date == null) stringResource(R.string.dep_db_missing, db.ecosystem)
            else stringResource(R.string.dep_db_used, db.ecosystem, formatDay(db.date!!), formatCount(db.advisories)),
            style = MaterialTheme.typography.labelSmall,
            color = if (db.date == null) FuncodeColors.Medium else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    summary.databaseAgeDays?.let { age ->
        if (age >= com.funcodestudiocb.analyzer.vulndb.VulnDbStore.STALE_AFTER_DAYS) {
            Text(stringResource(R.string.dep_db_old, age), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Medium)
        }
    }
    summary.notEvaluated.forEach { Text("• " + it.revealControlCharacters(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("dep_not_evaluated")) }
}

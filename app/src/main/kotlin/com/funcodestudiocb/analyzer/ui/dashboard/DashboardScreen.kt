package com.funcodestudiocb.analyzer.ui.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.dashboard.AnalysisDigest
import com.funcodestudiocb.analyzer.dashboard.Dashboard
import com.funcodestudiocb.analyzer.dashboard.DashboardBuilder
import com.funcodestudiocb.analyzer.dashboard.ProjectOverview
import com.funcodestudiocb.analyzer.dashboard.Usage
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.report.AnalysisOutcome
import com.funcodestudiocb.analyzer.engine.report.Labels
import com.funcodestudiocb.analyzer.ui.analysis.outcomeLabel
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.reports.toneColor
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import kotlinx.coroutines.flow.flowOf
import java.time.ZoneId

/** Acciones del Panel hacia el resto de la app. */
class DashboardActions(
    val onNewAnalysis: () -> Unit,
    val onOpenAnalysis: (String) -> Unit,
    val onOpenReport: (String) -> Unit,
    val onOpenSecurity: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * Panel (Fase 10): proyectos analizados, últimos análisis, hallazgos y vulnerabilidades por severidad, tecnologías y
 * lenguajes más usados, tendencia del índice de mantenimiento y estado de la instalación. Todo sale del historial
 * cifrado del teléfono; las cifras globales usan el último análisis terminado de cada proyecto.
 */
@Composable
fun DashboardScreen(container: AppContainer, actions: DashboardActions) {
    val db = container.session.databaseOrNull()
    val digestsFlow = remember(db) { container.repository.digests() }
    val digests by digestsFlow.collectAsStateWithLifecycle(initialValue = null)
    val diagnosticsFlow = remember(db) { db?.runs()?.countByMode("DIAGNOSTIC") ?: flowOf(0) }
    val diagnostics by diagnosticsFlow.collectAsStateWithLifecycle(initialValue = 0)
    val dashboard = remember(digests) { digests?.let(DashboardBuilder::build) }
    val zone = remember { ZoneId.systemDefault() }

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 840.dp)
            .testTag("dashboard"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            dashboard == null -> item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.dash_loading), color = FuncodeColors.TextSecondary)
                }
            }
            dashboard.empty -> item { Welcome(actions) }
            else -> {
                item { Totals(dashboard, zone) }
                item { SeverityPanel(dashboard, actions) }
                item { VulnerabilityPanel(dashboard, actions) }
                item { SectionTitle(stringResource(R.string.dash_projects), "dash_projects") }
                items(dashboard.projects.take(MAX_PROJECTS), key = { it.key }) { ProjectRow(it, zone, actions, Modifier.animateItem()) }
                if (dashboard.projects.size > MAX_PROJECTS) {
                    item { Note(pluralStringResource(R.plurals.dash_more_projects, dashboard.projects.size - MAX_PROJECTS, dashboard.projects.size - MAX_PROJECTS)) }
                }
                if (dashboard.trends.isNotEmpty()) item { TrendPanel(dashboard.trends, zone) }
                item { UsagePanel(dashboard) }
                item { SectionTitle(stringResource(R.string.dash_recent), "dash_recent") }
                items(dashboard.recent, key = { "r-" + it.id }) { RecentRow(it, zone, actions, Modifier.animateItem()) }
            }
        }
        item { InstallationPanel(container, diagnostics, zone, actions) }
    }
}

private const val MAX_PROJECTS = 12

@Composable
private fun SectionTitle(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(top = 4.dp)
            .semantics { heading() }
            .testTag(tag),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = FuncodeColors.TextSecondary)
}

@Composable
private fun Welcome(actions: DashboardActions) {
    FuncodePanel(accent = FuncodeColors.Gold, modifier = Modifier.testTag("dash_welcome")) {
        Text(stringResource(R.string.dash_welcome_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.dash_welcome_text), color = FuncodeColors.TextSecondary)
        listOf(R.string.dash_welcome_step1, R.string.dash_welcome_step2, R.string.dash_welcome_step3).forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.Top) {
                Text("${i + 1}.", color = FuncodeColors.Gold, fontWeight = FontWeight.Bold, softWrap = false, modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp))
                Text(stringResource(step))
            }
        }
        Button(onClick = actions.onNewAnalysis, modifier = Modifier.testTag("dash_new_analysis")) { Text(stringResource(R.string.nav_new_analysis)) }
    }
}

@Composable
private fun Totals(d: Dashboard, zone: ZoneId) {
    val findings = d.findingsBySeverity.values.sum()
    val last = d.recent.firstOrNull()?.startedAtEpochMs
    Column(Modifier.fillMaxWidth().testTag("dash_totals"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Labels.number(d.projects.size), pluralStringResource(R.plurals.dash_tile_projects, d.projects.size), FuncodeColors.Electric, "dash_tile_projects")
            Tile(Labels.number(d.analyses), pluralStringResource(R.plurals.dash_tile_analyses, d.analyses), FuncodeColors.ElectricSoft, "dash_tile_analyses")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Labels.number(findings), pluralStringResource(R.plurals.dash_tile_findings, findings), if (findings > 0) FuncodeColors.High else FuncodeColors.Success, "dash_tile_findings")
            if (last != null) {
                Tile(relativeDay(last, zone), stringResource(R.string.dash_tile_last), FuncodeColors.Gold, "dash_tile_last", detail = Labels.date(last, zone))
            }
        }
    }
}

/** «hoy», «ayer», «hace 3 días» o la fecha corta. */
@Composable
private fun relativeDay(epochMs: Long, zone: ZoneId): String {
    val day = java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(day, java.time.LocalDate.now(zone)).toInt()
    return when {
        days <= 0 -> stringResource(R.string.dash_today)
        days == 1 -> stringResource(R.string.dash_yesterday)
        days < 30 -> pluralStringResource(R.plurals.dash_days_ago, days, days)
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tile(value: String, label: String, color: Color, tag: String, detail: String? = null) {
    Column(
        Modifier
            .weight(1f)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(tag),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall, color = FuncodeColors.TextSecondary)
        if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = FuncodeColors.TextSecondary)
    }
}

/** Barra horizontal sin adornos: a 0 no dibuja nada sobre la pista (una marca parecería un dato). */
@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(
        Modifier
            .padding(top = 3.dp)
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(3.dp)),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
    }
}

private val SEVERITY_ORDER = listOf(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.INFO)

private fun severityColor(s: Severity) = when (s) {
    Severity.CRITICAL -> FuncodeColors.Critical
    Severity.HIGH -> FuncodeColors.High
    Severity.MEDIUM -> FuncodeColors.Medium
    Severity.LOW -> FuncodeColors.Low
    Severity.INFO -> FuncodeColors.Info
}

@Composable
private fun SeverityBars(counts: Map<Severity, Int>, tagPrefix: String) {
    val max = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SEVERITY_ORDER.forEach { s ->
            val n = counts[s] ?: 0
            Column(Modifier.semantics(mergeDescendants = true) {}.testTag("${tagPrefix}_${s.name}")) {
                Row {
                    Text(Labels.severity(s), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(Labels.number(n), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                Bar(n.toFloat() / max, severityColor(s))
            }
        }
    }
}

@Composable
private fun SeverityPanel(d: Dashboard, actions: DashboardActions) {
    FuncodePanel(accent = FuncodeColors.Critical, modifier = Modifier.testTag("dash_findings")) {
        Text(stringResource(R.string.dash_findings_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (d.findingsProjects == 0) {
            Note(stringResource(R.string.dash_findings_none_evaluated))
        } else {
            SeverityBars(d.findingsBySeverity, "dash_findings")
            Note(pluralStringResource(R.plurals.dash_latest_of_projects, d.findingsProjects, d.findingsProjects))
            OutlinedButton(onClick = actions.onOpenSecurity, modifier = Modifier.testTag("dash_open_security")) { Text(stringResource(R.string.dash_open_security)) }
        }
    }
}

@Composable
private fun VulnerabilityPanel(d: Dashboard, actions: DashboardActions) {
    FuncodePanel(accent = FuncodeColors.High, modifier = Modifier.testTag("dash_vulnerabilities")) {
        Text(stringResource(R.string.dash_vulns_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (d.vulnerabilityProjects > 0) {
            SeverityBars(d.vulnerabilitiesBySeverity, "dash_vulns")
            Note(pluralStringResource(R.plurals.dash_vulns_checked, d.vulnerabilityProjects, d.vulnerabilityProjects))
        }
        if (d.vulnerabilitiesNotChecked > 0) {
            Note(pluralStringResource(R.plurals.dash_vulns_not_checked, d.vulnerabilitiesNotChecked, d.vulnerabilitiesNotChecked))
            OutlinedButton(onClick = actions.onOpenSettings, modifier = Modifier.testTag("dash_open_settings")) { Text(stringResource(R.string.dash_download_db)) }
        } else if (d.vulnerabilityProjects == 0) {
            Note(stringResource(R.string.dash_vulns_no_dependencies))
        }
    }
}

@Composable
private fun ProjectRow(p: ProjectOverview, zone: ZoneId, actions: DashboardActions, modifier: Modifier = Modifier) {
    val latest = p.latest
    val index = latest.maintenance
    val open = if (latest.usable) actions.onOpenReport else actions.onOpenAnalysis
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(if (latest.usable) R.string.dash_open_report else R.string.dash_open_analysis)) { open(latest.id) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("dash_project_${p.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name.revealControlCharacters(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val analyses = pluralStringResource(R.plurals.dash_tile_analyses, p.history.size)
            Text(
                "${Labels.date(latest.startedAtEpochMs, zone)} · ${Labels.number(p.history.size)} $analyses" +
                    (latest.security?.let { " · " + Labels.count(it.findings, "hallazgo", "hallazgos") } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = FuncodeColors.TextSecondary,
            )
            if (!latest.usable && latest.outcome != null) {
                val (label, color) = outcomeLabel(latest.outcome)
                StatusChip(label, color)
            }
        }
        if (p.indexTrend.size >= 2) {
            Sparkline(p.indexTrend.map { it.second }, Labels.maintenanceTone(index?.level).let(::toneColor), Modifier.size(width = 56.dp, height = 24.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            val score = index?.score
            Text(score?.toString() ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = toneColor(Labels.maintenanceTone(index?.level)))
            Text(
                index?.level?.let { Labels.maintenance(it) } ?: stringResource(R.string.dash_no_index),
                style = MaterialTheme.typography.labelSmall,
                color = FuncodeColors.TextSecondary,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = FuncodeColors.TextSecondary)
    }
}

@Composable
private fun Sparkline(values: List<Int>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = (values.min() - 5).coerceAtLeast(0)
        val max = (values.max() + 5).coerceAtMost(100)
        val span = (max - min).coerceAtLeast(1)
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(i * step, size.height - (v - min).toFloat() / span * size.height)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        val last = values.last()
        drawCircle(color, 3.dp.toPx(), Offset(size.width, size.height - (last - min).toFloat() / span * size.height))
    }
}

@Composable
private fun TrendPanel(trends: List<ProjectOverview>, zone: ZoneId) {
    FuncodePanel(accent = FuncodeColors.Success, modifier = Modifier.testTag("dash_trends")) {
        Text(stringResource(R.string.dash_trend_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        trends.forEach { p ->
            val points = p.indexTrend
            val first = points.first()
            val last = points.last()
            val delta = last.second - first.second
            val description = stringResource(
                R.string.dash_trend_description, p.name, points.joinToString(", ") { it.second.toString() },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = description }
                    .testTag("dash_trend_${p.key}"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name.revealControlCharacters(), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${first.second} → ${last.second} (${if (delta > 0) "+" else ""}$delta)",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            delta > 0 -> FuncodeColors.Success
                            delta < 0 -> FuncodeColors.Critical
                            else -> FuncodeColors.TextSecondary
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TrendChart(points.map { it.second }, Modifier.fillMaxWidth().height(72.dp))
                Row {
                    Text(Labels.date(first.first, zone), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.TextSecondary, modifier = Modifier.weight(1f))
                    Text(Labels.date(last.first, zone), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.TextSecondary)
                }
            }
        }
        Note(stringResource(R.string.dash_trend_note))
    }
}

/** Índice de 0 a 100 en el tiempo, con líneas guía en 50 y 70 (medio y bueno). */
@Composable
private fun TrendChart(values: List<Int>, modifier: Modifier) {
    Canvas(modifier) {
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        listOf(50 to FuncodeColors.Medium, 70 to FuncodeColors.Success).forEach { (g, color) ->
            val y = size.height * (1 - g / 100f)
            drawLine(color.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        }
        if (values.size < 2) return@Canvas
        val step = size.width / (values.size - 1)
        val points = values.mapIndexed { i, v -> Offset(i * step, size.height * (1 - v / 100f)) }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, FuncodeColors.Electric, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        points.forEachIndexed { i, p -> drawCircle(if (i == points.lastIndex) FuncodeColors.Gold else FuncodeColors.ElectricSoft, 3.5.dp.toPx(), p) }
    }
}

@Composable
private fun UsagePanel(d: Dashboard) {
    FuncodePanel(accent = FuncodeColors.Electric, modifier = Modifier.testTag("dash_usage").animateContentSize()) {
        Text(stringResource(R.string.dash_technologies_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (d.technologies.isEmpty()) {
            Note(stringResource(R.string.dash_technologies_none))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.technologies.forEach { TechnologyChip(it) }
            }
            Note(stringResource(R.string.dash_technologies_note))
        }
        if (d.languages.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.dash_languages_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            val max = d.languages.maxOf { it.lines ?: 0L }.coerceAtLeast(1L)
            d.languages.forEach { l ->
                Column(Modifier.semantics(mergeDescendants = true) {}.testTag("dash_language_${l.name}")) {
                    Row {
                        Text(l.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(
                            Labels.count(l.lines ?: 0L, "línea", "líneas") + " · " + pluralStringResource(R.plurals.dash_in_projects, l.projects, l.projects),
                            style = MaterialTheme.typography.bodySmall,
                            color = FuncodeColors.TextSecondary,
                        )
                    }
                    Bar((l.lines ?: 0L).toFloat() / max, FuncodeColors.ElectricSoft)
                }
            }
        }
    }
}

@Composable
private fun TechnologyChip(t: Usage) {
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("dash_technology_${t.name}"),
    ) {
        Text(t.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(t.category, pluralStringResource(R.plurals.dash_in_projects, t.projects, t.projects)).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = FuncodeColors.TextSecondary,
        )
    }
}

@Composable
private fun RecentRow(a: AnalysisDigest, zone: ZoneId, actions: DashboardActions, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.dash_open_analysis)) { actions.onOpenAnalysis(a.id) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("dash_recent_${a.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.archiveName.revealControlCharacters(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Labels.dateTime(a.startedAtEpochMs, zone) + " · " + Labels.count(a.fileCount, "archivo", "archivos"),
                style = MaterialTheme.typography.bodySmall,
                color = FuncodeColors.TextSecondary,
            )
        }
        val (label, color) = outcomeLabel(a.outcome ?: AnalysisOutcome.FAILED)
        StatusChip(label, color)
    }
}

@Composable
private fun InstallationPanel(container: AppContainer, diagnostics: Int, zone: ZoneId, actions: DashboardActions) {
    val biometric = remember { container.vault.isBiometricEnabled() }
    val vulnDb = remember { container.vulnDbStore.status() }
    FuncodePanel(accent = FuncodeColors.Outline, modifier = Modifier.testTag("dash_installation")) {
        Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        StatusLine(stringResource(R.string.status_access), ok = true)
        StatusLine(stringResource(R.string.status_database), ok = true)
        StatusLine(stringResource(if (biometric) R.string.status_biometric_on else R.string.status_biometric_off), ok = biometric)
        if (vulnDb == null) {
            StatusLine(stringResource(R.string.dash_vulndb_none), ok = false)
        } else {
            StatusLine(stringResource(R.string.dash_vulndb_date, Labels.date(vulnDb.createdAt, zone)), ok = true)
        }
        StatusLine(stringResource(R.string.status_diagnostics, diagnostics), ok = null)
        if (vulnDb == null) {
            OutlinedButton(onClick = actions.onOpenSettings) { Text(stringResource(R.string.dash_download_db)) }
        }
    }
}

@Composable
private fun StatusLine(text: String, ok: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (ok == true) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = null,
                tint = when (ok) {
                    true -> FuncodeColors.Success
                    false -> FuncodeColors.Medium
                    null -> FuncodeColors.ElectricSoft
                },
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

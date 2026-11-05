package com.funcodestudiocb.analyzer.ui.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.ui.analysis.severityColor
import com.funcodestudiocb.analyzer.ui.analysis.severityLabel
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

/**
 * Pantalla Seguridad (Fase 6): aviso de los límites del análisis estático, totales, filtros por severidad, certeza y
 * categoría, lista de hallazgos con su ficha completa, comprobaciones que requieren prueba dinámica y lo que esta
 * versión no evalúa.
 */
@Composable
fun SecurityScreen(
    viewModel: SecurityViewModel,
    onOpenFile: (analysisId: String, path: String) -> Unit,
    onOpenDependencies: (analysisId: String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val analyses by viewModel.analyses.collectAsStateWithLifecycle()
    val loaded = state as? SecurityUiState.Loaded
    BackHandler(enabled = loaded?.selected != null) { viewModel.back() }

    when (val s = state) {
        SecurityUiState.Loading -> Centered {
            CircularProgressIndicator()
            Text(stringResource(R.string.security_loading), Modifier.padding(top = 12.dp))
        }
        SecurityUiState.NoAnalyses -> Centered { Text(stringResource(R.string.security_no_analyses)) }
        SecurityUiState.NotFound -> Centered { Text(stringResource(R.string.security_not_found)) }
        is SecurityUiState.Loaded -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val open = { path: String -> onOpenFile(s.analysis.id, path) }
            val selected = s.selected?.let { s.report.security.getOrNull(it) }
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.45f).fillMaxHeight()) { FindingList(s, analyses, viewModel, onOpenDependencies) }
                    Box(Modifier.weight(0.55f).fillMaxHeight()) {
                        if (selected != null) DetailPane(selected, onBack = null, onOpenFile = open)
                    }
                }
            } else if (selected != null) {
                DetailPane(selected, onBack = { viewModel.select(null) }, onOpenFile = open)
            } else {
                FindingList(s, analyses, viewModel, onOpenDependencies)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
}

@Composable
private fun DetailPane(finding: com.funcodestudiocb.analyzer.engine.api.SecurityFinding, onBack: (() -> Unit)?, onOpenFile: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("finding_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.security_back))
            }
        }
        FindingDetail(finding, onOpenFile)
    }
}

@Composable
internal fun AnalysisPicker(current: AnalysisEntity, analyses: List<AnalysisEntity>, onPick: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            current.archiveName.revealControlCharacters(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (analyses.size > 1) {
            Box {
                TextButton(onClick = { open = true }) { Text(stringResource(R.string.explorer_change_analysis)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    analyses.forEach { a ->
                        DropdownMenuItem(
                            text = { Text(a.archiveName.revealControlCharacters(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                open = false
                                if (a.id != current.id) onPick(a.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FindingList(s: SecurityUiState.Loaded, analyses: List<AnalysisEntity>, viewModel: SecurityViewModel, onOpenDependencies: (String) -> Unit) {
    val report = s.report
    val summary = report.securitySummary
    LazyColumn(Modifier.fillMaxSize().testTag("security_list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { AnalysisPicker(s.analysis, analyses, viewModel::pick) }
        item {
            FuncodePanel(accent = FuncodeColors.Gold) {
                Text(stringResource(R.string.security_disclaimer), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("security_disclaimer"))
            }
        }
        if (!report.hasSecurity) {
            item { Text(stringResource(R.string.security_legacy), modifier = Modifier.testTag("security_legacy")) }
            return@LazyColumn
        }
        if (summary != null) {
            item {
                FuncodePanel {
                    Text(stringResource(R.string.security_totals, summary.findings, summary.rules, summary.filesExamined), fontWeight = FontWeight.Medium, modifier = Modifier.testTag("security_totals"))
                    if (summary.downgraded > 0) Text(stringResource(R.string.security_downgraded_count, summary.downgraded), style = MaterialTheme.typography.bodySmall)
                    if (summary.suppressions > 0) Text(stringResource(R.string.security_suppressions_count, summary.suppressions), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // Enlace a las dependencias solo si las hay (o hay versiones con fin de soporte).
        report.dependencySummary?.takeIf { it.direct + it.indirect > 0 || report.endOfLife.isNotEmpty() }?.let { deps ->
            item {
                FuncodePanel(accent = FuncodeColors.Electric, modifier = Modifier.testTag("security_dependencies")) {
                    Text(stringResource(R.string.security_dependencies_link, deps.direct, deps.indirect, deps.vulnerablePackages), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onOpenDependencies(s.analysis.id) }, modifier = Modifier.testTag("security_open_dependencies")) { Text(stringResource(R.string.dep_open)) }
                }
            }
        }
        if (report.security.isNotEmpty()) item { Filters(s, viewModel) }
        if (report.security.isEmpty()) {
            item { Text(stringResource(R.string.security_none), modifier = Modifier.testTag("security_none")) }
        } else if (s.visible.isEmpty()) {
            item { Text(stringResource(R.string.security_none_filtered)) }
        }
        items(s.visible, key = { it.index }) { (index, finding) ->
            FindingRow(finding, onClick = { viewModel.select(index) }, modifier = Modifier.testTag("finding_row_$index"))
            HorizontalDivider()
        }
        if (summary != null && summary.dynamicChecks.isNotEmpty()) {
            item {
                FuncodePanel(accent = FuncodeColors.Electric) {
                    SectionTitle(stringResource(R.string.security_dynamic_title))
                    Text(stringResource(R.string.security_dynamic_intro), style = MaterialTheme.typography.bodySmall)
                    summary.dynamicChecks.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("security_dynamic_item")) }
                }
            }
        }
        if (summary != null && summary.notEvaluated.isNotEmpty()) {
            item {
                FuncodePanel {
                    SectionTitle(stringResource(R.string.security_not_evaluated_title))
                    summary.notEvaluated.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("security_not_evaluated_item")) }
                }
            }
        }
        val coverage = report.coverage.filter { it.stageId == "security" }
        if (coverage.isNotEmpty()) {
            item {
                FuncodePanel(accent = FuncodeColors.Medium) {
                    SectionTitle(stringResource(R.string.security_coverage_title))
                    coverage.forEach { Text("• " + it.detail, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun Filters(s: SecurityUiState.Loaded, viewModel: SecurityViewModel) {
    val f = s.filters
    val counts = s.report.securitySummary?.bySeverity.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.security_filter_severity))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Severity.entries.filter { (counts[it] ?: 0) > 0 }) { sev ->
                FilterChip(
                    selected = sev in f.severities,
                    onClick = { viewModel.toggleSeverity(sev) },
                    label = { Text("${severityLabel(sev)} ${counts[sev] ?: 0}") },
                    leadingIcon = { StatusDot(sev) },
                    modifier = Modifier.testTag("filter_severity_${sev.name}"),
                )
            }
        }
        SectionTitle(stringResource(R.string.security_filter_certainty))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Certainty.entries.filter { c -> s.report.security.any { it.certainty == c } }) { c ->
                FilterChip(
                    selected = c in f.certainties,
                    onClick = { viewModel.toggleCertainty(c) },
                    label = { Text(certaintyLabel(c)) },
                    modifier = Modifier.testTag("filter_certainty_${c.name}"),
                )
            }
        }
        if (s.categories.size > 1) {
            SectionTitle(stringResource(R.string.security_filter_category))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(selected = f.category == null, onClick = { viewModel.setCategory(null) }, label = { Text(stringResource(R.string.security_filter_all)) })
                }
                items(s.categories) { c ->
                    FilterChip(
                        selected = f.category == c,
                        onClick = { viewModel.setCategory(if (f.category == c) null else c) },
                        label = { Text(categoryLabel(c)) },
                        modifier = Modifier.testTag("filter_category_${c.name}"),
                    )
                }
            }
        }
        if (f.active) TextButton(onClick = viewModel::clearFilters) { Text(stringResource(R.string.security_clear_filters)) }
    }
}

@Composable
private fun StatusDot(severity: Severity) {
    Box(Modifier.size(10.dp).background(severityColor(severity), CircleShape))
}

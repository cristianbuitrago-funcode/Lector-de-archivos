package com.funcodestudiocb.analyzer.ui.dependencies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.engine.api.EolStatus
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.explorer.ecosystemLabel
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

/**
 * Dependencias de un análisis (Fase 7): totales y estado de la base, filtros, lista ordenada por riesgo, ficha de cada
 * dependencia (versión, de dónde sale, cadenas que la introducen, uso real, criticidad y vulnerabilidades) y fin de
 * soporte de entornos, frameworks y bases de datos.
 */
@Composable
fun DependenciesScreen(viewModel: DependenciesViewModel, onOpenFile: (analysisId: String, path: String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loaded = state as? DependenciesUiState.Loaded
    BackHandler(enabled = loaded?.selected != null) { viewModel.back() }

    when (val s = state) {
        DependenciesUiState.Loading -> Centered {
            CircularProgressIndicator()
            Text(stringResource(R.string.dep_loading), Modifier.padding(top = 12.dp))
        }
        DependenciesUiState.NotFound -> Centered { Text(stringResource(R.string.security_not_found)) }
        is DependenciesUiState.Loaded -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val open = { path: String -> onOpenFile(s.analysis.id, path) }
            val selected = s.selected?.let { s.report.resolvedDependencies.getOrNull(it) }
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.45f).fillMaxHeight()) { DependencyList(s, viewModel, open) }
                    Box(Modifier.weight(0.55f).fillMaxHeight()) {
                        if (selected != null) DetailPane(selected, onBack = null, onOpenFile = open)
                    }
                }
            } else if (selected != null) {
                DetailPane(selected, onBack = { viewModel.select(null) }, onOpenFile = open)
            } else {
                DependencyList(s, viewModel, open)
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
private fun DetailPane(d: com.funcodestudiocb.analyzer.engine.api.ResolvedDependency, onBack: (() -> Unit)?, onOpenFile: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("dependency_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.security_back))
            }
        }
        DependencyDetail(d, onOpenFile)
    }
}

@Composable
private fun DependencyList(s: DependenciesUiState.Loaded, viewModel: DependenciesViewModel, onOpenFile: (String) -> Unit) {
    val report = s.report
    val summary = report.dependencySummary
    LazyColumn(Modifier.fillMaxSize().testTag("dependency_list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                s.analysis.archiveName.revealControlCharacters(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item {
            FuncodePanel(accent = FuncodeColors.Gold) {
                Text(stringResource(R.string.dep_disclaimer), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("dependency_disclaimer"))
            }
        }
        if (!report.hasDependencyAudit) {
            item { Text(stringResource(R.string.dep_legacy), modifier = Modifier.testTag("dependency_legacy")) }
            return@LazyColumn
        }
        if (summary != null) {
            item {
                FuncodePanel {
                    Text(stringResource(R.string.dep_totals, summary.direct, summary.indirect, summary.lockfiles), fontWeight = FontWeight.Medium, modifier = Modifier.testTag("dependency_totals"))
                    Text(
                        if (summary.vulnerabilities == 0) stringResource(R.string.dep_summary_no_vulns)
                        else stringResource(R.string.dep_summary_vulns, summary.vulnerablePackages, summary.vulnerabilities),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (summary.malicious > 0) Text(stringResource(R.string.dep_summary_malicious, summary.malicious), color = FuncodeColors.Critical, fontWeight = FontWeight.Medium)
                    if (summary.notSeen > 0) Text(stringResource(R.string.dep_summary_not_seen, summary.notSeen), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.dep_exact_versions, summary.exactVersions, summary.direct + summary.indirect), style = MaterialTheme.typography.bodySmall)
                    DatabaseState(report)
                }
            }
        }
        s.filters.file?.let { file ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("dependency_file_filter")) {
                    Text(stringResource(R.string.dep_only_file, file.revealControlCharacters()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearFile) { Text(stringResource(R.string.dep_show_all)) }
                }
            }
        }
        if (report.resolvedDependencies.isNotEmpty()) item { Filters(s, viewModel) }
        if (report.resolvedDependencies.isEmpty()) {
            item { Text(stringResource(R.string.dep_none), modifier = Modifier.testTag("dependency_none")) }
        } else if (s.visible.isEmpty()) {
            item { Text(stringResource(R.string.dep_none_filtered)) }
        } else {
            item { Text(stringResource(R.string.dep_showing, s.visible.size, report.resolvedDependencies.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(s.visible.take(MAX_ROWS), key = { it.index }) { (index, d) ->
            DependencyRow(d, onClick = { viewModel.select(index) }, modifier = Modifier.testTag("dependency_row_$index"))
            HorizontalDivider()
        }
        if (s.visible.size > MAX_ROWS) {
            item { Text(stringResource(R.string.dep_too_many, MAX_ROWS), style = MaterialTheme.typography.labelSmall) }
        }
        val eol = report.endOfLife.filter { s.filters.file == null || it.path == s.filters.file }
            .sortedBy { listOf(EolStatus.UNSUPPORTED, EolStatus.ENDING_SOON, EolStatus.UNKNOWN_CYCLE, EolStatus.SUPPORTED).indexOf(it.status) }
        if (eol.isNotEmpty()) {
            item {
                FuncodePanel(accent = if (eol.any { it.status == EolStatus.UNSUPPORTED }) FuncodeColors.High else FuncodeColors.Electric, modifier = Modifier.testTag("dependency_eol")) {
                    SectionTitle(stringResource(R.string.eol_title))
                    eol.forEach { EndOfLifeItem(it, onOpenFile) }
                    summary?.eolDataDate?.let { Text(stringResource(R.string.eol_data_date, formatDay(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        val coverage = report.coverage.filter { it.stageId == "dependency-audit" || it.stageId == "dependencies" }
        if (coverage.isNotEmpty()) {
            item {
                FuncodePanel(accent = FuncodeColors.Medium) {
                    SectionTitle(stringResource(R.string.security_coverage_title))
                    coverage.forEach { Text("• " + it.detail.revealControlCharacters(), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun Filters(s: DependenciesUiState.Loaded, viewModel: DependenciesViewModel) {
    val f = s.filters
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = f.query,
            onValueChange = viewModel::setQuery,
            label = { Text(stringResource(R.string.dep_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("dependency_search"),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(DependencyKind.entries) { kind ->
                FilterChip(
                    selected = f.kind == kind,
                    onClick = { viewModel.setKind(kind) },
                    label = {
                        Text(
                            stringResource(
                                when (kind) {
                                    DependencyKind.ALL -> R.string.dep_filter_all
                                    DependencyKind.DIRECT -> R.string.dep_filter_direct
                                    DependencyKind.INDIRECT -> R.string.dep_filter_indirect
                                },
                            ),
                        )
                    },
                    modifier = Modifier.testTag("dep_filter_${kind.name}"),
                )
            }
            item {
                FilterChip(
                    selected = f.onlyVulnerable,
                    onClick = viewModel::toggleOnlyVulnerable,
                    label = { Text(stringResource(R.string.dep_filter_vulnerable)) },
                    modifier = Modifier.testTag("dep_filter_vulnerable"),
                )
            }
        }
        if (s.usages.size > 1) {
            SectionTitle(stringResource(R.string.dep_filter_usage))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(s.usages) { u ->
                    FilterChip(selected = f.usage == u, onClick = { viewModel.setUsage(u) }, label = { Text(usageLabel(u)) }, modifier = Modifier.testTag("dep_filter_usage_${u.name}"))
                }
            }
        }
        if (s.ecosystems.size > 1) {
            SectionTitle(stringResource(R.string.dep_filter_ecosystem))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(s.ecosystems) { e ->
                    FilterChip(selected = f.ecosystem == e, onClick = { viewModel.setEcosystem(e) }, label = { Text(ecosystemLabel(e)) })
                }
            }
        }
        if (f.active) TextButton(onClick = viewModel::clearFilters) { Text(stringResource(R.string.security_clear_filters)) }
    }
}

/** Filas como mucho en la lista (el resto se alcanza filtrando o buscando). */
private const val MAX_ROWS = 2_000

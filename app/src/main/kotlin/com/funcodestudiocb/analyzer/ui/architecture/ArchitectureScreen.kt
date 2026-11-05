package com.funcodestudiocb.analyzer.ui.architecture

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.funcodestudiocb.analyzer.engine.api.Diagram
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.security.AnalysisPicker
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

/**
 * Pantalla Arquitectura (Fase 8): diagrama por capas, flujos de información, módulos, proyectos con sus dependencias y
 * el grafo de un archivo con foco. Todo sale de lo que el motor encontró en el código; lo deducido por convención se
 * marca como probable y lo que no se pudo deducir se dice.
 */
@Composable
fun ArchitectureScreen(
    viewModel: ArchitectureViewModel,
    onOpenFile: (analysisId: String, path: String) -> Unit,
    onOpenDependencies: (analysisId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val analyses by viewModel.analyses.collectAsStateWithLifecycle()
    val loaded = state as? ArchitectureUiState.Loaded
    BackHandler(enabled = loaded != null && (!loaded.selection.empty || (loaded.tab == ArchTab.FILES && loaded.focus != null))) { viewModel.back() }

    when (val s = state) {
        ArchitectureUiState.Loading -> Centered {
            CircularProgressIndicator()
            Text(stringResource(R.string.arch_loading), Modifier.padding(top = 12.dp))
        }
        ArchitectureUiState.NoAnalyses -> Centered { Text(stringResource(R.string.security_no_analyses), modifier = Modifier.testTag("arch_no_analyses")) }
        ArchitectureUiState.NotFound -> Centered { Text(stringResource(R.string.security_not_found)) }
        is ArchitectureUiState.Loaded -> {
            val open = { path: String -> onOpenFile(s.analysis.id, path) }
            Column(Modifier.fillMaxSize()) {
                Header(s, analyses, viewModel)
                if (!s.report.hasArchitecture) {
                    Text(stringResource(R.string.arch_legacy), modifier = Modifier.padding(12.dp).testTag("arch_legacy"))
                    return@Column
                }
                when (s.tab) {
                    ArchTab.FLOWS -> Flows(s, viewModel, open)
                    ArchTab.FILES -> if (s.focus == null) FilePicker(s, viewModel) else DiagramTab(s, viewModel, open, onOpenDependencies)
                    else -> DiagramTab(s, viewModel, open, onOpenDependencies)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

@Composable
private fun Header(s: ArchitectureUiState.Loaded, analyses: List<AnalysisEntity>, viewModel: ArchitectureViewModel) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AnalysisPicker(s.analysis, analyses, viewModel::pick)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ArchTab.entries) { tab ->
                FilterChip(
                    selected = s.tab == tab,
                    onClick = { viewModel.setTab(tab) },
                    label = {
                        Text(
                            stringResource(
                                when (tab) {
                                    ArchTab.LAYERS -> R.string.arch_tab_layers
                                    ArchTab.FLOWS -> R.string.arch_tab_flows
                                    ArchTab.MODULES -> R.string.arch_tab_modules
                                    ArchTab.PROJECTS -> R.string.arch_tab_projects
                                    ArchTab.FILES -> R.string.arch_tab_files
                                },
                            ) + if (tab == ArchTab.FLOWS) " ${s.report.flows.size}" else "",
                        )
                    },
                    modifier = Modifier.testTag("arch_tab_${tab.name}"),
                )
            }
        }
    }
}

/** Resumen compacto encima del diagrama de capas, con las notas plegables. */
@Composable
private fun Summary(s: ArchitectureUiState.Loaded) {
    val summary = s.report.architecture ?: return
    var notes by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 12.dp).testTag("arch_summary")) {
        Text(
            listOfNotNull(
                summary.pattern?.let { stringResource(R.string.arch_pattern, it) },
                stringResource(R.string.arch_classified, summary.classified, summary.files),
                if (summary.inverted > 0) stringResource(R.string.arch_inverted_count, summary.inverted) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { notes = !notes }, modifier = Modifier.testTag("arch_notes_toggle")) {
            Text(stringResource(if (notes) R.string.arch_hide_notes else R.string.arch_show_notes))
        }
        if (notes) {
            FuncodePanel(accent = FuncodeColors.Gold) {
                summary.notes.forEach { Text("• " + it.revealControlCharacters(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("arch_note")) }
                summary.invertedSamples.forEach { Text("↺ " + it.revealControlCharacters(), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.High) }
                DiagramLegend(Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun DiagramTab(s: ArchitectureUiState.Loaded, viewModel: ArchitectureViewModel, onOpenFile: (String) -> Unit, onOpenDependencies: (String) -> Unit) {
    val diagram = s.diagram()
    if (s.tab == ArchTab.LAYERS) Summary(s)
    if (s.tab == ArchTab.FILES) FocusOptionsRow(s, viewModel)
    if (diagram == null) {
        if (s.tab == ArchTab.FILES) {
            Centered { CircularProgressIndicator() }
        } else {
            Text(stringResource(R.string.arch_no_diagram), modifier = Modifier.padding(12.dp))
        }
        return
    }
    if (diagram.nodes.isEmpty()) {
        Text(stringResource(R.string.arch_empty_diagram), modifier = Modifier.padding(12.dp).testTag("arch_empty"))
        return
    }
    diagram.note?.let { Text(it.revealControlCharacters(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp)) }
    if (s.tab == ArchTab.FILES && diagram.truncated) {
        Text(stringResource(R.string.arch_focus_truncated, FocusGraph.MAX_NODES), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Medium, modifier = Modifier.padding(horizontal = 12.dp))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val detail: @Composable (Modifier) -> Unit = { m ->
            Column(m.verticalScroll(rememberScrollState()).padding(12.dp)) {
                NodeChips(diagram, s, viewModel)
                if (!s.selection.empty) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SelectionDetail(s, diagram, onOpenFile, viewModel::focus, { onOpenDependencies(s.analysis.id) }, viewModel::selectNode)
                } else {
                    Text(stringResource(R.string.arch_tap_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                DiagramArea(diagram, s, viewModel, Modifier.weight(0.62f).fillMaxHeight())
                detail(Modifier.weight(0.38f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                DiagramArea(diagram, s, viewModel, Modifier.fillMaxWidth().weight(1f))
                detail(Modifier.fillMaxWidth().heightIn(max = if (s.selection.empty) 120.dp else 300.dp))
            }
        }
    }
}

@Composable
private fun DiagramArea(diagram: Diagram, s: ArchitectureUiState.Loaded, viewModel: ArchitectureViewModel, modifier: Modifier) {
    DiagramCanvas(
        diagram, s.selection,
        onSelectNode = { id -> if (id == null) viewModel.clearSelection() else viewModel.selectNode(id) },
        onSelectEdge = viewModel::selectEdge,
        modifier = modifier,
    )
}

/** Elementos del diagrama como botones: accesibles y útiles cuando el dibujo es grande. */
@Composable
private fun NodeChips(diagram: Diagram, s: ArchitectureUiState.Loaded, viewModel: ArchitectureViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("arch_node_chips")) {
        items(diagram.nodes.sortedBy { it.label.lowercase() }, key = { it.id }) { n ->
            FilterChip(
                selected = s.selection.node == n.id,
                onClick = { if (s.selection.node == n.id) viewModel.clearSelection() else viewModel.selectNode(n.id) },
                label = { Text(n.label.revealControlCharacters(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Box(Modifier.padding(start = 2.dp)) { LayerDot(n.layer) } },
                modifier = Modifier.testTag("arch_node_${n.id}"),
            )
        }
    }
}

@Composable
private fun FocusOptionsRow(s: ArchitectureUiState.Loaded, viewModel: ArchitectureViewModel) {
    val o = s.focusOptions
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.arch_focus_on, s.focus.orEmpty().substringAfterLast('/').revealControlCharacters()),
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).testTag("arch_focus_title"), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { viewModel.focus(null) }) { Text(stringResource(R.string.arch_change_file)) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(o.depth == 1, { viewModel.setFocusOptions(o.copy(depth = 1)) }, { Text(stringResource(R.string.arch_depth_1)) }, Modifier.testTag("arch_depth_1")) }
            item { FilterChip(o.depth == 2, { viewModel.setFocusOptions(o.copy(depth = 2)) }, { Text(stringResource(R.string.arch_depth_2)) }, Modifier.testTag("arch_depth_2")) }
            items(FocusDirection.entries) { d ->
                FilterChip(
                    o.direction == d, { viewModel.setFocusOptions(o.copy(direction = d)) },
                    {
                        Text(
                            stringResource(
                                when (d) {
                                    FocusDirection.BOTH -> R.string.arch_dir_both
                                    FocusDirection.OUTGOING -> R.string.arch_dir_out
                                    FocusDirection.INCOMING -> R.string.arch_dir_in
                                },
                            ),
                        )
                    },
                    Modifier.testTag("arch_dir_${d.name}"),
                )
            }
            item { FilterChip(o.onlyStrong, { viewModel.setFocusOptions(o.copy(onlyStrong = !o.onlyStrong)) }, { Text(stringResource(R.string.arch_only_strong)) }, Modifier.testTag("arch_only_strong")) }
        }
    }
}

@Composable
private fun FilePicker(s: ArchitectureUiState.Loaded, viewModel: ArchitectureViewModel) {
    LazyColumn(Modifier.fillMaxSize().testTag("arch_file_picker"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text(stringResource(R.string.arch_pick_file), style = MaterialTheme.typography.bodySmall) }
        item {
            OutlinedTextField(
                value = s.fileQuery, onValueChange = viewModel::setFileQuery, singleLine = true,
                label = { Text(stringResource(R.string.dep_search)) }, modifier = Modifier.fillMaxWidth().testTag("arch_file_search"),
            )
        }
        if (s.fileQuery.isBlank()) item { SectionTitle(stringResource(R.string.arch_most_connected)) }
        items(s.fileSuggestions, key = { it }) { path ->
            val l = s.layers[path]
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.focus(path) }.padding(vertical = 6.dp).testTag("arch_pick:$path"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LayerDot(l?.layer)
                Column(Modifier.weight(1f)) {
                    Text(path.substringAfterLast('/').revealControlCharacters(), fontWeight = FontWeight.Medium)
                    Text(path.substringBeforeLast('/', "").revealControlCharacters(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                l?.let { Text(layerLabel(it.layer), style = MaterialTheme.typography.labelSmall, color = layerColor(it.layer)) }
            }
            HorizontalDivider()
        }
        if (s.fileSuggestions.isEmpty()) item { Text(stringResource(R.string.arch_no_files)) }
    }
}

@Composable
private fun Flows(s: ArchitectureUiState.Loaded, viewModel: ArchitectureViewModel, onOpenFile: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("arch_flows"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FuncodePanel(accent = FuncodeColors.Gold) {
                Text(stringResource(R.string.arch_flows_intro), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (s.report.flows.isEmpty()) {
            item {
                Text(
                    s.report.architecture?.notes?.firstOrNull { "ruta" in it } ?: stringResource(R.string.arch_no_flows),
                    modifier = Modifier.testTag("arch_no_flows"),
                )
            }
            return@LazyColumn
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = s.flowQuery, onValueChange = viewModel::setFlowQuery, singleLine = true,
                    label = { Text(stringResource(R.string.arch_flow_search)) }, modifier = Modifier.fillMaxWidth().testTag("arch_flow_search"),
                )
                FilterChip(s.onlyCompleteFlows, viewModel::toggleOnlyComplete, { Text(stringResource(R.string.arch_only_complete)) }, Modifier.testTag("arch_only_complete"))
                Text(stringResource(R.string.dep_showing, s.visibleFlows.size, s.report.flows.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(s.visibleFlows, key = { it.index }) { f -> FlowCard(f, onOpenFile) }
    }
}

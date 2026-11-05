package com.funcodestudiocb.analyzer.ui.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.engine.api.ContentState
import com.funcodestudiocb.analyzer.engine.api.EntryPoint
import com.funcodestudiocb.analyzer.engine.api.Evidence
import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.LineMethod
import com.funcodestudiocb.analyzer.ui.analysis.formatBytes
import com.funcodestudiocb.analyzer.ui.analysis.statusLabel
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors

@Composable
fun ExplorerScreen(viewModel: ExplorerViewModel, onOpenDependencies: (analysisId: String, file: String) -> Unit = { _, _ -> }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val analyses by viewModel.analyses.collectAsStateWithLifecycle()
    val loaded = state as? ExplorerUiState.Loaded
    BackHandler(enabled = loaded != null && (loaded.finding != null || loaded.relation != null || loaded.code != null || loaded.selected != null)) { viewModel.back() }

    when (val s = state) {
        ExplorerUiState.Loading -> Centered {
            CircularProgressIndicator()
            Text(stringResource(R.string.explorer_loading), Modifier.padding(top = 12.dp))
        }
        ExplorerUiState.NoAnalyses -> Centered { Text(stringResource(R.string.explorer_no_analyses)) }
        ExplorerUiState.NotFound -> Centered { Text(stringResource(R.string.explorer_not_found)) }
        is ExplorerUiState.Loaded -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.45f).fillMaxHeight()) { TreePane(s, analyses, viewModel) }
                    Box(Modifier.weight(0.55f).fillMaxHeight()) {
                        when {
                            s.finding != null -> FindingPane(s.finding, viewModel)
                            s.relation != null -> RelationDetail(s.relation, viewModel::closeRelation, onOpenFile = viewModel::select)
                            s.code != null -> CodeViewer(s.code, viewModel::closeCode)
                            s.selected != null -> FileDetail(s, s.selected, viewModel, onClose = null, onOpenDependencies)
                        }
                    }
                }
            } else {
                when {
                    s.finding != null -> FindingPane(s.finding, viewModel)
                    s.relation != null -> RelationDetail(s.relation, viewModel::closeRelation, onOpenFile = viewModel::select)
                    s.code != null -> CodeViewer(s.code, viewModel::closeCode)
                    s.selected != null -> FileDetail(s, s.selected, viewModel, onClose = { viewModel.select(null) }, onOpenDependencies)
                    else -> TreePane(s, analyses, viewModel)
                }
            }
        }
    }
}

/** Ficha de un hallazgo abierta desde la ficha del archivo. */
@Composable
private fun FindingPane(finding: com.funcodestudiocb.analyzer.engine.api.SecurityFinding, viewModel: ExplorerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        IconButton(onClick = viewModel::closeFinding, modifier = Modifier.testTag("finding_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.explorer_back))
        }
        // El archivo ya está abierto: la ficha no ofrece abrirlo otra vez.
        com.funcodestudiocb.analyzer.ui.security.FindingDetail(finding, onOpenFile = null)
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
private fun TreePane(s: ExplorerUiState.Loaded, analyses: List<com.funcodestudiocb.analyzer.data.db.AnalysisEntity>, viewModel: ExplorerViewModel) {
    val model = s.model
    var showEntries by rememberSaveable { mutableStateOf(true) }
    var showRoots by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("explorer_tree"), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            AnalysisPicker(s, analyses, viewModel::pick)
            val summary = model.report.inventory
            Text(
                stringResource(R.string.explorer_summary, model.files.size, model.report.entryPoints.size, summary?.projectRoots ?: model.report.projectRoots.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!model.hasInventory) {
                FuncodePanel(Modifier.padding(top = 8.dp), accent = FuncodeColors.Gold) { Text(stringResource(R.string.explorer_legacy)) }
            } else if (summary != null && !summary.copyEnabled) {
                Text(stringResource(R.string.explorer_copy_disabled_note), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Gold, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (model.hasInventory) {
            item {
                CollapsibleHeader(stringResource(R.string.explorer_entry_points) + " (${model.report.entryPoints.size})", showEntries) { showEntries = !showEntries }
            }
            if (showEntries) {
                if (model.report.entryPoints.isEmpty()) {
                    item { Text(stringResource(R.string.explorer_entry_points_none), style = MaterialTheme.typography.bodySmall) }
                }
                items(model.report.entryPoints.take(MAX_LISTED)) { ep -> EntryPointRow(ep, onOpen = { path -> viewModel.select(path) }) }
            }
            item {
                CollapsibleHeader(stringResource(R.string.explorer_subprojects) + " (${model.report.projectRoots.size})", showRoots) { showRoots = !showRoots }
            }
            if (showRoots) {
                items(model.report.projectRoots.take(MAX_LISTED)) { root ->
                    Text(
                        "${ecosystemLabel(root.ecosystem)} · ${root.directory.ifEmpty { stringResource(R.string.explorer_root_dir) }.revealControlCharacters()}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = s.query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.explorer_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("explorer_search"),
            )
            if (model.roleCounts.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    item {
                        FilterChip(selected = s.role == null, onClick = { viewModel.setRole(null) }, label = { Text(stringResource(R.string.explorer_filter_all)) })
                    }
                    items(model.roleCounts.entries.toList(), key = { it.key }) { (role, count) ->
                        FilterChip(
                            selected = s.role == role,
                            onClick = { viewModel.setRole(if (s.role == role) null else role) },
                            label = { Text("${roleLabel(role)} ($count)") },
                            modifier = Modifier.testTag("role_${role.name}"),
                        )
                    }
                }
            }
        }
        items(s.rows, key = { (if (it.node is DirNode) "d:" else "f:") + it.node.path }) { row ->
            TreeRowView(row, selected = row.node.path == s.selected, findings = model.findingsByFile, onClick = {
                when (val node = row.node) {
                    is DirNode -> if (node.excluded == null) viewModel.toggle(node.path)
                    is FileNode -> viewModel.select(node.path)
                }
            })
        }
        if (s.rows.isEmpty()) item { Text(stringResource(R.string.explorer_no_matches), Modifier.padding(8.dp)) }
    }
}

@Composable
private fun AnalysisPicker(s: ExplorerUiState.Loaded, analyses: List<com.funcodestudiocb.analyzer.data.db.AnalysisEntity>, onPick: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            s.analysis.archiveName.revealControlCharacters(),
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
                                if (a.id != s.analysis.id) onPick(a.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = FuncodeColors.Gold)
        SectionTitle(title)
    }
}

@Composable
private fun EntryPointRow(ep: EntryPoint, onOpen: (String) -> Unit) {
    val path = ep.path
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (path != null) Modifier.clickable { onOpen(path) } else Modifier)
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = FuncodeColors.Gold, modifier = Modifier.size(16.dp))
            Text(entryKindLabel(ep.kind), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            StatusChip(confidenceLabel(ep.confidence), confidenceColor(ep.confidence))
        }
        Text(
            (path ?: ep.target ?: "").revealControlCharacters(),
            style = MaterialTheme.typography.bodySmall,
            color = if (path != null) FuncodeColors.ElectricSoft else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (path == null) Text(stringResource(R.string.explorer_entry_missing), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Medium)
    }
}

@Composable
private fun TreeRowView(row: TreeRow, selected: Boolean, findings: Map<String, List<com.funcodestudiocb.analyzer.engine.api.SecurityFinding>>, onClick: () -> Unit) {
    val indent = (minOf(row.depth, MAX_INDENT_LEVELS) * 14).dp
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) FuncodeColors.Navy2 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = indent, top = 6.dp, bottom = 6.dp, end = 4.dp)
            .testTag("row:" + row.node.path),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (val node = row.node) {
            is DirNode -> {
                Icon(
                    if (row.expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(if (row.expanded) R.string.explorer_collapse else R.string.explorer_expand),
                    tint = if (node.excluded != null) FuncodeColors.Info else FuncodeColors.Electric,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(node.name.revealControlCharacters(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (node.excluded != null) FuncodeColors.Info else MaterialTheme.colorScheme.onSurface)
                    val excluded = node.excluded
                    Text(
                        if (excluded != null) {
                            stringResource(R.string.explorer_excluded_dir, pluralStringResource(R.plurals.explorer_dir_files, excluded.fileCount, excluded.fileCount, formatBytes(excluded.totalBytes)))
                        } else {
                            pluralStringResource(R.plurals.explorer_dir_files, node.fileCount, node.fileCount, formatBytes(node.totalBytes))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is FileNode -> {
                val file = node.file
                Box(Modifier.padding(start = 5.dp).size(8.dp).background(roleColor(file.primaryRole), CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(node.name.revealControlCharacters(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(file.primaryRole?.let { roleLabel(it) }, formatBytes(file.entry.sizeBytes)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (file.entryPoints.isNotEmpty()) Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.role_entry_point), tint = FuncodeColors.Gold, modifier = Modifier.size(16.dp))
                findings[file.path]?.firstOrNull()?.let { worst ->
                    Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.file_security), tint = com.funcodestudiocb.analyzer.ui.analysis.severityColor(worst.severity), modifier = Modifier.size(16.dp).testTag("tree_finding"))
                }
                if (file.redactions.isNotEmpty()) Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.file_secrets), tint = FuncodeColors.High, modifier = Modifier.size(16.dp))
                if (FileRole.POSSIBLY_UNNECESSARY in file.roles) Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.role_possibly_unnecessary), tint = FuncodeColors.Medium, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 96.dp, max = 140.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EvidenceLine(evidence: Evidence, onOpen: (String) -> Unit) {
    val where = listOfNotNull(
        evidence.line?.let { stringResource(R.string.file_evidence_line, it) },
        evidence.path?.let { stringResource(R.string.file_evidence_in, it.revealControlCharacters()) },
    ).joinToString(", ")
    val path = evidence.path
    Text(
        "• " + evidence.detail.revealControlCharacters() + if (where.isNotEmpty()) " ($where)" else "",
        style = MaterialTheme.typography.bodySmall,
        color = if (path != null) FuncodeColors.ElectricSoft else MaterialTheme.colorScheme.onSurface,
        modifier = if (path != null) Modifier.clickable { onOpen(path) } else Modifier,
    )
}

@Composable
private fun FileDetail(
    s: ExplorerUiState.Loaded,
    path: String,
    viewModel: ExplorerViewModel,
    onClose: (() -> Unit)?,
    onOpenDependencies: (analysisId: String, file: String) -> Unit,
) {
    val file = s.model.files[path] ?: return
    val profile = file.profile
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).testTag("file_detail"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.explorer_back)) }
            Text(file.name.revealControlCharacters(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        FuncodePanel(accent = FuncodeColors.Electric) {
            val entry = file.entry
            KeyValue(stringResource(R.string.file_location), file.directory.ifEmpty { stringResource(R.string.explorer_root_dir) }.revealControlCharacters())
            KeyValue(stringResource(R.string.file_extension), file.name.substringAfterLast('.', "").ifEmpty { stringResource(R.string.file_no_extension) })
            KeyValue(stringResource(R.string.file_size), formatBytes(entry.sizeBytes))
            KeyValue(
                stringResource(R.string.file_type),
                when (entry.kind) {
                    FileKind.TEXT -> stringResource(R.string.file_type_text, entry.encoding.orEmpty())
                    FileKind.BINARY -> stringResource(R.string.file_type_binary)
                    FileKind.ARCHIVE -> stringResource(R.string.file_type_archive)
                    FileKind.EMPTY -> stringResource(R.string.file_type_empty)
                    FileKind.UNKNOWN -> stringResource(R.string.file_type_unknown)
                },
            )
            profile?.lineCount?.let { KeyValue(stringResource(R.string.file_lines), it.toString()) }
            if (entry.status != FileStatus.ANALYZED) KeyValue(stringResource(R.string.file_status), statusLabel(entry.status))
            profile?.projectRoot?.let { root -> KeyValue(stringResource(R.string.file_project), root.ifEmpty { stringResource(R.string.explorer_root_dir) }.revealControlCharacters()) }
            entry.sha256?.let { KeyValue(stringResource(R.string.file_sha256), it, mono = true) }
        }

        profile?.language?.let { language ->
            FuncodePanel(accent = FuncodeColors.Electric) {
                SectionTitle(stringResource(R.string.file_language))
                Text("${language.name.revealControlCharacters()} · ${languageLevelLabel(language.level)}", fontWeight = FontWeight.Medium, modifier = Modifier.testTag("file_language"))
                Text(stringResource(R.string.file_lines_breakdown, language.code, language.comment, language.blank), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(if (language.method == LineMethod.SYNTAX) R.string.file_line_method_syntax else R.string.file_line_method_lexical),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (language.syntaxErrors > 0) {
                    Text(pluralStringResource(R.plurals.file_syntax_errors, language.syntaxErrors, language.syntaxErrors), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Medium)
                }
            }
        }

        val technologies = s.model.technologiesIn(path)
        if (technologies.isNotEmpty()) {
            FuncodePanel(accent = FuncodeColors.Gold) {
                SectionTitle(stringResource(R.string.file_technologies))
                technologies.forEach { (tech, evidence) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text(tech.name.revealControlCharacters(), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        StatusChip(confidenceLabel(tech.confidence), confidenceColor(tech.confidence))
                    }
                    evidence.forEach { e -> EvidenceLine(e.copy(path = null), onOpen = viewModel::select) }
                }
            }
        }

        FuncodePanel(accent = FuncodeColors.Gold) {
            SectionTitle(stringResource(R.string.file_function))
            if (profile == null || profile.roles.isEmpty()) {
                Text(stringResource(if (s.model.hasInventory) R.string.file_function_none else R.string.explorer_legacy), style = MaterialTheme.typography.bodySmall)
            }
            profile?.roles?.forEach { role ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Box(Modifier.size(8.dp).background(roleColor(role.role), CircleShape))
                    Text(roleLabel(role.role), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    StatusChip(confidenceLabel(role.confidence), confidenceColor(role.confidence))
                }
                role.evidence.forEach { EvidenceLine(it, onOpen = viewModel::select) }
            }
        }

        if (file.entryPoints.isNotEmpty()) {
            FuncodePanel(accent = FuncodeColors.Gold) {
                SectionTitle(stringResource(R.string.file_entry_points))
                file.entryPoints.forEach { ep ->
                    Text(entryKindLabel(ep.kind), fontWeight = FontWeight.Medium)
                    ep.evidence.forEach { EvidenceLine(it, onOpen = viewModel::select) }
                }
            }
        }

        if (s.model.hasRelations) RelationsPanel(s.model, path, viewModel::openRelation)

        FuncodePanel {
            SectionTitle(stringResource(R.string.file_related))
            val related = s.model.related(path)
            if (related.isEmpty()) Text(stringResource(R.string.file_related_none), style = MaterialTheme.typography.bodySmall)
            related.forEach { r ->
                Text(
                    "${relationLabel(r.reason)}: ${r.path.revealControlCharacters()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = FuncodeColors.ElectricSoft,
                    modifier = Modifier.clickable { viewModel.select(r.path) }.padding(vertical = 2.dp),
                )
            }
            if (!s.model.hasRelations) {
                Text(stringResource(R.string.file_related_phase5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val dependencies = s.model.dependenciesIn(path)
        val eol = s.model.report.endOfLife.filter { it.path == path }
        if (dependencies.isNotEmpty() || eol.isNotEmpty()) {
            val vulnerable = dependencies.filter { it.vulnerabilities.isNotEmpty() }
            FuncodePanel(
                accent = vulnerable.minOfOrNull { d -> d.vulnerabilities.minOf { it.severity.ordinal } }
                    ?.let { com.funcodestudiocb.analyzer.ui.analysis.severityColor(com.funcodestudiocb.analyzer.engine.api.Severity.entries[it]) } ?: FuncodeColors.Electric,
                modifier = Modifier.testTag("file_dependencies"),
            ) {
                SectionTitle(stringResource(R.string.file_dependencies))
                if (dependencies.isNotEmpty()) Text(stringResource(R.string.file_dependencies_count, dependencies.size, vulnerable.size), fontWeight = FontWeight.Medium)
                vulnerable.take(MAX_FINDINGS_LISTED).forEach { d ->
                    com.funcodestudiocb.analyzer.ui.dependencies.DependencyRow(d, onClick = { onOpenDependencies(s.analysis.id, path) })
                }
                eol.forEach { com.funcodestudiocb.analyzer.ui.dependencies.EndOfLifeItem(it, onOpenFile = null) }
                Button(onClick = { onOpenDependencies(s.analysis.id, path) }, modifier = Modifier.testTag("file_open_dependencies")) { Text(stringResource(R.string.dep_open)) }
            }
        }

        val findings = s.model.findingsByFile[path].orEmpty()
        if (findings.isNotEmpty()) {
            FuncodePanel(accent = com.funcodestudiocb.analyzer.ui.analysis.severityColor(findings.first().severity), modifier = Modifier.testTag("file_security")) {
                SectionTitle(stringResource(R.string.file_security))
                findings.take(MAX_FINDINGS_LISTED).forEach { f ->
                    com.funcodestudiocb.analyzer.ui.security.FindingRow(f, onClick = { viewModel.openFinding(f) })
                }
                if (findings.size > MAX_FINDINGS_LISTED) {
                    Text(stringResource(R.string.file_security_more, findings.size - MAX_FINDINGS_LISTED), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (file.redactions.isNotEmpty()) {
            FuncodePanel(accent = FuncodeColors.High) {
                SectionTitle(stringResource(R.string.file_secrets))
                file.redactions.take(MAX_LISTED).forEach { r ->
                    Text(
                        stringResource(R.string.file_secret_item, r.line, secretTypeLabel(r.type), r.length, r.masked),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        FuncodePanel {
            SectionTitle(stringResource(R.string.file_content))
            val content = profile?.content
            if (content != null) Text(contentStateLabel(content), style = MaterialTheme.typography.bodySmall)
            if (content == ContentState.STORED) {
                Button(onClick = viewModel::openCode, modifier = Modifier.testTag("file_view_code")) { Text(stringResource(R.string.file_view_code)) }
            }
        }
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun CodeViewer(code: CodeView, onClose: () -> Unit) {
    val name = code.path.substringAfterLast('/')
    Column(Modifier.fillMaxSize().testTag("code_viewer")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.explorer_back)) }
            Text(name.revealControlCharacters(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (code.findingLines.isNotEmpty()) {
            Text(
                stringResource(R.string.code_findings_banner),
                style = MaterialTheme.typography.bodySmall,
                color = FuncodeColors.Critical,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (code.redactedLines.isNotEmpty()) {
            Text(
                stringResource(R.string.code_secrets_banner),
                style = MaterialTheme.typography.bodySmall,
                color = FuncodeColors.Gold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        HorizontalDivider()
        val lines = code.lines
        when {
            lines == null -> Centered {
                CircularProgressIndicator()
                Text(stringResource(R.string.code_loading), Modifier.padding(top = 12.dp))
            }
            code.missing -> Centered { Text(stringResource(R.string.code_missing)) }
            else -> {
                val gutter = lines.size.toString().length
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                        itemsIndexed(lines) { index, raw ->
                            val number = index + 1
                            val (text, originalLength) = highlightedCodeLine(raw, code.highlight.getOrNull(index), MAX_LINE_CHARS)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        when {
                                            number in code.findingLines -> FuncodeColors.Critical.copy(alpha = 0.14f)
                                            number in code.redactedLines -> FuncodeColors.Gold.copy(alpha = 0.12f)
                                            else -> androidx.compose.ui.graphics.Color.Transparent
                                        },
                                    )
                                    .testTag("code_line_$number"),
                            ) {
                                // Margen fijo para la marca de hallazgo: los números de línea no se mueven.
                                if (code.findingLines.isNotEmpty()) {
                                    val severity = code.findingLines[number]
                                    Box(Modifier.padding(top = 5.dp, end = 3.dp).size(6.dp)) {
                                        if (severity != null) {
                                            Box(
                                                Modifier.size(6.dp)
                                                    .background(com.funcodestudiocb.analyzer.ui.analysis.severityColor(severity), CircleShape)
                                                    .testTag("code_finding_$number"),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    number.toString().padStart(gutter),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = FuncodeColors.Info,
                                    modifier = Modifier.width((gutter * 8 + 8).dp),
                                )
                                val suffix = if (originalLength != null) stringResource(R.string.code_long_line, originalLength) else ""
                                Text(
                                    if (suffix.isEmpty()) text else text + androidx.compose.ui.text.AnnotatedString(suffix),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Prepara una línea de código para mostrarla: tabuladores como espacios y cualquier carácter de control o de
 * dirección bidireccional visible (contra el "Trojan Source"). Las líneas muy largas se recortan.
 * Devuelve el texto y, si se recortó, la longitud original.
 */
internal fun displayCodeLine(raw: String, maxChars: Int = MAX_LINE_CHARS): Pair<String, Int?> =
    highlightedCodeLine(raw, null, maxChars).let { (text, original) -> text.text to original }

private const val MAX_LISTED = 200
private const val MAX_FINDINGS_LISTED = 30
private const val MAX_INDENT_LEVELS = 12
private const val MAX_LINE_CHARS = 2_000

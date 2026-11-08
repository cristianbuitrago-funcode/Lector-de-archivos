package com.funcodestudiocb.analyzer.ui.reports

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.engine.report.AnalysisOutcome
import com.funcodestudiocb.analyzer.engine.report.Block
import com.funcodestudiocb.analyzer.engine.report.Labels
import com.funcodestudiocb.analyzer.reports.ReportFormat
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import java.io.File
import java.time.ZoneId

@Composable
fun ReportsScreen(vm: ReportsViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler(enabled = s.view != ReportsView.HISTORY) { vm.back() }

    // Guardar: el selector de documentos de Android decide dónde (sin permisos de almacenamiento).
    var comparisonExport by rememberSaveable { mutableStateOf(false) }
    val launchers = ReportFormat.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri: Uri? ->
            if (uri != null) {
                if (comparisonExport) vm.saveComparison(format, uri) else vm.save(format, uri)
            }
        }
    }
    val save = { format: ReportFormat, comparison: Boolean ->
        comparisonExport = comparison
        launchers.getValue(format).launch(vm.suggestedName(format, comparison))
    }

    // Compartir: solo cuando el usuario lo pide, con permiso de lectura temporal para la app que elija.
    LaunchedEffect(s.export) {
        val shared = s.export as? ExportState.Shared ?: return@LaunchedEffect
        shareFile(context, shared.file, shared.format)
        vm.consumeExport()
    }

    when (s.view) {
        ReportsView.HISTORY -> History(s, vm, save)
        ReportsView.REPORT -> Reader(s, vm, save, comparison = false)
        ReportsView.COMPARISON -> Reader(s, vm, save, comparison = true)
    }
}

private fun shareFile(context: Context, file: File, format: ReportFormat) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.informes", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = format.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.reports_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Ninguna app puede recibirlo: la copia se borra en la próxima exportación.
    }
}

@Composable
private fun History(s: ReportsUiState, vm: ReportsViewModel, save: (ReportFormat, Boolean) -> Unit) {
    if (!s.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (s.analyses.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.reports_no_analyses), modifier = Modifier.testTag("reports_no_analyses"))
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("reports_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FuncodePanel(accent = FuncodeColors.Gold, modifier = Modifier.testTag("reports_privacy")) {
                Text(stringResource(R.string.reports_privacy), style = MaterialTheme.typography.bodySmall)
            }
        }
        s.selectedAnalysis?.let { selected -> item(key = "seleccionado") { SelectedAnalysis(selected, s, vm, save) } }
        item { Text(stringResource(R.string.reports_history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(s.analyses, key = { it.id }) { a -> HistoryItem(a, selected = a.id == s.selected) { vm.select(a.id) } }
    }
}

@Composable
private fun SelectedAnalysis(a: AnalysisEntity, s: ReportsUiState, vm: ReportsViewModel, save: (ReportFormat, Boolean) -> Unit) {
    FuncodePanel(accent = FuncodeColors.Electric, modifier = Modifier.testTag("reports_selected")) {
        Text(a.archiveName.revealControlCharacters(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(Labels.dateTime(a.startedAtEpochMs, ZoneId.systemDefault()), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.TextSecondary)
        val doc = s.document
        when {
            s.loadingDocument -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.reports_loading))
            }
            doc == null -> Text(stringResource(R.string.reports_unavailable), color = FuncodeColors.Medium)
            else -> {
                doc.sections.flatMap { it.blocks }.filterIsInstance<Block.Gauge>().firstOrNull()?.let { g ->
                    StatusChip(
                        stringResource(R.string.reports_index, g.score?.let { "$it/100 · ${g.label}" } ?: g.label),
                        toneColor(g.tone),
                        Modifier.testTag("reports_index"),
                    )
                }
                Text(stringResource(R.string.reports_summary), style = MaterialTheme.typography.labelMedium, color = FuncodeColors.TextSecondary)
                doc.sections.first().blocks.filterIsInstance<Block.Statements>().firstOrNull()?.let { ReportBlock(it.copy(items = it.items.take(5))) }
                Button(onClick = { vm.show(ReportsView.REPORT) }, modifier = Modifier.testTag("reports_read")) { Text(stringResource(R.string.reports_read)) }
            }
        }
        Text(stringResource(R.string.reports_formats), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.TextSecondary)
        ExportButtons(s, formats = ReportFormat.entries, comparison = false, vm = vm, save = save)
        CompareMenu(s, vm)
    }
}

@Composable
private fun ExportButtons(s: ReportsUiState, formats: List<ReportFormat>, comparison: Boolean, vm: ReportsViewModel, save: (ReportFormat, Boolean) -> Unit) {
    val busy = s.export is ExportState.Working
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        formats.forEach { f ->
            OutlinedButton(
                onClick = { save(f, comparison) },
                enabled = !busy,
                modifier = Modifier.testTag("reports_save_${f.name}"),
            ) { Text("${stringResource(R.string.reports_save)} ${f.label}") }
        }
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }, enabled = !busy, modifier = Modifier.testTag("reports_share")) { Text(stringResource(R.string.reports_share)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                formats.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(f.label) },
                        onClick = {
                            open = false
                            if (comparison) vm.shareComparison(f) else vm.share(f)
                        },
                        modifier = Modifier.testTag("reports_share_${f.name}"),
                    )
                }
            }
        }
    }
    when (val e = s.export) {
        is ExportState.Working -> Text(stringResource(R.string.reports_working, e.format.label), modifier = Modifier.testTag("reports_export_status"))
        is ExportState.Saved -> Text(stringResource(R.string.reports_saved, e.fileName), color = FuncodeColors.Success, modifier = Modifier.testTag("reports_export_status"))
        is ExportState.Failed -> Text(stringResource(R.string.reports_failed, e.message), color = FuncodeColors.Critical, modifier = Modifier.testTag("reports_export_status"))
        else -> {}
    }
}

@Composable
private fun CompareMenu(s: ReportsUiState, vm: ReportsViewModel) {
    val others = s.comparable
    if (others.isEmpty()) {
        Text(stringResource(R.string.reports_compare_none), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.TextSecondary)
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("reports_compare")) { Text(stringResource(R.string.reports_compare)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            others.take(30).forEach { a ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${a.archiveName.revealControlCharacters()} · ${Labels.dateTime(a.startedAtEpochMs, ZoneId.systemDefault())}",
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        open = false
                        vm.compareWith(a.id)
                    },
                    modifier = Modifier.testTag("reports_compare_${a.id}"),
                )
            }
        }
    }
}

@Composable
private fun HistoryItem(a: AnalysisEntity, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(1.5.dp, FuncodeColors.Electric) else null,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("reports_item_${a.id}"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.archiveName.revealControlCharacters(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val outcome = runCatching { AnalysisOutcome.valueOf(a.status) }.getOrNull()
                outcome?.let { StatusChip(Labels.outcome(it), if (it == AnalysisOutcome.COMPLETED) FuncodeColors.Success else FuncodeColors.Medium) }
            }
            Text(Labels.dateTime(a.startedAtEpochMs, ZoneId.systemDefault()), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.TextSecondary)
            Text(
                stringResource(R.string.reports_files_findings, Labels.number(a.analyzedCount), Labels.number(a.findingCount)),
                style = MaterialTheme.typography.bodySmall, color = FuncodeColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun Reader(s: ReportsUiState, vm: ReportsViewModel, save: (ReportFormat, Boolean) -> Unit, comparison: Boolean) {
    val doc = if (comparison) s.comparisonDocument else s.document
    LazyColumn(
        Modifier.fillMaxSize().testTag(if (comparison) "reports_comparison" else "reports_reader"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.back() }, modifier = Modifier.testTag("reports_back")) { Text(stringResource(R.string.reports_back)) }
                if (doc != null) {
                    Text(doc.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(doc.subtitle.revealControlCharacters(), style = MaterialTheme.typography.titleSmall, color = FuncodeColors.TextSecondary)
                    ExportButtons(s, formats = if (comparison) listOf(ReportFormat.PDF, ReportFormat.HTML) else ReportFormat.entries, comparison = comparison, vm = vm, save = save)
                }
            }
        }
        when {
            doc != null -> reportItems(doc)
            comparison && s.comparing -> item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp)); Text(stringResource(R.string.reports_comparing)) } }
            comparison -> item { Text(stringResource(R.string.reports_comparison_failed), color = FuncodeColors.Critical) }
            else -> item { Text(stringResource(R.string.reports_loading)) }
        }
    }
}

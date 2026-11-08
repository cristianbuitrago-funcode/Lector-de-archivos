package com.funcodestudiocb.analyzer.ui.analysis

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.analysis.AnalysisState
import com.funcodestudiocb.analyzer.analysis.FailureReason
import com.funcodestudiocb.analyzer.analysis.ImportedArchive
import com.funcodestudiocb.analyzer.engine.report.AnalysisOutcome
import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.PasswordField
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import java.text.DateFormat
import java.util.Date

/** Tipos MIME que se ofrecen en el selector (muchos gestores marcan los RAR como binario genérico). */
val ARCHIVE_MIME_TYPES = arrayOf(
    "application/zip", "application/x-zip-compressed",
    "application/vnd.rar", "application/x-rar-compressed", "application/x-rar",
    "application/octet-stream",
)

@Composable
fun NewAnalysisScreen(
    viewModel: NewAnalysisViewModel,
    onOpenAnalysis: (String) -> Unit,
    onOpenExplorer: (String) -> Unit = {},
    onOpenFile: (String, String) -> Unit = { _, _ -> },
    onOpenDependencies: (String) -> Unit = {},
    onOpenArchitecture: (String) -> Unit = {},
    onOpenReports: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exclusions by viewModel.exclusions.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle(initialValue = emptyList())

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.select(uri)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.start() }
    val startAnalysis = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Sin el permiso el análisis funciona igual; solo no se ve la notificación de progreso.
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.start()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val s = state) {
            AnalysisState.Idle -> SelectPanel(onPick = { picker.launch(ARCHIVE_MIME_TYPES) }, onDrop = viewModel::select)
            AnalysisState.Importing -> ProgressPanel(stringResource(R.string.analysis_importing), null, emptyList(), null, onCancel = null)
            is AnalysisState.Ready -> {
                ArchivePanel(s.archive)
                ExclusionsEditor(viewModel, exclusions)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = startAnalysis, modifier = Modifier.testTag("analysis_start")) { Text(stringResource(R.string.analysis_start)) }
                    OutlinedButton(onClick = viewModel::discard) { Text(stringResource(R.string.analysis_discard)) }
                }
            }
            is AnalysisState.Running -> ProgressPanel(
                title = stringResource(R.string.analysis_running, s.archive.displayName.revealControlCharacters()),
                overall = s.overall,
                stages = s.stages.map { it.title to it.done },
                message = s.message,
                onCancel = viewModel::discard,
            )
            is AnalysisState.NeedsPassword -> PasswordDialog(
                wrong = s.wrongPassword,
                onSubmit = viewModel::startWithPassword,
                onSkip = viewModel::continueWithoutPassword,
                onDiscard = viewModel::discard,
            )
            is AnalysisState.Finished -> {
                if (!s.saved) {
                    FuncodePanel(accent = FuncodeColors.Gold) { Text(stringResource(R.string.analysis_not_saved_yet)) }
                }
                ReportView(
                    s.archiveName, s.report, s.outcome,
                    onOpenExplorer = if (s.saved) ({ onOpenExplorer(s.analysisId) }) else null,
                    onOpenFile = if (s.saved) ({ path -> onOpenFile(s.analysisId, path) }) else null,
                    onOpenDependencies = if (s.saved) ({ onOpenDependencies(s.analysisId) }) else null,
                    onOpenArchitecture = if (s.saved) ({ onOpenArchitecture(s.analysisId) }) else null,
                    onOpenReports = if (s.saved) ({ onOpenReports(s.analysisId) }) else null,
                )
                Button(onClick = viewModel::reset, modifier = Modifier.testTag("analysis_new")) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.analysis_new))
                }
            }
            is AnalysisState.Failed -> FailedPanel(s.reason, s.detail, onBack = viewModel::reset)
        }

        if (recent.isNotEmpty()) RecentPanel(recent, onOpenAnalysis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectPanel(onPick: () -> Unit, onDrop: (android.net.Uri) -> Unit) {
    val activity = LocalActivity.current
    var hovering by remember { mutableStateOf(false) }
    val target = remember(activity) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val dragEvent = event.toAndroidDragEvent()
                // Permiso temporal para leer el contenido arrastrado desde otra app.
                activity?.requestDragAndDropPermissions(dragEvent)
                val uri = dragEvent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return false
                onDrop(uri)
                return true
            }

            override fun onEntered(event: DragAndDropEvent) {
                hovering = true
            }

            override fun onExited(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                hovering = false
            }
        }
    }
    FuncodePanel(accent = if (hovering) FuncodeColors.Gold else FuncodeColors.Electric) {
        Text(stringResource(R.string.analysis_select_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.analysis_select_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, (if (hovering) FuncodeColors.Gold else FuncodeColors.Outline), RoundedCornerShape(12.dp))
                .dragAndDropTarget(shouldStartDragAndDrop = { e -> e.mimeTypes().isNotEmpty() }, target = target)
                .clickable(onClick = onPick)
                .padding(24.dp)
                .testTag("analysis_drop_zone"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.AddCircle, contentDescription = null, tint = FuncodeColors.Electric, modifier = Modifier.size(40.dp))
                Text(stringResource(R.string.analysis_pick), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.analysis_drop_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.analysis_share_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.analysis_privacy_note), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Gold)
    }
}

@Composable
private fun ArchivePanel(archive: ImportedArchive) {
    FuncodePanel(accent = FuncodeColors.Electric) {
        Text(stringResource(R.string.analysis_file_title).uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
        Text(archive.displayName.revealControlCharacters(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("analysis_file_name"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(archive.format.name, FuncodeColors.Electric)
            StatusChip(formatBytes(archive.sizeBytes), FuncodeColors.Info)
        }
        Text("SHA-256 ${archive.sha256.take(16)}…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (archive.extensionMismatch) {
            Text(stringResource(R.string.analysis_extension_mismatch, archive.format.name), color = FuncodeColors.Medium, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ExclusionsEditor(viewModel: NewAnalysisViewModel, options: ExclusionOptions) {
    var custom by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<AddExclusionResult?>(null) }
    FuncodePanel {
        Text(stringResource(R.string.exclusions_title).uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
        Text(stringResource(R.string.exclusions_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val names = viewModel.allDirectoryNames(options)
        // Filas desplazables horizontalmente de 3 en 3 grupos para no ocupar demasiado alto.
        names.chunked(6).forEach { row ->
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { name ->
                    val selected = name in options.directoryNames
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setDirectoryExcluded(name, !selected) },
                        label = { Text(name.revealControlCharacters()) },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = custom,
                onValueChange = {
                    custom = it
                    error = null
                },
                label = { Text(stringResource(R.string.exclusions_add_label)) },
                singleLine = true,
                isError = error != null && error != AddExclusionResult.ADDED,
                supportingText = error?.takeIf { it != AddExclusionResult.ADDED }?.let {
                    { Text(stringResource(if (it == AddExclusionResult.TOO_MANY) R.string.exclusions_too_many else if (it == AddExclusionResult.DUPLICATE) R.string.exclusions_duplicate else R.string.exclusions_invalid)) }
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val result = viewModel.addDirectory(custom)
                error = result
                if (result == AddExclusionResult.ADDED) custom = ""
            }) { Text(stringResource(R.string.exclusions_add)) }
        }
        SwitchRow(stringResource(R.string.exclusions_context), stringResource(R.string.exclusions_context_desc), options.contextRules, viewModel::setContextRules)
        SwitchRow(stringResource(R.string.exclusions_temporary), stringResource(R.string.exclusions_temporary_desc), options.temporaryFiles, viewModel::setTemporaryFiles)
        TextButton(onClick = viewModel::restoreDefaultExclusions) { Text(stringResource(R.string.exclusions_restore)) }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProgressPanel(title: String, overall: Float?, stages: List<Pair<String, Boolean>>, message: String?, onCancel: (() -> Unit)?) {
    FuncodePanel(accent = FuncodeColors.Electric) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("analysis_progress"))
        val animated by animateFloatAsState(overall ?: 0f, label = "progreso")
        if (overall == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
        stages.filter { it.first.isNotEmpty() }.forEach { (stageTitle, done) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (done) Icons.Filled.CheckCircle else Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = if (done) FuncodeColors.Success else FuncodeColors.Electric,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(stageTitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
        message?.let { Text(it.revealControlCharacters(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (onCancel != null) OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.analysis_cancel)) }
    }
}

@Composable
private fun PasswordDialog(wrong: Boolean, onSubmit: (String) -> Unit, onSkip: () -> Unit, onDiscard: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(if (wrong) R.string.password_wrong_title else R.string.password_needed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.password_needed_text))
                PasswordField(value = password, onValueChange = { password = it }, label = stringResource(R.string.field_password), modifier = Modifier.testTag("archive_password"))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) { Text(stringResource(R.string.password_analyze)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.analysis_discard)) }
                TextButton(onClick = onSkip) { Text(stringResource(R.string.password_skip)) }
            }
        },
    )
}

@Composable
private fun FailedPanel(reason: FailureReason, detail: String?, onBack: () -> Unit) {
    val text = when (reason) {
        FailureReason.TOO_LARGE -> stringResource(R.string.failure_too_large, formatBytes(detail?.toLongOrNull() ?: 0))
        FailureReason.UNSUPPORTED -> stringResource(R.string.failure_unsupported, detail.orEmpty())
        FailureReason.UNREADABLE -> stringResource(R.string.failure_unreadable, detail.orEmpty())
        FailureReason.BUSY -> stringResource(R.string.failure_busy)
        FailureReason.INTERNAL -> stringResource(R.string.failure_internal, detail.orEmpty())
    }
    FuncodePanel(accent = MaterialTheme.colorScheme.error) {
        Text(text, modifier = Modifier.testTag("analysis_failure"))
        Button(onClick = onBack) { Text(stringResource(R.string.analysis_back)) }
    }
}

@Composable
private fun RecentPanel(items: List<AnalysisEntity>, onOpen: (String) -> Unit) {
    FuncodePanel {
        Text(stringResource(R.string.analysis_recent).uppercase(), style = MaterialTheme.typography.labelSmall, color = FuncodeColors.Gold)
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        items.forEach { item ->
            val outcome = runCatching { AnalysisOutcome.valueOf(item.status) }.getOrDefault(AnalysisOutcome.FAILED)
            val (label, color) = outcomeLabel(outcome)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(item.id) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.archiveName.revealControlCharacters(), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${format.format(Date(item.startedAtEpochMs))} · ${stringResource(R.string.report_x_of_y, item.analyzedCount, item.fileCount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(label, color)
            }
        }
    }
}

/** Detalle de un análisis del historial. */
@Composable
fun AnalysisDetailScreen(
    viewModel: AnalysisDetailViewModel,
    onDeleted: () -> Unit,
    onOpenExplorer: (String) -> Unit = {},
    onOpenFile: (String, String) -> Unit = { _, _ -> },
    onOpenDependencies: (String) -> Unit = {},
    onOpenArchitecture: (String) -> Unit = {},
    onOpenReports: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val s = state) {
            AnalysisDetailViewModel.DetailState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            AnalysisDetailViewModel.DetailState.NotFound -> Text(stringResource(R.string.analysis_not_found))
            AnalysisDetailViewModel.DetailState.Deleted -> LaunchedEffect(Unit) { onDeleted() }
            is AnalysisDetailViewModel.DetailState.Loaded -> {
                val outcome = runCatching { AnalysisOutcome.valueOf(s.entity.status) }.getOrDefault(AnalysisOutcome.FAILED)
                ReportView(
                    s.entity.archiveName, s.report, outcome,
                    onOpenExplorer = { onOpenExplorer(s.entity.id) },
                    onOpenFile = { path -> onOpenFile(s.entity.id, path) },
                    onOpenDependencies = { onOpenDependencies(s.entity.id) },
                    onOpenArchitecture = { onOpenArchitecture(s.entity.id) },
                    onOpenReports = { onOpenReports(s.entity.id) },
                )
                OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.analysis_delete)) }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.analysis_delete_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.action_wipe), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

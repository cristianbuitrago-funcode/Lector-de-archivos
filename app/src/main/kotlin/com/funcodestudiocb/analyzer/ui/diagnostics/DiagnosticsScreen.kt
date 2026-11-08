package com.funcodestudiocb.analyzer.ui.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.funcodestudiocb.analyzer.BuildConfig
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.service.client.DiagnosticCheck
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.components.StatusChip
import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import java.text.DateFormat
import java.util.Date

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by animateFloatAsState(state.progress, label = "progreso")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FuncodePanel(accent = FuncodeColors.Electric) {
            Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.diag_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (BuildConfig.DEBUG) {
                Text(stringResource(R.string.diag_debug_note), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Gold)
            }
            Button(
                onClick = viewModel::run,
                enabled = !state.running,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("diag_run"),
            ) { Text(stringResource(if (state.running) R.string.diag_running else R.string.diag_run)) }
            AnimatedVisibility(state.running) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    state.stageTitle?.let { Text(it.revealControlCharacters(), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        state.error?.let { Text(stringResource(R.string.diag_error, it), color = MaterialTheme.colorScheme.error) }

        state.report?.let { report ->
            val ok = report.allPassed
            FuncodePanel(accent = if (ok) FuncodeColors.Success else MaterialTheme.colorScheme.error) {
                StatusChip(
                    stringResource(if (ok) R.string.diag_result_ok else R.string.diag_result_fail),
                    if (ok) FuncodeColors.Success else MaterialTheme.colorScheme.error,
                    Modifier.testTag(if (ok) "diag_ok" else "diag_fail"),
                )
                report.checks.forEachIndexed { index, check ->
                    if (index > 0) HorizontalDivider()
                    CheckRow(check)
                }
            }
        }

        FuncodePanel {
            Text(stringResource(R.string.diag_history), style = MaterialTheme.typography.titleSmall)
            if (history.isEmpty()) {
                Text(stringResource(R.string.diag_history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            history.forEach { run ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(
                        stringResource(if (run.passed) R.string.diag_check_passed else R.string.diag_check_failed),
                        if (run.passed) FuncodeColors.Success else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text(format.format(Date(run.startedAtEpochMs)), style = MaterialTheme.typography.bodyMedium)
                        Text(run.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: DiagnosticCheck) {
    val (label, color) = when {
        check.passed -> stringResource(R.string.diag_check_passed) to FuncodeColors.Success
        check.inconclusive -> stringResource(R.string.diag_check_inconclusive) to FuncodeColors.Medium
        else -> stringResource(R.string.diag_check_failed) to MaterialTheme.colorScheme.error
    }
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(check.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            StatusChip(label, color)
        }
        // El detalle procede del proceso aislado: se muestra como texto plano y con los caracteres de control visibles.
        Text(check.detail.revealControlCharacters(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

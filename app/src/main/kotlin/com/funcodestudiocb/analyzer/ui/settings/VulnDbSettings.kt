package com.funcodestudiocb.analyzer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.R
import com.funcodestudiocb.analyzer.engine.vulndb.OsvEcosystems
import com.funcodestudiocb.analyzer.ui.analysis.formatBytes
import com.funcodestudiocb.analyzer.ui.components.FuncodePanel
import com.funcodestudiocb.analyzer.ui.dependencies.formatCount
import com.funcodestudiocb.analyzer.ui.dependencies.formatDay
import com.funcodestudiocb.analyzer.ui.theme.FuncodeColors
import com.funcodestudiocb.analyzer.vulndb.UpdateState
import com.funcodestudiocb.analyzer.vulndb.VulnDbPreferences
import com.funcodestudiocb.analyzer.vulndb.VulnDbStatus
import com.funcodestudiocb.analyzer.vulndb.VulnDbStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado de la sección «Bases de vulnerabilidades». */
data class VulnDbUiState(
    val status: VulnDbStatus? = null,
    val preferences: VulnDbPreferences = VulnDbPreferences(),
    val update: UpdateState = UpdateState.Idle,
    /** Diálogo de consentimiento antes de conectarse a Internet. */
    val confirmingUpdate: Boolean = false,
    val confirmingDelete: Boolean = false,
    val now: Long = System.currentTimeMillis(),
) {
    val running: Boolean get() = update is UpdateState.Running
    val approxDownloadMb: Int get() = preferences.ecosystems.sumOf { VulnDbStore.APPROX_DOWNLOAD_MB[it] ?: 0 } + if (preferences.endOfLife) 1 else 0
    val canUpdate: Boolean get() = !running && (preferences.ecosystems.isNotEmpty() || preferences.endOfLife)
    val stale: Boolean get() = status != null && status.ageDays(now) >= VulnDbStore.STALE_AFTER_DAYS
}

class VulnDbSettingsViewModel(container: AppContainer, private val clock: () -> Long = System::currentTimeMillis) : ViewModel() {

    private val store = container.vulnDbStore
    private val updater = container.vulnDbUpdater

    private data class Local(
        val status: VulnDbStatus? = null,
        val preferences: VulnDbPreferences = VulnDbPreferences(),
        val confirmingUpdate: Boolean = false,
        val confirmingDelete: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<VulnDbUiState> = combine(local, updater.state) { l, u ->
        VulnDbUiState(l.status, l.preferences, u, l.confirmingUpdate, l.confirmingDelete, clock())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VulnDbUiState())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = store.preferences()
            local.update { it.copy(status = store.status(), preferences = prefs) }
        }
        viewModelScope.launch {
            // Al terminar una actualización, se vuelve a leer el estado instalado.
            updater.state.collect { u -> if (u !is UpdateState.Running) refresh() }
        }
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { local.update { it.copy(status = store.status()) } }
    }

    private fun savePreferences(transform: (VulnDbPreferences) -> VulnDbPreferences) {
        local.update { it.copy(preferences = transform(it.preferences)) }
        val prefs = local.value.preferences
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.savePreferences(prefs) } }
    }

    fun toggleEcosystem(name: String) = savePreferences { p ->
        p.copy(ecosystems = if (name in p.ecosystems) p.ecosystems - name else p.ecosystems + name)
    }

    fun setEndOfLife(enabled: Boolean) = savePreferences { it.copy(endOfLife = enabled) }

    fun requestUpdate() {
        if (state.value.canUpdate) local.update { it.copy(confirmingUpdate = true) }
    }

    /** El usuario aceptó conectarse: empieza la descarga. */
    fun confirmUpdate() {
        local.update { it.copy(confirmingUpdate = false) }
        updater.start(local.value.preferences)
    }

    fun cancelUpdate() = updater.cancel()

    fun acknowledge() = updater.acknowledge()

    fun requestDelete() = local.update { it.copy(confirmingDelete = true) }

    fun confirmDelete() {
        local.update { it.copy(confirmingDelete = false) }
        viewModelScope.launch(Dispatchers.IO) {
            updater.delete()
            local.update { it.copy(status = store.status()) }
        }
    }

    fun dismissDialogs() = local.update { it.copy(confirmingUpdate = false, confirmingDelete = false) }
}


/** Sección de Ajustes: qué hay descargado, qué se descarga, actualizar (con consentimiento), progreso y borrado. */
@Composable
fun VulnDbSection(viewModel: VulnDbSettingsViewModel) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    FuncodePanel(accent = if (s.status == null || s.stale) FuncodeColors.Medium else FuncodeColors.Electric, modifier = Modifier.testTag("vulndb_section")) {
        SectionTitle(stringResource(R.string.vulndb_title))
        Text(stringResource(R.string.vulndb_intro), style = MaterialTheme.typography.bodySmall)

        val status = s.status
        if (status == null) {
            Text(stringResource(R.string.vulndb_none), fontWeight = FontWeight.Medium, color = FuncodeColors.Medium, modifier = Modifier.testTag("vulndb_none"))
        } else {
            val age = status.ageDays(s.now).toInt()
            Text(
                stringResource(R.string.vulndb_installed, formatDay(status.createdAt), pluralStringResource(R.plurals.vulndb_days_ago, age, age), formatBytes(status.sizeBytes)),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("vulndb_status"),
            )
            status.ecosystems.forEach { e ->
                Text(
                    stringResource(R.string.vulndb_ecosystem_line, e.name, formatCount(e.advisories), formatCount(e.packages), formatDay(e.sourceDate)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (status.eolProducts > 0) {
                Text(stringResource(R.string.vulndb_eol_line, status.eolProducts, formatDay(status.eolDate)), style = MaterialTheme.typography.bodySmall)
            }
            if (status.eolMissing.isNotEmpty()) {
                Text(stringResource(R.string.vulndb_eol_missing, status.eolMissing.joinToString()), style = MaterialTheme.typography.bodySmall, color = FuncodeColors.Medium)
            }
            if (s.stale) {
                Text(stringResource(R.string.vulndb_stale, age), color = FuncodeColors.Medium, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("vulndb_stale"))
            }
        }

        Text(stringResource(R.string.vulndb_choose), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OsvEcosystems.SUPPORTED.forEach { eco ->
                FilterChip(
                    selected = eco in s.preferences.ecosystems,
                    onClick = { viewModel.toggleEcosystem(eco) },
                    enabled = !s.running,
                    label = { Text(stringResource(R.string.vulndb_ecosystem_chip, eco, VulnDbStore.APPROX_DOWNLOAD_MB[eco] ?: 0)) },
                    modifier = Modifier.testTag("vulndb_eco_$eco"),
                )
            }
        }
        SettingRow(
            title = stringResource(R.string.vulndb_eol_option),
            subtitle = stringResource(R.string.vulndb_eol_option_desc),
            trailing = { Switch(checked = s.preferences.endOfLife, onCheckedChange = viewModel::setEndOfLife, enabled = !s.running, modifier = Modifier.testTag("vulndb_eol")) },
        )
        Text(stringResource(R.string.vulndb_approx, s.approxDownloadMb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        when (val u = s.update) {
            is UpdateState.Running -> Column(Modifier.fillMaxWidth().testTag("vulndb_progress"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(u.step, fontWeight = FontWeight.Medium)
                LinearProgressIndicator(progress = { u.overall }, modifier = Modifier.fillMaxWidth())
                u.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = viewModel::cancelUpdate, modifier = Modifier.testTag("vulndb_cancel")) { Text(stringResource(R.string.vulndb_cancel)) }
            }
            is UpdateState.Done -> ResultLine(stringResource(R.string.vulndb_done), FuncodeColors.Success, viewModel::acknowledge, "vulndb_done")
            is UpdateState.Failed -> ResultLine(u.message, MaterialTheme.colorScheme.error, viewModel::acknowledge, "vulndb_failed")
            UpdateState.Cancelled -> ResultLine(stringResource(R.string.vulndb_cancelled), FuncodeColors.Medium, viewModel::acknowledge, "vulndb_cancelled")
            UpdateState.Idle -> Unit
        }

        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::requestUpdate, enabled = s.canUpdate, modifier = Modifier.testTag("vulndb_update")) {
                Text(stringResource(if (status == null) R.string.vulndb_download else R.string.vulndb_update))
            }
            if (status != null) {
                OutlinedButton(onClick = viewModel::requestDelete, enabled = !s.running, modifier = Modifier.testTag("vulndb_delete")) { Text(stringResource(R.string.vulndb_delete)) }
            }
        }
        Text(stringResource(R.string.vulndb_sources), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (s.confirmingUpdate) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDialogs,
            title = { Text(stringResource(R.string.vulndb_confirm_title)) },
            text = { Text(stringResource(R.string.vulndb_confirm_text, s.approxDownloadMb)) },
            confirmButton = { TextButton(onClick = viewModel::confirmUpdate, modifier = Modifier.testTag("vulndb_confirm")) { Text(stringResource(R.string.vulndb_confirm_ok)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialogs) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (s.confirmingDelete) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDialogs,
            title = { Text(stringResource(R.string.vulndb_delete_title)) },
            text = { Text(stringResource(R.string.vulndb_delete_text)) },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete, modifier = Modifier.testTag("vulndb_delete_confirm")) { Text(stringResource(R.string.vulndb_delete)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialogs) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ResultLine(text: String, color: androidx.compose.ui.graphics.Color, onOk: () -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().testTag(tag), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onOk) { Text(stringResource(R.string.vulndb_ok)) }
    }
}

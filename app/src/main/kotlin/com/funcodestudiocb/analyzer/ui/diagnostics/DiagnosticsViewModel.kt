package com.funcodestudiocb.analyzer.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.data.db.RunEntity
import com.funcodestudiocb.analyzer.service.client.DiagnosticsReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class DiagnosticsUiState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val stageTitle: String? = null,
    val report: DiagnosticsReport? = null,
    val error: String? = null,
)

class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    val history: Flow<List<RunEntity>> =
        container.session.databaseOrNull()?.runs()?.recent(HISTORY_SIZE) ?: flowOf(emptyList())

    fun run() {
        val diagnostics = container.diagnostics ?: return
        if (_state.value.running) return
        _state.value = DiagnosticsUiState(running = true)
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val report = try {
                diagnostics.run(onProgress = { update ->
                    _state.update { it.copy(progress = update.overallFraction, stageTitle = update.stageTitle) }
                })
            } catch (e: Exception) {
                _state.value = DiagnosticsUiState(error = e::class.java.simpleName)
                return@launch
            }
            _state.value = DiagnosticsUiState(report = report, progress = 1f)
            save(report, started)
        }
    }

    private suspend fun save(report: DiagnosticsReport, started: Long) {
        val db = container.session.databaseOrNull() ?: return
        val passed = report.checks.count { it.passed }
        db.runs().insert(
            RunEntity(
                id = UUID.randomUUID().toString(),
                mode = "DIAGNOSTIC",
                status = report.run.status.name,
                startedAtEpochMs = started,
                finishedAtEpochMs = System.currentTimeMillis(),
                summary = "$passed de ${report.checks.size} comprobaciones correctas",
                passed = report.allPassed,
            ),
        )
    }

    private companion object {
        const val HISTORY_SIZE = 10
    }
}

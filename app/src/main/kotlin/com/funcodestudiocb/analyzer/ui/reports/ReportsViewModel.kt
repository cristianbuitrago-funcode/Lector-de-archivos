package com.funcodestudiocb.analyzer.ui.reports

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.engine.report.Comparison
import com.funcodestudiocb.analyzer.engine.report.ReportDocument
import com.funcodestudiocb.analyzer.reports.ExportedReport
import com.funcodestudiocb.analyzer.reports.ReportExporter
import com.funcodestudiocb.analyzer.reports.ReportFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Estado de una exportación. */
sealed interface ExportState {
    data object Idle : ExportState

    data class Working(val format: ReportFormat) : ExportState

    data class Saved(val fileName: String) : ExportState

    /** Copia temporal lista para el selector de «Compartir». */
    data class Shared(val file: File, val format: ReportFormat) : ExportState

    data class Failed(val message: String) : ExportState
}

/** Qué se está viendo: el historial, el informe completo de un análisis o una comparación. */
enum class ReportsView { HISTORY, REPORT, COMPARISON }

data class ReportsUiState(
    val loaded: Boolean = false,
    val analyses: List<AnalysisEntity> = emptyList(),
    val selected: String? = null,
    /** Informe del análisis seleccionado, en el modelo común (vista previa y lectura completa). */
    val document: ReportDocument? = null,
    val loadingDocument: Boolean = false,
    val view: ReportsView = ReportsView.HISTORY,
    val comparison: Comparison? = null,
    val comparisonDocument: ReportDocument? = null,
    val comparing: Boolean = false,
    val export: ExportState = ExportState.Idle,
) {
    val selectedAnalysis: AnalysisEntity? get() = analyses.firstOrNull { it.id == selected }

    /** Otros análisis para comparar, primero los del mismo comprimido. */
    val comparable: List<AnalysisEntity>
        get() {
            val current = selectedAnalysis ?: return emptyList()
            return analyses.filter { it.id != current.id }.sortedWith(compareBy({ !it.archiveName.equals(current.archiveName, ignoreCase = true) }, { -it.startedAtEpochMs }))
        }
}

/** Pantalla Informes: historial de análisis, exportación en PDF, HTML y JSON, y comparación entre análisis. */
class ReportsViewModel(private val container: AppContainer, initialId: String? = null) : ViewModel() {

    private val exporter: ReportExporter = container.reports
    private val _state = MutableStateFlow(ReportsUiState(selected = initialId))
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()
    private var documentJob: Job? = null

    init {
        viewModelScope.launch {
            container.repository.recent(LIMIT).collect { list ->
                val previous = _state.value
                val selected = previous.selected?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
                _state.update { it.copy(loaded = true, analyses = list, selected = selected) }
                if (selected != null && (selected != previous.selected || previous.document == null)) loadDocument(selected)
                if (selected == null) _state.update { it.copy(document = null, view = ReportsView.HISTORY) }
            }
        }
    }

    fun select(id: String) {
        if (id == _state.value.selected && _state.value.document != null) return
        _state.update { it.copy(selected = id, document = null, comparison = null, comparisonDocument = null) }
        loadDocument(id)
    }

    private fun loadDocument(id: String) {
        documentJob?.cancel()
        _state.update { it.copy(loadingDocument = true) }
        documentJob = viewModelScope.launch {
            val doc = runCatching { exporter.document(id) }.getOrNull()
            _state.update { if (it.selected == id) it.copy(document = doc, loadingDocument = false) else it }
        }
    }

    fun show(view: ReportsView) = _state.update { it.copy(view = view) }

    /** Atrás: de un informe o una comparación, al historial. */
    fun back(): Boolean {
        if (_state.value.view == ReportsView.HISTORY) return false
        _state.update { it.copy(view = ReportsView.HISTORY) }
        return true
    }

    /** Nombre propuesto al selector de documentos. */
    fun suggestedName(format: ReportFormat, comparison: Boolean = false): String {
        val s = _state.value
        val name = if (comparison) s.comparison?.after?.archiveName else s.selectedAnalysis?.archiveName
        return ReportExporter.fileName(if (comparison) "comparacion" else "informe", name ?: "proyecto", System.currentTimeMillis(), format)
    }

    /** Guarda el informe del análisis seleccionado en el documento elegido por el usuario. */
    fun save(format: ReportFormat, uri: Uri) = launchExport(format) { exported ->
        exporter.write(exported, uri)
        ExportState.Saved(exported.fileName)
    }

    /** Prepara la copia temporal para compartir (solo tras pulsar «Compartir»). */
    fun share(format: ReportFormat) = launchExport(format) { exported -> ExportState.Shared(exporter.shareCopy(exported), format) }

    fun saveComparison(format: ReportFormat, uri: Uri) = launchComparisonExport(format) { exported ->
        exporter.write(exported, uri)
        ExportState.Saved(exported.fileName)
    }

    fun shareComparison(format: ReportFormat) = launchComparisonExport(format) { exported -> ExportState.Shared(exporter.shareCopy(exported), format) }

    private fun launchExport(format: ReportFormat, then: suspend (ExportedReport) -> ExportState) {
        val id = _state.value.selected ?: return
        if (_state.value.export is ExportState.Working) return
        _state.update { it.copy(export = ExportState.Working(format)) }
        viewModelScope.launch {
            val result = try {
                exporter.export(id, format)?.let { then(it) } ?: ExportState.Failed("El análisis ya no existe.")
            } catch (e: Exception) {
                ExportState.Failed(e.message ?: "No se pudo generar el informe.")
            }
            _state.update { it.copy(export = result) }
        }
    }

    private fun launchComparisonExport(format: ReportFormat, then: suspend (ExportedReport) -> ExportState) {
        val comparison = _state.value.comparison ?: return
        if (_state.value.export is ExportState.Working) return
        _state.update { it.copy(export = ExportState.Working(format)) }
        viewModelScope.launch {
            val result = try {
                then(exporter.exportComparison(comparison, format))
            } catch (e: Exception) {
                ExportState.Failed(e.message ?: "No se pudo generar la comparación.")
            }
            _state.update { it.copy(export = result) }
        }
    }

    fun consumeExport() = _state.update { it.copy(export = ExportState.Idle) }

    /** Compara el análisis seleccionado con otro, del más antiguo al más reciente. */
    fun compareWith(otherId: String) {
        val current = _state.value.selectedAnalysis ?: return
        val other = _state.value.analyses.firstOrNull { it.id == otherId } ?: return
        if (other.id == current.id) return
        val (before, after) = if (other.startedAtEpochMs <= current.startedAtEpochMs) other to current else current to other
        _state.update { it.copy(comparing = true, view = ReportsView.COMPARISON, comparison = null, comparisonDocument = null) }
        viewModelScope.launch {
            val comparison = runCatching { exporter.comparison(before.id, after.id) }.getOrNull()
            _state.update {
                it.copy(comparing = false, comparison = comparison, comparisonDocument = comparison?.toDocument(System.currentTimeMillis()))
            }
        }
    }

    private companion object {
        const val LIMIT = 100
    }
}

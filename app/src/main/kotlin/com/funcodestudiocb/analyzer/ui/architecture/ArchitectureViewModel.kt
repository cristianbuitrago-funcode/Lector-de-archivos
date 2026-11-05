package com.funcodestudiocb.analyzer.ui.architecture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.analysis.AnalysisReport
import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.engine.api.Diagram
import com.funcodestudiocb.analyzer.engine.api.DiagramKind
import com.funcodestudiocb.analyzer.engine.api.FileLayer
import com.funcodestudiocb.analyzer.engine.api.InformationFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ArchTab { LAYERS, FLOWS, MODULES, PROJECTS, FILES }

/** Elemento seleccionado en un diagrama: un nodo o una arista (origen, destino). */
data class DiagramSelection(val node: String? = null, val edge: Pair<String, String>? = null) {
    val empty: Boolean get() = node == null && edge == null
}

sealed interface ArchitectureUiState {
    data object Loading : ArchitectureUiState
    data object NoAnalyses : ArchitectureUiState
    data object NotFound : ArchitectureUiState

    data class Loaded(
        val analysis: AnalysisEntity,
        val report: AnalysisReport,
        val tab: ArchTab = ArchTab.LAYERS,
        val selection: DiagramSelection = DiagramSelection(),
        /** Archivo con foco (pestaña Archivos) y su grafo, calculado en segundo plano. */
        val focus: String? = null,
        val focusOptions: FocusOptions = FocusOptions(),
        val focusDiagram: Diagram? = null,
        val fileQuery: String = "",
        val flowQuery: String = "",
        val onlyCompleteFlows: Boolean = false,
    ) : ArchitectureUiState {
        val layers: Map<String, FileLayer> = report.fileLayers.associateBy { it.path }

        fun diagram(tab: ArchTab = this.tab): Diagram? = when (tab) {
            ArchTab.LAYERS -> report.diagrams.firstOrNull { it.kind == DiagramKind.LAYERS }
            ArchTab.MODULES -> report.diagrams.firstOrNull { it.kind == DiagramKind.MODULES }
            ArchTab.PROJECTS -> report.diagrams.firstOrNull { it.kind == DiagramKind.PROJECTS }
            ArchTab.FILES -> focusDiagram
            ArchTab.FLOWS -> null
        }

        val visibleFlows: List<InformationFlow> = report.flows.filter { f ->
            (!onlyCompleteFlows || f.reachesDatabase) &&
                (flowQuery.isBlank() || "${f.method} ${f.path}".contains(flowQuery.trim(), ignoreCase = true) ||
                    f.steps.any { it.label.contains(flowQuery.trim(), ignoreCase = true) })
        }

        /** Archivos para elegir el foco: los que coinciden con la búsqueda, o los más conectados. */
        val fileSuggestions: List<String> = run {
            val q = fileQuery.trim()
            if (q.isNotEmpty()) {
                report.fileLayers.asSequence().map { it.path }.filter { it.contains(q, ignoreCase = true) }.take(MAX_SUGGESTIONS).toList()
            } else {
                report.fileGraphs.sortedBy { it.rank }.map { it.path }.take(MAX_SUGGESTIONS)
            }
        }
    }

    companion object {
        const val MAX_SUGGESTIONS = 40
    }
}

/** Arquitectura de un análisis guardado (por defecto, el más reciente). */
class ArchitectureViewModel(
    private val container: AppContainer,
    initialId: String? = null,
    private val initialFile: String? = null,
) : ViewModel() {

    private val repository = container.repository
    private val _state = MutableStateFlow<ArchitectureUiState>(ArchitectureUiState.Loading)
    val state: StateFlow<ArchitectureUiState> = _state.asStateFlow()

    val analyses: StateFlow<List<AnalysisEntity>> = repository.recent(LIMIT).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var followLatest = initialId == null
    private var focusJob: Job? = null

    init {
        if (initialId != null) {
            open(initialId, initialFile)
        } else {
            viewModelScope.launch {
                repository.recent(1).map { it.firstOrNull()?.id }.distinctUntilChanged().collect { id ->
                    if (!followLatest) return@collect
                    if (id == null) _state.value = ArchitectureUiState.NoAnalyses else open(id, null)
                }
            }
        }
    }

    fun pick(id: String) {
        followLatest = false
        open(id, null)
    }

    private fun open(id: String, file: String?) {
        _state.value = ArchitectureUiState.Loading
        viewModelScope.launch {
            val loaded = repository.load(id)
            _state.value = if (loaded == null) {
                ArchitectureUiState.NotFound
            } else {
                ArchitectureUiState.Loaded(loaded.first, loaded.second)
            }
            if (loaded != null && file != null) focus(file)
        }
    }

    private fun updateLoaded(transform: (ArchitectureUiState.Loaded) -> ArchitectureUiState.Loaded) =
        _state.update { s -> if (s is ArchitectureUiState.Loaded) transform(s) else s }

    fun setTab(tab: ArchTab) = updateLoaded { it.copy(tab = tab, selection = DiagramSelection()) }

    fun selectNode(id: String?) = updateLoaded { it.copy(selection = DiagramSelection(node = id)) }

    fun selectEdge(from: String, to: String) = updateLoaded { it.copy(selection = DiagramSelection(edge = from to to)) }

    fun clearSelection() = updateLoaded { it.copy(selection = DiagramSelection()) }

    fun setFileQuery(q: String) = updateLoaded { it.copy(fileQuery = q.take(MAX_QUERY)) }

    fun setFlowQuery(q: String) = updateLoaded { it.copy(flowQuery = q.take(MAX_QUERY)) }

    fun toggleOnlyComplete() = updateLoaded { it.copy(onlyCompleteFlows = !it.onlyCompleteFlows) }

    /** Pone el foco en un archivo: pestaña Archivos con su grafo (calculado fuera del hilo principal). */
    fun focus(path: String?) {
        updateLoaded { it.copy(tab = ArchTab.FILES, focus = path, focusDiagram = null, selection = DiagramSelection(node = path)) }
        recomputeFocus()
    }

    fun setFocusOptions(options: FocusOptions) {
        updateLoaded { it.copy(focusOptions = options) }
        recomputeFocus()
    }

    private fun recomputeFocus() {
        val s = _state.value as? ArchitectureUiState.Loaded ?: return
        val path = s.focus ?: return
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            val diagram = withContext(Dispatchers.Default) { FocusGraph.build(path, s.report.relations, s.layers, s.focusOptions) }
            updateLoaded { if (it.focus == path && it.focusOptions == s.focusOptions) it.copy(focusDiagram = diagram) else it }
        }
    }

    /** Atrás: primero se quita la selección; luego, el foco. */
    fun back(): Boolean {
        val s = _state.value as? ArchitectureUiState.Loaded ?: return false
        return when {
            !s.selection.empty && !(s.tab == ArchTab.FILES && s.selection.node == s.focus) -> {
                clearSelection()
                true
            }
            s.tab == ArchTab.FILES && s.focus != null -> {
                updateLoaded { it.copy(focus = null, focusDiagram = null, selection = DiagramSelection()) }
                true
            }
            else -> false
        }
    }

    private companion object {
        const val LIMIT = 50
        const val MAX_QUERY = 100
    }
}

package com.funcodestudiocb.analyzer.ui.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.engine.api.FileRole
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

/** Copia redactada de un archivo abierta en el visor. */
data class CodeView(
    val path: String,
    /** `null` mientras se carga. */
    val lines: List<String>?,
    /** Líneas (desde 1) con algún posible secreto ocultado. */
    val redactedLines: Set<Int>,
    val missing: Boolean = false,
    /** Resaltado de cada línea (tríos relativos a la línea); vacío si no hay. */
    val highlight: List<IntArray> = emptyList(),
)

sealed interface ExplorerUiState {
    data object Loading : ExplorerUiState

    /** Aún no hay ningún análisis guardado. */
    data object NoAnalyses : ExplorerUiState
    data object NotFound : ExplorerUiState

    data class Loaded(
        val analysis: AnalysisEntity,
        val model: ExplorerModel,
        val expanded: Set<String>,
        val role: FileRole? = null,
        val query: String = "",
        val rows: List<TreeRow>,
        val selected: String? = null,
        val code: CodeView? = null,
        /** Conexión abierta con sus cinco atributos. */
        val relation: com.funcodestudiocb.analyzer.engine.api.Relation? = null,
    ) : ExplorerUiState
}

/** Explorador de un análisis guardado: árbol, filtros, ficha de cada archivo y visor de la copia redactada. */
class ExplorerViewModel(private val container: AppContainer, initialId: String?, private val initialPath: String? = null) : ViewModel() {

    private val repository = container.repository
    private val _state = MutableStateFlow<ExplorerUiState>(ExplorerUiState.Loading)
    val state: StateFlow<ExplorerUiState> = _state.asStateFlow()

    /** Análisis guardados, para elegir cuál explorar. */
    val analyses: StateFlow<List<AnalysisEntity>> = repository.recent(LIMIT).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var codeJob: Job? = null

    /** Desde el menú se muestra el análisis más reciente (también el que acaba de terminar) hasta elegir otro. */
    private var followLatest = initialId == null

    /** Análisis al que pertenece [initialPath]. */
    private val entityIdForPath = initialId

    init {
        if (initialId != null) {
            open(initialId)
        } else {
            viewModelScope.launch {
                repository.recent(1).map { it.firstOrNull()?.id }.distinctUntilChanged().collect { id ->
                    if (!followLatest) return@collect
                    if (id == null) _state.value = ExplorerUiState.NoAnalyses else open(id)
                }
            }
        }
    }

    /** El usuario elige un análisis concreto: deja de seguir al más reciente. */
    fun pick(id: String) {
        followLatest = false
        open(id)
    }

    fun open(id: String) {
        _state.value = ExplorerUiState.Loading
        codeJob?.cancel()
        viewModelScope.launch {
            val loaded = repository.load(id)
            if (loaded == null) {
                _state.value = ExplorerUiState.NotFound
                return@launch
            }
            val (entity, report) = loaded
            val model = withContext(Dispatchers.Default) { ExplorerModel(report) }
            val expanded = model.initiallyExpanded()
            // Desde una evidencia del informe se abre directamente la ficha de ese archivo.
            val selected = initialPath?.takeIf { it in model.files && id == entityIdForPath }
            _state.value = ExplorerUiState.Loaded(entity, model, expanded, rows = model.rows(expanded, null, ""), selected = selected)
        }
    }

    private fun updateLoaded(transform: (ExplorerUiState.Loaded) -> ExplorerUiState.Loaded) = _state.update { s ->
        if (s !is ExplorerUiState.Loaded) return@update s
        val next = transform(s)
        if (next.expanded != s.expanded || next.role != s.role || next.query != s.query) {
            next.copy(rows = next.model.rows(next.expanded, next.role, next.query))
        } else {
            next
        }
    }

    fun toggle(path: String) = updateLoaded { s -> s.copy(expanded = if (path in s.expanded) s.expanded - path else s.expanded + path) }

    fun setRole(role: FileRole?) = updateLoaded { it.copy(role = role) }

    fun setQuery(query: String) = updateLoaded { it.copy(query = query.take(MAX_QUERY)) }

    fun select(path: String?) {
        codeJob?.cancel()
        updateLoaded { it.copy(selected = path, code = null, relation = null) }
    }

    fun openRelation(relation: com.funcodestudiocb.analyzer.engine.api.Relation) = updateLoaded { it.copy(relation = relation) }

    fun closeRelation() = updateLoaded { it.copy(relation = null) }

    fun openCode() {
        val s = _state.value as? ExplorerUiState.Loaded ?: return
        val path = s.selected ?: return
        val redacted = s.model.files[path]?.redactions?.map { it.line }?.toSet().orEmpty()
        updateLoaded { it.copy(code = CodeView(path, null, redacted)) }
        codeJob?.cancel()
        codeJob = viewModelScope.launch {
            val copy = repository.code(s.analysis.id, path)
            val prepared = copy?.let {
                withContext(Dispatchers.Default) {
                    val lines = it.text.removeSuffix("\n").split('\n')
                    lines to splitHighlight(lines, it.highlight)
                }
            }
            updateLoaded { cur ->
                if (cur.code?.path != path) return@updateLoaded cur
                cur.copy(code = CodeView(path, prepared?.first.orEmpty(), redacted, missing = prepared == null, highlight = prepared?.second.orEmpty()))
            }
        }
    }

    fun closeCode() {
        codeJob?.cancel()
        updateLoaded { it.copy(code = null) }
    }

    /** Atrás dentro del Explorador: conexión → visor → ficha → árbol. Devuelve `false` si no había nada que cerrar. */
    fun back(): Boolean {
        val s = _state.value as? ExplorerUiState.Loaded ?: return false
        return when {
            s.relation != null -> {
                closeRelation()
                true
            }
            s.code != null -> {
                closeCode()
                true
            }
            s.selected != null -> {
                select(null)
                true
            }
            else -> false
        }
    }

    private companion object {
        const val LIMIT = 50
        const val MAX_QUERY = 200
    }
}

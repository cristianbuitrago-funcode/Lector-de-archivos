package com.funcodestudiocb.analyzer.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.engine.report.AnalysisReport
import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory
import com.funcodestudiocb.analyzer.engine.api.SecurityFinding
import com.funcodestudiocb.analyzer.engine.api.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Filtros de la lista de hallazgos (vacío = todos). */
data class SecurityFilters(
    val severities: Set<Severity> = emptySet(),
    val certainties: Set<Certainty> = emptySet(),
    val category: SecurityCategory? = null,
) {
    val active: Boolean get() = severities.isNotEmpty() || certainties.isNotEmpty() || category != null

    fun matches(f: SecurityFinding) = (severities.isEmpty() || f.severity in severities) &&
        (certainties.isEmpty() || f.certainty in certainties) && (category == null || f.category == category)
}

sealed interface SecurityUiState {
    data object Loading : SecurityUiState
    data object NoAnalyses : SecurityUiState
    data object NotFound : SecurityUiState

    data class Loaded(
        val analysis: AnalysisEntity,
        val report: AnalysisReport,
        val filters: SecurityFilters = SecurityFilters(),
        /** Índice en [AnalysisReport.security] del hallazgo abierto. */
        val selected: Int? = null,
    ) : SecurityUiState {
        /** Hallazgos visibles con su índice, ordenados por severidad y certeza (el motor ya los envía así). */
        val visible: List<IndexedValue<SecurityFinding>> = report.security.withIndex().filter { filters.matches(it.value) }
        val categories: List<SecurityCategory> = report.security.map { it.category }.distinct().sortedBy { it.ordinal }
    }
}

/** Auditoría de seguridad de un análisis guardado (por defecto, el más reciente). */
class SecurityViewModel(private val container: AppContainer, initialId: String? = null) : ViewModel() {

    private val repository = container.repository
    private val _state = MutableStateFlow<SecurityUiState>(SecurityUiState.Loading)
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()

    val analyses: StateFlow<List<AnalysisEntity>> = repository.recent(LIMIT).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var followLatest = initialId == null

    init {
        if (initialId != null) {
            open(initialId)
        } else {
            viewModelScope.launch {
                repository.recent(1).map { it.firstOrNull()?.id }.distinctUntilChanged().collect { id ->
                    if (!followLatest) return@collect
                    if (id == null) _state.value = SecurityUiState.NoAnalyses else open(id)
                }
            }
        }
    }

    fun pick(id: String) {
        followLatest = false
        open(id)
    }

    private fun open(id: String) {
        _state.value = SecurityUiState.Loading
        viewModelScope.launch {
            val loaded = repository.load(id)
            _state.value = if (loaded == null) SecurityUiState.NotFound else SecurityUiState.Loaded(loaded.first, loaded.second)
        }
    }

    private fun updateLoaded(transform: (SecurityUiState.Loaded) -> SecurityUiState.Loaded) =
        _state.update { s -> if (s is SecurityUiState.Loaded) transform(s) else s }

    fun toggleSeverity(severity: Severity) = updateLoaded { s ->
        val set = s.filters.severities
        s.copy(filters = s.filters.copy(severities = if (severity in set) set - severity else set + severity))
    }

    fun toggleCertainty(certainty: Certainty) = updateLoaded { s ->
        val set = s.filters.certainties
        s.copy(filters = s.filters.copy(certainties = if (certainty in set) set - certainty else set + certainty))
    }

    fun setCategory(category: SecurityCategory?) = updateLoaded { it.copy(filters = it.filters.copy(category = category)) }

    fun clearFilters() = updateLoaded { it.copy(filters = SecurityFilters()) }

    fun select(index: Int?) = updateLoaded { it.copy(selected = index?.takeIf { i -> i in it.report.security.indices }) }

    /** Atrás: de la ficha a la lista. */
    fun back(): Boolean {
        val s = _state.value as? SecurityUiState.Loaded ?: return false
        if (s.selected == null) return false
        select(null)
        return true
    }

    private companion object {
        const val LIMIT = 50
    }
}

package com.funcodestudiocb.analyzer.ui.dependencies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.analysis.AnalysisReport
import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.api.ResolvedDependency
import com.funcodestudiocb.analyzer.engine.api.UsageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DependencyKind { ALL, DIRECT, INDIRECT }

/** Filtros de la lista de dependencias (por defecto, todas). */
data class DependencyFilters(
    val kind: DependencyKind = DependencyKind.ALL,
    val onlyVulnerable: Boolean = false,
    val usage: UsageStatus? = null,
    val ecosystem: Ecosystem? = null,
    val query: String = "",
    /** Solo las de este manifiesto o archivo de bloqueo (al venir desde el Explorador). */
    val file: String? = null,
) {
    val active: Boolean get() = this != DependencyFilters(file = file)

    fun matches(d: ResolvedDependency): Boolean =
        (kind == DependencyKind.ALL || d.direct == (kind == DependencyKind.DIRECT)) &&
            (!onlyVulnerable || d.vulnerabilities.isNotEmpty()) &&
            (usage == null || d.usage == usage) &&
            (ecosystem == null || d.ecosystem == ecosystem) &&
            (query.isBlank() || d.name.contains(query.trim(), ignoreCase = true)) &&
            (file == null || inFile(d, file))

    companion object {
        /** La dependencia se declara o se fija en [path]. */
        fun inFile(d: ResolvedDependency, path: String): Boolean =
            d.manifest == path || d.lockfile == path || d.alsoIn.any { it.substringBeforeLast(':') == path || it == path }
    }
}

sealed interface DependenciesUiState {
    data object Loading : DependenciesUiState
    data object NotFound : DependenciesUiState

    data class Loaded(
        val analysis: AnalysisEntity,
        val report: AnalysisReport,
        val filters: DependencyFilters = DependencyFilters(),
        /** Índice en [AnalysisReport.resolvedDependencies] de la dependencia abierta. */
        val selected: Int? = null,
    ) : DependenciesUiState {
        /** Orden: con vulnerabilidades primero (por la peor), luego criticidad, directas y nombre. */
        private val ordered: List<IndexedValue<ResolvedDependency>> = report.resolvedDependencies.withIndex().sortedWith(
            compareBy<IndexedValue<ResolvedDependency>>(
                { it.value.vulnerabilities.minOfOrNull { v -> v.severity.ordinal } ?: Int.MAX_VALUE },
                { it.value.criticality.ordinal },
                { if (it.value.direct) 0 else 1 },
                { it.value.name.lowercase() },
            ),
        )
        val visible: List<IndexedValue<ResolvedDependency>> = ordered.filter { filters.matches(it.value) }
        val ecosystems: List<Ecosystem> = report.resolvedDependencies.map { it.ecosystem }.distinct().sortedBy { it.ordinal }
        val usages: List<UsageStatus> = report.resolvedDependencies.map { it.usage }.distinct().sortedBy { it.ordinal }
    }
}

/** Dependencias de un análisis guardado: directas e indirectas, uso, criticidad, vulnerabilidades y fin de soporte. */
class DependenciesViewModel(container: AppContainer, analysisId: String, file: String? = null) : ViewModel() {

    private val _state = MutableStateFlow<DependenciesUiState>(DependenciesUiState.Loading)
    val state: StateFlow<DependenciesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = container.repository.load(analysisId)
            _state.value = if (loaded == null) {
                DependenciesUiState.NotFound
            } else {
                DependenciesUiState.Loaded(loaded.first, loaded.second, DependencyFilters(file = file))
            }
        }
    }

    private fun updateLoaded(transform: (DependenciesUiState.Loaded) -> DependenciesUiState.Loaded) =
        _state.update { s -> if (s is DependenciesUiState.Loaded) transform(s) else s }

    private fun filters(transform: (DependencyFilters) -> DependencyFilters) = updateLoaded { it.copy(filters = transform(it.filters)) }

    fun setKind(kind: DependencyKind) = filters { it.copy(kind = kind) }

    fun toggleOnlyVulnerable() = filters { it.copy(onlyVulnerable = !it.onlyVulnerable) }

    fun setUsage(usage: UsageStatus?) = filters { it.copy(usage = if (it.usage == usage) null else usage) }

    fun setEcosystem(ecosystem: Ecosystem?) = filters { it.copy(ecosystem = if (it.ecosystem == ecosystem) null else ecosystem) }

    fun setQuery(query: String) = filters { it.copy(query = query.take(MAX_QUERY)) }

    fun clearFilters() = filters { DependencyFilters(file = it.file) }

    fun clearFile() = filters { it.copy(file = null) }

    fun select(index: Int?) = updateLoaded { it.copy(selected = index?.takeIf { i -> i in it.report.resolvedDependencies.indices }) }

    /** Atrás: de la ficha a la lista. */
    fun back(): Boolean {
        val s = _state.value as? DependenciesUiState.Loaded ?: return false
        if (s.selected == null) return false
        select(null)
        return true
    }

    private companion object {
        const val MAX_QUERY = 100
    }
}

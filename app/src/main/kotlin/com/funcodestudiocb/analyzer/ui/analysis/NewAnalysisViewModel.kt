package com.funcodestudiocb.analyzer.ui.analysis

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funcodestudiocb.analyzer.AppContainer
import com.funcodestudiocb.analyzer.engine.report.AnalysisReport
import com.funcodestudiocb.analyzer.analysis.AnalysisState
import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Resultado de intentar añadir una carpeta a las exclusiones. */
enum class AddExclusionResult { ADDED, INVALID, DUPLICATE, TOO_MANY }

class NewAnalysisViewModel(private val container: AppContainer) : ViewModel() {

    private val coordinator = container.coordinator
    val state: StateFlow<AnalysisState> = coordinator.state

    private val _exclusions = MutableStateFlow(ExclusionOptions())
    val exclusions: StateFlow<ExclusionOptions> = _exclusions.asStateFlow()

    val recent: Flow<List<AnalysisEntity>> = container.repository.recent()

    init {
        viewModelScope.launch { _exclusions.value = container.repository.loadExclusions() }
    }

    fun select(uri: Uri) = coordinator.select(uri)

    fun start() = coordinator.start(_exclusions.value)

    /** La contraseña se convierte en `CharArray` y el coordinador la borra al terminar. */
    fun startWithPassword(password: String) = coordinator.start(_exclusions.value, password.toCharArray())

    fun continueWithoutPassword() = coordinator.continueWithoutPassword()

    fun discard() = coordinator.discard()

    fun reset() = coordinator.reset()

    fun setDirectoryExcluded(name: String, excluded: Boolean) = _exclusions.update { options ->
        val names = options.directoryNames.toMutableList()
        if (excluded && name !in names) names += name
        if (!excluded) names.remove(name)
        options.copy(directoryNames = names)
    }

    fun addDirectory(raw: String): AddExclusionResult {
        val name = raw.trim()
        val options = _exclusions.value
        return when {
            name.isEmpty() || name.length > ExclusionOptions.MAX_NAME_LENGTH || name == "." || name == ".." ||
                name.any { it == '/' || it == '\\' || it.isISOControl() } -> AddExclusionResult.INVALID
            name in options.directoryNames -> AddExclusionResult.DUPLICATE
            options.directoryNames.size >= ExclusionOptions.MAX_NAMES -> AddExclusionResult.TOO_MANY
            else -> {
                _exclusions.value = options.copy(directoryNames = options.directoryNames + name)
                AddExclusionResult.ADDED
            }
        }
    }

    fun setContextRules(enabled: Boolean) = _exclusions.update { it.copy(contextRules = enabled) }

    fun setTemporaryFiles(enabled: Boolean) = _exclusions.update { it.copy(temporaryFiles = enabled) }

    fun restoreDefaultExclusions() {
        _exclusions.value = ExclusionOptions()
    }

    /** Nombres a mostrar: los predeterminados y los añadidos por el usuario. */
    fun allDirectoryNames(options: ExclusionOptions): List<String> =
        (ExclusionOptions.DEFAULT_DIRECTORY_NAMES + options.directoryNames).distinct()
}

/** Detalle de un análisis guardado en el historial. */
class AnalysisDetailViewModel(private val container: AppContainer, private val id: String) : ViewModel() {

    sealed interface DetailState {
        data object Loading : DetailState
        data object NotFound : DetailState
        data class Loaded(val entity: AnalysisEntity, val report: AnalysisReport) : DetailState
        data object Deleted : DetailState
    }

    private val _state = MutableStateFlow<DetailState>(DetailState.Loading)
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = container.repository.load(id)?.let { (entity, report) -> DetailState.Loaded(entity, report) }
                ?: DetailState.NotFound
        }
    }

    fun delete() {
        viewModelScope.launch {
            container.repository.delete(id)
            _state.value = DetailState.Deleted
        }
    }
}

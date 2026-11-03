package com.funcodestudiocb.analyzer.analysis

import android.net.Uri
import com.funcodestudiocb.analyzer.data.session.SessionManager
import com.funcodestudiocb.analyzer.data.session.SessionState
import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.ContentChunk
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.engine.api.IngestLimits
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.InventoryOptions
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.RejectionReason
import com.funcodestudiocb.analyzer.engine.core.ProgressUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Progreso de una etapa, tal como lo muestra la interfaz. */
data class StageProgress(val title: String, val done: Boolean)

sealed interface AnalysisState {
    data object Idle : AnalysisState

    data object Importing : AnalysisState

    /** Comprimido importado: el usuario revisa los datos y las exclusiones antes de analizar. */
    data class Ready(val archive: ImportedArchive) : AnalysisState

    data class Running(
        val archive: ImportedArchive,
        val stages: List<StageProgress> = emptyList(),
        val overall: Float = 0f,
        val message: String? = null,
    ) : AnalysisState

    /** Parte o todo el comprimido está cifrado: se puede reintentar con la contraseña o seguir sin ella. */
    data class NeedsPassword(
        val archive: ImportedArchive,
        val wrongPassword: Boolean,
        val report: AnalysisReport,
        internal val pending: PendingResult,
    ) : AnalysisState

    data class Finished(
        val analysisId: String,
        val archiveName: String,
        val report: AnalysisReport,
        val outcome: AnalysisOutcome,
        /** Guardado en el historial cifrado (si la sesión estaba bloqueada al terminar, se guarda al desbloquear). */
        val saved: Boolean,
    ) : AnalysisState

    data class Failed(val reason: FailureReason, val detail: String? = null) : AnalysisState
}

enum class FailureReason { TOO_LARGE, UNSUPPORTED, UNREADABLE, BUSY, INTERNAL }

/** Resultado del motor todavía no guardado. */
class PendingResult internal constructor(
    val id: String,
    val archive: ImportedArchive,
    val startedAtEpochMs: Long,
    val records: List<ResultRecord>,
    val report: AnalysisReport,
    /** Copia redactada del código, cifrada con una clave efímera hasta que se guarde. */
    val spool: ContentSpool?,
)

/** Mantiene la app en primer plano mientras dura un análisis (servicio con notificación). */
interface ForegroundController {
    fun start()
    fun stop()

    companion object {
        val NONE = object : ForegroundController {
            override fun start() = Unit
            override fun stop() = Unit
        }
    }
}

/**
 * Coordina un análisis completo desde el proceso principal: importación, ejecución en el proceso aislado,
 * contraseña, guardado en el historial cifrado y limpieza de la copia temporal.
 *
 * Vive mientras vive el proceso de la app (no depende de la pantalla), de modo que el análisis continúa
 * aunque el usuario cambie de pantalla o la sesión se bloquee. Si la sesión está bloqueada al terminar,
 * el resultado se guarda en cuanto se desbloquea.
 */
class AnalysisCoordinator(
    private val importer: ArchiveImporter,
    private val engine: AnalysisEngine,
    private val repository: AnalysisRepository,
    private val session: SessionManager,
    /** Carpeta privada para la copia del código mientras se guarda (cifrada con clave efímera). */
    private val spoolDirectory: File,
    private val foreground: ForegroundController = ForegroundController.NONE,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val limits: IngestLimits = IngestLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    /** Se activa cuando otra app abre o comparte un comprimido con Funcode: la interfaz va a "Nuevo análisis". */
    private val _openRequested = MutableStateFlow(false)
    val openRequested: StateFlow<Boolean> = _openRequested.asStateFlow()

    private var job: Job? = null
    private var unsaved: AnalysisToSave? = null
    private val lock = Any()
    private val saveMutex = Mutex()

    init {
        importer.cleanupOrphans()
        ContentSpool.cleanupOrphans(spoolDirectory)
        scope.launch {
            // Se observa el contador de desbloqueos y no el estado: un bloqueo y desbloqueo rápidos podrían no
            // notarse en el estado y el resultado pendiente quedaría sin guardar.
            session.unlockCount.collect { if (session.state.value == SessionState.Unlocked) saveIfPending() }
        }
    }

    val busy: Boolean get() = job?.isActive == true || _state.value is AnalysisState.Running

    fun consumeOpenRequest() {
        _openRequested.value = false
    }

    /** Comprimido elegido con el selector, arrastrado o recibido de otra app. Se copia de inmediato. */
    fun select(uri: Uri, fromOtherApp: Boolean = false) = synchronized(lock) {
        if (busy) {
            _state.value = AnalysisState.Failed(FailureReason.BUSY)
            return@synchronized
        }
        if (fromOtherApp) _openRequested.value = true
        discardArchive()
        _state.value = AnalysisState.Importing
        job = scope.launch {
            _state.value = when (val result = importer.import(uri, limits.maxArchiveBytes)) {
                is ImportResult.Imported -> AnalysisState.Ready(result.archive)
                is ImportResult.TooLarge -> AnalysisState.Failed(FailureReason.TOO_LARGE, result.maxBytes.toString())
                is ImportResult.Unsupported -> AnalysisState.Failed(FailureReason.UNSUPPORTED, result.description)
                is ImportResult.Unreadable -> AnalysisState.Failed(FailureReason.UNREADABLE, result.reason)
            }
        }
    }

    /**
     * Analiza el comprimido importado. [password] (si se indica) se borra de memoria al terminar.
     */
    fun start(exclusions: ExclusionOptions, password: CharArray? = null) = synchronized(lock) {
        val archive = when (val current = _state.value) {
            is AnalysisState.Ready -> current.archive
            is AnalysisState.NeedsPassword -> {
                // Se repite con contraseña: la copia del intento anterior ya no sirve.
                current.pending.spool?.close()
                current.archive
            }
            else -> {
                password?.fill('\u0000')
                return@synchronized
            }
        }
        val id = "a" + UUID.randomUUID().toString().replace("-", "").take(24)
        val startedAt = clock()
        _state.value = AnalysisState.Running(archive)
        foreground.start()
        job = scope.launch {
            var spool: ContentSpool? = null
            try {
                repository.saveExclusions(exclusions)
                val inventory = InventoryOptions(keepRedactedCopy = repository.loadKeepCodeCopy())
                val config = AnalysisConfig(
                    analysisId = id,
                    mode = AnalysisMode.FULL,
                    ingest = IngestOptions(archive.displayName, limits, exclusions),
                    inventory = inventory,
                )
                val copy = ContentSpool.create(spoolDirectory).also { spool = it }
                val records = ArrayList<ResultRecord>()
                var copyProblem: String? = null
                val run = engine.analyze(config, archive.file, password, { onProgress(archive, it) }) { record ->
                    if (record !is ContentChunk) {
                        records += record
                    } else if (copyProblem == null) {
                        // El proceso principal también impone el máximo de la copia: no confía en el motor.
                        copyProblem = when {
                            !inventory.keepRedactedCopy -> "El motor envió una copia del código estando desactivada; se descartó"
                            copy.chars + record.text.length > inventory.maxStoredChars -> "La copia del código superó su tamaño máximo; el resto se descartó"
                            else -> try {
                                copy.append(record)
                                null
                            } catch (e: IOException) {
                                "No se pudo guardar la copia del código (¿falta espacio en el teléfono?)"
                            }
                        }
                    }
                }
                copy.finishWriting()
                copyProblem?.let { records += CoverageNote(APP_STAGE, CoverageReason.LIMIT_REACHED, it) }
                val report = AnalysisReport.from(records, run.sessionOutcome, run.problem)
                val pending = PendingResult(id, archive, startedAt, records, report, copy)
                spool = null // a partir de aquí, su dueño es el resultado pendiente
                if (report.needsPassword && run.problem == null) {
                    _state.value = AnalysisState.NeedsPassword(archive, wrongPassword(report), report, pending)
                } else {
                    finish(pending)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { discardArchive() }
                _state.value = AnalysisState.Idle
                throw e
            } catch (e: Throwable) {
                // Cualquier error (también Error, p. ej. falta de memoria o una clase incompatible) deja la
                // interfaz en un estado claro en lugar de quedarse "en curso".
                discardArchive()
                _state.value = AnalysisState.Failed(FailureReason.INTERNAL, e::class.java.simpleName)
            } finally {
                spool?.close()
                password?.fill('\u0000')
                foreground.stop()
            }
        }
    }

    /** Desde [AnalysisState.NeedsPassword]: se acepta el resultado parcial sin contraseña. */
    fun continueWithoutPassword() = synchronized(lock) {
        val current = _state.value as? AnalysisState.NeedsPassword ?: return@synchronized
        job = scope.launch { finish(current.pending) }
    }

    /** Cancela el análisis en curso o descarta el comprimido importado. */
    fun discard() = synchronized(lock) {
        val running = job?.takeIf { it.isActive && _state.value is AnalysisState.Running }
        if (running != null) {
            running.cancel()
        } else {
            discardArchive()
            _state.value = AnalysisState.Idle
        }
    }

    /** Vuelve al estado inicial tras ver un resultado o un error. */
    fun reset() = synchronized(lock) {
        if (!busy) {
            discardArchive()
            _state.value = AnalysisState.Idle
        }
    }

    /** Tras un borrado total: se olvida todo, incluido lo pendiente de guardar. */
    fun forgetEverything() = synchronized(lock) {
        job?.cancel()
        unsaved?.spool?.close()
        unsaved = null
        discardArchive()
        importer.cleanupOrphans()
        ContentSpool.cleanupOrphans(spoolDirectory)
        _state.value = AnalysisState.Idle
    }

    private suspend fun finish(pending: PendingResult) {
        val toSave = AnalysisToSave(
            id = pending.id,
            archiveName = pending.archive.displayName,
            archiveSize = pending.archive.sizeBytes,
            archiveSha256 = pending.archive.sha256,
            startedAtEpochMs = pending.startedAtEpochMs,
            finishedAtEpochMs = clock(),
            records = pending.records,
            report = pending.report,
            spool = pending.spool,
        )
        pending.archive.file.delete()
        synchronized(lock) { unsaved = toSave }
        _state.value = AnalysisState.Finished(pending.id, pending.archive.displayName, pending.report, pending.report.outcome(), saved = false)
        saveIfPending()
    }

    /**
     * Guarda el resultado pendiente si la sesión está abierta. Los guardados se hacen de uno en uno (al terminar un
     * análisis y al desbloquear pueden coincidir) y un fallo, por ejemplo porque la sesión se bloquea a mitad, no se
     * propaga: el resultado sigue pendiente y se reintenta en el siguiente desbloqueo.
     */
    private suspend fun saveIfPending() = saveMutex.withLock {
        val toSave = synchronized(lock) { unsaved } ?: return@withLock
        val saved = try {
            repository.save(toSave)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (saved) {
            toSave.spool?.close()
            synchronized(lock) { if (unsaved === toSave) unsaved = null }
            _state.update { if (it is AnalysisState.Finished && it.analysisId == toSave.id) it.copy(saved = true) else it }
        }
    }

    private fun onProgress(archive: ImportedArchive, update: ProgressUpdate) {
        _state.update { current ->
            if (current !is AnalysisState.Running || current.archive != archive) return@update current
            val stages = current.stages.toMutableList()
            while (stages.size <= update.stageIndex) stages += StageProgress("", done = false)
            for (i in 0 until update.stageIndex) stages[i] = stages[i].copy(done = true)
            stages[update.stageIndex] = StageProgress(update.stageTitle, done = update.stageFraction >= 1f)
            current.copy(stages = stages, overall = update.overallFraction, message = update.message)
        }
    }

    private fun wrongPassword(report: AnalysisReport): Boolean =
        report.passwordRequired?.wrongPassword == true || report.rejection?.reason == RejectionReason.WRONG_PASSWORD

    private fun discardArchive() {
        when (val current = _state.value) {
            is AnalysisState.Ready -> current.archive.file.delete()
            is AnalysisState.NeedsPassword -> {
                current.archive.file.delete()
                current.pending.spool?.close()
            }
            is AnalysisState.Running -> current.archive.file.delete()
            else -> Unit
        }
    }

    private companion object {
        /** Etapa con la que la app firma sus propias notas de cobertura. */
        const val APP_STAGE = "app"
    }
}

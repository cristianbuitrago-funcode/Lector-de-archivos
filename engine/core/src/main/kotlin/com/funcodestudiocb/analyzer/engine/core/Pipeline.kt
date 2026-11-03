package com.funcodestudiocb.analyzer.engine.core

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.RESULT_SCHEMA_VERSION
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionEnd
import com.funcodestudiocb.analyzer.engine.api.SessionOutcome
import com.funcodestudiocb.analyzer.engine.api.SessionStart
import com.funcodestudiocb.analyzer.engine.api.StageEvent
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource

/** Error de programación en la definición del pipeline (no depende del proyecto analizado). */
class PipelineDefinitionException(message: String) : IllegalStateException(message)

/**
 * Registro de etapas. Calcula el orden de ejecución respetando las dependencias y, a igualdad,
 * el orden de registro.
 */
class StageRegistry {
    private val stages = LinkedHashMap<String, Stage>()

    fun register(stage: Stage): StageRegistry {
        if (!STAGE_ID.matches(stage.id)) throw PipelineDefinitionException("Identificador de etapa inválido: ${stage.id}")
        if (stages.containsKey(stage.id)) throw PipelineDefinitionException("Etapa duplicada: ${stage.id}")
        stages[stage.id] = stage
        return this
    }

    fun plan(): List<Stage> {
        stages.values.forEach { stage ->
            stage.dependsOn.forEach { dep ->
                if (!stages.containsKey(dep)) {
                    throw PipelineDefinitionException("La etapa ${stage.id} depende de una etapa inexistente: $dep")
                }
            }
        }
        val ordered = mutableListOf<Stage>()
        val done = mutableSetOf<String>()
        val pending = stages.values.toMutableList()
        while (pending.isNotEmpty()) {
            val next = pending.firstOrNull { done.containsAll(it.dependsOn) }
                ?: throw PipelineDefinitionException(
                    "Dependencia circular entre etapas: ${pending.joinToString { it.id }}",
                )
            ordered += next
            done += next.id
            pending.remove(next)
        }
        return ordered
    }

    private companion object {
        val STAGE_ID = Regex("^[A-Za-z0-9_.-]{1,64}$")
    }
}

/** Resultado de ejecutar el pipeline. */
data class PipelineResult(
    val outcome: SessionOutcome,
    val statuses: Map<String, StageStatus>,
    val recordCount: Long,
)

/**
 * Ejecuta un pipeline de etapas ya ordenado.
 *
 * Garantías:
 * - Siempre emite [SessionStart] al principio y [SessionEnd] al final, incluso si hay cancelación.
 * - Un fallo en una etapa no detiene el análisis: se registra y las etapas que dependen de ella se omiten.
 * - Los tiempos máximos por etapa y total se aplican de forma cooperativa. Una etapa que se bloquee sin
 *   cooperar no puede detenerse desde aquí: para eso existe el vigilante del proceso principal, que
 *   destruye el proceso aislado.
 * - Los mensajes de las excepciones NO se envían (podrían contener fragmentos del proyecto, incluidos
 *   secretos); solo el tipo de error.
 */
class PipelineRunner(private val engineVersion: String) {

    suspend fun run(
        config: AnalysisConfig,
        stages: List<Stage>,
        sink: RecordSink,
        listener: ProgressListener = ProgressListener { },
    ): PipelineResult {
        val counter = AtomicLong(0)
        val countingSink = RecordSink { record ->
            sink.accept(record)
            counter.incrementAndGet()
        }
        val statuses = LinkedHashMap<String, StageStatus>()
        var currentIndex = 0

        countingSink.accept(
            SessionStart(
                schemaVersion = RESULT_SCHEMA_VERSION,
                engineVersion = engineVersion,
                analysisId = config.analysisId,
                mode = config.mode,
                stageIds = stages.map { it.id },
            ),
        )

        try {
            val finished = withTimeoutOrNull(config.limits.maxTotalMillis) {
                stages.forEachIndexed { index, stage ->
                    currentIndex = index
                    statuses[stage.id] = runStage(config, stage, index, stages.size, statuses, countingSink, listener)
                }
                true
            }
            if (finished == null) {
                markRemaining(stages, currentIndex, statuses, countingSink, StageStatus.TIMED_OUT)
                return finish(SessionOutcome.TIMED_OUT, "Se agotó el tiempo máximo del análisis", statuses, counter, countingSink)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                markRemaining(stages, currentIndex, statuses, countingSink, StageStatus.CANCELLED)
                finish(SessionOutcome.CANCELLED, "Análisis cancelado", statuses, counter, countingSink)
            }
            throw e
        }

        val outcome = if (statuses.values.all { it == StageStatus.COMPLETED }) {
            SessionOutcome.COMPLETED
        } else {
            SessionOutcome.PARTIAL
        }
        return finish(outcome, null, statuses, counter, countingSink)
    }

    private suspend fun runStage(
        config: AnalysisConfig,
        stage: Stage,
        index: Int,
        count: Int,
        statuses: Map<String, StageStatus>,
        sink: RecordSink,
        listener: ProgressListener,
    ): StageStatus {
        val failedDependency = stage.dependsOn.firstOrNull { statuses[it] != StageStatus.COMPLETED }
        if (failedDependency != null) {
            val detail = "No se ejecutó porque depende de '$failedDependency', que no terminó correctamente"
            sink.accept(StageEvent(stage.id, StageStatus.SKIPPED, 0, detail))
            sink.accept(CoverageNote(stage.id, CoverageReason.STAGE_SKIPPED, detail))
            return StageStatus.SKIPPED
        }

        val context = DefaultStageContext(config, stage, index, count, sink, listener)
        val mark = TimeSource.Monotonic.markNow()
        context.progress(0f)
        val status = try {
            val completed = withTimeoutOrNull(config.limits.maxStageMillis) {
                stage.run(context)
                true
            }
            if (completed == null) StageStatus.TIMED_OUT else StageStatus.COMPLETED
        } catch (e: CancellationException) {
            // Una cancelación real (análisis cancelado o tiempo total agotado) se propaga. Si el
            // pipeline sigue activo, la excepción salió de la propia etapa: se trata como un fallo.
            if (!currentCoroutineContext().isActive) throw e
            failure(stage, e, mark, sink)
            return StageStatus.FAILED
        } catch (e: Exception) {
            failure(stage, e, mark, sink)
            return StageStatus.FAILED
        } catch (e: StackOverflowError) {
            failure(stage, e, mark, sink)
            return StageStatus.FAILED
        }

        val elapsed = mark.elapsedNow().inWholeMilliseconds
        if (status == StageStatus.TIMED_OUT) {
            val detail = "La etapa superó su tiempo máximo (${config.limits.maxStageMillis} ms); sus resultados pueden estar incompletos"
            sink.accept(StageEvent(stage.id, StageStatus.TIMED_OUT, elapsed, detail))
            sink.accept(CoverageNote(stage.id, CoverageReason.STAGE_TIMED_OUT, detail))
        } else {
            sink.accept(StageEvent(stage.id, StageStatus.COMPLETED, elapsed))
        }
        context.progress(1f)
        return status
    }

    private fun failure(stage: Stage, error: Throwable, mark: TimeSource.Monotonic.ValueTimeMark, sink: RecordSink) {
        val detail = "La etapa falló (${error::class.java.simpleName}); sus resultados no están disponibles"
        sink.accept(StageEvent(stage.id, StageStatus.FAILED, mark.elapsedNow().inWholeMilliseconds, detail))
        sink.accept(CoverageNote(stage.id, CoverageReason.STAGE_FAILED, detail))
    }

    private fun markRemaining(
        stages: List<Stage>,
        fromIndex: Int,
        statuses: MutableMap<String, StageStatus>,
        sink: RecordSink,
        status: StageStatus,
    ) {
        val reason = if (status == StageStatus.TIMED_OUT) CoverageReason.STAGE_TIMED_OUT else CoverageReason.NOT_ANALYZED
        val detail = if (status == StageStatus.TIMED_OUT) {
            "No se completó: se agotó el tiempo máximo del análisis"
        } else {
            "No se completó: el análisis fue cancelado"
        }
        for (i in fromIndex until stages.size) {
            val stage = stages[i]
            if (statuses.containsKey(stage.id)) continue
            statuses[stage.id] = status
            sink.accept(StageEvent(stage.id, status, 0, detail))
            sink.accept(CoverageNote(stage.id, reason, detail))
        }
    }

    private fun finish(
        outcome: SessionOutcome,
        message: String?,
        statuses: Map<String, StageStatus>,
        counter: AtomicLong,
        sink: RecordSink,
    ): PipelineResult {
        val count = counter.get()
        sink.accept(SessionEnd(outcome, count, message))
        return PipelineResult(outcome, statuses.toMap(), count + 1)
    }
}

/** Sumidero que guarda los registros en memoria (pruebas y diagnósticos). */
class CollectingSink : RecordSink {
    private val lock = Any()
    private val items = mutableListOf<ResultRecord>()

    val records: List<ResultRecord> get() = synchronized(lock) { items.toList() }

    override fun accept(record: ResultRecord) {
        synchronized(lock) { items += record }
    }
}

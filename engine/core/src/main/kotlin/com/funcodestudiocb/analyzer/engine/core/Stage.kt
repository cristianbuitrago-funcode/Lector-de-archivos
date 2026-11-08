package com.funcodestudiocb.analyzer.engine.core

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Una etapa del pipeline de análisis (E1–E12 en la arquitectura).
 *
 * Las etapas son el principal punto de extensión del motor: cada lenguaje, framework o
 * analizador nuevo se incorpora como una o varias etapas registradas en el [StageRegistry].
 */
interface Stage {
    /** Identificador estable, p. ej. `ingest.inspect`. */
    val id: String

    /** Nombre en español que se muestra en el progreso. */
    val title: String

    /** Etapas que deben haber terminado correctamente antes de esta. */
    val dependsOn: Set<String> get() = emptySet()

    suspend fun run(context: StageContext)
}

/** Lo que una etapa puede hacer mientras se ejecuta. */
interface StageContext {
    val config: AnalysisConfig

    /** Datos compartidos con las demás etapas de esta ejecución. */
    val attributes: Attributes

    /** Emite un registro de resultado. */
    fun emit(record: ResultRecord)

    /** Informa del progreso de la etapa actual, entre 0 y 1. */
    fun progress(fraction: Float, message: String? = null)

    /** Registra algo que no se pudo analizar y por qué. */
    fun coverage(reason: CoverageReason, detail: String, subject: String? = null)

    /** Lanza una excepción de cancelación si el análisis fue cancelado o agotó su tiempo. */
    suspend fun checkActive()
}

/**
 * Detención controlada de una etapa con un mensaje redactado por el propio motor (nunca con texto del
 * proyecto sin escapar). Las etapas que dependen de ella se omiten.
 */
class StageStoppedException(message: String) : Exception(message)

/** Destino de los registros producidos por el pipeline. */
fun interface RecordSink {
    fun accept(record: ResultRecord)
}

/** Recibe el progreso del pipeline. */
fun interface ProgressListener {
    fun onProgress(update: ProgressUpdate)
}

data class ProgressUpdate(
    val stageId: String,
    val stageTitle: String,
    /** Posición de la etapa (desde 0). */
    val stageIndex: Int,
    val stageCount: Int,
    /** Progreso dentro de la etapa, entre 0 y 1. */
    val stageFraction: Float,
    val message: String?,
) {
    /** Progreso global aproximado, entre 0 y 1. */
    val overallFraction: Float
        get() = if (stageCount == 0) 1f else ((stageIndex + stageFraction.coerceIn(0f, 1f)) / stageCount)
}

internal class DefaultStageContext(
    override val config: AnalysisConfig,
    override val attributes: Attributes,
    private val stage: Stage,
    private val stageIndex: Int,
    private val stageCount: Int,
    private val sink: RecordSink,
    private val listener: ProgressListener,
    private val timings: StageTimings? = null,
) : StageContext {

    override fun emit(record: ResultRecord) {
        if (timings == null) sink.accept(record) else timings.measure(StageTimings.SEND) { sink.accept(record) }
    }

    override fun progress(fraction: Float, message: String?) {
        listener.onProgress(
            ProgressUpdate(stage.id, stage.title, stageIndex, stageCount, fraction.coerceIn(0f, 1f), message),
        )
    }

    override fun coverage(reason: CoverageReason, detail: String, subject: String?) {
        sink.accept(CoverageNote(stage.id, reason, detail, subject))
    }

    override suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
    }
}

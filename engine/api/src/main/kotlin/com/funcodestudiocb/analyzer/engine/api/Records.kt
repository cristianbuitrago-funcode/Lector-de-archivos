package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versión del esquema de registros que el motor envía al proceso principal.
 * Se incrementa cada vez que cambia la forma de algún registro.
 */
const val RESULT_SCHEMA_VERSION: Int = 5

/**
 * Registro producido por el motor de análisis.
 *
 * Los registros viajan desde el proceso aislado hasta el proceso principal por una tubería
 * (ver [FrameWriter] y [FrameReader]). El proceso principal los trata como datos NO confiables:
 * cada uno se valida con [RecordValidator] antes de usarse.
 */
@Serializable
sealed interface ResultRecord

/** Primer registro obligatorio de toda sesión. */
@Serializable
@SerialName("session_start")
data class SessionStart(
    val schemaVersion: Int,
    val engineVersion: String,
    val analysisId: String,
    val mode: AnalysisMode,
    val stageIds: List<String>,
) : ResultRecord

/** Resultado de una etapa del pipeline. */
@Serializable
@SerialName("stage")
data class StageEvent(
    val stageId: String,
    val status: StageStatus,
    val durationMs: Long,
    val message: String? = null,
) : ResultRecord

/**
 * Anotación de cobertura: algo que no se analizó, o se analizó parcialmente, y el motivo.
 * Alimenta la sección obligatoria "Cobertura y limitaciones del análisis".
 */
@Serializable
@SerialName("coverage")
data class CoverageNote(
    val stageId: String,
    val reason: CoverageReason,
    val detail: String,
    val subject: String? = null,
) : ResultRecord

/** Informe del autodiagnóstico de aislamiento, generado dentro del proceso aislado. */
@Serializable
@SerialName("isolation")
data class IsolationReport(
    val uid: Int,
    val pid: Int,
    val processName: String,
    val isolatedBySystem: Boolean,
    val network: ProbeResult,
    val appFiles: ProbeResult,
    val sharedStorage: ProbeResult,
) : ResultRecord

/** Registro sintético usado para verificar el canal de resultados. */
@Serializable
@SerialName("sample")
data class DiagnosticSample(
    val index: Int,
    val payload: String,
) : ResultRecord

/** Último registro obligatorio de toda sesión. */
@Serializable
@SerialName("session_end")
data class SessionEnd(
    val outcome: SessionOutcome,
    val recordCount: Long,
    val message: String? = null,
) : ResultRecord

@Serializable
enum class AnalysisMode {
    /** Autodiagnóstico del aislamiento y del canal de resultados. */
    DIAGNOSTIC,

    /** Análisis completo de un proyecto. */
    FULL,
}

@Serializable
enum class StageStatus { COMPLETED, FAILED, SKIPPED, TIMED_OUT, CANCELLED }

@Serializable
enum class SessionOutcome {
    /** Todas las etapas terminaron (algunas pueden haber registrado fallos parciales en la cobertura). */
    COMPLETED,

    /** Al menos una etapa falló o no se ejecutó; los resultados son parciales. */
    PARTIAL,

    /** Se agotó el tiempo máximo del análisis. */
    TIMED_OUT,

    /** El análisis fue cancelado. */
    CANCELLED,
}

@Serializable
enum class CoverageReason {
    STAGE_FAILED,
    STAGE_SKIPPED,
    STAGE_TIMED_OUT,
    NOT_ANALYZED,
    LIMIT_REACHED,
    UNSUPPORTED,
    EXCLUDED,
}

/**
 * Resultado de una sonda de aislamiento.
 *
 * - [ProbeOutcome.BLOCKED]: el sistema denegó la operación (lo esperado).
 * - [ProbeOutcome.ALLOWED]: la operación funcionó (fallo grave de aislamiento).
 * - [ProbeOutcome.INCONCLUSIVE]: falló por otro motivo (p. ej. sin conexión), así que no demuestra nada.
 */
@Serializable
data class ProbeResult(
    val outcome: ProbeOutcome,
    val detail: String,
)

@Serializable
enum class ProbeOutcome { BLOCKED, ALLOWED, INCONCLUSIVE }

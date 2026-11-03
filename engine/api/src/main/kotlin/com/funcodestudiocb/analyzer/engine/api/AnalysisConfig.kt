package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.Serializable

/**
 * Configuración de un análisis, enviada por el proceso principal al proceso aislado.
 *
 * El proceso aislado vuelve a validarla con [ConfigValidator] antes de usarla: no confía
 * ciegamente en lo que recibe, igual que el proceso principal no confía en lo que devuelve.
 */
@Serializable
data class AnalysisConfig(
    val analysisId: String,
    val mode: AnalysisMode,
    val limits: AnalysisLimits = AnalysisLimits(),
    val diagnostic: DiagnosticOptions? = null,
    /** Obligatorio en el modo [AnalysisMode.FULL]. */
    val ingest: IngestOptions? = null,
    val inventory: InventoryOptions = InventoryOptions(),
)

/** Opciones del inventario y de la copia redactada del código (modo [AnalysisMode.FULL]). */
@Serializable
data class InventoryOptions(
    /** Guardar una copia del código con los posibles secretos ocultados, para el visor (decisión D4). */
    val keepRedactedCopy: Boolean = true,
    /** Tamaño máximo de un archivo de texto cuyo contenido se examina (y se copia). */
    val maxContentBytes: Long = 2L * IngestLimits.MIB,
    /** Tamaño máximo total de la copia del código, en caracteres. */
    val maxStoredChars: Long = 64L * IngestLimits.MIB,
) {
    companion object {
        const val MAX_CONTENT_BYTES_ALLOWED: Long = 16L * IngestLimits.MIB
        const val MAX_STORED_CHARS_ALLOWED: Long = 256L * IngestLimits.MIB
    }
}

/** Límites de tiempo del análisis. Los límites de tamaño están en [IngestLimits]. */
@Serializable
data class AnalysisLimits(
    /** Tiempo máximo total del análisis. */
    val maxTotalMillis: Long = DEFAULT_MAX_TOTAL_MILLIS,
    /** Tiempo máximo de una sola etapa. */
    val maxStageMillis: Long = DEFAULT_MAX_STAGE_MILLIS,
) {
    companion object {
        const val DEFAULT_MAX_TOTAL_MILLIS: Long = 30L * 60 * 1000
        const val DEFAULT_MAX_STAGE_MILLIS: Long = 15L * 60 * 1000
        const val MIN_MILLIS: Long = 100
        const val MAX_TOTAL_MILLIS_ALLOWED: Long = 2L * 60 * 60 * 1000
    }
}

/** Opciones del modo [AnalysisMode.DIAGNOSTIC]. */
@Serializable
data class DiagnosticOptions(
    /** Ruta de un archivo privado de la app que el proceso aislado NO debería poder leer. */
    val canaryPath: String,
    /** Ruta del directorio de datos de la app, que el proceso aislado NO debería poder listar. */
    val appDataDir: String,
    /** Número de registros sintéticos para probar el canal de resultados. */
    val sampleRecords: Int = 200,
    /** Solo pruebas: hace que una etapa se quede bloqueada para comprobar el vigilante. */
    val simulateHangMillis: Long = 0,
) {
    companion object {
        const val MAX_SAMPLE_RECORDS: Int = 10_000
    }
}

/** Valida una [AnalysisConfig] recibida desde el otro proceso. */
object ConfigValidator {

    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    private const val MAX_PATH_LENGTH = 1024

    fun validate(config: AnalysisConfig): List<String> {
        val errors = mutableListOf<String>()
        if (!ID_PATTERN.matches(config.analysisId)) {
            errors += "Identificador de análisis inválido"
        }
        val limits = config.limits
        if (limits.maxTotalMillis !in AnalysisLimits.MIN_MILLIS..AnalysisLimits.MAX_TOTAL_MILLIS_ALLOWED) {
            errors += "Tiempo máximo total fuera de rango"
        }
        if (limits.maxStageMillis < AnalysisLimits.MIN_MILLIS || limits.maxStageMillis > limits.maxTotalMillis) {
            errors += "Tiempo máximo por etapa fuera de rango"
        }
        when (config.mode) {
            AnalysisMode.DIAGNOSTIC -> {
                val d = config.diagnostic
                if (d == null) {
                    errors += "El modo diagnóstico requiere opciones de diagnóstico"
                } else {
                    if (!isSafeAbsolutePath(d.canaryPath)) errors += "Ruta de archivo canario inválida"
                    if (!isSafeAbsolutePath(d.appDataDir)) errors += "Ruta de datos de la app inválida"
                    if (d.sampleRecords !in 0..DiagnosticOptions.MAX_SAMPLE_RECORDS) {
                        errors += "Número de registros de prueba fuera de rango"
                    }
                    if (d.simulateHangMillis < 0 || d.simulateHangMillis > limits.maxTotalMillis * 2) {
                        errors += "Bloqueo simulado fuera de rango"
                    }
                }
            }
            AnalysisMode.FULL -> {
                val ingest = config.ingest
                if (ingest == null) errors += "El análisis completo requiere opciones de ingesta" else errors += IngestOptionsValidator.validate(ingest)
                val inventory = config.inventory
                if (inventory.maxContentBytes !in 1L..InventoryOptions.MAX_CONTENT_BYTES_ALLOWED) errors += "Tamaño máximo de contenido fuera de rango"
                if (inventory.maxStoredChars !in 0L..InventoryOptions.MAX_STORED_CHARS_ALLOWED) errors += "Tamaño máximo de la copia del código fuera de rango"
            }
        }
        return errors
    }

    private fun isSafeAbsolutePath(path: String): Boolean =
        path.length in 2..MAX_PATH_LENGTH &&
            path.startsWith("/") &&
            path.none { it == '\u0000' || it.isISOControl() } &&
            path.split('/').none { it == ".." }
}

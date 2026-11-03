package com.funcodestudiocb.analyzer.engine.api

/**
 * Validación semántica de los registros recibidos del proceso aislado.
 *
 * La deserialización ya garantiza la forma del registro; aquí se comprueban rangos, longitudes y
 * caracteres. Un registro inválido indica un motor defectuoso o comprometido: se rechaza el flujo.
 */
object RecordValidator {

    const val MAX_MESSAGE_LENGTH = 2_000
    const val MAX_DETAIL_LENGTH = 4_000
    const val MAX_SUBJECT_LENGTH = 1_024
    const val MAX_PAYLOAD_LENGTH = 4_096
    const val MAX_PROCESS_NAME_LENGTH = 256
    const val MAX_STAGES = 128

    private val ID_PATTERN = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val VERSION_PATTERN = Regex("^[A-Za-z0-9_.+-]{1,64}$")

    fun validate(record: ResultRecord): List<String> {
        val errors = mutableListOf<String>()
        when (record) {
            is SessionStart -> {
                if (record.schemaVersion != RESULT_SCHEMA_VERSION) {
                    errors += "Versión de esquema no admitida: ${record.schemaVersion}"
                }
                checkPattern(errors, "engineVersion", record.engineVersion, VERSION_PATTERN)
                checkPattern(errors, "analysisId", record.analysisId, ID_PATTERN)
                if (record.stageIds.size > MAX_STAGES) errors += "Demasiadas etapas"
                record.stageIds.forEach { checkPattern(errors, "stageId", it, ID_PATTERN) }
                if (record.stageIds.toSet().size != record.stageIds.size) errors += "Etapas duplicadas"
            }
            is StageEvent -> {
                checkPattern(errors, "stageId", record.stageId, ID_PATTERN)
                if (record.durationMs < 0) errors += "Duración negativa"
                checkText(errors, "message", record.message, MAX_MESSAGE_LENGTH)
            }
            is CoverageNote -> {
                checkPattern(errors, "stageId", record.stageId, ID_PATTERN)
                checkText(errors, "detail", record.detail, MAX_DETAIL_LENGTH)
                checkText(errors, "subject", record.subject, MAX_SUBJECT_LENGTH)
            }
            is IsolationReport -> {
                if (record.uid < 0) errors += "UID inválido"
                if (record.pid <= 0) errors += "PID inválido"
                checkText(errors, "processName", record.processName, MAX_PROCESS_NAME_LENGTH)
                listOf(record.network, record.appFiles, record.sharedStorage).forEach {
                    checkText(errors, "probe.detail", it.detail, MAX_MESSAGE_LENGTH)
                }
            }
            is DiagnosticSample -> {
                if (record.index < 0) errors += "Índice negativo"
                checkText(errors, "payload", record.payload, MAX_PAYLOAD_LENGTH)
            }
            is SessionEnd -> {
                if (record.recordCount < 0) errors += "Recuento negativo"
                checkText(errors, "message", record.message, MAX_MESSAGE_LENGTH)
            }
        }
        return errors
    }

    private fun checkPattern(errors: MutableList<String>, field: String, value: String, pattern: Regex) {
        if (!pattern.matches(value)) errors += "Campo '$field' con formato inválido"
    }

    private fun checkText(errors: MutableList<String>, field: String, value: String?, max: Int) {
        if (value == null) return
        if (value.length > max) errors += "Campo '$field' demasiado largo"
        if (value.any { it == '\u0000' }) errors += "Campo '$field' contiene caracteres nulos"
    }
}

/** Resultado de leer una sesión completa del motor. */
data class SessionReadResult(
    val start: SessionStart?,
    val records: List<ResultRecord>,
    val end: SessionEnd?,
    /** Motivo por el que se dejó de confiar en el flujo, o `null` si fue correcto. */
    val violation: String?,
) {
    /** La sesión terminó con su registro final y sin violaciones del protocolo. */
    val isComplete: Boolean get() = start != null && end != null && violation == null
}

/**
 * Lee una sesión completa aplicando las reglas del protocolo:
 * - el primer registro es [SessionStart] y no se repite;
 * - nada llega después de [SessionEnd];
 * - [SessionEnd.recordCount] coincide con el número de registros recibidos antes de él;
 * - cada registro supera [RecordValidator].
 *
 * Los registros válidos recibidos antes de una violación se conservan (resultados parciales).
 * [onRecord] se invoca con cada registro intermedio validado, en orden.
 */
class SessionReader(
    private val reader: FrameReader,
    private val maxBufferedRecords: Int = 100_000,
    private val onRecord: (ResultRecord) -> Unit = {},
) {
    fun readAll(): SessionReadResult {
        var start: SessionStart? = null
        var end: SessionEnd? = null
        val records = mutableListOf<ResultRecord>()
        var received = 0L

        fun result(violation: String?) = SessionReadResult(start, records, end, violation)

        while (true) {
            val record = try {
                reader.next()
            } catch (e: ProtocolViolationException) {
                return result(e.message)
            } ?: break

            val errors = RecordValidator.validate(record)
            if (errors.isNotEmpty()) return result("Registro inválido: ${errors.joinToString("; ")}")

            if (end != null) return result("Se recibieron datos después del final de la sesión")
            when (record) {
                is SessionStart -> {
                    if (start != null) return result("Inicio de sesión duplicado")
                    start = record
                    received++
                }
                is SessionEnd -> {
                    if (start == null) return result("Fin de sesión sin inicio")
                    if (record.recordCount != received) {
                        return result("Recuento de registros inconsistente: se esperaban ${record.recordCount}, llegaron $received")
                    }
                    end = record
                }
                else -> {
                    if (start == null) return result("Se recibieron datos antes del inicio de la sesión")
                    received++
                    if (records.size < maxBufferedRecords) records += record
                    onRecord(record)
                }
            }
        }
        return result(null)
    }
}

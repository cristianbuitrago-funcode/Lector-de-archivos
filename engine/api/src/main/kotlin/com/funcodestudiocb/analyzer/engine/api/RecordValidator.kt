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
    const val MAX_PATH_LENGTH = 8_192
    const val MAX_DUPLICATE_PATHS = 1_000
    const val MAX_EVIDENCE = 8
    const val MAX_EVIDENCE_DETAIL = 500
    const val MAX_TARGET_LENGTH = 512
    const val MAX_CHUNK_CHARS = 32_768
    const val MAX_CHUNKS_PER_FILE = 1_024
    const val MAX_MASKED_LENGTH = 32
    const val MAX_HIGHLIGHT_SPANS = 12_000
    const val MAX_HIGHLIGHT_CATEGORY = 7
    const val MAX_NAME_LENGTH = 200
    const val MAX_VERSION_LENGTH = 100
    const val MAX_TECH_VERSIONS = 8
    const val MAX_TECH_ROOTS = 50
    const val MAX_EXCHANGED = 50
    const val MAX_REFERENCE_LENGTH = 1_024
    const val MAX_SYMBOLS = 300
    const val MAX_CYCLE_PATHS = 100
    const val MAX_IMPACT_ENTRY_POINTS = 3
    const val MAX_SAMPLES = 5
    const val MAX_ROUTE_LENGTH = 512
    const val MAX_OPERATIONS = 8
    val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "ANY")

    /** Caracteres visibles que puede mostrar un secreto enmascarado: 4 al principio y 2 al final (§8.4). */
    const val MAX_VISIBLE_SECRET_CHARS = 6
    const val MASK_CHAR = '\u2022'

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

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
            is ArchiveSummary -> {
                if (record.sizeBytes < 0 || record.entryCount < 0 || record.directoryCount < 0 || record.declaredUncompressedBytes < 0) {
                    errors += "Valores negativos en el resumen del comprimido"
                }
            }
            is ArchiveRejected -> checkText(errors, "detail", record.detail, MAX_DETAIL_LENGTH)
            is PasswordRequired -> checkText(errors, "detail", record.detail, MAX_DETAIL_LENGTH)
            is FileEntry -> {
                checkPath(errors, record.path)
                if (record.sizeBytes < 0 || record.compressedBytes < 0) errors += "Tamaños negativos"
                record.sha256?.let { if (!SHA256_PATTERN.matches(it)) errors += "Huella SHA-256 inválida" }
                checkText(errors, "encoding", record.encoding, 32)
            }
            is ExcludedDirectory -> {
                checkPath(errors, record.path)
                checkText(errors, "rule", record.rule, 200)
                if (record.fileCount < 0 || record.totalBytes < 0) errors += "Valores negativos"
            }
            is ExcludedFile -> {
                checkPath(errors, record.path)
                checkText(errors, "rule", record.rule, 200)
                if (record.sizeBytes < 0) errors += "Tamaño negativo"
            }
            is IngestFinding -> {
                record.path?.let { checkPath(errors, it) }
                checkText(errors, "detail", record.detail, MAX_DETAIL_LENGTH)
            }
            is DuplicateGroup -> {
                if (!SHA256_PATTERN.matches(record.sha256)) errors += "Huella SHA-256 inválida"
                if (record.paths.size > MAX_DUPLICATE_PATHS || record.paths.size < 2 || record.count < record.paths.size) {
                    errors += "Grupo de duplicados inválido"
                }
                if (record.sizeBytes < 0) errors += "Tamaño negativo"
                record.paths.forEach { checkPath(errors, it) }
            }
            is FileProfile -> {
                checkPath(errors, record.path)
                if (record.roles.size > FileRole.entries.size || record.roles.map { it.role }.toSet().size != record.roles.size) {
                    errors += "Roles repetidos o excesivos"
                }
                record.roles.forEach { checkEvidence(errors, it.evidence) }
                if ((record.lineCount ?: 0) < 0 || record.redactions < 0) errors += "Valores negativos en la ficha"
                record.projectRoot?.let { checkDirectory(errors, it) }
                record.language?.let { language ->
                    checkPattern(errors, "language.id", language.id, ID_PATTERN)
                    checkName(errors, "language.name", language.name)
                    if (language.level !in 0..3) errors += "Nivel de soporte inválido"
                    if (listOf(language.code, language.comment, language.blank, language.syntaxErrors).any { it < 0 }) errors += "Recuento de líneas negativo"
                }
            }
            is ProjectRoot -> {
                checkDirectory(errors, record.directory)
                checkPath(errors, record.marker)
            }
            is EntryPoint -> {
                record.path?.let { checkPath(errors, it) }
                record.target?.let {
                    checkText(errors, "target", it, MAX_TARGET_LENGTH)
                    if (it.any { ch -> ch.isISOControl() }) errors += "Destino con caracteres de control"
                }
                if (record.evidence.isEmpty()) errors += "Punto de entrada sin evidencia"
                checkEvidence(errors, record.evidence)
                record.projectRoot?.let { checkDirectory(errors, it) }
            }
            is Redaction -> {
                checkPath(errors, record.path)
                if (record.line < 1 || record.length < 1) errors += "Posición o longitud inválida"
                checkMasked(errors, record.masked)
            }
            is ContentChunk -> {
                checkPath(errors, record.path)
                if (record.index !in 0 until MAX_CHUNKS_PER_FILE) errors += "Índice de fragmento fuera de rango"
                checkText(errors, "text", record.text, MAX_CHUNK_CHARS)
                checkHighlight(errors, record.highlight, record.text.length)
            }
            is LanguageStats -> {
                checkPattern(errors, "id", record.id, ID_PATTERN)
                checkName(errors, "name", record.name)
                if (record.level !in 0..3) errors += "Nivel de soporte inválido"
                val counts = listOf(record.files, record.syntaxFiles, record.lexicalFiles, record.filesWithErrors)
                if (counts.any { it < 0 } || listOf(record.code, record.comment, record.blank).any { it < 0 }) errors += "Totales negativos"
                if (record.syntaxFiles + record.lexicalFiles > record.files || record.filesWithErrors > record.files) errors += "Totales de lenguaje incoherentes"
            }
            is DeclaredDependency -> {
                checkPath(errors, record.manifest)
                checkName(errors, "name", record.name)
                record.version?.let { checkName(errors, "version", it, MAX_VERSION_LENGTH) }
                if ((record.line ?: 1) < 1) errors += "Línea inválida"
                record.projectRoot?.let { checkDirectory(errors, it) }
            }
            is Relation -> {
                checkPath(errors, record.source)
                record.target?.let { checkPath(errors, it) }
                record.external?.let { checkName(errors, "external", it) }
                if (record.confidence == RelationConfidence.BROKEN) {
                    if (record.target != null) errors += "Relación rota con destino"
                } else if ((record.target == null) == (record.external == null)) {
                    errors += "Relación sin destino o con dos destinos"
                }
                checkName(errors, "reference", record.reference, MAX_REFERENCE_LENGTH)
                if ((record.line ?: 1) < 1) errors += "Línea inválida"
                if (record.exchanged.size > MAX_EXCHANGED) errors += "Demasiados símbolos intercambiados"
                record.exchanged.forEach { checkName(errors, "exchanged", it) }
                record.exchangedNote?.let { checkText(errors, "exchangedNote", it, MAX_EVIDENCE_DETAIL) }
                if (record.evidence.isEmpty()) errors += "Relación sin evidencia"
                checkEvidence(errors, record.evidence)
                val impact = record.impact
                if (impact.undefinedSymbols.size > MAX_EXCHANGED) errors += "Demasiados símbolos en el impacto"
                impact.undefinedSymbols.forEach { checkName(errors, "undefinedSymbols", it) }
                if (listOf(impact.directDependents, impact.transitiveDependents, impact.entryPointCount).any { it < 0 }) errors += "Impacto negativo"
                if (impact.entryPoints.size > MAX_IMPACT_ENTRY_POINTS || impact.entryPoints.size > impact.entryPointCount) errors += "Puntos de entrada del impacto incoherentes"
                impact.entryPoints.forEach { checkPath(errors, it) }
                checkSamples(errors, "affected", impact.affected, impact.affectedCount)
            }
            is Endpoint -> {
                if (record.method !in HTTP_METHODS) errors += "Método HTTP desconocido"
                checkName(errors, "path", record.path, MAX_ROUTE_LENGTH)
                if (!record.path.startsWith("/")) errors += "La ruta no empieza por /"
                checkName(errors, "framework", record.framework)
                checkPath(errors, record.file)
                if (record.line < 1) errors += "Línea inválida"
                record.handler?.let { checkName(errors, "handler", it) }
                record.handlerFile?.let { checkPath(errors, it) }
                checkSamples(errors, "callers", record.callers, record.callerCount)
            }
            is DataEntity -> {
                checkName(errors, "name", record.name)
                record.file?.let { checkPath(errors, it) }
                if ((record.line ?: 1) < 1) errors += "Línea inválida"
                if ((record.file == null) != (record.line == null)) errors += "Definición de datos incompleta"
                record.definedBy?.let { checkName(errors, "definedBy", it) }
                if (record.accessedBy < 0) errors += "Recuento negativo"
                if (record.operations.size > MAX_OPERATIONS) errors += "Demasiadas operaciones"
                record.operations.forEach { checkName(errors, "operation", it) }
            }
            is ConfigKey -> {
                checkName(errors, "name", record.name)
                if (record.definedIn.size > MAX_SAMPLES) errors += "Demasiados archivos de definición"
                record.definedIn.forEach { checkPath(errors, it) }
                checkSamples(errors, "readers", record.readers, record.readCount)
            }
            is FileGraph -> {
                checkPath(errors, record.path)
                if (listOf(record.outgoing, record.incoming, record.externals, record.transitiveDependents).any { it < 0 } || record.rank < 1) errors += "Valores del grafo inválidos"
                if (record.importance.isNaN() || record.importance < 0.0 || record.importance > 1.0) errors += "Importancia fuera de rango"
                if ((record.cycle ?: 0) < 0) errors += "Ciclo inválido"
                if (record.symbols.size > MAX_SYMBOLS) errors += "Demasiados símbolos"
                record.symbols.forEach {
                    checkName(errors, "symbol", it.name)
                    if (it.line < 1) errors += "Línea de símbolo inválida"
                }
            }
            is DependencyCycle -> {
                if (record.index < 0 || record.size < 2 || record.paths.size > MAX_CYCLE_PATHS || record.paths.size > record.size || record.paths.isEmpty()) errors += "Ciclo inválido"
                record.paths.forEach { checkPath(errors, it) }
            }
            is RelationsSummary -> {
                val values = listOf(
                    record.files, record.relations, record.internal, record.external, record.broken, record.cycles, record.unreferencedFiles,
                    record.endpoints, record.pages, record.httpCalls, record.httpMatched, record.dataEntities, record.configKeys,
                )
                if (record.httpMatched > record.httpCalls) errors += "Llamadas HTTP incoherentes"
                if (values.any { it < 0 } || record.byKind.values.any { it < 0 } || record.byConfidence.values.any { it < 0 }) errors += "Totales negativos"
            }
            is Technology -> {
                checkPattern(errors, "id", record.id, ID_PATTERN)
                checkName(errors, "name", record.name)
                if (record.versions.size > MAX_TECH_VERSIONS) errors += "Demasiadas versiones"
                record.versions.forEach { checkName(errors, "version", it, MAX_VERSION_LENGTH) }
                if (record.evidence.isEmpty()) errors += "Tecnología sin evidencia"
                if (record.evidence.any { it.path == null }) errors += "Evidencia de tecnología sin archivo"
                checkEvidence(errors, record.evidence)
                if (record.evidenceCount < record.evidence.size) errors += "Recuento de evidencias incoherente"
                if (record.projectRoots.size > MAX_TECH_ROOTS) errors += "Demasiados subproyectos"
                record.projectRoots.forEach { checkDirectory(errors, it) }
            }
            is InventorySummary -> {
                val values = listOf(record.files, record.entryPoints, record.projectRoots, record.unclassified, record.storedFiles, record.redactions)
                if (values.any { it < 0 } || record.storedChars < 0 || record.roleCounts.values.any { it < 0 }) errors += "Totales negativos"
            }
            is IngestSummary -> {
                val values = listOf(
                    record.files, record.analyzedFiles, record.notAnalyzedFiles, record.excludedFiles,
                    record.excludedDirectories, record.duplicateGroups, record.duplicateFiles, record.findings,
                )
                if (values.any { it < 0 } || record.analyzedBytes < 0 || record.excludedBytes < 0) errors += "Totales negativos"
            }
        }
        return errors
    }

    private fun checkEvidence(errors: MutableList<String>, evidence: List<Evidence>) {
        if (evidence.size > MAX_EVIDENCE) errors += "Demasiadas evidencias"
        evidence.forEach { e ->
            checkText(errors, "evidence.detail", e.detail, MAX_EVIDENCE_DETAIL)
            if (e.detail.any { it.isISOControl() }) errors += "Evidencia con caracteres de control"
            e.path?.let { checkPath(errors, it) }
            if ((e.line ?: 1) < 1) errors += "Línea de evidencia inválida"
        }
    }

    /** Un secreto enmascarado solo puede mostrar 6 caracteres ASCII visibles; el resto, el carácter de máscara. */
    private fun checkMasked(errors: MutableList<String>, masked: String) {
        if (masked.isEmpty() || masked.length > MAX_MASKED_LENGTH) errors += "Secreto enmascarado con longitud inválida"
        val visible = masked.filter { it != MASK_CHAR }
        if (visible.length > MAX_VISIBLE_SECRET_CHARS || visible.any { it !in '!'..'~' }) {
            errors += "El secreto enmascarado muestra demasiado"
        }
    }

    /** Tríos (inicio, longitud, categoría) dentro del texto, en orden y sin solaparse. */
    private fun checkHighlight(errors: MutableList<String>, spans: List<Int>, textLength: Int) {
        if (spans.isEmpty()) return
        if (spans.size % 3 != 0 || spans.size > MAX_HIGHLIGHT_SPANS * 3) {
            errors += "Resaltado con formato inválido"
            return
        }
        var previousEnd = 0
        for (i in spans.indices step 3) {
            val start = spans[i]
            val length = spans[i + 1]
            val category = spans[i + 2]
            if (start < previousEnd || length < 1 || start.toLong() + length > textLength || category !in 1..MAX_HIGHLIGHT_CATEGORY) {
                errors += "Resaltado fuera del texto o desordenado"
                return
            }
            previousEnd = start + length
        }
    }

    /** Muestras "archivo:línea" (como máximo [MAX_SAMPLES]) y su total. */
    private fun checkSamples(errors: MutableList<String>, field: String, samples: List<String>, count: Int) {
        if (samples.size > MAX_SAMPLES || count < samples.size) errors += "Campo '$field' incoherente"
        samples.forEach { checkName(errors, field, it, MAX_PATH_LENGTH + 12) }
    }

    /** Nombre o versión corto, sin caracteres de control. */
    private fun checkName(errors: MutableList<String>, field: String, value: String, max: Int = MAX_NAME_LENGTH) {
        if (value.isEmpty() || value.length > max) errors += "Campo '$field' vacío o demasiado largo"
        if (value.any { it.isISOControl() }) errors += "Campo '$field' con caracteres de control"
    }

    /** Carpeta dentro del comprimido; "" es la raíz. */
    private fun checkDirectory(errors: MutableList<String>, directory: String) {
        if (directory.isNotEmpty()) checkPath(errors, directory)
    }

    private fun checkPattern(errors: MutableList<String>, field: String, value: String, pattern: Regex) {
        if (!pattern.matches(value)) errors += "Campo '$field' con formato inválido"
    }

    /** Las rutas llegan normalizadas y con los caracteres de control ya escapados por el motor. */
    private fun checkPath(errors: MutableList<String>, path: String) {
        if (path.isEmpty() || path.length > MAX_PATH_LENGTH) errors += "Ruta vacía o demasiado larga"
        if (path.any { it.isISOControl() }) errors += "Ruta con caracteres de control sin escapar"
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

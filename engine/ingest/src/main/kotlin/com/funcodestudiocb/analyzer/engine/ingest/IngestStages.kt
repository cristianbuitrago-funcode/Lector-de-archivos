package com.funcodestudiocb.analyzer.engine.ingest

import com.funcodestudiocb.analyzer.engine.api.ArchiveFormat
import com.funcodestudiocb.analyzer.engine.api.ArchiveRejected
import com.funcodestudiocb.analyzer.engine.api.ArchiveSummary
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.DuplicateGroup
import com.funcodestudiocb.analyzer.engine.api.EncryptionState
import com.funcodestudiocb.analyzer.engine.api.ExcludedDirectory
import com.funcodestudiocb.analyzer.engine.api.ExcludedFile
import com.funcodestudiocb.analyzer.engine.api.ExclusionReason
import com.funcodestudiocb.analyzer.engine.api.FileEntry
import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.IngestFinding
import com.funcodestudiocb.analyzer.engine.api.IngestFindingKind
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.IngestSummary
import com.funcodestudiocb.analyzer.engine.api.PasswordRequired
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.RejectionReason
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.core.AttributeKey
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.core.StageRegistry
import com.funcodestudiocb.analyzer.engine.core.StageStoppedException
import com.funcodestudiocb.analyzer.engine.ingest.rar.RarArchiveSession
import com.funcodestudiocb.analyzer.engine.ingest.zip.ZipArchiveSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** Contraseña del comprimido, solo en memoria. Su propietario la borra al terminar el análisis. */
class PasswordHolder(val chars: CharArray?) : AutoCloseable {
    override fun close() {
        chars?.fill('\u0000')
    }
}

/** Plan de la ingesta, calculado al abrir el comprimido y usado por las etapas siguientes. */
class IngestPlan(
    val listing: ArchiveListing,
    /** Índice de entrada -> ruta normalizada, de los archivos que se analizarán. */
    val included: Map<Int, String>,
)

/** Contadores compartidos entre etapas para el resumen final. */
class IngestCounters {
    var files = 0
    var analyzed = 0
    var notAnalyzed = 0
    var excludedFiles = 0
    var excludedDirectories = 0
    var analyzedBytes = 0L
    var excludedBytes = 0L
    var findings = 0
}

/** Huellas de los archivos leídos, para buscar duplicados. */
class HashIndex {
    val entries = mutableListOf<Triple<String, String, Long>>() // ruta, sha256, tamaño
}

object IngestKeys {
    val SOURCE = AttributeKey<ArchiveSource>("ingest.source")
    val PASSWORD = AttributeKey<PasswordHolder>("ingest.password")
    val SESSION = AttributeKey<ArchiveSession>("ingest.session")
    val PLAN = AttributeKey<IngestPlan>("ingest.plan")
    val COUNTERS = AttributeKey<IngestCounters>("ingest.counters")
    val HASHES = AttributeKey<HashIndex>("ingest.hashes")

    /** Todos los archivos del proyecto (no excluidos), tal como se emitieron. */
    val FILES = AttributeKey<MutableList<FileEntry>>("ingest.files")
}

/**
 * Un archivo recién leído. [path] es la ruta tal como aparece en los registros (ya escapada).
 * [content] es su contenido completo si se pidió conservarlo y no superaba el máximo; si no, `null`.
 */
class ReadFile(
    val path: String,
    val kind: FileKind,
    val encoding: String?,
    val sizeBytes: Long,
    val content: ByteArray?,
    /** El contenido no se conservó por superar el máximo. */
    val contentTooLarge: Boolean,
)

/**
 * Recibe cada archivo leído correctamente, en el mismo recorrido del comprimido (los RAR sólidos solo pueden
 * leerse una vez y en orden). Es el punto de extensión para analizar el contenido sin volver a descomprimir.
 */
fun interface FileContentConsumer {
    fun accept(context: StageContext, file: ReadFile)
}

/** Etapas de la ingesta (E1–E3 de la arquitectura). */
object IngestPipeline {
    fun stages(): List<Stage> = registry().plan()

    /** Registro con las etapas de la ingesta, para añadirle las siguientes. */
    fun registry(consumers: List<FileContentConsumer> = emptyList()): StageRegistry = StageRegistry()
        .register(OpenArchiveStage())
        .register(ReadEntriesStage(consumers))
        .register(DuplicatesStage())
}

private fun StageContext.finding(kind: IngestFindingKind, severity: Severity, path: String?, detail: String) {
    emit(IngestFinding(kind, severity, path, EntryNames.escapeForDisplay(detail).take(RecordValidator.MAX_DETAIL_LENGTH)))
    attributes[IngestKeys.COUNTERS]?.let { it.findings++ }
}

private fun StageContext.options(): IngestOptions =
    config.ingest ?: throw StageStoppedException("Faltan las opciones de ingesta")

/** E1: identifica, abre y comprueba el comprimido sin descomprimir datos; decide qué se analiza. */
class OpenArchiveStage : Stage {
    override val id = "ingest.open"
    override val title = "Apertura y comprobación del comprimido"

    override suspend fun run(context: StageContext) {
        val options = context.options()
        val limits = options.limits
        val source = context.attributes.require(IngestKeys.SOURCE)
        val password = context.attributes[IngestKeys.PASSWORD]?.chars
        context.attributes[IngestKeys.COUNTERS] = IngestCounters()

        val size = source.size
        if (size > limits.maxArchiveBytes) reject(context, RejectionReason.TOO_LARGE, "El comprimido ocupa $size bytes (máximo ${limits.maxArchiveBytes})")

        context.progress(0.05f, "Identificando el formato")
        val format = when (val detected = FormatDetector.detect(source.head(FormatDetector.HEAD_BYTES))) {
            is DetectedFormat.Supported -> detected.format
            is DetectedFormat.Unsupported -> reject(context, RejectionReason.UNSUPPORTED_FORMAT, "Formato no admitido: ${detected.description}. Solo ZIP y RAR.")
            DetectedFormat.Unknown -> reject(context, RejectionReason.UNSUPPORTED_FORMAT, "El archivo no es un ZIP ni un RAR (su contenido no corresponde a ninguno de los dos)")
        }

        context.progress(0.15f, "Leyendo el índice del comprimido")
        val session = try {
            when (format) {
                ArchiveFormat.ZIP -> ZipArchiveSession.open(source.channel, limits)
                ArchiveFormat.RAR4, ArchiveFormat.RAR5 ->
                    RarArchiveSession.open(source.rarName, format, limits, password, (context.config.limits.maxTotalMillis / 1000).toInt())
            }
        } catch (e: ArchiveRejectedException) {
            if (e.reason == RejectionReason.PASSWORD_REQUIRED || e.reason == RejectionReason.WRONG_PASSWORD) {
                context.emit(PasswordRequired(e.reason == RejectionReason.WRONG_PASSWORD, EntryNames.escapeForDisplay(e.message.orEmpty())))
            }
            reject(context, e.reason, e.message.orEmpty())
        }
        context.attributes.closeLater(session)
        context.attributes[IngestKeys.SESSION] = session
        val listing = session.listing

        context.progress(0.5f, "Comprobando nombres y estructura")
        val files = listing.entries.filter { !it.isDirectory }
        val declaredTotal = files.sumOf { it.declaredSize.coerceAtLeast(0) }
        if (declaredTotal > GLOBAL_RATIO_MIN_BYTES && declaredTotal / size.coerceAtLeast(1) > limits.maxGlobalRatio) {
            reject(context, RejectionReason.COMPRESSION_BOMB, "Proporción de compresión global anómala: ${declaredTotal / size.coerceAtLeast(1)}:1 (máximo ${limits.maxGlobalRatio}:1)")
        }

        listing.structuralWarnings.forEach {
            context.finding(IngestFindingKind.LOCAL_HEADER_MISMATCH, Severity.MEDIUM, it.path?.let(EntryNames::escapeForDisplay), it.detail)
        }

        val validFiles = LinkedHashMap<Int, String>()
        val validDirs = mutableListOf<String>()
        val seen = HashMap<String, Int>()
        val seenLower = HashMap<String, String>()
        for (entry in listing.entries) {
            val check = EntryNames.check(entry.rawName, limits.maxPathLength, limits.maxPathDepth)
            if (check is NameCheck.Dangerous) {
                context.finding(IngestFindingKind.DANGEROUS_PATH, Severity.HIGH, null, "Entrada con nombre peligroso (${check.problem.description}): ${check.display}. No se analiza.")
                continue
            }
            val path = (check as NameCheck.Valid).path
            if (check.deceptive) {
                context.finding(IngestFindingKind.DECEPTIVE_NAME, Severity.MEDIUM, EntryNames.escapeForDisplay(path), "El nombre contiene caracteres invisibles o de dirección bidireccional que pueden disfrazarlo")
            }
            if (entry.isDirectory) {
                validDirs += path
                continue
            }
            if (entry.symlink || entry.hardlink) {
                val target = entry.linkTarget?.let { " → " + EntryNames.escapeForDisplay(it) }.orEmpty()
                context.finding(
                    if (entry.symlink) IngestFindingKind.SYMLINK else IngestFindingKind.HARDLINK,
                    Severity.MEDIUM,
                    EntryNames.escapeForDisplay(path),
                    "Enlace${if (entry.symlink) " simbólico" else ""}$target. Se registra pero nunca se sigue.",
                )
                continue
            }
            if (seen.containsKey(path)) {
                context.finding(IngestFindingKind.DUPLICATE_PATH, Severity.MEDIUM, EntryNames.escapeForDisplay(path), "La misma ruta aparece varias veces en el comprimido; solo se analiza la primera")
                continue
            }
            seen[path] = entry.index
            seenLower[path.lowercase()]?.let { other ->
                context.finding(IngestFindingKind.CASE_COLLISION, Severity.LOW, EntryNames.escapeForDisplay(path), "Solo se diferencia en mayúsculas de '${EntryNames.escapeForDisplay(other)}': colisionan en sistemas que no distinguen mayúsculas")
            } ?: run { seenLower[path.lowercase()] = path }
            validFiles[entry.index] = path
        }

        context.progress(0.8f, "Aplicando exclusiones")
        val counters = context.attributes.require(IngestKeys.COUNTERS)
        val decisions = ExclusionPlanner(options.exclusions).plan(validFiles.values, validDirs)
        val excludedRoots = LinkedHashMap<String, Pair<ExclusionDecision, LongArray>>() // raíz -> (decisión, [archivos, bytes])
        val included = LinkedHashMap<Int, String>()
        for ((index, path) in validFiles) {
            val decision = decisions[path]
            if (decision == null) {
                included[index] = path
                continue
            }
            val declared = listing.entries[index].declaredSize.coerceAtLeast(0)
            counters.excludedFiles++
            counters.excludedBytes += declared
            if (decision.reason == ExclusionReason.TEMPORARY_FILE && decision.root == path) {
                context.emit(ExcludedFile(EntryNames.escapeForDisplay(path), decision.rule, declared))
            } else {
                val acc = excludedRoots.getOrPut(decision.root) { decision to LongArray(2) }.second
                acc[0]++
                acc[1] += declared
            }
        }
        // Las carpetas excluidas que solo contienen subcarpetas (o nada) también se registran.
        for (dir in validDirs) {
            val decision = decisions[dir] ?: continue
            excludedRoots.getOrPut(decision.root) { decision to LongArray(2) }
        }
        excludedRoots.forEach { (root, value) ->
            context.emit(ExcludedDirectory(EntryNames.escapeForDisplay(root), value.first.rule, value.first.reason, value.second[0].toInt(), value.second[1]))
        }
        counters.excludedDirectories = excludedRoots.size
        counters.files = included.size

        val encryptedFiles = files.count { it.encrypted }
        val encryption = when {
            listing.headersEncrypted -> EncryptionState.HEADERS
            encryptedFiles == 0 -> EncryptionState.NONE
            encryptedFiles == files.size -> EncryptionState.ALL_ENTRIES
            else -> EncryptionState.PARTIAL
        }
        context.emit(
            ArchiveSummary(
                format = format,
                sizeBytes = size,
                entryCount = listing.entries.size,
                directoryCount = listing.entries.count { it.isDirectory },
                declaredUncompressedBytes = declaredTotal,
                solid = listing.solid,
                encryption = encryption,
            ),
        )
        context.attributes[IngestKeys.PLAN] = IngestPlan(listing, included)
        context.attributes[IngestKeys.HASHES] = HashIndex()
    }

    private fun reject(context: StageContext, reason: RejectionReason, detail: String): Nothing {
        val safe = EntryNames.escapeForDisplay(detail).take(RecordValidator.MAX_DETAIL_LENGTH)
        context.emit(ArchiveRejected(reason, safe))
        throw StageStoppedException("Comprimido rechazado: $safe")
    }

    private companion object {
        /** Por debajo de este tamaño declarado no se aplica la proporción global (archivos de texto muy repetitivos). */
        const val GLOBAL_RATIO_MIN_BYTES = 10L * 1024 * 1024
    }
}

/**
 * E2–E3: lee cada archivo incluido en memoria, calcula su huella, tipo y codificación y aplica los límites.
 * Si hay [consumers], les entrega cada archivo leído con su contenido (hasta `inventory.maxContentBytes`).
 */
class ReadEntriesStage(
    private val consumers: List<FileContentConsumer> = emptyList(),
) : Stage {
    override val id = "ingest.read"
    override val title = "Lectura de los archivos"
    override val dependsOn = setOf("ingest.open")

    override suspend fun run(context: StageContext) {
        val options = context.options()
        val limits = options.limits
        val plan = context.attributes.require(IngestKeys.PLAN)
        val session = context.attributes.require(IngestKeys.SESSION)
        val counters = context.attributes.require(IngestKeys.COUNTERS)
        val hashes = context.attributes.require(IngestKeys.HASHES)
        val password = context.attributes[IngestKeys.PASSWORD]?.chars
        val job = currentCoroutineContext()[Job]
        val budget = ByteBudget(limits.maxTotalUncompressedBytes)
        val keepContentUpTo = if (consumers.isEmpty()) 0 else context.config.inventory.maxContentBytes
        val inspectors = HashMap<Int, ContentInspector>()
        var processed = 0
        var encryptedUnread = 0
        var wrongPassword = 0
        var totalLimitReported = false
        val total = plan.included.size.coerceAtLeast(1)
        val files = mutableListOf<FileEntry>().also { context.attributes[IngestKeys.FILES] = it }

        fun emitFile(entry: ArchiveEntry, path: String, status: FileStatus, size: Long = entry.declaredSize.coerceAtLeast(0), sha: String? = null, kind: FileKind = FileKind.UNKNOWN, encoding: String? = null) {
            val record = FileEntry(EntryNames.escapeForDisplay(path), size, entry.compressedSize, sha, kind, encoding, status, entry.modifiedEpochMs)
            context.emit(record)
            files += record
            if (status == FileStatus.ANALYZED) counters.analyzed++ else counters.notAnalyzed++
            processed++
            if (processed % 25 == 0) context.progress(processed.toFloat() / total, "Archivo $processed de ${plan.included.size}")
        }

        fun reportTotalLimit() {
            if (!totalLimitReported) {
                totalLimitReported = true
                context.finding(IngestFindingKind.TOTAL_LIMIT_REACHED, Severity.MEDIUM, null, "Se alcanzó el máximo de ${limits.maxTotalUncompressedBytes} bytes descomprimidos: el resto de archivos no se leyó")
                context.coverage(CoverageReason.LIMIT_REACHED, "Límite total de datos descomprimidos alcanzado; archivos restantes sin leer")
            }
        }

        session.readEntries(
            password,
            visitor = EntryVisitor { entry ->
                val path = plan.included[entry.index] ?: return@EntryVisitor null
                when {
                    job?.isActive == false -> null
                    budget.exhausted -> {
                        reportTotalLimit()
                        emitFile(entry, path, FileStatus.SKIPPED_LIMIT)
                        null
                    }
                    entry.declaredSize > limits.maxEntryBytes -> {
                        context.finding(IngestFindingKind.ENTRY_TOO_LARGE, Severity.INFO, EntryNames.escapeForDisplay(path), "Declara ${entry.declaredSize} bytes (máximo por archivo ${limits.maxEntryBytes}): no se lee")
                        emitFile(entry, path, FileStatus.SKIPPED_TOO_LARGE)
                        null
                    }
                    entry.dictionaryKb / 1024 > limits.maxRarMemoryMb -> {
                        context.finding(IngestFindingKind.LARGE_DICTIONARY, Severity.MEDIUM, EntryNames.escapeForDisplay(path), "Necesita ${entry.dictionaryKb / 1024} MiB de memoria para descomprimirse (máximo ${limits.maxRarMemoryMb})")
                        emitFile(entry, path, FileStatus.SKIPPED_LIMIT)
                        null
                    }
                    entry.encrypted && password == null -> {
                        encryptedUnread++
                        emitFile(entry, path, FileStatus.ENCRYPTED)
                        null
                    }
                    else -> ContentInspector(
                        compressedSize = entry.compressedSize,
                        maxEntryBytes = limits.maxEntryBytes,
                        ratioThresholdBytes = limits.entryRatioThresholdBytes,
                        maxRatio = limits.maxEntryRatio,
                        budget = budget,
                        isCancelled = { job?.isActive == false },
                        keepContentUpTo = keepContentUpTo,
                    ).also { inspectors[entry.index] = it }
                }
            },
        ) { entry, outcome ->
            val path = plan.included[entry.index] ?: return@readEntries
            val inspector = inspectors.remove(entry.index) ?: return@readEntries
            val escaped = EntryNames.escapeForDisplay(path)
            when (outcome) {
                ReadOutcome.Completed -> {
                    val (kind, encoding) = inspector.classify()
                    val sha = inspector.sha256()
                    if (entry.declaredSize >= 0 && entry.declaredSize != inspector.bytes) {
                        context.finding(IngestFindingKind.SIZE_MISMATCH, Severity.MEDIUM, escaped, "Tamaño declarado ${entry.declaredSize} bytes, real ${inspector.bytes} bytes")
                    }
                    if (kind == FileKind.ARCHIVE) {
                        context.finding(IngestFindingKind.NESTED_ARCHIVE, Severity.LOW, escaped, "Comprimido dentro del proyecto: se registra pero no se expande")
                    }
                    counters.analyzedBytes += inspector.bytes
                    if (inspector.bytes > 0) hashes.entries += Triple(escaped, sha, inspector.bytes)
                    emitFile(entry, path, FileStatus.ANALYZED, inspector.bytes, sha, kind, encoding)
                    if (consumers.isNotEmpty()) {
                        val file = ReadFile(escaped, kind, encoding, inspector.bytes, inspector.content(), inspector.contentTooLarge)
                        consumers.forEach { consumer ->
                            // Un fallo al examinar un archivo no detiene la lectura del resto; nunca se envía el mensaje
                            // de la excepción (podría contener texto del proyecto).
                            try {
                                consumer.accept(context, file)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                context.coverage(CoverageReason.NOT_ANALYZED, "No se pudo examinar su contenido (error interno: ${e.javaClass.simpleName})", escaped)
                            } catch (e: StackOverflowError) {
                                context.coverage(CoverageReason.NOT_ANALYZED, "No se pudo examinar su contenido (estructura demasiado profunda)", escaped)
                            }
                        }
                    }
                }
                ReadOutcome.StoppedByReceiver -> when (inspector.stopReason) {
                    StopReason.COMPRESSION_BOMB -> {
                        context.finding(IngestFindingKind.COMPRESSION_BOMB, Severity.HIGH, escaped, "Proporción de compresión anómala (más de ${limits.maxEntryRatio}:1): posible bomba de compresión. Lectura detenida.")
                        emitFile(entry, path, FileStatus.SKIPPED_BOMB)
                    }
                    StopReason.ENTRY_TOO_LARGE -> {
                        context.finding(IngestFindingKind.SIZE_MISMATCH, Severity.HIGH, escaped, "Declaraba ${entry.declaredSize} bytes pero contiene más de ${limits.maxEntryBytes}: lectura detenida")
                        emitFile(entry, path, FileStatus.SKIPPED_TOO_LARGE)
                    }
                    StopReason.TOTAL_LIMIT -> {
                        reportTotalLimit()
                        emitFile(entry, path, FileStatus.SKIPPED_LIMIT)
                    }
                    StopReason.CANCELLED, null -> emitFile(entry, path, FileStatus.SKIPPED_LIMIT)
                }
                ReadOutcome.MissingPassword -> {
                    encryptedUnread++
                    emitFile(entry, path, FileStatus.ENCRYPTED)
                }
                ReadOutcome.WrongPassword -> {
                    encryptedUnread++
                    wrongPassword++
                    emitFile(entry, path, FileStatus.ENCRYPTED)
                }
                is ReadOutcome.Corrupt -> {
                    context.finding(IngestFindingKind.CORRUPT_ENTRY, Severity.MEDIUM, escaped, "Datos dañados: ${outcome.detail}")
                    emitFile(entry, path, FileStatus.CORRUPT)
                }
                is ReadOutcome.Unsupported -> {
                    context.finding(IngestFindingKind.UNSUPPORTED_METHOD, Severity.LOW, escaped, outcome.detail)
                    emitFile(entry, path, FileStatus.UNSUPPORTED_METHOD)
                }
            }
        }
        context.checkActive()

        if (encryptedUnread > 0) {
            val detail = if (wrongPassword > 0) {
                "$encryptedUnread archivos cifrados no se pudieron leer: la contraseña no es correcta"
            } else {
                "$encryptedUnread archivos cifrados no se pudieron leer sin contraseña"
            }
            context.finding(IngestFindingKind.ENCRYPTED_ENTRY, Severity.INFO, null, detail)
            context.emit(PasswordRequired(wrongPassword > 0, detail))
            context.coverage(CoverageReason.NOT_ANALYZED, detail)
        }
        val notAnalyzed = counters.notAnalyzed
        if (notAnalyzed > 0) {
            context.coverage(CoverageReason.NOT_ANALYZED, "$notAnalyzed archivos no se pudieron analizar (ver su estado en el inventario)")
        }
    }
}

/** Agrupa los archivos con contenido idéntico y emite el resumen de la ingesta. */
class DuplicatesStage : Stage {
    override val id = "ingest.duplicates"
    override val title = "Búsqueda de archivos duplicados"
    override val dependsOn = setOf("ingest.read")

    override suspend fun run(context: StageContext) {
        val hashes = context.attributes.require(IngestKeys.HASHES)
        val counters = context.attributes.require(IngestKeys.COUNTERS)
        val groups = hashes.entries.groupBy { it.second }.values.filter { it.size > 1 }
        groups.sortedByDescending { it.first().third * (it.size - 1) }.forEach { group ->
            context.emit(
                DuplicateGroup(
                    sha256 = group.first().second,
                    sizeBytes = group.first().third,
                    paths = group.take(RecordValidator.MAX_DUPLICATE_PATHS).map { it.first },
                    count = group.size,
                ),
            )
        }
        context.emit(
            IngestSummary(
                files = counters.files,
                analyzedFiles = counters.analyzed,
                notAnalyzedFiles = counters.notAnalyzed,
                excludedFiles = counters.excludedFiles,
                excludedDirectories = counters.excludedDirectories,
                analyzedBytes = counters.analyzedBytes,
                excludedBytes = counters.excludedBytes,
                duplicateGroups = groups.size,
                duplicateFiles = groups.sumOf { it.size },
                findings = counters.findings,
            ),
        )
    }
}

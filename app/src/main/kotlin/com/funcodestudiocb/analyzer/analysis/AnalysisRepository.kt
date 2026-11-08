package com.funcodestudiocb.analyzer.analysis

import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.data.db.AnalysisRecordEntity
import com.funcodestudiocb.analyzer.data.db.FileContentEntity
import com.funcodestudiocb.analyzer.data.db.HighlightCodec
import com.funcodestudiocb.analyzer.data.db.SettingEntity
import com.funcodestudiocb.analyzer.dashboard.AnalysisDigest
import com.funcodestudiocb.analyzer.data.session.SessionManager
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.DependencySummary
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.LanguageStats
import com.funcodestudiocb.analyzer.engine.api.MaintenanceIndex
import com.funcodestudiocb.analyzer.engine.api.ProtocolJson
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SecuritySummary
import com.funcodestudiocb.analyzer.engine.api.SessionEnd
import com.funcodestudiocb.analyzer.engine.api.SessionOutcome
import com.funcodestudiocb.analyzer.engine.api.Technology
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.funcodestudiocb.analyzer.engine.report.AnalysisOutcome
import com.funcodestudiocb.analyzer.engine.report.AnalysisReport

/** Un análisis listo para guardarse en el historial cifrado. */
data class AnalysisToSave(
    val id: String,
    val archiveName: String,
    val archiveSize: Long,
    val archiveSha256: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val records: List<ResultRecord>,
    val report: AnalysisReport,
    /** Copia redactada del código (se vuelca a la BD al guardar). */
    val spool: ContentSpool? = null,
)

/** Persistencia de los análisis y de los ajustes de análisis en la base de datos cifrada. */
class AnalysisRepository(private val session: SessionManager) {

    private val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun loadExclusions(): ExclusionOptions {
        val stored = session.databaseOrNull()?.settings()?.get(KEY_EXCLUSIONS) ?: return ExclusionOptions()
        return runCatching { settingsJson.decodeFromString(ExclusionOptions.serializer(), stored) }.getOrDefault(ExclusionOptions())
    }

    suspend fun saveExclusions(options: ExclusionOptions) {
        session.databaseOrNull()?.settings()?.put(SettingEntity(KEY_EXCLUSIONS, settingsJson.encodeToString(ExclusionOptions.serializer(), options)))
    }

    /** Guardar una copia del código con los secretos ocultados (decisión D4). Activado por defecto. */
    /**
     * Clave local de las huellas de los secretos (HMAC-SHA256), en Base64. Se crea una vez con el generador
     * criptográfico del sistema y se guarda solo en la base de datos cifrada: así el mismo secreto tiene la misma
     * huella en todos los análisis de este teléfono y ninguna huella sirve fuera de él. `null` si la bóveda está cerrada.
     */
    suspend fun fingerprintKey(): String? {
        val settings = session.databaseOrNull()?.settings() ?: return null
        settings.get(KEY_FINGERPRINT)?.let { return it }
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val key = java.util.Base64.getEncoder().encodeToString(bytes)
        bytes.fill(0)
        settings.put(SettingEntity(KEY_FINGERPRINT, key))
        return key
    }

    suspend fun loadKeepCodeCopy(): Boolean = session.databaseOrNull()?.settings()?.get(KEY_KEEP_COPY)?.toBooleanStrictOrNull() ?: true

    suspend fun saveKeepCodeCopy(keep: Boolean) {
        session.databaseOrNull()?.settings()?.put(SettingEntity(KEY_KEEP_COPY, keep.toString()))
    }

    /** Guarda el análisis. Devuelve `false` si la sesión está bloqueada (se reintentará al desbloquear). */
    suspend fun save(analysis: AnalysisToSave): Boolean {
        val db = session.databaseOrNull() ?: return false
        val report = analysis.report
        val summary = report.summary
        val entity = AnalysisEntity(
            id = analysis.id,
            archiveName = analysis.archiveName,
            archiveSize = analysis.archiveSize,
            archiveSha256 = analysis.archiveSha256,
            format = report.archive?.format?.name,
            status = report.outcome().name,
            startedAtEpochMs = analysis.startedAtEpochMs,
            finishedAtEpochMs = analysis.finishedAtEpochMs,
            fileCount = summary?.files ?: report.files.size,
            analyzedCount = summary?.analyzedFiles ?: report.files.count { it.status == FileStatus.ANALYZED },
            excludedCount = summary?.excludedFiles ?: report.excludedFiles.size,
            findingCount = report.findings.size,
            duplicateGroups = report.duplicates.size,
            message = report.engineProblem ?: report.rejection?.detail,
        )
        // Se convierten a JSON por lotes mientras se guardan, no todos a la vez (ver AnalysisDao.insert).
        fun entities(records: List<ResultRecord>) = records.asSequence().mapIndexed { i, r ->
            val json = ProtocolJson.encodeToString(ResultRecord.serializer(), r)
            AnalysisRecordEntity(analysis.id, i, typeOf(json), json)
        }
        val contents = analysis.spool?.read()?.map { FileContentEntity(analysis.id, it.path, it.index, it.text, HighlightCodec.encode(it.highlight)) } ?: emptySequence()
        try {
            db.analyses().insert(entity, entities(analysis.records), contents)
        } catch (e: SpoolUnreadableException) {
            // La copia temporal no supera la verificación: se guarda el análisis sin ella y se dice por qué.
            val note = CoverageNote("app", CoverageReason.NOT_ANALYZED, "La copia del código no se pudo recuperar y no se guardó")
            db.analyses().insert(entity, entities(analysis.records + note), emptySequence())
        }
        return true
    }

    /** Copia redactada de un archivo de un análisis guardado, o `null` si no la hay. */
    suspend fun content(analysisId: String, path: String): String? = code(analysisId, path)?.text

    /**
     * Copia redactada con su resaltado de sintaxis (posiciones sobre el texto completo), o `null` si no la hay.
     * Un resaltado incoherente con su fragmento se descarta: el texto se muestra igualmente, sin colores.
     */
    suspend fun code(analysisId: String, path: String): CodeCopy? {
        val rows = session.databaseOrNull()?.analyses()?.contentRows(analysisId, path) ?: return null
        if (rows.isEmpty()) return null
        val text = StringBuilder()
        val spans = ArrayList<Int>()
        for (row in rows) {
            val offset = text.length
            val decoded = HighlightCodec.decode(row.highlight)
            var valid = true
            var previousEnd = 0
            for (i in decoded.indices step 3) {
                val start = decoded[i]
                val end = start + decoded[i + 1]
                if (start < previousEnd || end > row.text.length || decoded[i + 1] <= 0) valid = false
                previousEnd = end
            }
            if (valid) for (i in decoded.indices step 3) {
                spans += decoded[i] + offset
                spans += decoded[i + 1]
                spans += decoded[i + 2]
            }
            text.append(row.text)
        }
        return CodeCopy(text.toString(), spans.toIntArray())
    }

    fun recent(limit: Int = 20): Flow<List<AnalysisEntity>> =
        session.databaseOrNull()?.analyses()?.recent(limit) ?: flowOf(emptyList())

    fun count(): Flow<Int> = session.databaseOrNull()?.analyses()?.count() ?: flowOf(0)

    /**
     * Datos del Panel: los últimos [limit] análisis con sus registros de resumen (seguridad, dependencias, tecnologías,
     * lenguajes e índice). Solo se leen esos tipos de registro, no el análisis entero. Se actualiza al guardar o borrar.
     */
    fun digests(limit: Int = DASHBOARD_LIMIT): Flow<List<AnalysisDigest>> {
        val db = session.databaseOrNull() ?: return flowOf(emptyList())
        return db.analyses().recent(limit).map { entities ->
            val records = entities.map { it.id }.chunked(MAX_IDS_PER_QUERY)
                .flatMap { ids -> db.analyses().recordsOfTypes(ids, DIGEST_TYPES) }
                .groupBy({ it.analysisId }) { runCatching { ProtocolJson.decodeFromString(ResultRecord.serializer(), it.json) }.getOrNull() }
            entities.map { e ->
                val own = records[e.id].orEmpty().filterNotNull()
                AnalysisDigest(
                    id = e.id,
                    archiveName = e.archiveName,
                    startedAtEpochMs = e.startedAtEpochMs,
                    outcome = runCatching { AnalysisOutcome.valueOf(e.status) }.getOrNull(),
                    fileCount = e.fileCount,
                    findingCount = e.findingCount,
                    security = own.filterIsInstance<SecuritySummary>().lastOrNull(),
                    dependencies = own.filterIsInstance<DependencySummary>().lastOrNull(),
                    technologies = own.filterIsInstance<Technology>(),
                    languages = own.filterIsInstance<LanguageStats>(),
                    maintenance = own.filterIsInstance<MaintenanceIndex>().lastOrNull(),
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    /** Carga un análisis guardado y reconstruye su informe. */
    suspend fun load(id: String): Pair<AnalysisEntity, AnalysisReport>? = loadWithRecords(id)?.let { it.entity to it.report }

    /** Análisis guardado con su informe y los registros de los que sale (para exportar el JSON completo). */
    class StoredAnalysis(val entity: AnalysisEntity, val report: AnalysisReport, val records: List<ResultRecord>)

    suspend fun loadWithRecords(id: String): StoredAnalysis? {
        val db = session.databaseOrNull() ?: return null
        val entity = db.analyses().get(id) ?: return null
        val records = db.analyses().records(id).mapNotNull {
            runCatching { ProtocolJson.decodeFromString(ResultRecord.serializer(), it.json) }.getOrNull()
        }
        // El resultado de la sesión se guarda en el estado del análisis, no como registro: se recupera de ahí para que el
        // informe reconstruido diga lo mismo que el historial (si no, un análisis completo parecería no terminado).
        val sessionOutcome = records.filterIsInstance<SessionEnd>().lastOrNull()?.outcome ?: when (entity.status) {
            AnalysisOutcome.COMPLETED.name -> SessionOutcome.COMPLETED
            AnalysisOutcome.PARTIAL.name -> SessionOutcome.PARTIAL
            else -> null
        }
        val problem = if (entity.status == AnalysisOutcome.FAILED.name) entity.message else null
        return StoredAnalysis(entity, AnalysisReport.from(records, sessionOutcome, problem), records)
    }

    suspend fun delete(id: String) {
        session.databaseOrNull()?.analyses()?.delete(id)
    }

    private fun typeOf(json: String): String =
        runCatching { ProtocolJson.parseToJsonElement(json).jsonObject["t"]?.jsonPrimitive?.content }.getOrNull() ?: "?"

    private companion object {
        const val KEY_EXCLUSIONS = "analysis.exclusions"
        const val KEY_KEEP_COPY = "analysis.keepCodeCopy"
        const val KEY_FINGERPRINT = "security.fingerprintKey"
        const val DASHBOARD_LIMIT = 300
        const val MAX_IDS_PER_QUERY = 400 // SQLite admite como mucho 999 parámetros por consulta
        val DIGEST_TYPES = listOf("security_summary", "dependency_summary", "technology", "language_stats", "maintenance_index")
    }
}

/** Copia redactada de un archivo y su resaltado: tríos (inicio, longitud, categoría) en caracteres del texto. */
class CodeCopy(val text: String, val highlight: IntArray)

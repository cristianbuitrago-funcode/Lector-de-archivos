package com.funcodestudiocb.analyzer.analysis

import com.funcodestudiocb.analyzer.data.db.AnalysisEntity
import com.funcodestudiocb.analyzer.data.db.AnalysisRecordEntity
import com.funcodestudiocb.analyzer.data.db.SettingEntity
import com.funcodestudiocb.analyzer.data.session.SessionManager
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.ProtocolJson
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionEnd
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val records = analysis.records.mapIndexed { i, r ->
            val json = ProtocolJson.encodeToString(ResultRecord.serializer(), r)
            AnalysisRecordEntity(analysis.id, i, typeOf(json), json)
        }
        db.analyses().insert(entity, records)
        return true
    }

    fun recent(limit: Int = 20): Flow<List<AnalysisEntity>> =
        session.databaseOrNull()?.analyses()?.recent(limit) ?: flowOf(emptyList())

    fun count(): Flow<Int> = session.databaseOrNull()?.analyses()?.count() ?: flowOf(0)

    /** Carga un análisis guardado y reconstruye su informe. */
    suspend fun load(id: String): Pair<AnalysisEntity, AnalysisReport>? {
        val db = session.databaseOrNull() ?: return null
        val entity = db.analyses().get(id) ?: return null
        val records = db.analyses().records(id).mapNotNull {
            runCatching { ProtocolJson.decodeFromString(ResultRecord.serializer(), it.json) }.getOrNull()
        }
        val end = records.filterIsInstance<SessionEnd>().lastOrNull()
        val problem = if (entity.status == AnalysisOutcome.FAILED.name) entity.message else null
        return entity to AnalysisReport.from(records, end?.outcome, problem)
    }

    suspend fun delete(id: String) {
        session.databaseOrNull()?.analyses()?.delete(id)
    }

    private fun typeOf(json: String): String =
        runCatching { ProtocolJson.parseToJsonElement(json).jsonObject["t"]?.jsonPrimitive?.content }.getOrNull() ?: "?"

    private companion object {
        const val KEY_EXCLUSIONS = "analysis.exclusions"
    }
}

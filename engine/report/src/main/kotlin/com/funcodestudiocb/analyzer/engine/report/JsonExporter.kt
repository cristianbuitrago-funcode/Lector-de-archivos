package com.funcodestudiocb.analyzer.engine.report

import com.funcodestudiocb.analyzer.engine.api.ContentChunk
import com.funcodestudiocb.analyzer.engine.api.ProtocolJson
import com.funcodestudiocb.analyzer.engine.api.RESULT_SCHEMA_VERSION
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionEnd
import com.funcodestudiocb.analyzer.engine.api.SessionStart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * Informe en JSON: los datos completos del análisis para otras herramientas. `schema_version` identifica el formato
 * del informe y `result_schema` el de los registros del motor. Los registros son los mismos que validó el proceso
 * principal, agrupados por tipo, con los secretos ya enmascarados (sin la copia del código); las rutas, reglas y huellas
 * sirven de identificadores estables entre análisis.
 */
object JsonExporter {

    const val SCHEMA = "funcode-report/1"

    private val PRETTY = Json(ProtocolJson) { prettyPrint = true }

    fun export(doc: ReportDocument, records: List<ResultRecord>): String {
        val meta = doc.meta
        val root = buildJsonObject {
            put("schema_version", SCHEMA)
            put("result_schema", RESULT_SCHEMA_VERSION)
            putJsonObject("generator") {
                put("name", "Funcode Code Analyzer")
                put("vendor", "Funcode Studio CB")
                put("app_version", meta.appVersion)
                meta.engineVersion?.let { put("engine_version", it) }
            }
            putJsonObject("metadata") {
                put("analysis_id", meta.analysisId)
                put("archive_name", meta.archiveName)
                put("archive_size", meta.archiveSize)
                meta.archiveSha256?.let { put("archive_sha256", it) }
                meta.analyzedAtEpochMs?.let { put("analyzed_at", Instant.ofEpochMilli(it).toString()) }
                put("generated_at", Instant.ofEpochMilli(meta.generatedAtEpochMs).toString())
            }
            putJsonArray("sections") {
                doc.sections.forEach { s ->
                    add(buildJsonObject { put("id", s.id); put("number", s.number); put("title", s.title) })
                }
            }
            put("executive_summary", statements(doc, "s1"))
            put("recommendations", statements(doc, "s13"))
            put("conclusions", statements(doc, "s15"))
            putJsonObject("data") {
                records.asSequence()
                    .filterNot { it is ContentChunk || it is SessionStart || it is SessionEnd }
                    .map { ProtocolJson.encodeToJsonElement(ResultRecord.serializer(), it).jsonObject }
                    .groupBy { (it["t"] as? JsonPrimitive)?.content ?: "otros" }
                    .toSortedMap()
                    .forEach { (type, list) -> put(type, JsonArray(list.map(::strip))) }
            }
        }
        return PRETTY.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    private fun statements(doc: ReportDocument, id: String) = buildJsonArray {
        doc.sections.firstOrNull { it.id == id }?.blocks?.filterIsInstance<Block.Statements>()?.flatMap { it.items }?.forEach { st ->
            add(buildJsonObject { put("text", st.text); put("source", st.ref) })
        }
    }

    /** El discriminador sobra dentro de su grupo. */
    private fun strip(obj: JsonObject) = JsonObject(obj.filterKeys { it != "t" })
}

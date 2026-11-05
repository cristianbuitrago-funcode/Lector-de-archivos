package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.FrameReader
import com.funcodestudiocb.analyzer.engine.api.FrameWriter
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.ProtocolJson
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SecurityFinding
import com.funcodestudiocb.analyzer.engine.api.SecurityOptions
import com.funcodestudiocb.analyzer.engine.api.SecuritySummary
import com.funcodestudiocb.analyzer.engine.api.SessionReader
import com.funcodestudiocb.analyzer.engine.api.StageEvent
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import com.funcodestudiocb.analyzer.engine.core.Attributes
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyCollector
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyStage
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveSource
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.PasswordHolder
import com.funcodestudiocb.analyzer.engine.inventory.ContentCollector
import com.funcodestudiocb.analyzer.engine.inventory.InventoryPipeline
import com.funcodestudiocb.analyzer.engine.relations.RelationCollector
import com.funcodestudiocb.analyzer.engine.relations.RelationStage
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Resultado de auditar un proyecto de prueba. */
internal class Audit(val records: List<ResultRecord>) {
    val findings = records.filterIsInstance<SecurityFinding>()
    val summary: SecuritySummary = records.filterIsInstance<SecuritySummary>().singleOrNull() ?: fail("Sin resumen de seguridad")

    /** Todo el flujo serializado como lo recibe el proceso principal (para buscar secretos completos). */
    val json: String = records.joinToString("\n") { ProtocolJson.encodeToString(ResultRecord.serializer(), it) }

    fun at(path: String, line: Int? = null) = findings.filter { it.path == path && (line == null || it.line == line) }

    fun find(rule: String, path: String, line: Int? = null): SecurityFinding =
        findings.firstOrNull { it.ruleId == rule && it.path == path && (line == null || it.line == line) }
            ?: fail("No hay $rule en $path:$line. Hallazgos del archivo:\n" + describe(at(path)))

    fun none(rule: String, path: String? = null) {
        val hits = findings.filter { it.ruleId == rule && (path == null || it.path == path) }
        assertTrue(hits.isEmpty(), "No debería haber $rule en $path:\n" + describe(hits))
    }

    fun describe(list: List<SecurityFinding> = findings) =
        list.joinToString("\n") { "  ${it.ruleId} ${it.path}:${it.line} ${it.severity} ${it.certainty} ${it.explanation.take(90)} | ${it.downgraded ?: ""}" }
}

/** Categorías cuya certeza "probable" sale de seguir el dato: tienen que mostrar el recorrido. */
private val FLOW_CATEGORIES = setOf(
    com.funcodestudiocb.analyzer.engine.api.SecurityCategory.SQL_INJECTION, com.funcodestudiocb.analyzer.engine.api.SecurityCategory.COMMAND_EXECUTION,
    com.funcodestudiocb.analyzer.engine.api.SecurityCategory.PATH_TRAVERSAL, com.funcodestudiocb.analyzer.engine.api.SecurityCategory.XSS,
    com.funcodestudiocb.analyzer.engine.api.SecurityCategory.INSUFFICIENT_VALIDATION, com.funcodestudiocb.analyzer.engine.api.SecurityCategory.WEAK_AUTHENTICATION,
)

/** Clave de huellas fija para las pruebas (32 bytes en Base64). */
internal const val TEST_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

/**
 * Audita un proyecto de prueba (un ZIP en memoria) con el pipeline real (ingesta, inventario, dependencias,
 * relaciones y seguridad), valida cada registro como lo hace el proceso principal y devuelve el resultado.
 */
internal fun audit(dir: File, entries: Map<String, String>, binary: Map<String, ByteArray> = emptyMap(), key: String? = TEST_KEY): Audit {
    val file = File.createTempFile("proyecto", ".zip", dir)
    ZipOutputStream(file.outputStream()).use { out ->
        entries.forEach { (name, text) ->
            out.putNextEntry(ZipEntry(name))
            out.write(text.trimIndent().toByteArray())
            out.closeEntry()
        }
        binary.forEach { (name, bytes) ->
            out.putNextEntry(ZipEntry(name))
            out.write(bytes)
            out.closeEntry()
        }
    }
    val dependencies = DependencyCollector()
    val relations = RelationCollector()
    val audit = AuditCollector()
    val stages = InventoryPipeline.registry(ContentCollector(listOf(dependencies, relations, audit)))
        .register(DependencyStage(dependencies))
        .register(RelationStage(relations))
        .register(SecurityStage(audit))
        .plan()
    FileInputStream(file).use { stream ->
        val attributes = Attributes()
        attributes[IngestKeys.SOURCE] = ArchiveSource(stream.channel, file.absolutePath)
        attributes[IngestKeys.PASSWORD] = attributes.closeLater(PasswordHolder(null))
        val bytes = ByteArrayOutputStream()
        val writer = FrameWriter(bytes)
        val config = AnalysisConfig("prueba", AnalysisMode.FULL, ingest = IngestOptions(file.name), security = SecurityOptions(key))
        runBlocking { PipelineRunner("test").run(config, stages, writer::write, attributes) }
        writer.close()
        val session = SessionReader(FrameReader(ByteArrayInputStream(bytes.toByteArray())), maxBufferedRecords = Int.MAX_VALUE).readAll()
        if (session.violation != null) fail("Flujo inválido: ${session.violation}")
        session.records.forEach { assertTrue(RecordValidator.validate(it).isEmpty(), "Registro inválido: $it ${RecordValidator.validate(it)}") }
        val failed = session.records.filterIsInstance<StageEvent>().filter { it.status != StageStatus.COMPLETED }
        assertTrue(failed.isEmpty(), "Etapas sin completar: $failed")
        return Audit(session.records)
    }
}

/** Criterio de aceptación: cada ficha está completa y es coherente con su certeza. */
internal fun assertCompleteCards(a: Audit) {
    for (f in a.findings) {
        assertTrue(f.name.isNotBlank() && f.explanation.isNotBlank() && f.consequences.isNotBlank() && f.recommendation.isNotBlank(), "Ficha incompleta: $f")
        assertTrue(f.ruleId.startsWith("FC-") && f.ruleVersion >= 1, "Regla: $f")
        if (f.category != com.funcodestudiocb.analyzer.engine.api.SecurityCategory.SUPPRESSION) {
            assertTrue(f.cwe != null && f.references.isNotEmpty(), "Sin CWE o referencias: $f")
        }
        // Las rutas se conocen al final (sin el texto): su ficha lleva la ruta y la línea, y el código se abre en el visor.
        if (f.line != null) assertTrue(f.snippet != null || f.ruleId.startsWith("FC-ACCESS"), "Sin fragmento: $f")
        if (f.certainty == Certainty.PROBABLE && f.category in FLOW_CATEGORIES) assertTrue(f.flow.isNotEmpty(), "Probable sin recorrido: $f")
        f.downgraded?.let { assertTrue(it.isNotBlank()) }
    }
    val s = a.summary
    assertEquals(a.findings.size, s.findings)
    assertEquals(a.findings.size, s.bySeverity.values.sum())
    assertEquals(a.findings.size, s.byCertainty.values.sum())
}

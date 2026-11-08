package com.funcodestudiocb.analyzer.engine.relations

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.DependencyCycle
import com.funcodestudiocb.analyzer.engine.api.FileGraph
import com.funcodestudiocb.analyzer.engine.api.FrameReader
import com.funcodestudiocb.analyzer.engine.api.FrameWriter
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.Relation
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.BROKEN
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.PROBABLE
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.UNVERIFIED
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.VERIFIED
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.api.RelationsSummary
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
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
import com.funcodestudiocb.analyzer.engine.inventory.RedactedText
import com.funcodestudiocb.analyzer.engine.inventory.RedactedTextListener
import com.funcodestudiocb.analyzer.engine.languages.LanguageAnalyzer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Resultado de analizar un proyecto de prueba: registros y búsquedas para las aserciones. */
internal class Run(val records: List<ResultRecord>) {
    val relations = records.filterIsInstance<Relation>()
    fun rel(source: String, target: String, kind: RelationKind? = null): Relation =
        relations.firstOrNull { it.source == source && it.target == target && (kind == null || it.kind == kind) }
            ?: fail("No hay relación $source → $target ($kind):\n" + relations.filter { it.source == source }.joinToString("\n") { "  ${it.kind} ${it.target ?: it.external ?: "ROTA " + it.reference} ${it.confidence} ${it.exchanged}" })
    fun external(source: String, name: String): Relation = relations.firstOrNull { it.source == source && it.external == name } ?: fail("Sin externa $name en $source: ${relations.filter { it.source == source }.map { "${it.kind} ${it.external}" }}")
    fun broken(source: String, reference: String): Relation =
        relations.firstOrNull { it.source == source && it.confidence == BROKEN && it.reference == reference } ?: fail("Sin relación rota $reference en $source: ${relations.filter { it.source == source && it.confidence == BROKEN }.map { it.reference }}")
    fun graph(path: String): FileGraph = records.filterIsInstance<FileGraph>().firstOrNull { it.path == path } ?: fail("Sin ficha de grafo para $path")
}

/**
 * Analiza un proyecto de prueba (un ZIP en memoria) con el pipeline real hasta las relaciones, valida cada registro
 * como lo hace el proceso principal y devuelve los registros.
 *
 * @param lexical archivos que se tratan como si tree-sitter no los hubiera podido analizar (sin importaciones).
 */
internal fun analyzeProject(dir: File, entries: Map<String, String>, lexical: Set<String> = emptySet()): Run {
    val file = File.createTempFile("proyecto", ".zip", dir)
    ZipOutputStream(file.outputStream()).use { out ->
        entries.forEach { (name, text) ->
            out.putNextEntry(ZipEntry(name))
            out.write(text.trimIndent().toByteArray())
            out.closeEntry()
        }
    }
    val dependencies = DependencyCollector()
    val relations = RelationCollector()
    val listener = RedactedTextListener { context, f ->
        val text = if (f.path in lexical && f.language != null) {
            RedactedText(f.path, f.info, f.text, f.lineStarts, f.language, LanguageAnalyzer.analyze(f.language!!, f.text, highlight = false, syntax = false), f.state)
        } else {
            f
        }
        relations.onText(context, text)
    }
    val stages = InventoryPipeline.registry(ContentCollector(listOf(dependencies, listener)))
        .register(DependencyStage(dependencies))
        .register(RelationStage(relations))
        .plan()
    FileInputStream(file).use { stream ->
        val attributes = Attributes()
        attributes[IngestKeys.SOURCE] = ArchiveSource(stream.channel, file.absolutePath)
        attributes[IngestKeys.PASSWORD] = attributes.closeLater(PasswordHolder(null))
        val bytes = ByteArrayOutputStream()
        val writer = FrameWriter(bytes)
        runBlocking { PipelineRunner("test").run(AnalysisConfig("prueba", AnalysisMode.FULL, ingest = IngestOptions(file.name)), stages, writer::write, attributes) }
        writer.close()
        val session = SessionReader(FrameReader(ByteArrayInputStream(bytes.toByteArray())), maxBufferedRecords = Int.MAX_VALUE).readAll()
        if (session.violation != null) fail("Flujo inválido: ${session.violation}")
        session.records.forEach { assertTrue(RecordValidator.validate(it).isEmpty(), "Registro inválido: $it ${RecordValidator.validate(it)}") }
        val failed = session.records.filterIsInstance<StageEvent>().filter { it.status != StageStatus.COMPLETED }
        assertTrue(failed.isEmpty(), "Etapas sin completar: $failed")
        return Run(session.records)
    }
}

/** Criterio de aceptación: toda conexión tiene sus cinco atributos y su evidencia, empezando en el origen. */
internal fun assertFiveAttributes(run: Run) {
    assertTrue(run.relations.isNotEmpty())
    for (r in run.relations) {
        assertTrue(r.source.isNotEmpty(), "Origen")
        assertTrue(r.target != null || r.external != null || r.confidence == BROKEN, "Destino: $r")
        assertNotNull(r.kind)
        assertNotNull(r.impact, "Impacto")
        assertTrue(r.evidence.isNotEmpty() && r.evidence.first().path == r.source && r.evidence.all { it.detail.isNotBlank() && it.path != null }, "Evidencia: $r")
    }
}

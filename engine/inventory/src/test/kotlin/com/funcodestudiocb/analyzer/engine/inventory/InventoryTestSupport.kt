package com.funcodestudiocb.analyzer.engine.inventory

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.ContentChunk
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.EntryPoint
import com.funcodestudiocb.analyzer.engine.api.EntryPointKind
import com.funcodestudiocb.analyzer.engine.api.FileProfile
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.FrameReader
import com.funcodestudiocb.analyzer.engine.api.FrameWriter
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.InventoryOptions
import com.funcodestudiocb.analyzer.engine.api.InventorySummary
import com.funcodestudiocb.analyzer.engine.api.ProjectRoot
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.Redaction
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionReader
import com.funcodestudiocb.analyzer.engine.core.Attributes
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveSource
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.PasswordHolder
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertTrue
import kotlin.test.fail

/** Resultado del pipeline completo, ya validado como lo haría el proceso principal. */
class InventoryRun(val records: List<ResultRecord>) {
    inline fun <reified T : ResultRecord> all(): List<T> = records.filterIsInstance<T>()
    fun profile(path: String): FileProfile = all<FileProfile>().firstOrNull { it.path == path } ?: fail("Sin ficha para $path")
    fun roles(path: String): Set<FileRole> = profile(path).roles.map { it.role }.toSet()
    fun entries(kind: EntryPointKind): List<EntryPoint> = all<EntryPoint>().filter { it.kind == kind }
    fun roots(): List<ProjectRoot> = all()
    fun redactions(path: String): List<Redaction> = all<Redaction>().filter { it.path == path }
    fun summary(): InventorySummary = all<InventorySummary>().single()
    fun coverage(): List<CoverageNote> = all()

    /** Copia redactada de un archivo, reconstruida a partir de sus fragmentos. */
    fun copy(path: String): String? {
        val chunks = all<ContentChunk>().filter { it.path == path }.sortedBy { it.index }
        if (chunks.isEmpty()) return null
        assertTrue(chunks.last().last && chunks.dropLast(1).none { it.last }, "Solo el último fragmento se marca como último")
        assertTrue(chunks.map { it.index } == chunks.indices.toList(), "Fragmentos consecutivos")
        return chunks.joinToString("") { it.text }
    }
}

fun zipOf(dir: File, entries: Map<String, String>): File {
    val file = File.createTempFile("proyecto", ".zip", dir)
    ZipOutputStream(file.outputStream()).use { out ->
        entries.forEach { (name, text) ->
            out.putNextEntry(ZipEntry(name))
            out.write(text.toByteArray())
            out.closeEntry()
        }
    }
    return file
}

fun runFull(file: File, inventory: InventoryOptions = InventoryOptions()): InventoryRun {
    FileInputStream(file).use { stream ->
        val attributes = Attributes()
        attributes[IngestKeys.SOURCE] = ArchiveSource(stream.channel, file.absolutePath)
        attributes[IngestKeys.PASSWORD] = attributes.closeLater(PasswordHolder(null))
        val config = AnalysisConfig("prueba", AnalysisMode.FULL, ingest = IngestOptions(file.name), inventory = inventory)
        val bytes = ByteArrayOutputStream()
        val writer = FrameWriter(bytes)
        runBlocking { PipelineRunner("test").run(config, InventoryPipeline.stages(), writer::write, attributes) }
        writer.close()
        val session = SessionReader(FrameReader(ByteArrayInputStream(bytes.toByteArray())), maxBufferedRecords = Int.MAX_VALUE).readAll()
        if (session.violation != null) fail("El motor produjo un flujo inválido: ${session.violation}")
        session.records.forEach { r -> assertTrue(RecordValidator.validate(r).isEmpty(), "Registro inválido: $r") }
        return InventoryRun(session.records)
    }
}

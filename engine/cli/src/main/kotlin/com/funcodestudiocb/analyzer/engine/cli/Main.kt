package com.funcodestudiocb.analyzer.engine.cli

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.ProtocolJson
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionEnd
import com.funcodestudiocb.analyzer.engine.api.SessionStart
import com.funcodestudiocb.analyzer.engine.audit.deps.AuditKeys
import com.funcodestudiocb.analyzer.engine.core.Attributes
import com.funcodestudiocb.analyzer.engine.core.CollectingSink
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveSource
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.PasswordHolder
import com.funcodestudiocb.analyzer.engine.pipeline.FullPipeline
import com.funcodestudiocb.analyzer.engine.report.AnalysisReport
import com.funcodestudiocb.analyzer.engine.report.HtmlRenderer
import com.funcodestudiocb.analyzer.engine.report.JsonExporter
import com.funcodestudiocb.analyzer.engine.report.ReportBuilder
import com.funcodestudiocb.analyzer.engine.report.ReportMeta
import com.funcodestudiocb.analyzer.engine.vulndb.EolData
import com.funcodestudiocb.analyzer.engine.vulndb.InvalidIndexException
import com.funcodestudiocb.analyzer.engine.vulndb.OsvEcosystems
import com.funcodestudiocb.analyzer.engine.vulndb.VulnDatabase
import com.funcodestudiocb.analyzer.engine.vulndb.VulnIndexWriter
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.system.exitProcess

private const val USAGE = """Uso:
  funcode-analyzer <archivo.zip|archivo.rar> [--password <contraseña>] [--vulndb <base.fcv>] [--json] [--informe <salida.html|salida.json>]
  funcode-analyzer --construir-base <base.fcv> <ecosistema>=<all.zip>… [--eol <carpeta con <producto>.json>]

Herramienta de pruebas del motor de Funcode Code Analyzer. Ejecuta el análisis en este equipo, SIN el
aislamiento de la app Android. Usar solo en entornos controlados.

La segunda forma construye la base de vulnerabilidades a partir de los volcados de OSV
(https://osv-vulnerabilities.storage.googleapis.com/<ecosistema>/all.zip) y de los datos de endoflife.date
(https://endoflife.date/api/<producto>.json), igual que el actualizador de la app.

Con --informe escribe además el informe completo (15 secciones y anexos) en HTML o JSON, como la app."""

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--construir-base") {
        buildDatabase(args.drop(1))
        return
    }
    val file = args.firstOrNull { !it.startsWith("--") && args.getOrNull(args.indexOf(it) - 1).let { prev -> prev != "--password" && prev != "--vulndb" && prev != "--informe" } }?.let(::File)
    if (file == null || !file.isFile) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val password = args.indexOf("--password").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.toCharArray()
    val vulndb = args.indexOf("--vulndb").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let(::File)
    val json = "--json" in args
    val reportFile = args.indexOf("--informe").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let(::File)
    val started = System.currentTimeMillis()

    val sink = CollectingSink()
    FileInputStream(file).use { stream ->
        val attributes = Attributes()
        attributes[IngestKeys.SOURCE] = ArchiveSource(stream.channel, file.absolutePath)
        attributes[IngestKeys.PASSWORD] = attributes.closeLater(PasswordHolder(password))
        if (vulndb != null) {
            try {
                attributes[AuditKeys.DATABASE] = attributes.closeLater(VulnDatabase.open(FileInputStream(vulndb).channel))
            } catch (e: InvalidIndexException) {
                attributes[AuditKeys.DATABASE_PROBLEM] = e.message ?: "base no válida"
            }
        }
        val config = AnalysisConfig("cli", AnalysisMode.FULL, ingest = IngestOptions(file.name))
        runBlocking { PipelineRunner("cli").run(config, FullPipeline.stages(), sink, attributes) }
    }
    val invalid = sink.records.filter { RecordValidator.validate(it).isNotEmpty() }
    sink.records.forEach { record ->
        if (json) println(ProtocolJson.encodeToString(ResultRecord.serializer(), record)) else println(record)
    }
    if (invalid.isNotEmpty()) {
        System.err.println("ERROR: ${invalid.size} registros no superan la validación")
        exitProcess(1)
    }
    if (reportFile != null) writeReport(file, reportFile, sink.records, started)
}

private fun writeReport(archive: File, target: File, records: List<ResultRecord>, started: Long) {
    val analysis = AnalysisReport.from(records, records.filterIsInstance<SessionEnd>().lastOrNull()?.outcome, null)
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(archive).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    val meta = ReportMeta(
        analysisId = records.filterIsInstance<SessionStart>().firstOrNull()?.analysisId ?: "cli",
        archiveName = archive.name,
        archiveSize = archive.length(),
        archiveSha256 = digest.digest().joinToString("") { "%02x".format(it) },
        analyzedAtEpochMs = started,
        generatedAtEpochMs = System.currentTimeMillis(),
        appVersion = "CLI de pruebas",
        engineVersion = "cli",
    )
    val document = ReportBuilder.build(analysis, meta)
    target.writeText(if (target.name.endsWith(".json")) JsonExporter.export(document, records) else HtmlRenderer.render(document))
    System.err.println("Informe escrito en ${target.path}")
}

private fun buildDatabase(args: List<String>) {
    val target = args.firstOrNull()?.let(::File)
    val sources = args.drop(1).filter { it.contains('=') && !it.startsWith("--") }
    if (target == null || sources.isEmpty()) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val work = Files.createTempDirectory("funcode-vulndb").toFile()
    try {
        VulnIndexWriter(work).use { writer ->
            for (s in sources) {
                val (ecosystem, path) = s.split('=', limit = 2)
                require(ecosystem in OsvEcosystems.SUPPORTED) { "Ecosistema desconocido: $ecosystem (válidos: ${OsvEcosystems.SUPPORTED})" }
                val zip = File(path)
                val started = System.currentTimeMillis()
                val r = writer.addOsvZip(ecosystem, zip, zip.lastModified())
                System.err.println("$ecosystem: ${r.records} avisos leídos, ${r.advisories} útiles, ${r.packages} paquetes, ${r.invalid} no válidos (${System.currentTimeMillis() - started} ms)")
            }
            val eolDir = args.indexOf("--eol").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let(::File)
            if (eolDir != null) {
                val products = EolData.PRODUCTS.mapNotNull { (product, label) ->
                    File(eolDir, "$product.json").takeIf { it.isFile }?.let { EolData.parse(product, label, it.readText()) }
                }
                writer.setEol(products, products.size.toLong().let { eolDir.lastModified() })
                System.err.println("Fin de soporte: ${products.size} productos")
            }
            val started = System.currentTimeMillis()
            val info = writer.writeTo(target)
            System.err.println("Base escrita en ${target.path}: ${info.sizeBytes / 1024} KB, sha256 ${info.sha256} (${System.currentTimeMillis() - started} ms)")
        }
    } finally {
        work.deleteRecursively()
    }
}

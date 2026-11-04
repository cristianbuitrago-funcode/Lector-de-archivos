package com.funcodestudiocb.analyzer.engine.cli

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.ProtocolJson
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.core.Attributes
import com.funcodestudiocb.analyzer.engine.core.CollectingSink
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveSource
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.PasswordHolder
import com.funcodestudiocb.analyzer.engine.pipeline.FullPipeline
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import kotlin.system.exitProcess

private const val USAGE = """Uso: funcode-analyzer <archivo.zip|archivo.rar> [--password <contraseña>] [--json]

Herramienta de pruebas del motor de Funcode Code Analyzer. Ejecuta la ingesta en este equipo, SIN el
aislamiento de la app Android. Usar solo en entornos controlados."""

fun main(args: Array<String>) {
    val file = args.firstOrNull { !it.startsWith("--") }?.let(::File)
    if (file == null || !file.isFile) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val password = args.indexOf("--password").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.toCharArray()
    val json = "--json" in args

    val sink = CollectingSink()
    FileInputStream(file).use { stream ->
        val attributes = Attributes()
        attributes[IngestKeys.SOURCE] = ArchiveSource(stream.channel, file.absolutePath)
        attributes[IngestKeys.PASSWORD] = attributes.closeLater(PasswordHolder(password))
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
}

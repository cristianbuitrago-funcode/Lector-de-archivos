package com.funcodestudiocb.analyzer.engine.inventory

import com.funcodestudiocb.analyzer.engine.api.ContentChunk
import com.funcodestudiocb.analyzer.engine.api.ContentState
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.Redaction
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.ingest.FileContentConsumer
import com.funcodestudiocb.analyzer.engine.ingest.ReadFile
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeXmlReader
import com.funcodestudiocb.analyzer.engine.security.SecretRedactor
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Lo que se averiguó del contenido de un archivo de texto. */
class FileFacts(
    val lineCount: Int?,
    val content: ContentState,
    val redactions: Int,
    val signals: List<SignalHit>,
)

/**
 * Examina el contenido de cada archivo de texto en el mismo recorrido del comprimido (el contenido no se
 * conserva después): cuenta líneas, busca señales de función y puntos de entrada, lee los manifiestos, oculta
 * los posibles secretos y emite la copia redactada para el visor.
 *
 * Todo lo que sale de aquí ya está redactado o es texto del propio motor.
 */
class ContentCollector : FileContentConsumer {

    val facts = HashMap<String, FileFacts>()
    val androidManifests = mutableListOf<Pair<String, AndroidManifestFacts>>()
    val npmPackages = mutableListOf<Pair<String, NpmFacts>>()
    val angularConfigs = mutableListOf<Pair<String, List<Pair<String, Declared>>>>()
    val godotProjects = mutableListOf<Pair<String, GodotFacts>>()
    val unityFirstScenes = mutableListOf<Pair<String, Declared>>()
    val unrealMaps = mutableListOf<Pair<String, Declared>>()
    val htmlScripts = mutableListOf<Pair<String, List<Declared>>>()
    val executableCsprojs = mutableListOf<String>()

    /** Carpeta del módulo → espacio de nombres declarado en su build.gradle(.kts). */
    val gradleNamespaces = HashMap<String, String>()

    /** Archivo Kotlin/Java → paquete declarado. */
    val jvmPackages = HashMap<String, String>()

    var storedFiles = 0
        private set
    var storedChars = 0L
        private set
    var redactionsTotal = 0
        private set
    var tooLarge = 0
        private set
    var copyEnabled = true
        private set
    private var limitReported = false

    override fun accept(context: StageContext, file: ReadFile) {
        if (file.kind != FileKind.TEXT) return
        val options = context.config.inventory
        copyEnabled = options.keepRedactedCopy
        val bytes = file.content
        if (bytes == null) {
            if (file.contentTooLarge) tooLarge++
            facts[file.path] = FileFacts(null, ContentState.TOO_LARGE, 0, emptyList())
            return
        }
        val info = PathInfo(file.path)
        val text = decode(bytes, file.encoding)
        val lineStarts = SafeXmlReader.computeLineStarts(text)
        val lineCount = if (text.isEmpty()) 0 else lineStarts.size - if (text.endsWith('\n')) 1 else 0

        val signals = ContentSignals.scan(info, text, lineStarts)
        readManifest(info, text, lineStarts)

        val redaction = SecretRedactor.redact(text, info.name)
        redaction.secrets.take(MAX_REDACTIONS_PER_FILE).forEach {
            context.emit(Redaction(file.path, it.line, it.type, it.length, it.masked))
        }
        redactionsTotal += redaction.secrets.size

        val state = when {
            !options.keepRedactedCopy -> ContentState.NOT_STORED_DISABLED
            PathRules.isLockFile(info) || isMinified(info, text, lineCount) -> ContentState.NOT_STORED_GENERATED
            storedChars + text.length > options.maxStoredChars -> {
                if (!limitReported) {
                    limitReported = true
                    context.coverage(
                        CoverageReason.LIMIT_REACHED,
                        "Se alcanzó el tamaño máximo de la copia del código (${options.maxStoredChars / (1024 * 1024)} MB): los archivos restantes no tienen copia para el visor",
                    )
                }
                ContentState.NOT_STORED_LIMIT
            }
            chunkCount(redaction.text) > RecordValidator.MAX_CHUNKS_PER_FILE -> ContentState.NOT_STORED_LIMIT
            else -> ContentState.STORED
        }
        if (state == ContentState.STORED) {
            emitChunks(context, file.path, redaction.text)
            storedFiles++
            storedChars += text.length
        }
        facts[file.path] = FileFacts(lineCount, state, redaction.secrets.size, signals)
    }

    private fun readManifest(info: PathInfo, text: String, lineStarts: IntArray) {
        val path = info.path
        when {
            info.lower == "androidmanifest.xml" -> androidManifests += path to Manifests.androidManifest(text)
            info.lower == "package.json" -> Manifests.npmPackage(text)?.let { npmPackages += path to it }
            info.lower == "angular.json" -> angularConfigs += path to Manifests.angularMains(text)
            info.lower == "project.godot" -> godotProjects += path to Manifests.godotProject(text, lineStarts)
            info.lower == "editorbuildsettings.asset" -> Manifests.unityFirstScene(text, lineStarts)?.let { unityFirstScenes += path to it }
            info.lower == "defaultengine.ini" -> Manifests.unrealDefaultMap(text, lineStarts)?.let { unrealMaps += path to it }
            info.ext == "html" || info.ext == "htm" -> Manifests.htmlScripts(text, lineStarts).takeIf { it.isNotEmpty() }?.let { htmlScripts += path to it }
            info.lower == "build.gradle" || info.lower == "build.gradle.kts" -> Manifests.gradleNamespace(text)?.let { gradleNamespaces[info.directory] = it }
            info.ext == "kt" || info.ext == "java" -> Manifests.jvmPackage(text)?.let { jvmPackages[path] = it }
            info.ext == "csproj" -> if (Manifests.csprojIsExecutable(text)) executableCsprojs += path
        }
    }

    /** Parte el texto en fragmentos de hasta [RecordValidator.MAX_CHUNK_CHARS], cortando por líneas si se puede. */
    private fun emitChunks(context: StageContext, path: String, text: String) {
        if (text.isEmpty()) {
            context.emit(ContentChunk(path, 0, "", last = true))
            return
        }
        var start = 0
        var index = 0
        while (start < text.length) {
            var end = minOf(text.length, start + RecordValidator.MAX_CHUNK_CHARS)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end - 1)
                end = if (newline >= start + RecordValidator.MAX_CHUNK_CHARS / 2) newline + 1 else end
                if (Character.isHighSurrogate(text[end - 1])) end--
            }
            context.emit(ContentChunk(path, index++, text.substring(start, end), last = end >= text.length))
            start = end
        }
    }

    private fun chunkCount(text: String): Int = text.length / (RecordValidator.MAX_CHUNK_CHARS / 2) + 1

    private fun isMinified(info: PathInfo, text: String, lineCount: Int): Boolean {
        if (info.ext !in setOf("js", "mjs", "cjs", "css")) return false
        if (info.lower.contains(".min.")) return true
        return text.length > MINIFIED_MIN_CHARS && text.length / lineCount.coerceAtLeast(1) > MINIFIED_AVG_LINE
    }

    internal companion object {
        const val MAX_REDACTIONS_PER_FILE = 1_000
        private const val MINIFIED_MIN_CHARS = 10_000
        private const val MINIFIED_AVG_LINE = 500

        /**
         * Decodifica con la codificación detectada, sustituyendo lo inválido. Quita la marca BOM y cambia los
         * caracteres nulos (que el protocolo no admite) por U+FFFD.
         */
        fun decode(bytes: ByteArray, encoding: String?): String {
            val charset: Charset = when {
                encoding == null -> Charsets.UTF_8
                encoding.startsWith("UTF-16LE") -> Charsets.UTF_16LE
                encoding.startsWith("UTF-16BE") -> Charsets.UTF_16BE
                encoding.startsWith("ISO-8859-1") -> Charsets.ISO_8859_1
                else -> Charsets.UTF_8
            }
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            var text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            if (text.startsWith('﻿')) text = text.substring(1)
            return if (text.indexOf('\u0000') >= 0) text.replace('\u0000', '�') else text
        }
    }
}

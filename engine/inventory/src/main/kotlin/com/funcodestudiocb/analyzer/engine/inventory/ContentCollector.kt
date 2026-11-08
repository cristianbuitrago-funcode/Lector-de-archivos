package com.funcodestudiocb.analyzer.engine.inventory

import com.funcodestudiocb.analyzer.engine.api.ContentChunk
import com.funcodestudiocb.analyzer.engine.api.ContentState
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.api.FileLanguage
import com.funcodestudiocb.analyzer.engine.api.LanguageCategory
import com.funcodestudiocb.analyzer.engine.api.LanguageStats
import com.funcodestudiocb.analyzer.engine.api.LineMethod
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.Redaction
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.core.StageTimings
import com.funcodestudiocb.analyzer.engine.ingest.FileContentConsumer
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.ReadFile
import com.funcodestudiocb.analyzer.engine.languages.CountMethod
import com.funcodestudiocb.analyzer.engine.languages.Language
import com.funcodestudiocb.analyzer.engine.languages.LanguageAnalysis
import com.funcodestudiocb.analyzer.engine.languages.LanguageAnalyzer
import com.funcodestudiocb.analyzer.engine.languages.LanguageKind
import com.funcodestudiocb.analyzer.engine.languages.Languages
import com.funcodestudiocb.analyzer.engine.languages.treesitter.TreeSitter
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeXmlReader
import com.funcodestudiocb.analyzer.engine.security.DetectedSecret
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
    val language: FileLanguage? = null,
)

/**
 * Texto de un archivo con los posibles secretos ya ocultados, su lenguaje y lo que obtuvo el análisis de lenguaje
 * (importaciones, rangos de comentarios). Es lo único del contenido que ven las demás etapas.
 */
class RedactedText(
    val path: String,
    val info: PathInfo,
    val text: String,
    val lineStarts: IntArray,
    val language: Language?,
    val analysis: LanguageAnalysis?,
    /** Si hay copia para el visor o por qué no (un archivo generado no es código escrito por el equipo). */
    val state: ContentState,
    /** Posibles secretos ocultados en el texto (sin su valor): tipo, línea, máscara y huella. */
    val secrets: List<DetectedSecret> = emptyList(),
)

/** Recibe el texto redactado de cada archivo examinado, en el mismo recorrido del comprimido. */
fun interface RedactedTextListener {
    fun onText(context: StageContext, file: RedactedText)

    /** Nombre de esta parte en el desglose del tiempo de la lectura (Anexo A del informe). */
    val timingLabel: String get() = javaClass.simpleName
}

/** Totales de un lenguaje mientras se recorre el comprimido. */
internal class LanguageTally(val language: Language) {
    var files = 0
    var code = 0L
    var comment = 0L
    var blank = 0L
    var syntaxFiles = 0
    var lexicalFiles = 0
    var filesWithErrors = 0

    fun toRecord() = LanguageStats(
        language.id, language.name, language.level,
        when (language.kind) {
            LanguageKind.PROGRAMMING -> LanguageCategory.PROGRAMMING
            LanguageKind.MARKUP -> LanguageCategory.MARKUP
            LanguageKind.DATA -> LanguageCategory.DATA
            LanguageKind.PROSE -> LanguageCategory.PROSE
        },
        files, code, comment, blank, syntaxFiles, lexicalFiles, filesWithErrors,
    )
}

/**
 * Examina el contenido de cada archivo de texto en el mismo recorrido del comprimido (el contenido no se
 * conserva después): cuenta líneas, busca señales de función y puntos de entrada, lee los manifiestos, oculta
 * los posibles secretos y emite la copia redactada para el visor.
 *
 * Todo lo que sale de aquí ya está redactado o es texto del propio motor.
 */
class ContentCollector(private val listeners: List<RedactedTextListener> = emptyList()) : FileContentConsumer {

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

    internal val languages = LinkedHashMap<String, LanguageTally>()

    /** Archivos cuyo análisis sintáctico no terminó (tiempo, memoria), con el motivo. */
    val syntaxProblems = mutableListOf<Pair<String, String>>()

    /** Tiempo total dedicado al análisis sintáctico; al agotarse el presupuesto se sigue con el recuento léxico. */
    private var syntaxMillis = 0L
    private var syntaxBudgetReported = false
    private var cppProject: Boolean? = null

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
        val timings = context.attributes[StageTimings.KEY]?.also { it.restLabel = "descompresión, huellas y tipo de cada archivo" }
        fun <T> part(name: String, block: () -> T): T = timings?.measure(name, block) ?: block()
        val info = PathInfo(file.path)
        val text = part("decodificación del texto") { decode(bytes, file.encoding) }
        val lineStarts = SafeXmlReader.computeLineStarts(text)
        val lineCount = if (text.isEmpty()) 0 else lineStarts.size - if (text.endsWith('\n')) 1 else 0
        val language = Languages.detect(file.path, text.take(DETECTION_HEAD), isCppProject(context))

        val signals = part("funciones y puntos de entrada") {
            ContentSignals.scan(info, text, lineStarts).also { readManifest(info, text, lineStarts) }
        }

        val redaction = part("redactor de secretos") { SecretRedactor.redact(text, info.name, fingerprintKey(context)) }
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
        // El análisis de lenguaje trabaja sobre el texto redactado (misma longitud que el original): lo que se
        // obtiene de él (importaciones, nombres) nunca contiene un secreto completo.
        val analysis = language?.let { part("análisis sintáctico y resaltado") { analyzeLanguage(context, file.path, it, redaction.text, state) } }
        if (state == ContentState.STORED) {
            part("copia redactada para el visor") { emitChunks(context, file.path, redaction.text, analysis?.highlight) }
            storedFiles++
            storedChars += text.length
        }
        val fileLanguage = analysis?.let {
            FileLanguage(
                it.language.id, it.language.name, it.language.level,
                if (it.method == CountMethod.SYNTAX) LineMethod.SYNTAX else LineMethod.LEXICAL,
                it.codeLines, it.commentLines, it.blankLines, it.syntaxErrors,
            )
        }
        facts[file.path] = FileFacts(lineCount, state, redaction.secrets.size, signals, fileLanguage)
        if (listeners.isNotEmpty()) {
            val redacted = RedactedText(file.path, info, redaction.text, lineStarts, language, analysis, state, redaction.secrets)
            listeners.forEach { listener -> part(listener.timingLabel) { listener.onText(context, redacted) } }
        }
    }

    private var keyDecoded = false
    private var key: ByteArray? = null

    /** Clave local de las huellas de los secretos (si el proceso principal la envió). */
    private fun fingerprintKey(context: StageContext): ByteArray? {
        if (!keyDecoded) {
            keyDecoded = true
            key = context.config.security.fingerprintKey?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.size >= 16 }
        }
        return key
    }

    private fun analyzeLanguage(context: StageContext, path: String, language: Language, text: String, state: ContentState): LanguageAnalysis {
        val remaining = SYNTAX_BUDGET_MS - syntaxMillis
        val useSyntax = state != ContentState.NOT_STORED_GENERATED && remaining > 0
        if (!useSyntax && remaining <= 0 && language.grammar != null && !syntaxBudgetReported) {
            syntaxBudgetReported = true
            context.coverage(
                CoverageReason.LIMIT_REACHED,
                "Se agotó el tiempo total del análisis sintáctico (${SYNTAX_BUDGET_MS / 1000} s): los archivos restantes se contaron con el analizador léxico",
            )
        }
        val started = System.nanoTime()
        val analysis = LanguageAnalyzer.analyze(
            language, text,
            highlight = state == ContentState.STORED,
            timeoutMs = minOf(LanguageAnalyzer.DEFAULT_TIMEOUT_MS.toLong(), remaining.coerceAtLeast(1)).toInt(),
            syntax = useSyntax,
        )
        if (language.grammar != null && useSyntax) syntaxMillis += (System.nanoTime() - started) / 1_000_000
        analysis.syntaxProblem?.let { if (syntaxProblems.size < MAX_SYNTAX_PROBLEMS) syntaxProblems += path to it }

        val tally = languages.getOrPut(language.id) { LanguageTally(language) }
        tally.files++
        tally.code += analysis.codeLines
        tally.comment += analysis.commentLines
        tally.blank += analysis.blankLines
        if (analysis.method == CountMethod.SYNTAX) tally.syntaxFiles++ else tally.lexicalFiles++
        if (analysis.syntaxErrors > 0) tally.filesWithErrors++
        return analysis
    }

    /** El proyecto tiene fuentes C++: los `.h` se analizan como C++ (si no, como C). */
    private fun isCppProject(context: StageContext): Boolean = cppProject ?: run {
        val included = context.attributes[IngestKeys.PLAN]?.included?.values.orEmpty()
        val cpp = included.any { path -> CPP_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) } }
        cppProject = cpp
        cpp
    }

    /** Estado de tree-sitter para la cobertura del análisis. */
    val syntaxAvailable: Boolean get() = TreeSitter.available

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

    /**
     * Parte el texto en fragmentos de hasta [RecordValidator.MAX_CHUNK_CHARS], cortando por líneas si se puede.
     * [highlight] son tríos (inicio, longitud, categoría) sobre el texto completo: cada fragmento lleva los suyos,
     * relativos a su inicio (un comentario que cruza el corte se reparte entre los dos).
     */
    private fun emitChunks(context: StageContext, path: String, text: String, highlight: IntArray?) {
        if (text.isEmpty()) {
            context.emit(ContentChunk(path, 0, "", last = true))
            return
        }
        var start = 0
        var index = 0
        var span = 0
        while (start < text.length) {
            var end = minOf(text.length, start + RecordValidator.MAX_CHUNK_CHARS)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end - 1)
                end = if (newline >= start + RecordValidator.MAX_CHUNK_CHARS / 2) newline + 1 else end
                if (Character.isHighSurrogate(text[end - 1])) end--
            }
            val spans = ArrayList<Int>()
            if (highlight != null) {
                while (span + 2 < highlight.size) {
                    val s0 = highlight[span]
                    val e0 = s0 + highlight[span + 1]
                    if (s0 >= end) break
                    val from = maxOf(s0, start)
                    val to = minOf(e0, end)
                    if (to > from && spans.size < RecordValidator.MAX_HIGHLIGHT_SPANS * 3) {
                        spans += from - start
                        spans += to - from
                        spans += highlight[span + 2]
                    }
                    if (e0 > end) break // sigue en el fragmento siguiente
                    span += 3
                }
            }
            context.emit(ContentChunk(path, index++, text.substring(start, end), last = end >= text.length, highlight = spans))
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
        private const val DETECTION_HEAD = 512
        private const val MAX_SYNTAX_PROBLEMS = 200

        /** Tiempo total para el análisis sintáctico de todo el proyecto. */
        const val SYNTAX_BUDGET_MS = 180_000L
        private val CPP_EXTENSIONS = listOf(".cpp", ".cc", ".cxx", ".hpp", ".hh", ".hxx", ".ino")
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

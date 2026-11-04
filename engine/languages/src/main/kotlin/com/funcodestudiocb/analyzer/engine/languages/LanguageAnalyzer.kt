package com.funcodestudiocb.analyzer.engine.languages

import com.funcodestudiocb.analyzer.engine.languages.treesitter.SyntaxStatus
import com.funcodestudiocb.analyzer.engine.languages.treesitter.TreeSitter
import com.google.re2j.Pattern

enum class CountMethod { SYNTAX, LEXICAL }

/** Resultado del análisis de un archivo de texto. */
class LanguageAnalysis(
    val language: Language,
    val method: CountMethod,
    val codeLines: Int,
    val commentLines: Int,
    val blankLines: Int,
    val syntaxErrors: Int,
    /** Motivo por el que no se usó el análisis sintáctico estando disponible la gramática (tiempo, memoria). */
    val syntaxProblem: String?,
    val imports: List<ImportRef>,
    /** Resaltado: tríos (inicio en caracteres, longitud, categoría) sobre el texto analizado; `null` si no se pidió. */
    val highlight: IntArray?,
    /** Comentarios: pares (inicio, fin) en caracteres, en orden. */
    val comments: IntArray = IntArray(0),
    /** Símbolos declarados en el nivel superior (clases, funciones…), con sus supertipos. */
    val declarations: List<Declaration> = emptyList(),
    /** Paquete o espacios de nombres declarados (Java, Kotlin, C#, PHP, C++). */
    val namespaces: List<String> = emptyList(),
) {
    /** La posición (en caracteres) está dentro de un comentario. */
    fun inComment(offset: Int): Boolean {
        var lo = 0
        var hi = comments.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                offset < comments[mid * 2] -> hi = mid - 1
                offset >= comments[mid * 2 + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}

/**
 * Cuenta líneas de código, comentario y en blanco, extrae las importaciones y prepara el resaltado.
 *
 * Con gramática de tree-sitter, los comentarios son los del árbol sintáctico: una línea es de comentario solo si todo
 * lo que contiene, salvo espacios, está dentro de un comentario. Sin gramática (o si el análisis no termina a tiempo)
 * se usa un analizador léxico con la sintaxis de comentarios y cadenas del lenguaje.
 */
object LanguageAnalyzer {

    const val DEFAULT_TIMEOUT_MS = 5_000
    private const val MAX_ITEMS = 400_000
    private const val MAX_IMPORTS = 500

    /**
     * @param syntax `false` para usar solo el recuento léxico (archivos generados, presupuesto de tiempo agotado)
     */
    fun analyze(language: Language, text: String, highlight: Boolean, timeoutMs: Int = DEFAULT_TIMEOUT_MS, syntax: Boolean = true): LanguageAnalysis {
        val grammar = language.grammar
        if (syntax && grammar != null && TreeSitter.hasGrammar(grammar)) {
            val utf8 = text.toByteArray(Charsets.UTF_8)
            val result = TreeSitter.parse(grammar, utf8, timeoutMs, MAX_ITEMS, highlight, language.wantedTypes)
            if (result.status == SyntaxStatus.OK && !result.commentsTruncated) {
                val counts = countWithComments(utf8, result.comments)
                val starts = lineStarts(utf8)
                val symbols = SymbolExtractor.extract(language, utf8, result.nodes) { lineOf(starts, it) }
                val imports = symbols.imports + requireCalls(language, text, utf8, result.comments)
                val spans = if (highlight && !result.tokensTruncated) toCharSpans(utf8, result.tokens) else null
                val comments = toCharRanges(utf8, result.comments)
                var declarations = symbols.declarations
                if (result.syntaxErrors > 0) {
                    // Con errores de sintaxis el árbol puede perder declaraciones: se completan línea a línea.
                    val textStarts = charLineStarts(text)
                    if (language.id !in setOf("csharp", "c", "cpp")) {
                        // Una declaración sangrada dentro de una zona con errores suele ser una variable local.
                        declarations = declarations.filter { d ->
                            val start = textStarts.getOrNull(d.line - 1) ?: return@filter true
                            start >= text.length || !(text[start] == ' ' || text[start] == '\t')
                        }
                    }
                    val known = declarations.mapTo(HashSet()) { it.name }
                    val probe = LanguageAnalysis(language, CountMethod.SYNTAX, 0, 0, 0, 0, null, emptyList(), null, comments)
                    val extra = LexicalDeclarations.scan(language, text, probe::inComment) { offset ->
                        val i = textStarts.binarySearch(offset)
                        (if (i >= 0) i else -i - 2) + 1
                    }.filter { it.name !in known }
                    declarations = (declarations + extra).distinctBy { it.name }.take(SymbolExtractor.MAX_DECLARATIONS)
                }
                return LanguageAnalysis(
                    language, CountMethod.SYNTAX, counts[0], counts[1], counts[2], result.syntaxErrors, null,
                    imports.take(MAX_IMPORTS), spans, comments, declarations, symbols.namespaces,
                )
            }
            val problem = when (result.status) {
                SyntaxStatus.OK -> "el archivo tiene más comentarios de los que admite el análisis sintáctico"
                SyntaxStatus.TIMED_OUT -> "el análisis sintáctico superó su tiempo máximo"
                SyntaxStatus.OUT_OF_MEMORY -> "el análisis sintáctico se quedó sin memoria"
                SyntaxStatus.UNAVAILABLE -> "la gramática no está disponible"
            }
            return lexical(language, text, problem)
        }
        return lexical(language, text, null)
    }

    private fun lexical(language: Language, text: String, problem: String?): LanguageAnalysis {
        val ranges = IntList()
        val counts = LexicalCounter.count(text, language.syntax, ranges)
        return LanguageAnalysis(language, CountMethod.LEXICAL, counts[0], counts[1], counts[2], 0, problem, emptyList(), null, ranges.toArray())
    }

    /** Pares (inicio, fin) en bytes UTF-8, en orden, a caracteres. */
    internal fun toCharRanges(utf8: ByteArray, ranges: IntArray): IntArray {
        val triples = IntArray(ranges.size / 2 * 3)
        for (i in 0 until ranges.size / 2) {
            triples[i * 3] = ranges[i * 2]
            triples[i * 3 + 1] = ranges[i * 2 + 1]
            triples[i * 3 + 2] = 1
        }
        val spans = toCharSpans(utf8, triples)
        val out = IntArray(spans.size / 3 * 2)
        for (i in 0 until spans.size / 3) {
            out[i * 2] = spans[i * 3]
            out[i * 2 + 1] = spans[i * 3] + spans[i * 3 + 1]
        }
        return out
    }

    /** [código, comentario, en blanco] a partir de los rangos de comentarios (en bytes, ordenados). */
    internal fun countWithComments(utf8: ByteArray, comments: IntArray): IntArray {
        var code = 0
        var comment = 0
        var blank = 0
        var c = 0 // índice del par de comentario actual
        var lineStart = 0
        while (lineStart <= utf8.size) {
            var lineEnd = lineStart
            while (lineEnd < utf8.size && utf8[lineEnd] != '\n'.code.toByte()) lineEnd++
            if (lineStart == utf8.size && lineEnd == lineStart) break // última línea vacía tras el último salto
            var hasCode = false
            var hasComment = false
            for (i in lineStart until lineEnd) {
                val b = utf8[i]
                if (b == ' '.code.toByte() || b == '\t'.code.toByte() || b == '\r'.code.toByte() || b == 0x0C.toByte()) continue
                while (c + 1 < comments.size && comments[c + 1] <= i) c += 2
                if (c + 1 < comments.size && comments[c] <= i && i < comments[c + 1]) hasComment = true else hasCode = true
                if (hasCode) break
            }
            when {
                hasCode -> code++
                hasComment -> comment++
                else -> blank++
            }
            lineStart = lineEnd + 1
        }
        return intArrayOf(code, comment, blank)
    }

    private fun charLineStarts(text: String): IntArray {
        var count = 1
        for (ch in text) if (ch == '\n') count++
        val starts = IntArray(count)
        var n = 1
        for (i in text.indices) if (text[i] == '\n') starts[n++] = i + 1
        return starts
    }

    private fun lineStarts(utf8: ByteArray): IntArray {
        var count = 1
        for (b in utf8) if (b == '\n'.code.toByte()) count++
        val starts = IntArray(count)
        var n = 1
        for (i in utf8.indices) if (utf8[i] == '\n'.code.toByte()) starts[n++] = i + 1
        return starts
    }

    private fun lineOf(starts: IntArray, offset: Int): Int {
        val i = starts.binarySearch(offset)
        return (if (i >= 0) i else -i - 2) + 1
    }

    private val REQUIRE = Pattern.compile("""\b(?:require|import)\s*\(\s*["'`]([^"'`\r\n]+)["'`]\s*\)""")

    /** `require("x")` e `import("x")` en JavaScript/TypeScript, fuera de los comentarios. */
    private fun requireCalls(language: Language, text: String, utf8: ByteArray, comments: IntArray): List<ImportRef> {
        if (language.id != "javascript" && language.id != "typescript" && language.id != "tsx") return emptyList()
        if (!text.contains("require") && !text.contains("import(")) return emptyList()
        val starts = lineStarts(utf8)
        val m = REQUIRE.matcher(utf8.toString(Charsets.UTF_8))
        val out = mutableListOf<ImportRef>()
        // Los desplazamientos del comparador son en caracteres; se convierten a bytes para comparar con los comentarios.
        val charToByte = CharToByte(text)
        while (m.find() && out.size < MAX_IMPORTS) {
            val byteStart = charToByte.byteOffset(m.start())
            if (insideRanges(comments, byteStart)) continue
            if (text.regionMatches(m.start(), "import", 0, 6) && text.substring(maxOf(0, m.start() - 1), m.start()).let { it == "." }) continue
            out += ImportRef(m.group(1), lineOf(starts, byteStart))
        }
        return out
    }

    private fun insideRanges(ranges: IntArray, offset: Int): Boolean {
        var i = 0
        while (i + 1 < ranges.size) {
            if (offset >= ranges[i] && offset < ranges[i + 1]) return true
            if (ranges[i] > offset) return false
            i += 2
        }
        return false
    }

    /**
     * Convierte los fragmentos de resaltado (bytes UTF-8) a (inicio en caracteres, longitud, categoría). Los fragmentos
     * llegan en orden de documento y no se solapan.
     */
    internal fun toCharSpans(utf8: ByteArray, tokens: IntArray): IntArray {
        val out = IntArray(tokens.size)
        var bytePos = 0
        var charPos = 0
        fun advanceTo(target: Int) {
            while (bytePos < target && bytePos < utf8.size) {
                val b = utf8[bytePos].toInt() and 0xFF
                val width = when {
                    b < 0x80 -> 1
                    b >= 0xF0 -> 4
                    b >= 0xE0 -> 3
                    b >= 0xC0 -> 2
                    else -> 1
                }
                bytePos += width
                charPos += if (width == 4) 2 else 1
            }
        }
        var n = 0
        var i = 0
        while (i + 2 < tokens.size) {
            val start = tokens[i]
            val end = tokens[i + 1]
            val category = tokens[i + 2]
            i += 3
            if (start < bytePos || end < start) continue
            advanceTo(start)
            val charStart = charPos
            advanceTo(end)
            out[n++] = charStart
            out[n++] = charPos - charStart
            out[n++] = category
        }
        return out.copyOf(n)
    }

    /** Desplazamiento en bytes UTF-8 de una posición en caracteres (consultas en orden creciente). */
    private class CharToByte(private val text: String) {
        private var charPos = 0
        private var bytePos = 0

        fun byteOffset(target: Int): Int {
            if (target < charPos) {
                charPos = 0
                bytePos = 0
            }
            while (charPos < target && charPos < text.length) {
                val ch = text[charPos]
                bytePos += when {
                    ch.code < 0x80 -> 1
                    ch.code < 0x800 -> 2
                    Character.isHighSurrogate(ch) && charPos + 1 < text.length && Character.isLowSurrogate(text[charPos + 1]) -> {
                        charPos++
                        4
                    }
                    else -> 3
                }
                charPos++
            }
            return bytePos
        }
    }
}

/** Lista de enteros que crece sin cajas. */
internal class IntList {
    private var data = IntArray(16)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}

/** Recuento léxico de líneas para lenguajes sin gramática (comentarios de línea y de bloque, y cadenas). */
internal object LexicalCounter {
    private const val MAX_RANGES = 400_000

    /** [código, comentario, en blanco]; si se pasa [ranges], añade los comentarios como pares (inicio, fin). */
    fun count(text: String, syntax: CommentSyntax, ranges: IntList? = null): IntArray {
        var code = 0
        var comment = 0
        var blank = 0
        var inBlock: Pair<String, String>? = null
        var inString: Char? = null
        var i = 0
        var lineHasCode = false
        var lineHasComment = false
        fun endLine() {
            when {
                lineHasCode -> code++
                lineHasComment -> comment++
                else -> blank++
            }
            lineHasCode = false
            lineHasComment = false
        }
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n') {
                endLine()
                i++
                continue
            }
            val block = inBlock
            when {
                block != null -> {
                    if (!ch.isWhitespace()) lineHasComment = true
                    if (text.startsWith(block.second, i)) {
                        inBlock = null
                        i += block.second.length
                        if (ranges != null && ranges.size % 2 == 1) ranges.add(i)
                        continue
                    }
                }
                inString != null -> {
                    lineHasCode = true
                    if (ch == '\\') {
                        i += 2
                        continue
                    }
                    if (ch == inString) inString = null
                }
                ch.isWhitespace() -> Unit
                else -> {
                    val opening = syntax.block.firstOrNull { text.startsWith(it.first, i) }
                    when {
                        opening != null -> {
                            inBlock = opening
                            lineHasComment = true
                            if (ranges != null && ranges.size + 2 <= MAX_RANGES) ranges.add(i)
                            i += opening.first.length
                            continue
                        }
                        syntax.line.any { text.startsWith(it, i) } -> {
                            lineHasComment = true
                            val start = i
                            while (i < text.length && text[i] != '\n') i++
                            if (ranges != null && ranges.size + 2 <= MAX_RANGES) {
                                ranges.add(start)
                                ranges.add(i)
                            }
                            continue
                        }
                        ch in syntax.quotes -> {
                            inString = ch
                            lineHasCode = true
                        }
                        else -> lineHasCode = true
                    }
                }
            }
            i++
        }
        if (text.isNotEmpty() && !text.endsWith('\n')) endLine()
        // Un comentario de bloque sin cerrar llega hasta el final.
        if (ranges != null && ranges.size % 2 == 1) ranges.add(text.length)
        return intArrayOf(code, comment, blank)
    }
}

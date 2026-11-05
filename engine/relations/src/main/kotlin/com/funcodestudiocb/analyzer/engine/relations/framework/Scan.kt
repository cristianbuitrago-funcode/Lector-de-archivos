package com.funcodestudiocb.analyzer.engine.relations.framework

import com.funcodestudiocb.analyzer.engine.inventory.PathInfo
import com.funcodestudiocb.analyzer.engine.inventory.RedactedText

/** Cadena literal leída del código: su valor, con las partes interpoladas como [DYNAMIC]. */
internal class Literal(val value: String, val start: Int, val end: Int) {
    val dynamic: Boolean get() = DYNAMIC in value
    val startsDynamic: Boolean get() = value.startsWith(DYNAMIC)
}

/** Marca de una parte de una cadena o URL que solo se conoce al ejecutar. */
internal const val DYNAMIC = "\u0001"

/**
 * Lector de código para los analizadores de framework: busca llamadas, lee sus argumentos y cadenas (con la
 * interpolación de cada lenguaje) y descarta lo que está dentro de comentarios. No usa expresiones regulares de
 * Java (en Android son de ICU y se comportan distinto): solo recorridos de caracteres acotados.
 */
internal class Src(file: RedactedText) {
    val path: String = file.path
    val text: String = file.text
    val info: PathInfo = file.info
    val language: String? = file.language?.id
    private val lineStarts = file.lineStarts
    private val analysis = file.analysis

    fun line(offset: Int): Int {
        val i = lineStarts.binarySearch(offset)
        return (if (i >= 0) i else -i - 2) + 1
    }

    fun isCode(offset: Int): Boolean = analysis?.inComment(offset) != true

    fun has(needle: String): Boolean = text.contains(needle)

    /** Cada aparición de [needle] fuera de los comentarios (como máximo [MAX_HITS]). */
    fun each(needle: String, action: (Int) -> Unit) {
        var i = text.indexOf(needle)
        var n = 0
        while (i >= 0 && n < MAX_HITS) {
            if (isCode(i) && !inString(i)) {
                action(i)
                n++
            }
            i = text.indexOf(needle, i + needle.length)
        }
    }

    /** Como [each], pero solo si [needle] empieza un identificador (no es el final de otro nombre). */
    fun eachWord(needle: String, action: (Int) -> Unit) = each(needle) { i -> if (i == 0 || !isIdent(text[i - 1]) && text[i - 1] != '.') action(i) }

    /**
     * Cada `receptor<sep>miembro` del texto (`User.findAll`, `User::where`) en una sola pasada, sin el límite de
     * [each]: el receptor puede llevar puntos (`db.User`).
     */
    fun eachMember(sep: String, limit: Int = 50_000, action: (String, String, Int) -> Unit) {
        var i = text.indexOf(sep)
        var n = 0
        while (i >= 0 && n < limit) {
            if (i > 0 && isIdent(text[i - 1]) && isCode(i) && !inString(i)) {
                val member = wordAt(i + sep.length)
                val recv = if (member != null) receiverBefore(i) else null
                if (member != null && recv != null) {
                    action(recv, member, i)
                    n++
                }
            }
            i = text.indexOf(sep, i + sep.length)
        }
    }

    /**
     * La posición puede ser una anotación, un decorador o un atributo (`@GetMapping`, `[HttpGet]`): está al principio
     * de la línea o detrás de otra anotación o de un paréntesis, nunca dentro de una cadena como `"@Entity"`.
     */
    fun annotationAt(at: Int): Boolean {
        if (!isCode(at) || inString(at)) return false
        var i = at - 1
        while (i >= 0 && text[i] != '\n' && text[i].isWhitespace()) i--
        if (i < 0 || text[i] == '\n') return true
        val c = text[i]
        if (c == ')' || c == ']') return true
        if (isIdent(c)) {
            var j = i
            while (j >= 0 && (isIdent(text[j]) || text[j] == '.')) j--
            return j >= 0 && text[j] == '@'
        }
        return false
    }

    /** Cadenas del archivo: pares (inicio en la comilla, fin tras la de cierre) y sus interpolaciones (que son código). */
    private val strings: Pair<IntArray, IntArray> by lazy { computeStrings() }

    private fun computeStrings(): Pair<IntArray, IntArray> {
        if (language == null || language in MARKUP) return IntArray(0) to IntArray(0)
        val ranges = ArrayList<Int>()
        val holes = ArrayList<Int>()
        val comments = analysis?.comments ?: IntArray(0)
        var ci = 0
        var i = 0
        while (i < text.length && ranges.size < MAX_STRINGS * 2) {
            while (ci < comments.size / 2 && comments[ci * 2 + 1] <= i) ci++
            if (ci < comments.size / 2 && comments[ci * 2] <= i) {
                i = comments[ci * 2 + 1]
                continue
            }
            val c = text[i]
            if (c == '"' || c == '\'' || c == '`') {
                // Prefijo pegado a la comilla (f"…", r'…', @"…", $"…") si no es el final de un nombre.
                var p = i
                while (p > 0 && i - p < 3 && text[p - 1] in PREFIXES) p--
                val from = if (p < i && (p == 0 || !isIdent(text[p - 1]))) p else i
                val lit = literal(from, holes)
                if (lit != null) {
                    ranges += lit.start
                    ranges += lit.end
                    i = lit.end
                    continue
                }
            }
            i++
        }
        return ranges.toIntArray() to holes.toIntArray()
    }

    private fun inRanges(r: IntArray, at: Int, strict: Boolean): Boolean {
        var lo = 0
        var hi = r.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val a = r[mid * 2]
            val b = r[mid * 2 + 1]
            when {
                at < (if (strict) a + 1 else a) -> hi = mid - 1
                at >= (if (strict) b - 1 else b) -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /**
     * La posición está dentro del contenido de una cadena (no en su comilla de apertura ni en una interpolación como
     * `${x}`, que es código). Sirve para que un texto que parece código (`"@Entity"`, un proyecto de ejemplo dentro
     * de una cadena de varias líneas) no se tome por código.
     */
    fun inString(at: Int): Boolean {
        val (ranges, holes) = strings
        return inRanges(ranges, at, strict = true) && !inRanges(holes, at, strict = false)
    }

    /** [at] es lo primero (tras espacios) del contenido de una cadena: el SQL de `"CREATE TABLE …"`. */
    fun startsString(at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        return i >= 0 && text[i] in QUOTES && !inString(i)
    }

    fun skipWs(from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun skipWsBack(from: Int): Int {
        var i = from
        while (i >= 0 && text[i].isWhitespace()) i--
        return i
    }

    /** Identificador (con puntos: `this.http`, `app.router`) que termina justo antes de [end]. */
    fun receiverBefore(end: Int): String? {
        var i = skipWsBack(end - 1)
        val stop = i
        while (i >= 0 && (isIdent(text[i]) || text[i] == '.' || text[i] == '$')) i--
        if (i == stop) return null
        return text.substring(i + 1, stop + 1).trim('.').takeIf { it.isNotEmpty() }
    }

    /** Palabra (identificador) que empieza en [from]. */
    fun wordAt(from: Int): String? {
        var j = from
        while (j < text.length && (isIdent(text[j]) || text[j] == '$')) j++
        return if (j > from) text.substring(from, j) else null
    }

    /** Posición del cierre que corresponde a la apertura en [open] (`(`, `[`, `{`), o -1. Respeta las cadenas. */
    fun close(open: Int, limit: Int = MAX_SPAN): Int {
        val opener = text[open]
        val closer = when (opener) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            '<' -> '>'
            else -> return -1
        }
        var depth = 0
        var i = open
        val end = minOf(text.length, open + limit)
        while (i < end) {
            val c = text[i]
            if (!isCode(i)) {
                i++
                continue
            }
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    val lit = literal(i)
                    i = lit?.end ?: (i + 1)
                    continue
                }
                c == opener -> depth++
                c == closer -> {
                    depth--
                    if (depth == 0) return i
                }
                opener == '<' && (c == ';' || c == '{') -> return -1
            }
            i++
        }
        return -1
    }

    /** Argumentos de primer nivel de la llamada cuyo paréntesis abre en [open]. */
    fun args(open: Int): List<IntRange>? {
        val end = close(open)
        if (end < 0) return null
        return splitTop(open + 1, end, ',')
    }

    /** Divide [from, to) por [separator] fuera de cadenas y paréntesis. */
    fun splitTop(from: Int, to: Int, separator: Char): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var depth = 0
        var start = from
        var i = from
        while (i < to) {
            val c = text[i]
            if (!isCode(i)) {
                i++
                continue
            }
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    val lit = literal(i)
                    if (lit == null || lit.end > to) {
                        i++
                        continue
                    }
                    i = lit.end
                    continue
                }
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == separator && depth == 0 -> {
                    out += start until i
                    start = i + 1
                }
            }
            i++
        }
        if (to > start || out.isNotEmpty()) out += start until to
        return out.filter { r -> (r.first..r.last).any { !text[it].isWhitespace() } }
    }

    fun slice(r: IntRange): String = text.substring(r.first, r.last + 1).trim()

    /** Primera posición de [r] que no es espacio. */
    fun firstOf(r: IntRange): Int = skipWs(r.first).coerceAtMost(r.last + 1)

    /**
     * Cadena literal que empieza en [at] (después de espacios y de un prefijo `r`, `f`, `b`, `@`, `$`): comillas
     * simples, dobles, invertidas y triples. Las interpolaciones (`${x}`, `$x`, `{x}` de f-strings y C#, `\(x)`)
     * se sustituyen por [DYNAMIC].
     */
    fun literal(at: Int, holes: MutableList<Int>? = null): Literal? {
        var i = skipWs(at)
        if (i >= text.length) return null
        var raw = false
        var braces = false // f-string o $"…" de C#: {x}
        var prefixEnd = i
        while (prefixEnd < text.length && prefixEnd - i < 3 && text[prefixEnd] in PREFIXES) prefixEnd++
        if (prefixEnd > i && prefixEnd < text.length && text[prefixEnd] in QUOTES) {
            val prefix = text.substring(i, prefixEnd).lowercase()
            if (!prefixValid(prefix)) return null
            raw = 'r' in prefix || '@' in prefix
            braces = 'f' in prefix || '$' in prefix
            i = prefixEnd
        }
        val q = text.getOrNull(i) ?: return null
        if (q !in QUOTES) return null
        val triple = i + 2 < text.length && text[i + 1] == q && text[i + 2] == q
        val start = i
        i += if (triple) 3 else 1
        val sb = StringBuilder()
        val limit = minOf(text.length, start + MAX_LITERAL)
        val lang = language
        while (i < limit) {
            val c = text[i]
            if (triple) {
                if (c == q && i + 2 < text.length && text[i + 1] == q && text[i + 2] == q) return Literal(sb.toString(), start, i + 3)
            } else if (c == q) {
                if (raw && q == '"' && text.getOrNull(i + 1) == '"' && '@' in text.substring(maxOf(0, start - 2), start)) {
                    sb.append('"')
                    i += 2
                    continue
                }
                return Literal(sb.toString(), start, i + 1)
            }
            if (!triple && c == '\n' && q != '`') return null
            when {
                c == '\\' && !raw -> {
                    if (lang == "swift" && text.getOrNull(i + 1) == '(') {
                        val end = close(i + 1, 400)
                        if (end < 0) return null
                        holes?.let { it += i; it += end + 1 }
                        sb.append(DYNAMIC)
                        i = end + 1
                        continue
                    }
                    text.getOrNull(i + 1)?.let { sb.append(it) }
                    i += 2
                    continue
                }
                c == '$' && text.getOrNull(i + 1) == '{' && (q == '`' || lang in DOLLAR_LANGS && !(lang == "php" && q == '\'')) -> {
                    val end = close(i + 1, 400)
                    if (end < 0) return null
                    holes?.let { it += i; it += end + 1 }
                    sb.append(DYNAMIC)
                    i = end + 1
                    continue
                }
                c == '$' && lang in DOLLAR_LANGS && q != '`' && text.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true &&
                    !(lang == "php" && q == '\'') && !(lang == "kotlin" && q == '\'') -> {
                    var j = i + 1
                    while (j < limit && (isIdent(text[j]) || (lang == "php" && text[j] == '-' && text.getOrNull(j + 1) == '>'))) j += if (text[j] == '-') 2 else 1
                    holes?.let { it += i; it += j }
                    sb.append(DYNAMIC)
                    i = j
                    continue
                }
                c == '{' && braces -> {
                    if (text.getOrNull(i + 1) == '{') {
                        sb.append('{')
                        i += 2
                        continue
                    }
                    val end = close(i, 400)
                    if (end < 0) return null
                    holes?.let { it += i; it += end + 1 }
                    sb.append(DYNAMIC)
                    i = end + 1
                    continue
                }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }

    /** El argumento es una sola cadena literal (sin concatenar). */
    fun literalArg(r: IntRange): Literal? {
        val lit = literal(r.first) ?: return null
        return lit.takeIf { skipWs(it.end) > r.last }
    }

    /** Claves de primer nivel de un objeto o mapa literal (`{a: 1, b}`, `{'a': 1}`, `['a' => 1]`, `mapOf("a" to 1)`). */
    fun objectKeys(open: Int): List<String> {
        val end = close(open)
        if (end < 0) return emptyList()
        val out = mutableListOf<String>()
        for (part in splitTop(open + 1, end, ',')) {
            val s = skipWs(part.first)
            if (s > part.last) continue
            val lit = literal(s)
            val key = when {
                lit != null && !lit.dynamic -> lit.value
                text.startsWith("...", s) -> null
                else -> wordAt(s)
            } ?: continue
            if (key.isNotEmpty() && key.length <= 60 && key.none { it.isISOControl() } && out.size < 20) out += key
        }
        return out.distinct()
    }

    companion object {
        const val MAX_HITS = 2_000
        const val MAX_SPAN = 6_000
        const val MAX_LITERAL = 8_000
        private const val PREFIXES = "rRbBfFuU@$"
        private const val QUOTES = "\"'`"
        private val DOLLAR_LANGS = setOf("kotlin", "dart", "php", "groovy")
        private val MARKUP = setOf("html", "xml", "vue", "svelte", "markdown", "text", "json", "yaml", "toml", "properties", "css", "scss")
        private const val MAX_STRINGS = 200_000

        fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

        private fun prefixValid(p: String): Boolean = p in setOf("r", "b", "f", "u", "rb", "br", "fr", "rf", "@", "$", "$@", "@$")
    }
}

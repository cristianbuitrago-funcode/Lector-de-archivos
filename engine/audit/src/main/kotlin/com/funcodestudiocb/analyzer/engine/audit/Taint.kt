package com.funcodestudiocb.analyzer.engine.audit

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.FlowStep
import com.funcodestudiocb.analyzer.engine.relations.framework.DYNAMIC
import com.funcodestudiocb.analyzer.engine.relations.framework.Src

/** Una expresión del código: su texto fijo y las partes que no lo son. */
internal class Expr(
    /** Texto fijo de la expresión (las partes variables como [DYNAMIC]). */
    val literal: String,
    /** Partes que no son texto fijo (variables, llamadas, interpolaciones). */
    val parts: List<String>,
    /** Líneas donde se construyó, si se siguió una variable (`sql = "…" + x` antes de `query(sql)`). */
    val built: List<Int> = emptyList(),
) {
    val dynamic: Boolean get() = parts.isNotEmpty()

    /** Tiene texto fijo además de partes variables: se construye concatenando o interpolando. */
    val constructed: Boolean get() = dynamic && literal.any { it != DYNAMIC[0] && !it.isWhitespace() }
}

/** Resultado de seguir los datos hasta un punto peligroso. */
internal class Flow(val certainty: Certainty, val steps: List<FlowStep>, val source: String?, val clientSource: Boolean = false) {
    /** El dato viene de la línea de órdenes o del teclado: quien lo da es quien ejecuta el programa. */
    val local: Boolean get() = source in Taint.LOCAL_SOURCES
}

/**
 * Flujo de datos intraprocedural aproximado (§8.1). Una parte variable está "contaminada" si contiene una entrada
 * del usuario, si es una variable asignada desde una en las líneas anteriores (como mucho [WINDOW]) o si es un
 * parámetro marcado como entrada (`@RequestParam`, `[FromQuery]`, parámetros de una ruta de Flask o FastAPI). Una
 * conversión a número o una función de escape la limpia; cualquier otra función la convierte en "derivada"
 * (certeza posible). Sin flujo demostrable, la certeza es como mucho "posible".
 */
internal object Taint {

    private val SOURCES = mapOf(
        "js" to listOf(
            "req.body", "req.query", "req.params", "req.headers", "req.cookies", "req.files", "req.file", "req.get(", "req.param(",
            "request.body", "request.query", "request.params", "request.headers",
            "ctx.request.body", "ctx.request.query", "ctx.query", "ctx.params", "event.body", "event.queryStringParameters", "event.pathParameters",
            "location.search", "location.hash", "location.href", "document.location", "window.location", "document.URL", "document.referrer",
            "searchParams.get(", "useParams(", "useSearchParams(", "router.query", "process.argv",
        ),
        "python" to listOf(
            "request.GET", "request.POST", "request.args", "request.form", "request.values", "request.json", "request.get_json(", "request.data",
            "request.query_params", "request.path_params", "request.cookies", "request.headers", "request.files", "request.FILES", "request.body",
            "self.request.", "sys.argv", "input(",
        ),
        "php" to listOf(
            "\$_GET", "\$_POST", "\$_REQUEST", "\$_COOKIE", "\$_FILES", "\$_SERVER['HTTP_", "\$_SERVER[\"HTTP_", "\$_SERVER['QUERY_STRING'",
            "\$_SERVER['REQUEST_URI'", "\$request->input(", "\$request->get(", "\$request->query(", "\$request->all(", "\$request->post(",
            "\$request->route(", "\$request->", "request()->", "request(", "Input::get(", "\$this->input->", "\$this->request->",
        ),
        "jvm" to listOf(
            "getParameter(", "getParameterValues(", "getParameterMap(", "getHeader(", "getQueryString(", "getCookies(", "getInputStream(",
            "getReader(", "getStringExtra(", "getQueryParameter(", "call.receive", "call.parameters", "call.request.queryParameters",
            "getPathInfo(", "getRequestURI(", "getRequestURL(",
        ),
        "csharp" to listOf(
            "Request.Query", "Request.Form", "Request.QueryString", "Request.Headers", "Request.Cookies", "Request.Body", "Request.RouteValues",
            "Request.Params", "Request[",
        ),
        "go" to listOf(
            "r.URL.Query()", "r.URL.Path", "r.FormValue(", "r.PostFormValue(", "r.Form", "mux.Vars(", "c.Query(", "c.Param(", "c.PostForm(",
            "c.DefaultQuery(", "c.Params(", "r.Body", "c.FormValue(", "c.QueryParam(",
        ),
    )

    /** Entradas que solo existen en el navegador: no cuentan para los puntos peligrosos del servidor. */
    private val CLIENT_SOURCES = setOf(
        "location.search", "location.hash", "location.href", "document.location", "window.location", "document.URL", "document.referrer",
        "searchParams.get(", "useParams(", "useSearchParams(", "router.query",
    )

    /** Entradas de quien ejecuta el programa (no de un usuario remoto). */
    val LOCAL_SOURCES = setOf("process.argv", "sys.argv", "input(")

    /** Anotaciones o atributos que marcan un parámetro como entrada del usuario. */
    private val PARAM_MARKERS = listOf(
        "@RequestParam", "@PathVariable", "@RequestBody", "@RequestHeader", "@CookieValue", "@QueryParam", "@PathParam", "@FormParam",
        "@HeaderParam", "[FromQuery]", "[FromBody]", "[FromRoute]", "[FromForm]", "[FromHeader]",
    )

    /** Funciones que limpian el dato: conversión a número, escape o nombre base de un archivo. */
    private val SANITIZERS = setOf(
        "parseInt", "parseFloat", "Number", "int", "float", "Integer.parseInt", "Integer.valueOf", "Long.parseLong", "Long.valueOf", "toInt",
        "toLong", "toIntOrNull", "toLongOrNull", "intval", "floatval", "int.Parse", "Int32.Parse", "long.Parse", "Guid.Parse", "UUID.fromString",
        "uuid.UUID", "strconv.Atoi", "strconv.ParseInt", "atoi", "bool", "Boolean", "escape", "escapeHtml", "escapeHTML", "htmlspecialchars",
        "htmlentities", "strip_tags", "e", "esc_html", "esc_attr", "esc_url", "encodeURIComponent", "encodeURI", "sanitize", "DOMPurify.sanitize",
        "sanitizeHtml", "xss", "validator.escape", "html.escape", "bleach.clean", "markupsafe.escape", "quote", "shlex.quote", "pipes.quote",
        "escapeshellarg", "escapeshellcmd", "mysqli_real_escape_string", "real_escape_string", "pg_escape_string", "basename", "path.basename",
        "secure_filename", "filepath.Base", "Path.GetFileName", "FilenameUtils.getName", "HtmlUtils.htmlEscape", "StringEscapeUtils.escapeHtml4",
        "Encode.forHtml", "HttpUtility.HtmlEncode", "WebUtility.HtmlEncode", "html.EscapeString", "template.HTMLEscapeString",
    )

    /** Funciones que dejan pasar el dato tal cual (conversión a texto, recortes, lectura de un parámetro). */
    private val PASSTHROUGH = setOf(
        "String", "str", "toString", "trim", "strip", "lower", "upper", "toLowerCase", "toUpperCase", "decodeURIComponent", "decodeURI",
        "unescape", "urldecode", "rawurldecode", "get", "getParameter", "getString", "getStringExtra", "getQueryParameter", "Query", "FormValue",
        "join", "format", "f", "concat", "replace", "substring", "slice", "split", "JSON.stringify", "json.dumps", "trimStart", "trimEnd",
    )

    fun family(language: String?): String? = when (language) {
        "javascript", "typescript", "tsx", "vue", "svelte" -> "js"
        "python" -> "python"
        "php" -> "php"
        "java", "kotlin" -> "jvm"
        "csharp" -> "csharp"
        "go" -> "go"
        else -> null
    }

    private enum class Kind { SOURCE, SANITIZED, DERIVED }

    private class Hit(val kind: Kind, val source: String, val via: String?)

    /** Entrada del usuario dentro de [text] y si llega directa, limpia o a través de otra función. */
    private fun classify(family: String?, text: String): Hit? {
        val list = SOURCES[family] ?: return null
        var best = -1
        var source: String? = null
        for (s in list) {
            var i = text.indexOf(s)
            while (i > 0 && (Src.isIdent(text[i - 1]) || text[i - 1] == '.' && s.first() != '.')) i = text.indexOf(s, i + 1)
            if (i >= 0 && (best < 0 || i < best)) {
                best = i
                source = s
            }
        }
        if (source == null) return null
        // Llamada que envuelve la entrada (la más interior), si la hay.
        val stack = ArrayList<Int>()
        for (k in 0 until best) {
            when (text[k]) {
                '(' -> stack += k
                ')' -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
        }
        if (stack.isEmpty()) return Hit(Kind.SOURCE, source, null)
        var j = stack.last() - 1
        while (j >= 0 && text[j].isWhitespace()) j--
        val end = j + 1
        while (j >= 0 && (Src.isIdent(text[j]) || text[j] == '.' || text[j] == '$')) j--
        val name = text.substring(j + 1, end).removePrefix("$").removePrefix("this.").removePrefix("self.")
        val short = name.substringAfterLast('.')
        return when {
            name.isEmpty() -> Hit(Kind.SOURCE, source, null) // paréntesis de agrupación
            name in SANITIZERS || short in SANITIZERS -> Hit(Kind.SANITIZED, source, name)
            name in PASSTHROUGH || short in PASSTHROUGH -> Hit(Kind.SOURCE, source, null)
            else -> Hit(Kind.DERIVED, source, name.take(60))
        }
    }

    /** Entrada del usuario que aparece directamente en un texto (para comprobaciones de una línea). */
    fun sourceIn(language: String?, text: String): String? = classify(family(language), text)?.takeIf { it.kind == Kind.SOURCE }?.source

    /**
     * Partes de la expresión en [r]: concatenaciones (`+`, `.` en PHP, `%` en Python), cadenas con interpolación
     * (`${x}`, f-strings, `$x`), `.format(…)`, `String.format(…)`, `sprintf(…)`, `fmt.Sprintf(…)`.
     */
    fun expr(src: Src, r: IntRange, depth: Int = 0): Expr {
        val start = src.skipWs(r.first)
        val text = src.text
        if (start > r.last || depth > 3) return Expr("", emptyList())
        // (expresión) entre paréntesis: se mira dentro.
        if (text[start] == '(') {
            val close = src.close(start)
            if (close > start && src.skipWs(close + 1) > r.last) return expr(src, start + 1 until close, depth + 1)
        }
        val parts = mutableListOf<String>()
        val literal = StringBuilder()
        for (fmt in FORMATTERS) {
            if (!text.startsWith(fmt, start)) continue
            val open = start + fmt.length - 1
            val close = src.close(open)
            if (close < 0 || src.skipWs(close + 1) <= r.last) break
            val args = src.args(open) ?: break
            val first = args.firstOrNull() ?: break
            val inner = expr(src, first, depth + 1)
            literal.append(inner.literal)
            parts += inner.parts
            args.drop(1).forEach { a -> addPiece(src, a, literal, parts) }
            return Expr(literal.toString(), parts)
        }
        val separators = when (src.language) {
            "php" -> listOf('.')
            "python" -> listOf('+', '%')
            else -> listOf('+')
        }
        var pieces = listOf(start..r.last)
        for (sep in separators) pieces = pieces.flatMap { p -> src.splitTop(p.first, p.last + 1, sep) }
        for (p in pieces) addPiece(src, p, literal, parts)
        return Expr(literal.toString(), parts)
    }

    private fun addPiece(src: Src, p: IntRange, literal: StringBuilder, parts: MutableList<String>) {
        val text = src.text
        val ps = src.skipWs(p.first)
        if (ps > p.last) return
        val holes = mutableListOf<Int>()
        val lit = src.literal(ps, holes)
        if (lit != null && src.skipWs(lit.end) > p.last) {
            literal.append(lit.value)
            var i = 0
            while (i + 1 < holes.size) {
                if (holes[i] >= lit.start && holes[i + 1] <= lit.end) {
                    parts += text.substring(holes[i], holes[i + 1]).trim('$', '{', '}', '\\', '(', ')', ' ')
                }
                i += 2
            }
            return
        }
        // 'texto {}'.format(x), "texto %s".formatted(x), "texto".concat(x)
        if (lit != null) {
            val after = lit.end
            val call = listOf(".format(", ".formatted(", ".concat(").firstOrNull { text.startsWith(it, after) }
            if (call != null) {
                literal.append(lit.value)
                src.args(after + call.length - 1)?.forEach { a -> addPiece(src, a, literal, parts) }
                return
            }
        }
        val piece = src.slice(p)
        if (piece.isEmpty()) return
        literal.append(DYNAMIC)
        parts += piece
    }

    /** La parte no puede venir del usuario: constante en mayúsculas, número, `len(x)`, llamada que limpia el dato. */
    fun constant(part: String): Boolean {
        val p = part.trim().removePrefix("$").removePrefix("this.").removePrefix("self.").removePrefix("static::").removePrefix("self::")
        if (p.isEmpty()) return true
        if (p.all { it.isDigit() || it == '.' || it == '-' }) return true
        val last = p.substringAfterLast('.').substringAfterLast("::")
        if (last.isNotEmpty() && last.all { it.isUpperCase() || it.isDigit() || it == '_' } && last.any { it.isLetter() }) return true
        if (p == "__DIR__" || p == "__FILE__" || p.startsWith("dirname(") || p.startsWith("os.path.dirname(") || p.startsWith("BASE_DIR")) return true
        if (CONSTANT_SUFFIXES.any { p.endsWith(it) } || CONSTANT_CALLS.any { p.startsWith(it) }) return true
        val open = p.indexOf('(')
        if (open > 0 && p.endsWith(")")) {
            val name = p.substring(0, open).trim()
            val short = name.substringAfterLast('.')
            if (name in SANITIZERS || short in SANITIZERS) return true
        }
        return false
    }

    /** Partes que pueden llevar datos variables (sin las constantes). */
    fun variableParts(e: Expr): List<String> = e.parts.filterNot { constant(it) }

    /**
     * Si [e] es solo una variable, su valor según las asignaciones anteriores (`sql = "SELECT …"` y los `+=` que le
     * siguen); si no, [e] tal cual.
     */
    fun resolve(src: Src, e: Expr, line: Int): Expr {
        if (e.literal != DYNAMIC || e.parts.size != 1) return e
        val raw = e.parts[0].trim()
        val ident = identifierOf(raw)?.takeIf { it == raw.removePrefix("$") } ?: return e
        val chunks = ArrayList<Pair<Expr, Int>>()
        for (n in linesWith(src, ident, line - 1)) {
            if (n >= line || chunks.size >= 10) continue
            val lineText = src.lineText(n)
            val eq = if (containsWord(lineText, ident)) assignmentEquals(lineText, ident) else null
            if (eq != null) {
                val start = src.lineStart(n) + eq + 1
                chunks += expr(src, statement(src, start)) to n
                val op = lineText.getOrNull(eq - 1)
                if (op != '+' && op != '.') break
            }
        }
        if (chunks.isEmpty()) return e
        val literal = StringBuilder()
        val parts = mutableListOf<String>()
        val built = mutableListOf<Int>()
        for ((ex, l) in chunks.asReversed()) {
            literal.append(ex.literal)
            parts += ex.parts
            built += l
        }
        return Expr(literal.toString(), parts, built)
    }

    /** La sentencia que empieza en [from]: hasta `;`, `,` o un salto de línea fuera de paréntesis y cadenas. */
    fun statement(src: Src, from: Int, maxLines: Int = 8): IntRange {
        val text = src.text
        var i = src.skipWsInline(from)
        var depth = 0
        var lines = 0
        val limit = minOf(text.length, from + 3_000)
        var lastCode = from - 1
        while (i < limit) {
            val c = text[i]
            if (!src.isCode(i)) {
                if (c == '\n' && depth == 0 && !continues(text, lastCode, i)) break
                i++
                continue
            }
            if (c == '"' || c == '\'' || c == '`') {
                val lit = src.literal(i)
                if (lit != null) {
                    i = lit.end
                    lastCode = i - 1
                    continue
                }
            }
            when {
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> {
                    if (depth == 0) break
                    depth--
                }
                (c == ';' || c == ',') && depth == 0 -> break
                c == '\n' -> {
                    lines++
                    if (lines > maxLines || depth == 0 && !continues(text, lastCode, i)) break
                }
            }
            if (!c.isWhitespace()) lastCode = i
            i++
        }
        return from until i
    }

    /** La línea sigue en la siguiente (termina o empieza por un operador). */
    private fun continues(text: String, lastCode: Int, newline: Int): Boolean {
        val end = text.getOrNull(lastCode)
        if (end != null && end in "+.,(=&|?:\\") return true
        var j = newline + 1
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        val next = text.getOrNull(j)
        return next == '+' || next == '.' && text.getOrNull(j + 1) != '.' || next == '?' || next == ':'
    }

    /**
     * Sigue las partes variables de [e] hasta una entrada del usuario. `null` si no hay partes variables (texto fijo,
     * constantes o datos ya limpiados: no hay hallazgo).
     */
    fun follow(src: Src, e: Expr, sinkLine: Int, what: String): Flow? {
        val parts = variableParts(e)
        if (parts.isEmpty()) return null
        val fam = family(src.language)
        var derived: Pair<String, String?>? = null
        var unknown = false
        for (part in parts) {
            val hit = classify(fam, part)
            when (hit?.kind) {
                Kind.SOURCE -> return Flow(
                    if (hit.source in LOCAL_SOURCES) Certainty.POSSIBLE else Certainty.PROBABLE,
                    e.built.map { FlowStep(it, "Se construye el valor con un dato de la petición (${hit.source.trimEnd('(')})") } +
                        FlowStep(sinkLine, "Un dato de la petición (${hit.source.trimEnd('(')}) llega a $what"),
                    hit.source, hit.source in CLIENT_SOURCES,
                )
                Kind.DERIVED -> if (derived == null) derived = hit.source to hit.via
                Kind.SANITIZED -> {}
                null -> unknown = true
            }
        }
        // Sin ninguna entrada del usuario en el archivo no hay nada que seguir hacia atrás.
        val traceable = hasSources(src)
        for (part in parts) {
            if (!traceable) break
            if (classify(fam, part) != null) continue
            val ident = identifierOf(part) ?: continue
            val name = ident.take(60)
            var assigned = false
            for (n in linesWith(src, ident, sinkLine)) {
                val lineText = src.lineText(n)
                if (!containsWord(lineText, ident)) continue
                PARAM_MARKERS.firstOrNull { m -> lineText.contains(m) && lineText.indexOf(m) < wordIndex(lineText, ident) }?.let { m ->
                    return Flow(
                        Certainty.PROBABLE,
                        listOf(FlowStep(n, "«$name» es un parámetro de la petición ($m)"), FlowStep(sinkLine, "«$name» llega a $what")),
                        m,
                    )
                }
                if (n == sinkLine) continue
                val eq = assignmentEquals(lineText, ident) ?: continue
                assigned = true
                val hit = classify(fam, lineText.substring(eq + 1))
                when (hit?.kind) {
                    Kind.SOURCE -> return Flow(
                        if (hit.source in LOCAL_SOURCES) Certainty.POSSIBLE else Certainty.PROBABLE,
                        listOf(
                            FlowStep(n, "«$name» recibe un dato de la petición (${hit.source.trimEnd('(')})"),
                            FlowStep(sinkLine, "«$name» llega a $what"),
                        ),
                        hit.source, hit.source in CLIENT_SOURCES,
                    )
                    Kind.DERIVED -> if (derived == null) derived = hit.source to hit.via
                    Kind.SANITIZED -> if (parts.size == 1) return null
                    null -> {}
                }
                break
            }
            if (!assigned) {
                routeParameter(src, ident, sinkLine)?.let { defLine ->
                    return Flow(
                        Certainty.PROBABLE,
                        listOf(FlowStep(defLine, "«$name» es un parámetro de la ruta"), FlowStep(sinkLine, "«$name» llega a $what")),
                        "parámetro de la ruta",
                    )
                }
            }
        }
        derived?.let { (source, via) ->
            return Flow(
                Certainty.POSSIBLE,
                listOf(FlowStep(sinkLine, "Un dato de la petición (${source.trimEnd('(')}) pasa por ${via ?: "otra función"}() antes de llegar a $what; no se sabe si lo limpia")),
                null,
            )
        }
        if (!unknown) return null
        return Flow(
            Certainty.POSSIBLE,
            listOf(FlowStep(sinkLine, "${what.replaceFirstChar { it.uppercase() }} recibe un valor variable; no se pudo seguir su origen")),
            null,
        )
    }

    private val SOURCE_CACHE = ThreadLocal<Pair<Src, Boolean>>()

    /** El archivo contiene alguna entrada del usuario, un parámetro marcado o rutas de Python (se calcula una vez). */
    fun hasSources(src: Src): Boolean {
        SOURCE_CACHE.get()?.let { (s, v) -> if (s === src) return v }
        val text = src.text
        val v = SOURCES[family(src.language)].orEmpty().any { text.contains(it) } || PARAM_MARKERS.any { text.contains(it) } ||
            src.language == "python" && ROUTE_DECORATORS.any { text.contains(it) }
        SOURCE_CACHE.set(src to v)
        return v
    }

    /**
     * Líneas que contienen [ident], de [line] hacia atrás (como mucho [WINDOW] líneas), cada una una vez. Busca en el
     * texto en lugar de recorrer línea a línea.
     */
    fun linesWith(src: Src, ident: String, line: Int): List<Int> {
        val text = src.text
        val windowStart = src.lineStart((line - WINDOW).coerceAtLeast(1))
        var pos = if (line < src.lineCount) src.lineStart(line + 1) - 1 else text.length
        val out = ArrayList<Int>()
        while (pos >= windowStart) {
            val i = text.lastIndexOf(ident, pos)
            if (i < windowStart) break
            val n = src.line(i)
            out += n
            pos = src.lineStart(n) - 1
        }
        return out
    }

    /** Parámetro de una función de Flask o FastAPI decorada como ruta. */
    private fun routeParameter(src: Src, ident: String, sinkLine: Int): Int? {
        if (src.language != "python") return null
        for (n in sinkLine downTo (sinkLine - WINDOW).coerceAtLeast(1)) {
            val t = src.lineText(n).trimStart()
            if (!t.startsWith("def ") && !t.startsWith("async def ")) continue
            val params = t.substringAfter('(', "").substringBeforeLast(')')
            if (!containsWord(params, ident)) return null
            val decorators = (n - 1 downTo (n - 6).coerceAtLeast(1)).map { src.lineText(it).trim() }.takeWhile { it.startsWith("@") }
            return if (decorators.any { d -> ROUTE_DECORATORS.any { d.contains(it) } }) n else null
        }
        return null
    }

    fun identifierOf(part: String): String? {
        val t = part.trim().removePrefix("await ").removePrefix("$").removePrefix("this.").removePrefix("self.")
        val end = t.indexOfFirst { !Src.isIdent(it) }
        val ident = if (end < 0) t else t.substring(0, end)
        return ident.takeIf { it.isNotEmpty() && (it.first().isLetter() || it.first() == '_') }
    }

    fun containsWord(text: String, word: String): Boolean = wordIndex(text, word) >= 0

    private fun wordIndex(text: String, word: String): Int {
        var i = text.indexOf(word)
        while (i >= 0) {
            val before = if (i == 0) ' ' else text[i - 1]
            val after = text.getOrElse(i + word.length) { ' ' }
            if (!Src.isIdent(before) && !Src.isIdent(after)) return i
            i = text.indexOf(word, i + 1)
        }
        return -1
    }

    /** Posición del `=` que asigna a [ident] en la línea (`x = …`, `x += …`, `$x .= …`, `String x = …`), o `null`. */
    fun assignmentEquals(line: String, ident: String): Int? {
        var i = 0
        while (i < line.length) {
            val eq = line.indexOf('=', i)
            if (eq < 0) return null
            val prev = line.getOrElse(eq - 1) { ' ' }
            val next = line.getOrElse(eq + 1) { ' ' }
            if (next == '=' || next == '>' || prev == '=' || prev == '!' || prev == '<' || prev == '>') {
                i = eq + 2
                continue
            }
            val lhs = line.substring(0, eq)
            if (!containsWord(lhs, ident) || lhs.contains('(') && !lhs.trimStart().startsWith("const {")) return null
            // El nombre tiene que ser lo último del lado izquierdo (`String sql`, `$sql .`, `sql: String`).
            val trimmed = lhs.trimEnd('+', '.', ' ', '\t').let { t -> if (t.contains(':')) t.substringBefore(':').trimEnd() else t }
            val lastWord = trimmed.trimEnd('?', '!').takeLastWhile { Src.isIdent(it) }
            return if (lastWord == ident || trimmed.endsWith("}")) eq else null
        }
        return null
    }

    private val FORMATTERS = listOf(
        "String.format(", "string.Format(", "String.Format(", "sprintf(", "fmt.Sprintf(", "format!(", "MessageFormat.format(", "vsprintf(",
        "util.format(",
    )
    private val CONSTANT_SUFFIXES = listOf(".length", ".size", ".count", ".size()", ".count()", ".length()", ".Length", ".Count")
    private val CONSTANT_CALLS = listOf("len(", "count(", "sizeof(", "strlen(", "range(", "Math.", "Date.now(", "new Date(", "time.time(", "uuid", "UUID.")
    private val ROUTE_DECORATORS = listOf(".route(", ".get(", ".post(", ".put(", ".delete(", ".patch(", ".api_route(", ".websocket(")
    private const val WINDOW = 80
}

/** Espacios y tabuladores (no saltos de línea). */
internal fun Src.skipWsInline(from: Int): Int {
    var i = from
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
    return i
}

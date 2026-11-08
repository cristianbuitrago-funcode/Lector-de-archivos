package com.funcodestudiocb.analyzer.engine.relations.framework

import com.google.re2j.Pattern

/**
 * Patrones RE2/J de los analizadores de framework (tiempo lineal, el mismo comportamiento en el PC y en Android) y
 * funciones pequeñas sobre fragmentos de texto ya acotados. Sin repeticiones acotadas grandes.
 */
object Regexes {
    val CLASS_NAME: Pattern = Pattern.compile("""\bclass\s+([A-Za-z_$][\w$]*)""")
    val CLASS_DECL: Pattern = Pattern.compile("""\b(class|interface|object)\s+([A-Za-z_]\w*)""")
    val CS_CLASS: Pattern = Pattern.compile("""\bclass\s+([A-Za-z_]\w*)""")
    val REQUEST_METHOD: Pattern = Pattern.compile("""RequestMethod\.([A-Z]+)""")
    val CONTROLLER_GROUP: Pattern = Pattern.compile("""controller\(\s*\\?([A-Za-z_][\w\\]*)::class""")
    val JSX_ELEMENT: Pattern = Pattern.compile("""element\s*=\s*\{\s*<\s*([A-Z][\w.]*)""")
    val JSX_COMPONENT: Pattern = Pattern.compile("""[Cc]omponent\s*=\s*\{\s*([A-Z][\w.]*)""")
    val LEADING_TAG: Pattern = Pattern.compile("""^<\s*([A-Z][\w.]*)""")
    val FORM: Pattern = Pattern.compile("""(?i)<form\b[^>]*>""")
    val HTTP_METHOD_ENUM: Pattern = Pattern.compile("""HttpMethod\.([A-Z]+)""")
    val CS_HTTP_METHOD: Pattern = Pattern.compile("""HttpMethod\.(Get|Post|Put|Patch|Delete|Head|Options)\b""")
    val GD_METHOD: Pattern = Pattern.compile("""METHOD_([A-Z]+)""")
    private val PRINTF = Pattern.compile("""%[-+ 0#]*[0-9]*[sdifxX]""")
    private val BRACES = Pattern.compile("""\{[^{}]*\}""")
    private val FORM_FIELD = Pattern.compile("""(?i)<(?:input|select|textarea)\b[^>]*\bname\s*=\s*["']([^"']+)["']""")
    private val ATTRS = HashMap<String, Pattern>()
    private val SCRIPT_EXT = setOf("php", "asp", "aspx", "jsp", "cgi", "html", "htm", "do", "action", "pl", "py")

    /** `%s`, `%d`… de un formato → parte dinámica. */
    fun printf(value: String): String = PRINTF.matcher(value).replaceAll(DYNAMIC)

    /** `{}` / `{nombre}` de `str.format` → parte dinámica. */
    fun braces(value: String): String = BRACES.matcher(value).replaceAll(DYNAMIC)

    /** `save.php`, `login.aspx`: una ruta a un script del servidor aunque no tenga barras. */
    fun isScriptPath(url: String): Boolean = url.substringBefore('?').substringAfterLast('.', "").lowercase() in SCRIPT_EXT

    /** Valor de un atributo HTML de una etiqueta ya recortada. */
    fun attr(tag: String, name: String): String? {
        val p = synchronized(ATTRS) {
            ATTRS.getOrPut(name) { Pattern.compile("""(?i)\b""" + name + """\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""") }
        }
        val m = p.matcher(tag)
        return if (m.find()) m.group(1) ?: m.group(2) ?: m.group(3) else null
    }

    /** Nombres de los campos de un formulario (hasta `</form>`, acotado). */
    fun formFields(text: String, from: Int): List<String> {
        val end = text.indexOf("</form", from).let { if (it < 0) minOf(text.length, from + 8_000) else minOf(it, from + 8_000) }
        val m = FORM_FIELD.matcher(text.substring(from, end))
        val out = mutableListOf<String>()
        while (m.find() && out.size < 20) {
            val n = m.group(1)
            if (n.length <= 60 && n !in out) out += n
        }
        return out
    }

    val PY_CLASS: Pattern = Pattern.compile("""(?m)^class\s+(\w+)\s*\(([^)]*)\)\s*:""")
    val PY_TABLENAME: Pattern = Pattern.compile("""__tablename__\s*=\s*["']([^"']+)["']""")
    val PY_DB_TABLE: Pattern = Pattern.compile("""\bdb_table\s*=\s*["']([^"']+)["']""")
    val SPRING_REPOSITORY: Pattern = Pattern.compile(
        """\b(?:JpaRepository|CrudRepository|PagingAndSortingRepository|MongoRepository|ListCrudRepository|ReactiveCrudRepository|CoroutineCrudRepository|R2dbcRepository|JpaSpecificationExecutor)\s*<\s*(\w+)""",
    )
    val PARAM_TYPE: Pattern = Pattern.compile("""\(\s*(?:vararg\s+)?\w+\s*:\s*(?:List<|Array<|Collection<)?([A-Z]\w*)|\(\s*(?:final\s+)?(?:List<|Collection<)?([A-Z]\w*)>?\s+\w+""")
    val DBSET: Pattern = Pattern.compile("""DbSet<\s*(\w+)\s*>\??\s+(\w+)""")
    val ELOQUENT_MODEL: Pattern = Pattern.compile("""\bclass\s+(\w+)\s+extends\s+(?:Model|Authenticatable|Pivot|\\?Illuminate\\Database\\Eloquent\\Model)\b""")
    val PHP_TABLE: Pattern = Pattern.compile("""protected\s+\${'$'}table\s*=\s*['"]([^'"]+)['"]""")
    val PRISMA_MODEL: Pattern = Pattern.compile("""(?m)^\s*model\s+(\w+)\s*\{""")
    val PRISMA_MAP: Pattern = Pattern.compile("""@@map\(\s*"([^"]+)"""")

    val KT_PROPERTY_TYPE: Pattern = Pattern.compile("""(?:lateinit\s+)?(?:var|val)\s+\w+\s*:\s*([A-Za-z_][\w.]*)""")
    val JAVA_FIELD_TYPE: Pattern = Pattern.compile("""^(?:(?:private|protected|public|final|static)\s+)*([A-Z][\w.]*)(?:<[^>]*>)?\s+\w+\s*[;=]""")
    val KT_PARAM_TYPE: Pattern = Pattern.compile("""\b\w+\s*:\s*([A-Za-z_][\w.]*)""")
    val JAVA_FINAL_FIELD: Pattern = Pattern.compile("""private\s+final\s+([A-Z][\w.]*)(?:<[^>]*>)?\s+\w+\s*;""")
    val KT_FUN_SIGNATURE: Pattern = Pattern.compile("""fun\s+\w+\s*\(([^)]*)\)\s*:\s*([A-Za-z_][\w.]*)""")
    val JAVA_METHOD_SIGNATURE: Pattern = Pattern.compile("""([A-Z][\w.]*)(?:<[^>]*>)?\s+\w+\s*\(([^)]*)\)""")
    val JAVA_PARAM_TYPE: Pattern = Pattern.compile("""^\s*(?:final\s+)?(?:@\w+(?:\([^)]*\))?\s+)*([A-Z][\w.]*)""")
    val TS_INJECT_TOKEN: Pattern = Pattern.compile("""@Inject\(\s*([A-Za-z_]\w*)""")
    val TS_PARAM_TYPE: Pattern = Pattern.compile(""":\s*([A-Z][\w.]*)""")
    val CS_PARAM_TYPE: Pattern = Pattern.compile("""^\s*(?:\[[^\]]*\]\s*)?([A-Z][\w.]*)(?:<[^>]*>)?\??\s+\w+""")
    val PHP_PARAM_TYPE: Pattern = Pattern.compile("""(?:(?:private|protected|public|readonly)\s+)*\??\\?([A-Z][\w\\]*)\s+\${'$'}\w+""")
    private val BRACE_CLASS = Pattern.compile("""\{\s*([A-Z]\w*)\s*\(""")

    fun firstClassAfterBrace(text: String): String? = BRACE_CLASS.matcher(text).let { if (it.find()) it.group(1) else null }

    private val ARROW_CLASS = Pattern.compile("""=>\s*(?:const\s+|new\s+)?([A-Z]\w*)""")
    private val FIRST_LITERAL = Pattern.compile("""@?"([^"]*)"""")
    private val JSX_ATTR = Pattern.compile("""(?:^|\s)(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)'|\{\s*["'`]([^"'`]*)["'`]\s*\})""")

    /** `prefix('x')` → `x` dentro de una cadena de llamadas. */
    fun firstLiteralArg(chain: String, call: String): String? {
        val i = chain.indexOf(call)
        if (i < 0) return null
        val rest = chain.substring(i + call.length).trimStart()
        val q = rest.firstOrNull() ?: return null
        if (q != '\'' && q != '"') return null
        val end = rest.indexOf(q, 1)
        return if (end > 0) rest.substring(1, end) else null
    }

    /** `['prefix' => 'x', …]` → `x`. */
    fun arrayValue(array: String, key: String): String? {
        for (q in listOf('\'', '"')) {
            val k = array.indexOf("$q$key$q")
            if (k < 0) continue
            val arrow = array.indexOf("=>", k)
            if (arrow < 0) continue
            val rest = array.substring(arrow + 2).trimStart()
            val v = rest.firstOrNull() ?: continue
            if (v != '\'' && v != '"') continue
            val end = rest.indexOf(v, 1)
            if (end > 0) return rest.substring(1, end)
        }
        return null
    }

    /** Ruta de la última `@Anotación("…")` del fragmento (`value =`, `path =`, listas). `""` si no tiene ruta. */
    fun lastMapping(head: String, annotation: String): String? {
        val i = head.lastIndexOf("@$annotation")
        if (i < 0) return null
        val after = i + annotation.length + 1
        if (after < head.length && (head[after].isLetterOrDigit() || head[after] == '_')) return null
        val rest = head.substring(after).trimStart()
        if (!rest.startsWith("(")) return ""
        val close = rest.indexOf(')')
        val inner = if (close > 0) rest.substring(1, close) else rest.substring(1)
        val m = FIRST_LITERAL.matcher(inner)
        return if (m.find()) m.group(1) else ""
    }

    /** Plantilla de `[Atributo("…")]` (o `[A, Atributo("…")]`) del fragmento; `null` si no está. */
    fun lastAttribute(head: String, attribute: String): String? {
        var i = head.lastIndexOf(attribute)
        while (i >= 0) {
            val before = head.substring(0, i).trimEnd().lastOrNull()
            val after = head.getOrNull(i + attribute.length)
            if ((before == '[' || before == ',') && (after == '(' || after == ' ')) {
                val rest = head.substring(i + attribute.length).trimStart()
                if (rest.startsWith("(")) {
                    val close = rest.indexOf(')')
                    val m = FIRST_LITERAL.matcher(if (close > 0) rest.substring(1, close) else rest)
                    if (m.find()) return m.group(1)
                }
                return ""
            }
            i = head.lastIndexOf(attribute, i - 1)
        }
        return null
    }

    fun firstLiteral(text: String): String? = FIRST_LITERAL.matcher(text).let { if (it.find()) it.group(1) else null }

    /** Valor del atributo JSX `name="…"` / `name={'…'}`. */
    fun jsxAttr(attrs: String, name: String): String? {
        val m = JSX_ATTR.matcher(attrs)
        while (m.find()) {
            if (m.group(1) == name) return m.group(2) ?: m.group(3) ?: m.group(4)
        }
        return null
    }

    /** Primera clase construida después de `=>` (`(context) => const HomeScreen()`). */
    fun firstClassAfterArrow(text: String): String? = ARROW_CLASS.matcher(text).let { if (it.find()) it.group(1) else null }

    /**
     * Bloque de anotaciones o atributos justo encima de la declaración en [pos]: las líneas anteriores que empiezan
     * por `@` o `[`, o continúan una de ellas.
     */
    fun annotationBlock(text: String, pos: Int): String {
        var lineStart = text.lastIndexOf('\n', pos - 1) + 1
        var start = lineStart
        var guard = 0
        while (lineStart > 0 && guard++ < 30) {
            val prevEnd = lineStart - 1
            val prevStart = text.lastIndexOf('\n', prevEnd - 1) + 1
            val line = text.substring(prevStart, prevEnd).trim()
            val continuation = line.isEmpty() || line.startsWith("@") || line.startsWith("[") || line.startsWith(")") || line.startsWith("\"") ||
                line.endsWith(",") || line.endsWith("(")
            if (!continuation) break
            start = prevStart
            lineStart = prevStart
        }
        return text.substring(start, pos)
    }
}

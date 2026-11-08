package com.funcodestudiocb.analyzer.engine.languages

import com.google.re2j.Pattern

/**
 * Importación encontrada en el código: el módulo tal como se escribe, la línea (desde 1), los símbolos que se importan
 * (vacío si se importa el módulo entero) y si es una importación de todo el contenido (`*`, `using Espacio;`…).
 * [extends] marca la herencia declarada como importación (`extends "res://x.gd"` en GDScript).
 */
data class ImportRef(
    val module: String,
    val line: Int,
    val symbols: List<String> = emptyList(),
    val wildcard: Boolean = false,
    val extends: Boolean = false,
    /** Dentro de un bloque `#if`/`#ifdef`: puede no compilarse con la configuración actual. */
    val conditional: Boolean = false,
)

/** Supertipo declarado en la cabecera de una clase. [implementation] si la sintaxis indica una interfaz. */
data class Supertype(val name: String, val implementation: Boolean)

/**
 * Complejidad ciclomática de una función: 1 + los puntos de decisión que contiene (los de una función anidada cuentan
 * para la anidada). [line] y [endLine] son la primera y la última línea de la función.
 */
data class FunctionMeasure(val name: String, val line: Int, val endLine: Int, val complexity: Int) {
    val lines: Int get() = endLine - line + 1
}

/** Símbolo declarado en el nivel superior de un archivo, con su línea y sus supertipos. */
data class Declaration(val name: String, val kind: SymbolKind, val line: Int, val supertypes: List<Supertype> = emptyList())

/**
 * Extrae importaciones con sus símbolos, declaraciones de nivel superior y paquetes o espacios de nombres a partir de
 * los nodos que devuelve tree-sitter (tipo, rango y rango del nombre). Las expresiones son de RE2/J y se aplican solo
 * al texto de cada nodo (acotado), nunca al archivo completo.
 */
internal object SymbolExtractor {
    const val MAX_IMPORTS = 500
    const val MAX_DECLARATIONS = 500
    private const val MAX_NODE_TEXT = 2_000
    private const val MAX_HEADER = 800
    private const val MAX_SYMBOLS = 50
    private const val MAX_NAME = 200

    const val MAX_FUNCTIONS = 5_000

    /**
     * [functions]: complejidad de cada función (vacío si el lenguaje no la mide); [topLevelDecisions], puntos de
     * decisión fuera de cualquier función (código de nivel superior de un script).
     */
    class Result(
        val imports: List<ImportRef>,
        val declarations: List<Declaration>,
        val namespaces: List<String>,
        val functions: List<FunctionMeasure> = emptyList(),
        val topLevelDecisions: Int = 0,
    )

    private class Node(val type: Int, val start: Int, val end: Int, val nameStart: Int, val nameEnd: Int)

    fun extract(language: Language, utf8: ByteArray, nodes: IntArray, lineOf: (Int) -> Int): Result {
        val importCount = language.importTypes.size
        val declarationTypes = language.declarationTypes.entries.toList()
        val namespaceOffset = importCount + declarationTypes.size
        val conditionalOffset = namespaceOffset + language.namespaceTypes.size
        val complexity = language.complexity
        val functionOffset = conditionalOffset + language.conditionalTypes.size
        val decisionOffset = functionOffset + (complexity?.functions?.size ?: 0)
        val operatorOffset = decisionOffset + (complexity?.decisions?.size ?: 0)
        val functionNodes = mutableListOf<Node>()
        val decisionStarts = IntList()
        // El puente nativo da cada nodo con el primer tipo pedido que coincide: una declaración que también es función
        // (`function_declaration`) llega como declaración y se cuenta además como función.
        val declarationAsFunction = IntArray(declarationTypes.size) { i -> complexity?.functions?.indexOf(declarationTypes[i].key) ?: -1 }
        val imports = mutableListOf<ImportRef>()
        val importStarts = mutableListOf<Int>()
        val declarationNodes = mutableListOf<Node>()
        val namespaces = mutableListOf<String>()
        val conditionals = mutableListOf<Node>()
        var i = 0
        while (i + 4 < nodes.size) {
            val node = Node(nodes[i], nodes[i + 1], nodes[i + 2], nodes[i + 3], nodes[i + 4])
            i += 5
            if (node.start < 0 || node.end > utf8.size || node.end < node.start) continue
            when {
                node.type < importCount -> if (imports.size < MAX_IMPORTS) {
                    parseImport(language, text(utf8, node.start, node.end), lineOf(node.start)).forEach {
                        imports += it
                        importStarts += node.start
                    }
                }
                node.type < namespaceOffset -> {
                    declarationNodes += node
                    val f = declarationAsFunction[node.type - importCount]
                    if (f >= 0) functionNodes += Node(functionOffset + f, node.start, node.end, node.nameStart, node.nameEnd)
                }
                node.type < conditionalOffset -> namespaceName(language, utf8, node)?.let { if (namespaces.size < 20 && it !in namespaces) namespaces += it }
                node.type < functionOffset -> if (conditionals.size < 10_000) conditionals += node
                node.type < decisionOffset -> functionNodes += node
                node.type < operatorOffset -> if (!isDefaultBranch(utf8, node)) decisionStarts.add(node.start)
                else -> decisionStarts.add(node.start)
            }
        }
        if (conditionals.isNotEmpty()) {
            for (k in imports.indices) {
                val start = importStarts[k]
                if (conditionals.any { start > it.start && start < it.end }) imports[k] = imports[k].copy(conditional = true)
            }
        }
        val declarations = topLevel(declarationNodes).mapNotNull { node ->
            val type = declarationTypes[node.type - importCount]
            declaration(language, type.key, type.value, utf8, node, lineOf)
        }.take(MAX_DECLARATIONS)
        if (complexity == null) return Result(imports.take(MAX_IMPORTS), declarations, namespaces)
        val (functions, topLevel) = measure(language, utf8, functionNodes, decisionStarts.toArray(), lineOf) { complexity.functions[it.type - functionOffset] }
        return Result(imports.take(MAX_IMPORTS), declarations, namespaces, functions, topLevel)
    }

    // ------------------------------------------------------------------ complejidad

    /** Rama por defecto (`default:`, `else ->`, `_ =>`): no abre un camino nuevo. */
    private fun isDefaultBranch(utf8: ByteArray, node: Node): Boolean {
        val head = text(utf8, node.start, node.end, 12).trimStart()
        fun word(w: String) = head.startsWith(w) && (head.length == w.length || !(head[w.length].isLetterOrDigit() || head[w.length] == '_'))
        return word("default") || word("else") || head == "_" || head.startsWith("_ ") || head.startsWith("_=")
    }

    /**
     * Asigna cada punto de decisión a la función más interior que lo contiene (barrido por posición) y calcula la
     * complejidad de cada función como 1 + sus decisiones.
     */
    private fun measure(
        language: Language,
        utf8: ByteArray,
        functionNodes: List<Node>,
        decisions: IntArray,
        lineOf: (Int) -> Int,
        typeOf: (Node) -> String,
    ): Pair<List<FunctionMeasure>, Int> {
        val functions = functionNodes.sortedWith(compareBy<Node>({ it.start }, { -it.end })).take(MAX_FUNCTIONS)
        val counts = IntArray(functions.size)
        decisions.sort()
        val stack = ArrayList<Int>()
        var next = 0
        var topLevel = 0
        for (offset in decisions) {
            while (next < functions.size && functions[next].start <= offset) {
                while (stack.isNotEmpty() && functions[stack.last()].end <= functions[next].start) stack.removeAt(stack.size - 1)
                stack += next
                next++
            }
            while (stack.isNotEmpty() && functions[stack.last()].end <= offset) stack.removeAt(stack.size - 1)
            if (stack.isEmpty()) topLevel++ else counts[stack.last()]++
        }
        val out = functions.mapIndexed { i, node ->
            val name = functionName(language, typeOf(node), utf8, node) ?: ANONYMOUS
            FunctionMeasure(name.take(MAX_NAME), lineOf(node.start), lineOf(maxOf(node.start, node.end - 1)), 1 + counts[i])
        }
        return out to topLevel
    }

    private const val ANONYMOUS = "(anónima)"
    private val ASSIGNED = Pattern.compile("""([A-Za-z_$][\w$]*)\s*(?:=|:)\s*(?:async\s*)?$""")
    private val SIGNATURE = Pattern.compile("""([A-Za-z_]\w*)\s*(?:<[^<>]*>)?\s*\([^()]*\)\s*(?:async\*?|sync\*)?\s*(?:=>)?\s*$""")
    private val ACCESSOR = Pattern.compile("""\b(?:get|set)\s+([A-Za-z_]\w*)\s*(?:\([^()]*\))?\s*$""")

    /**
     * Nombre de una función: el campo `name` del árbol; si no lo tiene, el de su cabecera (`fun nombre`, `int nombre(`);
     * si es anónima, la variable o propiedad a la que se asigna (`const Usuarios = () => …`); en Dart, la firma que
     * precede al cuerpo. `null` si no tiene nombre.
     */
    private fun functionName(language: Language, type: String, utf8: ByteArray, node: Node): String? {
        if (node.nameStart >= 0 && node.nameEnd > node.nameStart && node.nameEnd <= utf8.size) {
            val name = text(utf8, node.nameStart, node.nameEnd, MAX_NAME).trim()
            if (IDENTIFIER.matcher(name).matches()) return name
        }
        val full = text(utf8, node.start, node.end, MAX_HEADER)
        if (type == "function_definition" && language.id in setOf("c", "cpp")) {
            val header = full.substringBefore('{')
            val paren = header.indexOf('(')
            if (paren > 0) header.substring(0, paren).trimEnd().takeLastWhile { it.isLetterOrDigit() || it == '_' }.ifEmpty { null }?.let { return it }
        }
        if (type == "getter" || type == "setter") return if (type == "getter") "get" else "set"
        FALLBACK_NAMES[type]?.matcher(full.substringBefore('{'))?.let { m -> if (m.find()) return m.group(1) }
        // Lo que precede a la función en su línea o en la anterior (asignación o firma).
        val from = maxOf(0, node.start - MAX_HEADER)
        val before = String(utf8, from, node.start - from, Charsets.UTF_8).takeLast(MAX_HEADER).trimEnd()
        for (pattern in listOf(ACCESSOR, SIGNATURE, ASSIGNED)) {
            val m = pattern.matcher(before)
            if (m.find()) {
                val name = m.group(1)
                if (name !in NOT_NAMES) return name
            }
        }
        return null
    }

    private val NOT_NAMES = setOf("if", "for", "while", "switch", "catch", "return", "function", "async", "await", "new", "else", "yield")

    private fun text(utf8: ByteArray, start: Int, end: Int, max: Int = MAX_NODE_TEXT): String =
        String(utf8, start, minOf(end, start + max) - start, Charsets.UTF_8)

    /** Declaraciones que no están dentro de otra declaración (miembros y variables locales fuera). */
    private fun topLevel(nodes: List<Node>): List<Node> {
        val sorted = nodes.sortedWith(compareBy<Node>({ it.start }, { -it.end }))
        val out = mutableListOf<Node>()
        var openEnd = -1
        for (node in sorted) {
            if (node.start < openEnd) continue // dentro de la anterior de nivel superior
            out += node
            openEnd = node.end
        }
        return out
    }

    // ------------------------------------------------------------------ declaraciones

    private val IDENTIFIER = Pattern.compile("""^[A-Za-z_$][\w$]*$""")
    private val FALLBACK_NAMES = mapOf(
        "class_declaration" to Pattern.compile("""\b(?:class|interface)\s+([A-Za-z_$][\w$]*)"""),
        "object_declaration" to Pattern.compile("""\bobject\s+([A-Za-z_]\w*)"""),
        "function_declaration" to Pattern.compile("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?([A-Za-z_]\w*)"""),
        "property_declaration" to Pattern.compile("""\b(?:val|var)\s+([A-Za-z_]\w*)"""),
        "type_alias" to Pattern.compile("""\b(?:typealias|typedef)\s+([A-Za-z_]\w*)"""),
        "class_name_statement" to Pattern.compile("""\bclass_name\s+([A-Za-z_]\w*)"""),
        "function_definition" to Pattern.compile("""([A-Za-z_]\w*)\s*\("""),
        "struct_specifier" to Pattern.compile("""\bstruct\s+([A-Za-z_]\w*)"""),
        "class_specifier" to Pattern.compile("""\bclass\s+([A-Za-z_]\w*)"""),
    )

    private fun declaration(language: Language, type: String, kind: SymbolKind, utf8: ByteArray, node: Node, lineOf: (Int) -> Int): Declaration? {
        val full = text(utf8, node.start, node.end, MAX_HEADER)
        // C y C++: una estructura sin cuerpo es un uso (`struct punto p;`), no una definición.
        if (type == "struct_specifier" || type == "class_specifier") {
            if (!full.contains('{')) return null
        }
        var name = if (node.nameStart >= 0 && node.nameEnd > node.nameStart && node.nameEnd <= utf8.size) {
            text(utf8, node.nameStart, node.nameEnd, MAX_NAME).trim()
        } else {
            null
        }
        if (name == null || (language.id in setOf("c", "cpp") && type == "function_definition")) {
            val header = full.substringBefore('{')
            val pattern = FALLBACK_NAMES[type] ?: return null
            val m = pattern.matcher(header)
            if (type == "function_definition" && language.id in setOf("c", "cpp")) {
                // El último identificador antes del primer paréntesis (`int Foo::bar(` → bar).
                val paren = header.indexOf('(')
                if (paren < 0) return null
                val before = header.substring(0, paren).trimEnd()
                name = before.takeLastWhile { it.isLetterOrDigit() || it == '_' }.ifEmpty { return null }
            } else if (m.find()) {
                name = m.group(1)
            }
        }
        val n = name ?: return null
        if (!IDENTIFIER.matcher(n).matches()) return null
        val header = header(language, utf8, if (node.nameEnd > node.nameStart && node.nameStart >= 0) node.nameEnd else node.start, node.end)
        var refined = kind
        if (language.id == "kotlin" && type == "class_declaration") {
            val words = full.substringBefore(n).trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }
            refined = when {
                words.lastOrNull() == "interface" -> SymbolKind.INTERFACE
                words.takeLast(2) == listOf("enum", "class") -> SymbolKind.ENUM
                else -> SymbolKind.CLASS
            }
        }
        val supertypes = if (refined == SymbolKind.CLASS || refined == SymbolKind.INTERFACE || refined == SymbolKind.OBJECT || refined == SymbolKind.ENUM) {
            Supertypes.parse(language, header, refined == SymbolKind.INTERFACE)
        } else {
            emptyList()
        }
        return Declaration(n, refined, lineOf(node.start), supertypes)
    }

    /**
     * Cabecera de una declaración desde el final del nombre hasta el inicio del cuerpo (`{`, o `:` en Python), sin
     * contar lo que hay entre paréntesis, corchetes o comillas.
     */
    private fun header(language: Language, utf8: ByteArray, from: Int, end: Int): String {
        val limit = minOf(end, from + MAX_HEADER)
        val text = String(utf8, from, limit - from, Charsets.UTF_8)
        var depth = 0
        var quote: Char? = null
        for ((i, ch) in text.withIndex()) {
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '(' || ch == '[' -> depth++
                ch == ')' || ch == ']' -> depth--
                depth == 0 && ch == '{' -> return text.substring(0, i)
                depth == 0 && ch == ':' && language.id == "python" -> return text.substring(0, i)
                depth == 0 && ch == ';' -> return text.substring(0, i)
            }
        }
        return text
    }

    private val PACKAGE = Pattern.compile("""\b(?:package|namespace)\s+([A-Za-z_][\w.\\:]*)""")

    private fun namespaceName(language: Language, utf8: ByteArray, node: Node): String? {
        val raw = if (node.nameStart >= 0 && node.nameEnd > node.nameStart) {
            text(utf8, node.nameStart, node.nameEnd, MAX_NAME)
        } else {
            val m = PACKAGE.matcher(text(utf8, node.start, node.end, 400))
            if (!m.find()) return null
            m.group(1)
        }
        val name = raw.trim().trimEnd(';').trim()
        return name.takeIf { it.isNotEmpty() && it.length <= MAX_NAME && it.none { ch -> ch.isWhitespace() } }
    }

    // ------------------------------------------------------------------ importaciones

    private val QUOTED = Pattern.compile("""["'`]([^"'`\r\n]+)["'`]""")
    private val ANGLED = Pattern.compile("""<([^<>\r\n]+)>""")
    private val JS_NAMED = Pattern.compile("""\{([^}]*)\}""")
    private val JS_DEFAULT = Pattern.compile("""^\s*import\s+(?:type\s+)?([A-Za-z_$][\w$]*)\s*(?:,|\s+from\b)""")
    private val JS_NAMESPACE = Pattern.compile("""\*\s*(?:as\s+[A-Za-z_$][\w$]*)?""")
    private val PY_FROM = Pattern.compile("""^\s*from\s+([.\w]+)\s+import\s+(.+)$""")
    private val PY_IMPORT = Pattern.compile("""^\s*import\s+(.+)$""")
    private val JVM_IMPORT = Pattern.compile("""^\s*import\s+(static\s+)?([A-Za-z_][\w.]*?)(\.\*)?\s*(?:\bas\s+\w+)?\s*;?\s*$""")
    private val CS_USING = Pattern.compile("""^\s*(?:global\s+)?using\s+(static\s+)?(?:([A-Za-z_]\w*)\s*=\s*)?([A-Za-z_][\w.]*)""")
    private val PHP_USE = Pattern.compile("""^\s*use\s+(function\s+|const\s+)?(.+?);?\s*$""")
    private val DART_SHOW = Pattern.compile("""\bshow\s+([\w\s,]+)""")

    private fun names(list: String): List<String> = list.split(',')
        .map { it.trim().removePrefix("type ").trim().substringBefore(" as ").trim() }
        .filter { IDENTIFIER.matcher(it).matches() }
        .take(MAX_SYMBOLS)

    private fun parseImport(language: Language, raw: String, line: Int): List<ImportRef> {
        val text = raw.replace('\n', ' ').replace('\r', ' ')
        return when (language.id) {
            "javascript", "typescript", "tsx" -> {
                val trimmed = text.trimStart()
                // Un `export` sin `from` (export const x…) no es una importación.
                if (trimmed.startsWith("export") && !text.contains(" from ")) return emptyList()
                val m = QUOTED.matcher(text)
                if (!m.find()) return emptyList()
                val module = m.group(1)
                val head = text.substring(0, m.start())
                val symbols = mutableListOf<String>()
                JS_NAMED.matcher(head).let { if (it.find()) symbols += names(it.group(1)) }
                JS_DEFAULT.matcher(head).let { if (it.find()) symbols += "default" }
                val wildcard = JS_NAMESPACE.matcher(head).find() && !head.contains("{")
                listOf(ImportRef(module, line, symbols.distinct(), wildcard))
            }
            "python" -> {
                val from = PY_FROM.matcher(text)
                if (from.find()) {
                    val list = from.group(2).trim().removePrefix("(").removeSuffix(")")
                    val wildcard = list.trim() == "*"
                    listOf(ImportRef(from.group(1), line, if (wildcard) emptyList() else names(list), wildcard))
                } else {
                    val imp = PY_IMPORT.matcher(text)
                    if (!imp.find()) return emptyList()
                    imp.group(1).split(',').map { it.trim().substringBefore(' ') }.filter { it.isNotEmpty() }.map { ImportRef(it, line) }
                }
            }
            "java", "kotlin" -> {
                val m = JVM_IMPORT.matcher(text.trim())
                if (!m.find()) return emptyList()
                val static = m.group(1) != null
                val path = m.group(2)
                val wildcard = m.group(3) != null
                when {
                    wildcard -> listOf(ImportRef(path, line, emptyList(), wildcard = true))
                    static -> listOf(ImportRef(path.substringBeforeLast('.'), line, listOf(path.substringAfterLast('.'))))
                    else -> listOf(ImportRef(path, line, listOf(path.substringAfterLast('.'))))
                }
            }
            "csharp" -> {
                val m = CS_USING.matcher(text)
                if (!m.find()) return emptyList()
                val path = m.group(3)
                when {
                    m.group(1) != null || m.group(2) != null -> listOf(ImportRef(path, line, listOf(path.substringAfterLast('.'))))
                    else -> listOf(ImportRef(path, line, emptyList(), wildcard = true))
                }
            }
            "php" -> {
                val trimmed = text.trimStart()
                if (!trimmed.startsWith("use")) {
                    val quoted = QUOTED.matcher(text)
                    return if (quoted.find()) listOf(ImportRef(quoted.group(1), line)) else emptyList()
                }
                val m = PHP_USE.matcher(text)
                if (!m.find()) return emptyList()
                val body = m.group(2).trim()
                val brace = body.indexOf('{')
                if (brace >= 0) {
                    val prefix = body.substring(0, brace).trim().trimEnd('\\')
                    body.substring(brace + 1).substringBefore('}').split(',').mapNotNull { item ->
                        val name = item.trim().substringBefore(" as ").trim().removePrefix("function ").removePrefix("const ").trim()
                        if (name.isEmpty()) null else ImportRef("$prefix\\$name".trimStart('\\'), line, listOf(name.substringAfterLast('\\')))
                    }
                } else {
                    body.split(',').mapNotNull { item ->
                        val name = item.trim().substringBefore(" as ").trim().trimStart('\\')
                        if (name.isEmpty()) null else ImportRef(name, line, listOf(name.substringAfterLast('\\')))
                    }
                }
            }
            "dart" -> {
                val m = QUOTED.matcher(text)
                if (!m.find()) return emptyList()
                val show = DART_SHOW.matcher(text)
                if (show.find()) listOf(ImportRef(m.group(1), line, names(show.group(1)))) else listOf(ImportRef(m.group(1), line, emptyList(), wildcard = true))
            }
            "c", "cpp" -> {
                val angled = ANGLED.matcher(text)
                val quoted = QUOTED.matcher(text)
                when {
                    quoted.find() -> listOf(ImportRef(quoted.group(1), line))
                    angled.find() -> listOf(ImportRef("<" + angled.group(1) + ">", line))
                    else -> emptyList()
                }
            }
            "css" -> {
                val m = QUOTED.matcher(text)
                if (m.find()) listOf(ImportRef(m.group(1), line)) else emptyList()
            }
            "gdscript" -> {
                val quoted = QUOTED.matcher(text)
                if (quoted.find()) {
                    listOf(ImportRef(quoted.group(1), line, extends = true))
                } else {
                    val name = text.trim().removePrefix("extends").trim().substringBefore(' ').substringBefore('.')
                    if (IDENTIFIER.matcher(name).matches()) listOf(ImportRef(name, line, extends = true)) else emptyList()
                }
            }
            else -> emptyList()
        }
    }
}

/** Supertipos de la cabecera de una clase, según la sintaxis de cada lenguaje. */
internal object Supertypes {
    private const val MAX = 20
    private val EXTENDS = Pattern.compile("""\bextends\s+(.+?)(?:\bimplements\b|\bwith\b|\bpermits\b|$)""")
    private val IMPLEMENTS = Pattern.compile("""\bimplements\s+(.+?)(?:\bpermits\b|$)""")
    private val WITH = Pattern.compile("""\bwith\s+(.+?)(?:\bimplements\b|$)""")
    private val TYPE_NAME = Pattern.compile("""^\s*(?:@[\w.]+\s+)*(?:(?:public|private|protected|internal|virtual)\s+)*([A-Za-z_\\][\w.\\:$]*)""")

    /** Quita lo que hay entre `<…>` o `(…)` (genéricos y argumentos del constructor). */
    private fun strip(text: String): String {
        val out = StringBuilder()
        var angle = 0
        var paren = 0
        for (ch in text) {
            when (ch) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                else -> if (angle == 0 && paren == 0) out.append(ch)
            }
        }
        return out.toString()
    }

    private fun splitNames(list: String): List<String> = strip(list).split(',').mapNotNull { part ->
        val m = TYPE_NAME.matcher(part)
        if (m.find()) m.group(1).trimEnd('.').takeIf { it.isNotEmpty() && it != "object" } else null
    }

    fun parse(language: Language, header: String, isInterface: Boolean): List<Supertype> {
        val h = header.replace('\n', ' ')
        val out = mutableListOf<Supertype>()
        when (language.id) {
            "java", "typescript", "tsx", "javascript", "php", "dart" -> {
                EXTENDS.matcher(h).let { m -> if (m.find()) splitNames(m.group(1)).forEach { out += Supertype(it, implementation = false) } }
                if (language.id == "dart") WITH.matcher(h).let { m -> if (m.find()) splitNames(m.group(1)).forEach { out += Supertype(it, implementation = false) } }
                IMPLEMENTS.matcher(h).let { m -> if (m.find()) splitNames(m.group(1)).forEach { out += Supertype(it, implementation = true) } }
                if (language.id == "javascript" && out.isEmpty().not()) {
                    // `extends mezcla(Base)`: solo vale el nombre al principio de la expresión.
                    return out.take(1)
                }
            }
            "kotlin" -> {
                // `(val x: Int) : Base(), Interfaz by delegado` — con llamada al constructor es la superclase.
                val colon = stripKeepParens(h).indexOf(':')
                if (colon >= 0) {
                    val list = stripKeepParens(h).substring(colon + 1).substringBefore(" where ")
                    splitTopLevel(list).forEach { part ->
                        val name = splitNames(part.substringBefore(" by ")).firstOrNull() ?: return@forEach
                        out += Supertype(name, implementation = !part.contains('(') && !isInterface)
                    }
                }
            }
            "csharp" -> {
                val stripped = strip(h)
                val colon = stripped.indexOf(':')
                if (colon >= 0) {
                    splitNames(stripped.substring(colon + 1).substringBefore(" where ")).forEach { name ->
                        val simple = name.substringAfterLast('.')
                        // Convención de .NET: las interfaces empiezan por I seguida de mayúscula.
                        out += Supertype(name, implementation = simple.length > 1 && simple[0] == 'I' && simple[1].isUpperCase())
                    }
                }
            }
            "python" -> {
                val open = h.indexOf('(')
                val close = h.lastIndexOf(')')
                if (open >= 0 && close > open) {
                    splitTopLevel(h.substring(open + 1, close)).filter { !it.contains('=') }.forEach { part ->
                        val m = TYPE_NAME.matcher(part)
                        if (m.find()) m.group(1).takeIf { it != "object" }?.let { out += Supertype(it, implementation = false) }
                    }
                }
            }
            "cpp" -> {
                val colon = h.indexOf(':')
                if (colon >= 0 && !h.startsWith("::")) splitNames(h.substring(colon + 1)).forEach { out += Supertype(it, implementation = false) }
            }
        }
        return out.distinctBy { it.name }.take(MAX)
    }

    /** Quita solo los genéricos (conserva los paréntesis para distinguir la superclase en Kotlin). */
    private fun stripKeepParens(text: String): String {
        val out = StringBuilder()
        var angle = 0
        var paren = 0
        for (ch in text) {
            when (ch) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> {
                    paren++
                    if (angle == 0 && paren == 1) out.append(ch)
                }
                ')' -> {
                    if (paren > 0) paren--
                    if (angle == 0 && paren == 0) out.append(ch)
                }
                else -> if (angle == 0 && paren == 0) out.append(ch)
            }
        }
        return out.toString()
    }

    /** Divide por comas que no están dentro de paréntesis ni genéricos. */
    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for ((i, ch) in text.withIndex()) {
            when (ch) {
                '(', '<', '[' -> depth++
                ')', '>', ']' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    parts += text.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += text.substring(start)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * Declaraciones de nivel superior buscadas línea a línea. Se usa para completar las del árbol cuando el archivo tiene
 * errores de sintaxis: la gramática puede no entender código válido (en Kotlin, `var open by …`) y el resto del
 * archivo queda dentro de un nodo de error.
 */
internal object LexicalDeclarations {
    private val PATTERNS: Map<String, Pattern> = mapOf(
        "kotlin" to Pattern.compile(
            """(?m)^(?:(?:public|private|internal|protected|open|abstract|sealed|data|inline|value|enum|annotation|suspend|operator|infix|""" +
                """tailrec|external|const|lateinit|override|expect|actual|fun)\s+)*(fun|class|interface|object|val|var|typealias)\s+(?:<[^>\n]*>\s*)?""" +
                """(?:[\w.]+\.)?([A-Za-z_]\w*)([^\n]*)""",
        ),
        "java" to Pattern.compile("""(?m)^(?:(?:public|private|protected|abstract|final|static|sealed|non-sealed|strictfp)\s+)*(class|interface|enum|record|@interface)\s+([A-Za-z_]\w*)([^\n]*)"""),
        "javascript" to Pattern.compile("""(?m)^(?:export\s+(?:default\s+)?)?(?:async\s+)?(function\*?|class|const|let|var)\s+([A-Za-z_$][\w$]*)([^\n]*)"""),
        "typescript" to Pattern.compile("""(?m)^(?:export\s+(?:default\s+)?)?(?:declare\s+)?(?:abstract\s+)?(?:async\s+)?(function\*?|class|interface|type|enum|const|let|var)\s+([A-Za-z_$][\w$]*)([^\n]*)"""),
        "python" to Pattern.compile("""(?m)^(?:async\s+)?(def|class)\s+([A-Za-z_]\w*)([^\n]*)"""),
        "php" to Pattern.compile("""(?m)^(?:(?:abstract|final|readonly)\s+)*(class|interface|trait|enum|function)\s+([A-Za-z_]\w*)([^\n]*)"""),
        "dart" to Pattern.compile("""(?m)^(?:(?:abstract|sealed|base|final|interface|mixin)\s+)*(class|mixin|enum|extension|typedef)\s+([A-Za-z_]\w*)([^\n]*)"""),
        "csharp" to Pattern.compile("""(?m)^[ \t]*(?:(?:public|internal|private|protected|static|abstract|sealed|partial|readonly)\s+)*(class|interface|struct|enum|record)\s+([A-Za-z_]\w*)([^\n]*)"""),
    )

    private fun kind(keyword: String): SymbolKind = when (keyword) {
        "class", "record", "struct", "mixin", "extension", "trait" -> SymbolKind.CLASS
        "interface", "@interface" -> SymbolKind.INTERFACE
        "enum" -> SymbolKind.ENUM
        "object" -> SymbolKind.OBJECT
        "type", "typealias", "typedef" -> SymbolKind.TYPE
        "val", "var", "const", "let" -> SymbolKind.CONSTANT
        else -> SymbolKind.FUNCTION
    }

    fun scan(language: Language, text: String, inComment: (Int) -> Boolean, lineOf: (Int) -> Int): List<Declaration> {
        val id = if (language.id == "tsx") "typescript" else language.id
        val pattern = PATTERNS[id] ?: return emptyList()
        val out = mutableListOf<Declaration>()
        val m = pattern.matcher(text)
        while (m.find() && out.size < SymbolExtractor.MAX_DECLARATIONS) {
            if (inComment(m.start())) continue
            val keyword = m.group(1)
            var k = kind(keyword)
            if (language.id == "kotlin" && keyword == "class" && text.regionMatches(maxOf(0, m.start(1) - 5), "enum ", 0, 5)) k = SymbolKind.ENUM
            val rest = m.group(3).substringBefore('{').substringBefore('=')
            val supertypes = if (k == SymbolKind.CLASS || k == SymbolKind.INTERFACE || k == SymbolKind.OBJECT) Supertypes.parse(language, rest, k == SymbolKind.INTERFACE) else emptyList()
            out += Declaration(m.group(2), k, lineOf(m.start()), supertypes)
        }
        return out
    }
}


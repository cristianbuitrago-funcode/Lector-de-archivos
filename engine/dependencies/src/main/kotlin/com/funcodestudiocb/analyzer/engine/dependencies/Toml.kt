package com.funcodestudiocb.analyzer.engine.dependencies

import com.funcodestudiocb.analyzer.engine.api.DependencyScope
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.dependencies.ManifestParsers.addDependency
import com.google.re2j.Pattern

/**
 * Lectura parcial de TOML por líneas, suficiente para las tablas de dependencias (pyproject.toml, Pipfile,
 * Cargo.toml y catálogos de versiones de Gradle). Reconoce tablas `[a.b]`, pares `clave = valor`, tablas en línea
 * y listas que ocupan varias líneas. No es un lector completo de TOML: lo que no entiende lo omite.
 */
internal object Toml {
    private const val MAX_VALUE_CHARS = 64_000
    private val HEADER = Pattern.compile("""^\s*\[\[?\s*([^\]]+?)\s*\]\]?\s*(?:#.*)?$""")
    private val KEY_VALUE = Pattern.compile("""^\s*("[^"]+"|'[^']+'|[A-Za-z0-9_.\-]+)\s*=\s*(.*)$""")
    private val STRING = Pattern.compile(""""([^"\r\n]*)"|'([^'\r\n]*)'""")
    private val INLINE_VERSION = Pattern.compile("""\bversion\s*=\s*"([^"]*)"""")
    private val INLINE_PACKAGE = Pattern.compile("""\bpackage\s*=\s*"([^"]*)"""")

    /** Quita las comillas de una tabla o clave (`target.'cfg(unix)'.dependencies`). */
    private fun unquote(name: String): String = name.replace("\"", "").replace("'", "").replace(" ", "")

    /** Recorre los pares clave = valor; [onEntry] recibe (tabla, clave, valor, línea de la clave). */
    fun entries(text: String, onEntry: (String, String, String, Int) -> Unit) {
        var table = ""
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val lineNumber = i + 1
            i++
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val header = HEADER.matcher(line)
            if (trimmed.startsWith("[") && header.matches()) {
                table = unquote(header.group(1))
                continue
            }
            val kv = KEY_VALUE.matcher(line)
            if (!kv.matches()) continue
            val key = unquote(kv.group(1))
            val value = StringBuilder(stripComment(kv.group(2)))
            // Listas y tablas en línea que siguen en las líneas siguientes.
            var balance = balance(value)
            while (balance > 0 && i < lines.size && value.length < MAX_VALUE_CHARS) {
                val next = stripComment(lines[i])
                value.append('\n').append(next)
                balance += balance(next)
                i++
            }
            onEntry(table, key, value.toString(), lineNumber)
        }
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        for ((k, ch) in line.withIndex()) {
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '#' -> return line.substring(0, k).trimEnd()
            }
        }
        return line.trimEnd()
    }

    private fun balance(text: CharSequence): Int {
        var depth = 0
        var quote: Char? = null
        for (ch in text) {
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '[' || ch == '{' -> depth++
                ch == ']' || ch == '}' -> depth--
            }
        }
        return depth
    }

    /** Cadenas de una lista, con la línea de cada una. */
    private fun strings(value: String, firstLine: Int): List<Pair<String, Int>> {
        val m = STRING.matcher(value)
        val out = mutableListOf<Pair<String, Int>>()
        while (m.find()) {
            val line = firstLine + value.substring(0, m.start()).count { it == '\n' }
            out += (m.group(1) ?: m.group(2)) to line
        }
        return out
    }

    /** Versión de `"1.0"` o de `{ version = "1.0", … }`. */
    private fun version(value: String): String? {
        val v = value.trim()
        if (v.startsWith("\"") || v.startsWith("'")) return strings(v, 0).firstOrNull()?.first
        val m = INLINE_VERSION.matcher(v)
        return if (m.find()) m.group(1) else null
    }

    private fun groupScope(group: String, default: DependencyScope): DependencyScope {
        val g = group.lowercase()
        return when {
            g.contains("test") -> DependencyScope.TEST
            g.contains("dev") || g.contains("lint") || g.contains("doc") -> DependencyScope.DEVELOPMENT
            else -> default
        }
    }

    fun pyproject(path: String, text: String): ParsedManifest {
        val out = mutableListOf<RawDependency>()
        fun pepList(value: String, line: Int, scope: DependencyScope) {
            strings(value, line).forEach { (spec, l) -> ManifestParsers.pep508(spec)?.let { (name, version) -> out.addDependency(name, version, scope, l) } }
        }
        entries(text) { table, key, value, line ->
            when {
                table == "project" && key == "dependencies" -> pepList(value, line, DependencyScope.RUNTIME)
                table == "project.optional-dependencies" -> pepList(value, line, groupScope(key, DependencyScope.OPTIONAL))
                table == "dependency-groups" -> pepList(value, line, groupScope(key, DependencyScope.DEVELOPMENT))
                table == "build-system" && key == "requires" -> pepList(value, line, DependencyScope.BUILD)
                table == "tool.poetry.dependencies" ->
                    out.addDependency(key, version(value), if (key == "python") DependencyScope.PLATFORM else DependencyScope.RUNTIME, line)
                table == "tool.poetry.dev-dependencies" -> out.addDependency(key, version(value), DependencyScope.DEVELOPMENT, line)
                table.startsWith("tool.poetry.group.") && table.endsWith(".dependencies") ->
                    out.addDependency(key, version(value), groupScope(table.removePrefix("tool.poetry.group."), DependencyScope.DEVELOPMENT), line)
            }
        }
        return ParsedManifest(path, Ecosystem.PYTHON, out)
    }

    fun pipfile(path: String, text: String): ParsedManifest {
        val out = mutableListOf<RawDependency>()
        entries(text) { table, key, value, line ->
            val scope = when (table) {
                "packages" -> DependencyScope.RUNTIME
                "dev-packages" -> DependencyScope.DEVELOPMENT
                else -> return@entries
            }
            out.addDependency(key, version(value)?.takeIf { it != "*" }, scope, line)
        }
        return ParsedManifest(path, Ecosystem.PYTHON, out)
    }

    private fun cargoScope(table: String): DependencyScope? {
        val last = table.substringAfterLast('.')
        val base = if (table.startsWith("target.") || table.startsWith("workspace.")) last else table.substringBefore('.')
        return when (base) {
            "dependencies" -> DependencyScope.RUNTIME
            "dev-dependencies" -> DependencyScope.DEVELOPMENT
            "build-dependencies" -> DependencyScope.BUILD
            else -> null
        }
    }

    fun cargo(path: String, text: String): ParsedManifest {
        val out = mutableListOf<RawDependency>()
        // Tablas `[dependencies.serde]`: el nombre va en la cabecera y la versión en sus claves.
        val tableDeps = LinkedHashMap<String, Triple<DependencyScope, Int, HashMap<String, String>>>()
        entries(text) { table, key, value, line ->
            val section = listOf("dependencies", "dev-dependencies", "build-dependencies").firstOrNull { table.startsWith("$it.") }
            if (section != null && !table.removePrefix("$section.").contains('.')) {
                val name = table.removePrefix("$section.")
                val entry = tableDeps.getOrPut(name) { Triple(cargoScope(section)!!, line - 1, HashMap()) }
                entry.third[key] = strings(value, 0).firstOrNull()?.first ?: value.trim()
                return@entries
            }
            val scope = cargoScope(table) ?: return@entries
            if (table.startsWith("target.") && !table.endsWith("dependencies")) return@entries
            val trimmed = value.trim()
            // Las dependencias locales (`path = …`) son partes del propio proyecto.
            if (trimmed.startsWith("{") && trimmed.contains("path") && !trimmed.contains("version")) return@entries
            val real = INLINE_PACKAGE.matcher(trimmed).let { if (it.find()) it.group(1) else key }
            out.addDependency(real, version(trimmed), scope, line)
        }
        for ((name, entry) in tableDeps) {
            val (scope, line, fields) = entry
            if (fields.containsKey("path") && !fields.containsKey("version")) continue
            out.addDependency(fields["package"] ?: name, fields["version"], scope, line.coerceAtLeast(1))
        }
        return ParsedManifest(path, Ecosystem.RUST_CARGO, out)
    }
}

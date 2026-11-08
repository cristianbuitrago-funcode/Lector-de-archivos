package com.funcodestudiocb.analyzer.engine.relations.framework

import com.funcodestudiocb.analyzer.engine.inventory.RedactedText
import com.google.re2j.Pattern

/**
 * Ejecuta los analizadores de framework sobre el texto redactado de un archivo. Un fallo de un analizador en un
 * archivo no detiene el análisis: se cuenta y la cobertura lo dice.
 */
internal class FrameworkExtractor {
    var failures = 0
        private set
    val failedAnalyzers = HashSet<String>()

    fun extract(file: RedactedText): FrameworkFacts? {
        if (file.text.isEmpty()) return null
        val src = Src(file)
        val out = FrameworkFacts()
        run("rutas") { Routes.extract(src, out) }
        run("llamadas HTTP") { HttpCalls.extract(src, out) }
        run("datos") { Data.extract(src, out) }
        run("configuración") { Config.extract(src, out) }
        run("inyección") { Injection.extract(src, out) }
        run("plataformas") { Platforms.extract(src, out) }
        // Las ligaduras de nombres solo hacen falta para enlazar controladores, montajes e inyecciones.
        val controller = src.language == "php" && (src.info.stem.endsWith("Controller") || src.text.contains("extends Controller"))
        if (out.routes.isNotEmpty() || out.mounts.isNotEmpty() || out.injections.isNotEmpty() || controller) run("importaciones") { Bindings.extract(src, out) }
        return out.takeIf { !it.isEmpty() }
    }

    private inline fun run(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            failures++
            failedAnalyzers += name
        }
    }
}

/** Nombres locales que introduce cada importación (para saber de dónde viene `ctrl` en `ctrl.list`). */
internal object Bindings {
    private val JS_IMPORT = Pattern.compile("""(?m)^\s*import\s+(?:type\s+)?([^'";]*?)\s+from\s+["']([^"']+)["']""")
    private val JS_REQUIRE = Pattern.compile("""(?:const|let|var)\s+(\{[^}]*\}|[A-Za-z_$][\w$]*)\s*=\s*require\(\s*["']([^"']+)["']\s*\)""")
    private val TS_IMPORT_EQ = Pattern.compile("""import\s+([A-Za-z_$][\w$]*)\s*=\s*require\(\s*["']([^"']+)["']""")
    private val PY_FROM = Pattern.compile("""(?m)^\s*from\s+([.\w]+)\s+import\s+(\([^)]*\)|[^\n#]+)""")
    private val PY_IMPORT = Pattern.compile("""(?m)^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?""")
    private val PHP_USE = Pattern.compile("""(?m)^\s*use\s+\\?([\w\\]+)(?:\s+as\s+(\w+))?\s*;""")
    private val PHP_GROUP_USE = Pattern.compile("""(?m)^\s*use\s+\\?([\w\\]+)\\\{([^}]*)\}\s*;""")
    private val PHP_METHOD = Pattern.compile("""\bfunction\s+(\w+)\s*\(""")

    fun extract(src: Src, out: FrameworkFacts) {
        when (src.language) {
            "javascript", "typescript", "tsx", "vue", "svelte" -> javascript(src, out)
            "python" -> python(src, out)
            "php" -> php(src, out)
        }
    }

    private fun put(out: FrameworkFacts, local: String, module: String, member: String?) {
        if (local.isNotEmpty() && out.bindings.size < 500) out.bindings.putIfAbsent(local, module to member)
    }

    private fun javascript(src: Src, out: FrameworkFacts) {
        val m = JS_IMPORT.matcher(src.text)
        while (m.find()) {
            if (!src.isCode(m.start())) continue
            val clause = m.group(1).trim()
            val module = m.group(2)
            val brace = clause.indexOf('{')
            val head = if (brace >= 0) clause.substring(0, brace) else clause
            head.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                if (part.startsWith("* as ")) put(out, part.removePrefix("* as ").trim(), module, null) else put(out, part, module, "default")
            }
            if (brace >= 0) named(clause.substring(brace + 1, clause.lastIndexOf('}').let { if (it < 0) clause.length else it }), " as ").forEach { (imported, local) -> put(out, local, module, imported) }
        }
        val r = JS_REQUIRE.matcher(src.text)
        while (r.find()) {
            if (!src.isCode(r.start())) continue
            val target = r.group(1)
            if (target.startsWith("{")) named(target.trim('{', '}'), ":").forEach { (imported, local) -> put(out, local, r.group(2), imported) } else put(out, target, r.group(2), null)
        }
        val t = TS_IMPORT_EQ.matcher(src.text)
        while (t.find()) put(out, t.group(1), t.group(2), null)
    }

    /** `a, b as c` → (a, a), (b, c). */
    private fun named(list: String, alias: String): List<Pair<String, String>> = list.split(',').mapNotNull { p ->
        val s = p.trim().removePrefix("type ").trim()
        if (s.isEmpty()) return@mapNotNull null
        val imported = s.substringBefore(alias).trim()
        val local = s.substringAfter(alias, imported).trim()
        if (imported.isEmpty() || local.isEmpty()) null else imported to local
    }

    private fun python(src: Src, out: FrameworkFacts) {
        val m = PY_FROM.matcher(src.text)
        while (m.find()) {
            if (!src.isCode(m.start())) continue
            val module = m.group(1)
            named(m.group(2).trim().trim('(', ')').replace('\n', ' '), " as ").forEach { (imported, local) -> if (imported != "*") put(out, local, module, imported) }
        }
        val i = PY_IMPORT.matcher(src.text)
        while (i.find()) {
            if (!src.isCode(i.start())) continue
            val module = i.group(1)
            put(out, i.group(2) ?: module.substringBefore('.'), if (i.group(2) != null) module else module.substringBefore('.'), null)
        }
    }

    private fun php(src: Src, out: FrameworkFacts) {
        val m = PHP_USE.matcher(src.text)
        while (m.find()) {
            if (!src.isCode(m.start())) continue
            val fqcn = m.group(1)
            put(out, m.group(2) ?: fqcn.substringAfterLast('\\'), fqcn, null)
        }
        val g = PHP_GROUP_USE.matcher(src.text)
        while (g.find()) {
            val base = g.group(1).trimEnd('\\')
            g.group(2).split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { item ->
                val name = item.substringBefore(" as ").trim()
                put(out, item.substringAfter(" as ", name.substringAfterLast('\\')).trim(), "$base\\$name", null)
            }
        }
        if (src.info.stem.endsWith("Controller") || src.text.contains("extends Controller")) {
            val f = PHP_METHOD.matcher(src.text)
            while (f.find() && out.methods.size < 500) out.methods += f.group(1)
        }
    }
}

package com.funcodestudiocb.analyzer.engine.dependencies

import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.inventory.PathInfo
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeJson
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeYaml
import com.funcodestudiocb.analyzer.engine.inventory.parse.YamlMap
import com.funcodestudiocb.analyzer.engine.inventory.parse.YamlScalar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Paquete con su versión exacta según un archivo de bloqueo. */
data class LockedPackage(
    val name: String,
    val version: String,
    /** Dependencia directa del proyecto según el propio archivo; `null` si el archivo no lo dice. */
    val direct: Boolean?,
    /** Solo para desarrollo o pruebas según el archivo. */
    val dev: Boolean,
    /** Paquetes que lo requieren (por nombre). */
    val parents: Set<String>,
    val line: Int?,
)

/** Archivo de bloqueo leído: versiones exactas de las dependencias directas e indirectas. */
class ParsedLockfile(
    val path: String,
    val ecosystem: Ecosystem,
    val packages: List<LockedPackage>,
    val readable: Boolean = true,
    /** Se alcanzó el máximo de paquetes por archivo. */
    val truncated: Boolean = false,
)

/**
 * Archivos de bloqueo de cada ecosistema (Fase 7). Se leen sobre el texto ya redactado, con analizadores con límites
 * (JSON y YAML seguros, recorridos de líneas) y sin ejecutar nada. Las versiones de los archivos de bloqueo son las
 * que se instalan: con ellas las vulnerabilidades son "confirmadas".
 */
object Lockfiles {
    const val MAX_PACKAGES = 20_000
    private const val MAX_NAME = 214
    private const val MAX_VERSION = 100

    /** Ecosistema del archivo de bloqueo, o `null` si no es uno que se sepa leer. */
    fun ecosystemOf(info: PathInfo): Ecosystem? = when (info.lower) {
        "package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml" -> Ecosystem.NPM
        "composer.lock" -> Ecosystem.PHP_COMPOSER
        "pipfile.lock", "poetry.lock", "uv.lock" -> Ecosystem.PYTHON
        "cargo.lock" -> Ecosystem.RUST_CARGO
        "gemfile.lock" -> Ecosystem.RUBY
        "packages.lock.json" -> Ecosystem.DOTNET
        "pubspec.lock" -> Ecosystem.DART_PUB
        "go.mod" -> Ecosystem.GO
        "gradle.lockfile" -> Ecosystem.GRADLE
        else -> null
    }?.takeIf { "node_modules" !in info.lowerDirs && "vendor" !in info.lowerDirs }

    fun parse(info: PathInfo, text: String): ParsedLockfile? {
        val ecosystem = ecosystemOf(info) ?: return null
        val path = info.path
        val out = Builder()
        val ok = runCatching {
            when (info.lower) {
                "package-lock.json", "npm-shrinkwrap.json" -> npmLock(text, out)
                "yarn.lock" -> yarnLock(text, out)
                "pnpm-lock.yaml" -> pnpmLock(text, out)
                "composer.lock" -> composerLock(text, out)
                "pipfile.lock" -> pipfileLock(text, out)
                "poetry.lock", "uv.lock", "cargo.lock" -> tomlPackages(text, info.lower, out)
                "gemfile.lock" -> gemfileLock(text, out)
                "packages.lock.json" -> nugetLock(text, out)
                "pubspec.lock" -> pubspecLock(text, out)
                "go.mod" -> goMod(text, out)
                "gradle.lockfile" -> gradleLock(text, out)
                else -> false
            }
        }.getOrDefault(false)
        return ParsedLockfile(path, ecosystem, out.result(), readable = ok, truncated = out.truncated)
    }

    /** Acumula paquetes únicos por nombre y versión, uniendo quién los requiere. */
    private class Builder {
        private val byKey = LinkedHashMap<String, LockedPackage>()
        var truncated = false

        fun add(name: String?, version: String?, direct: Boolean?, dev: Boolean, parents: Set<String> = emptySet(), line: Int? = null) {
            val n = name?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME && it.none { c -> c.isISOControl() || c.isWhitespace() } } ?: return
            val v = version?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_VERSION && it.none { c -> c.isISOControl() || c.isWhitespace() } } ?: return
            val key = "$n@$v"
            val old = byKey[key]
            if (old == null) {
                if (byKey.size >= MAX_PACKAGES) {
                    truncated = true
                    return
                }
                byKey[key] = LockedPackage(n, v, direct, dev, parents, line)
            } else {
                byKey[key] = old.copy(
                    direct = when {
                        old.direct == true || direct == true -> true
                        old.direct == null -> direct
                        else -> old.direct
                    },
                    dev = old.dev && dev,
                    parents = if (parents.isEmpty()) old.parents else old.parents + parents,
                    line = old.line ?: line,
                )
            }
        }

        fun result(): List<LockedPackage> = byKey.values.toList()
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject?.keysOf(vararg sections: String): Set<String> = sections.flatMap { this?.get(it).obj()?.keys.orEmpty() }.toSet()

    /** Línea (desde 1) de cada aparición de [prefix] al principio de una línea, en orden. */
    private fun lineIndex(text: String, extract: (String) -> String?): Map<String, Int> {
        val out = HashMap<String, Int>()
        var n = 0
        for (line in text.lineSequence()) {
            n++
            extract(line)?.let { out.putIfAbsent(it, n) }
        }
        return out
    }

    // ------------------------------------------------------------------ npm

    private fun npmLock(text: String, out: Builder): Boolean {
        val root = SafeJson.parse(text).obj() ?: return false
        val packages = root["packages"].obj()
        val lines = lineIndex(text) { l -> l.trim().takeIf { it.startsWith("\"node_modules/") || it.startsWith("\"") && it.contains("/node_modules/") }?.substringAfter('"')?.substringBefore('"') }
        if (packages != null) {
            val rootEntry = packages[""].obj()
            val direct = rootEntry.keysOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
            val devDirect = rootEntry.keysOf("devDependencies")
            // Quién requiere a quién: se resuelve como Node (node_modules anidados primero).
            val parents = HashMap<String, MutableSet<String>>()
            for ((key, value) in packages) {
                val obj = value.obj() ?: continue
                val name = obj["name"].str() ?: key.substringAfterLast("node_modules/", "").ifEmpty { continue }
                val deps = obj.keysOf("dependencies", "optionalDependencies", "peerDependencies")
                for (dep in deps) {
                    val child = resolveNode(packages, key, dep) ?: continue
                    parents.getOrPut(child) { HashSet() } += name
                }
            }
            for ((key, value) in packages) {
                if (!key.contains("node_modules/")) continue // raíz y paquetes del propio repositorio (workspaces)
                val obj = value.obj() ?: continue
                if ((obj["link"] as? JsonPrimitive)?.content == "true") continue
                val name = obj["name"].str() ?: key.substringAfterLast("node_modules/")
                val topLevel = key == "node_modules/$name"
                val dev = (obj["dev"] as? JsonPrimitive)?.content == "true" || (obj["devOptional"] as? JsonPrimitive)?.content == "true"
                out.add(name, obj["version"].str(), topLevel && name in direct, dev || topLevel && name in devDirect && name !in rootEntry.keysOf("dependencies"), parents[key].orEmpty(), lines[key])
            }
            return true
        }
        // Formato 1: árbol "dependencies" anidado.
        val deps = root["dependencies"].obj() ?: return false
        val stack = ArrayDeque<Triple<JsonObject, String?, Int>>()
        stack += Triple(deps, null, 0)
        while (stack.isNotEmpty()) {
            val (level, parent, depth) = stack.removeLast()
            if (depth > 64) continue
            for ((name, value) in level) {
                val obj = value.obj() ?: continue
                val dev = (obj["dev"] as? JsonPrimitive)?.content == "true"
                out.add(name, obj["version"].str()?.takeUnless { it.startsWith("file:") || it.startsWith("link:") }, if (parent == null) null else false, dev, setOfNotNull(parent))
                obj["requires"].obj()?.keys?.forEach { child -> out.add(child, level[child].obj()?.get("version").str() ?: deps[child].obj()?.get("version").str(), null, dev, setOf(name)) }
                obj["dependencies"].obj()?.let { stack += Triple(it, name, depth + 1) }
            }
        }
        return true
    }

    /** Clave del paquete [dep] que ve el paquete en [from] (`node_modules/a/node_modules/b` → hacia arriba). */
    private fun resolveNode(packages: JsonObject, from: String, dep: String): String? {
        var base = from
        while (true) {
            val candidate = (if (base.isEmpty()) "" else "$base/") + "node_modules/$dep"
            if (packages.containsKey(candidate)) return candidate
            if (base.isEmpty()) return null
            val idx = base.lastIndexOf("/node_modules/")
            base = if (idx >= 0) base.substring(0, idx) else ""
        }
    }

    private fun yarnLock(text: String, out: Builder): Boolean {
        class Block(val specs: List<String>, val line: Int) {
            var version: String? = null
            val deps = mutableListOf<Pair<String, String>>()
            var workspace = false
        }
        val blocks = mutableListOf<Block>()
        var current: Block? = null
        var inDeps = false
        var n = 0
        for (raw in text.lineSequence()) {
            n++
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            if (indent == 0) {
                inDeps = false
                if (!line.endsWith(":") || line.startsWith("__metadata")) {
                    current = null
                    continue
                }
                val specs = line.removeSuffix(":").split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                current = Block(specs, n).also { blocks += it }
                continue
            }
            val b = current ?: continue
            if (indent <= 2) {
                inDeps = line == "dependencies:" || line == "optionalDependencies:"
                when {
                    line.startsWith("version ") -> b.version = line.removePrefix("version ").trim().trim('"')
                    line.startsWith("version:") -> b.version = line.removePrefix("version:").trim().trim('"')
                    line.startsWith("linkType: soft") -> b.workspace = true
                }
                continue
            }
            if (inDeps) {
                // v1: `"@babel/highlight" "^7.12.13"`; berry: `"@babel/highlight": ^7.12.13`
                val t = line.removeSuffix(",")
                val name: String
                val range: String
                if (t.startsWith("\"")) {
                    val end = t.indexOf('"', 1)
                    if (end < 0) continue
                    name = t.substring(1, end)
                    range = t.substring(end + 1).trimStart(':', ' ').trim('"')
                } else {
                    val sep = t.indexOfFirst { it == ' ' || it == ':' }
                    if (sep <= 0) continue
                    name = t.substring(0, sep)
                    range = t.substring(sep + 1).trimStart(':', ' ').trim('"')
                }
                b.deps += name to range
            }
        }
        // Especificador (`a@^1.0.0`, `a@npm:^1.0.0`) → bloque.
        fun nameOf(spec: String): String = spec.substring(0, spec.indexOf('@', 1).let { if (it < 0) spec.length else it })
        fun rangeOf(spec: String): String = spec.substring(spec.indexOf('@', 1).let { if (it < 0) spec.length else it + 1 }).removePrefix("npm:")
        val bySpec = HashMap<String, Block>()
        blocks.forEach { b -> b.specs.forEach { bySpec["${nameOf(it)}@${rangeOf(it)}"] = b } }
        val parents = HashMap<Block, MutableSet<String>>()
        for (b in blocks) {
            val parentName = nameOf(b.specs.first())
            for ((dep, range) in b.deps) {
                val child = bySpec["$dep@${range.removePrefix("npm:")}"] ?: continue
                parents.getOrPut(child) { HashSet() } += parentName
            }
        }
        for (b in blocks) {
            if (b.workspace || b.specs.any { it.contains("@workspace:") || it.contains("@file:") || it.contains("@link:") || it.contains("@portal:") }) continue
            out.add(nameOf(b.specs.first()), b.version, null, false, parents[b].orEmpty(), b.line)
        }
        return true
    }

    private fun pnpmLock(text: String, out: Builder): Boolean {
        val root = SafeYaml.parse(text) as? YamlMap ?: return false
        val prodDirect = HashSet<String>()
        val devDirect = HashSet<String>()
        fun importer(m: YamlMap?) {
            for ((section, dev) in listOf("dependencies" to false, "devDependencies" to true, "optionalDependencies" to false)) {
                (m?.get(section) as? YamlMap)?.entries?.keys?.forEach { if (dev) devDirect += it else prodDirect += it }
            }
        }
        importer(root)
        (root["importers"] as? YamlMap)?.entries?.values?.forEach { importer(it as? YamlMap) }
        fun split(key: String): Pair<String, String>? {
            val k = key.removePrefix("/").substringBefore('(')
            // v6/v9: nombre@versión; v5: nombre/versión.
            val at = k.indexOf('@', 1)
            return if (at > 0) {
                k.substring(0, at) to k.substring(at + 1)
            } else {
                val slash = k.lastIndexOf('/')
                if (slash <= 0) null else k.substring(0, slash) to k.substring(slash + 1)
            }
        }
        val packages = root["packages"] as? YamlMap
        val snapshots = root["snapshots"] as? YamlMap
        val parents = HashMap<String, MutableSet<String>>()
        for (section in listOfNotNull(snapshots, packages)) {
            for ((key, value) in section.entries) {
                val (name, _) = split(key) ?: continue
                for (depSection in listOf("dependencies", "optionalDependencies")) {
                    (( value as? YamlMap)?.get(depSection) as? YamlMap)?.entries?.forEach { (dep, v) ->
                        val ver = (v as? YamlScalar)?.value?.substringBefore('(') ?: return@forEach
                        parents.getOrPut("$dep@$ver") { HashSet() } += name
                    }
                }
            }
        }
        val source = packages ?: return true
        for ((key, value) in source.entries) {
            val (name, version) = split(key) ?: continue
            val m = value as? YamlMap
            val dev = (m?.get("dev") as? YamlScalar)?.value == "true" || name in devDirect && name !in prodDirect
            val version0 = version.substringBefore('(')
            out.add(name, version0, name in prodDirect || name in devDirect, dev, parents["$name@$version0"].orEmpty(), source.lineOf(key))
        }
        return true
    }

    // ------------------------------------------------------------------ PHP, Python, Ruby

    private fun composerLock(text: String, out: Builder): Boolean {
        val root = SafeJson.parse(text).obj() ?: return false
        val lines = lineIndex(text) { l -> l.trim().takeIf { it.startsWith("\"name\":") }?.substringAfter(':')?.trim()?.trim(',', '"') }
        val parents = HashMap<String, MutableSet<String>>()
        val all = (root["packages"] as? JsonArray).orEmpty().map { it to false } + (root["packages-dev"] as? JsonArray).orEmpty().map { it to true }
        for ((p, _) in all) {
            val obj = p.obj() ?: continue
            val name = obj["name"].str() ?: continue
            obj["require"].obj()?.keys?.filter { it.contains('/') }?.forEach { parents.getOrPut(it.lowercase()) { HashSet() } += name }
        }
        for ((p, dev) in all) {
            val obj = p.obj() ?: continue
            val name = obj["name"].str() ?: continue
            out.add(name, obj["version"].str(), null, dev, parents[name.lowercase()].orEmpty(), lines[name])
        }
        return true
    }

    private fun pipfileLock(text: String, out: Builder): Boolean {
        val root = SafeJson.parse(text).obj() ?: return false
        val lines = lineIndex(text) { l -> l.trim().takeIf { it.startsWith("\"") && it.endsWith("{") }?.substringAfter('"')?.substringBefore('"') }
        for ((section, dev) in listOf("default" to false, "develop" to true)) {
            root[section].obj()?.forEach { (name, v) ->
                val version = v.obj()?.get("version").str()?.removePrefix("==") ?: return@forEach
                out.add(name, version, null, dev, emptySet(), lines[name])
            }
        }
        return true
    }

    /** `[[package]]` de poetry.lock, uv.lock y Cargo.lock. */
    private fun tomlPackages(text: String, file: String, out: Builder): Boolean {
        class Pkg(val line: Int) {
            var name: String? = null
            var version: String? = null
            var category: String? = null
            var local = false
            var hasSource = false
            val deps = mutableListOf<String>()
        }
        val pkgs = mutableListOf<Pkg>()
        var current: Pkg? = null
        var table = ""
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val n = ++i
            val t = raw.trim()
            if (t.startsWith("[[package]]")) {
                current = Pkg(n).also { pkgs += it }
                table = "package"
                continue
            }
            if (t.startsWith("[")) {
                table = t.trim('[', ']').trim()
                if (!table.startsWith("package")) current = null
                continue
            }
            val p = current ?: continue
            val eq = t.indexOf('=')
            if (eq <= 0) continue
            val key = t.substring(0, eq).trim().trim('"')
            var value = t.substring(eq + 1).trim()
            // Listas de varias líneas.
            if (value.startsWith("[") && !value.contains("]")) {
                val sb = StringBuilder(value)
                while (i < lines.size && !sb.contains("]") && sb.length < 64_000) sb.append(' ').append(lines[i++].trim())
                value = sb.toString()
            }
            when {
                table == "package" && key == "name" -> p.name = value.trim('"')
                table == "package" && key == "version" -> p.version = value.trim('"')
                table == "package" && key == "category" -> p.category = value.trim('"')
                table == "package" && key == "source" -> {
                    p.hasSource = true
                    p.local = file == "uv.lock" && (value.contains("editable") || value.contains("virtual"))
                }
                table == "package" && key == "dependencies" && value.startsWith("[") -> {
                    // Cargo: ["a", "b 1.0.0"]; uv: [{ name = "a" }, …]
                    if (file == "cargo.lock") {
                        Regexish.quoted(value).forEach { p.deps += it.substringBefore(' ') }
                    } else {
                        Regexish.namesInTables(value).forEach { p.deps += it }
                    }
                }
                table == "package.dependencies" -> p.deps += key
            }
        }
        // Cargo: los paquetes del propio proyecto no tienen "source"; uv: el proyecto es editable o virtual.
        if (file == "cargo.lock") pkgs.forEach { pk -> pk.local = !pk.hasSource }
        val parents = HashMap<String, MutableSet<String>>()
        pkgs.forEach { pk -> pk.deps.forEach { d -> parents.getOrPut(d.lowercase()) { HashSet() } += pk.name.orEmpty() } }
        val localNames = pkgs.filter { it.local }.mapNotNull { it.name?.lowercase() }.toSet()
        val directNames = pkgs.filter { it.local }.flatMap { it.deps }.map { it.lowercase() }.toSet()
        for (pk in pkgs) {
            if (pk.local) continue
            val name = pk.name ?: continue
            val direct = if (localNames.isEmpty()) null else name.lowercase() in directNames
            out.add(name, pk.version, direct, pk.category == "dev", parents[name.lowercase()].orEmpty() - localNames, pk.line)
        }
        return true
    }

    private fun gemfileLock(text: String, out: Builder): Boolean {
        var section = ""
        var inSpecs = false
        var currentGem: String? = null
        val gems = mutableListOf<Triple<String, String, Int>>()
        val parents = HashMap<String, MutableSet<String>>()
        val direct = HashSet<String>()
        var n = 0
        for (raw in text.lineSequence()) {
            n++
            if (raw.isBlank()) continue
            val indent = raw.length - raw.trimStart().length
            val t = raw.trim()
            if (indent == 0) {
                section = t
                inSpecs = false
                continue
            }
            when {
                section == "GEM" && indent == 2 -> inSpecs = t == "specs:"
                section == "GEM" && inSpecs && indent == 4 -> {
                    val name = t.substringBefore(' ')
                    val version = t.substringAfter('(', "").substringBefore(')')
                    currentGem = name
                    if (version.isNotEmpty()) gems += Triple(name, stripPlatform(version), n)
                }
                section == "GEM" && inSpecs && indent == 6 -> currentGem?.let { parents.getOrPut(t.substringBefore(' ')) { HashSet() } += it }
                section == "DEPENDENCIES" && indent == 2 -> direct += t.substringBefore(' ').removeSuffix("!")
            }
        }
        if (gems.isEmpty() && !text.contains("GEM")) return false
        gems.forEach { (name, version, line) -> out.add(name, version, name in direct, false, parents[name].orEmpty(), line) }
        return true
    }

    /** `1.13.10-x86_64-linux` → `1.13.10` (gemas compiladas para una plataforma). */
    private fun stripPlatform(version: String): String {
        val dash = version.indexOf('-')
        if (dash < 0) return version
        val suffix = version.substring(dash + 1)
        return if (PLATFORMS.any { suffix.contains(it) }) version.substring(0, dash) else version
    }

    private val PLATFORMS = listOf("x86", "x64", "arm", "aarch64", "java", "mingw", "mswin", "darwin", "linux", "universal")

    // ------------------------------------------------------------------ .NET, Dart, Go, Gradle

    private fun nugetLock(text: String, out: Builder): Boolean {
        val root = SafeJson.parse(text).obj() ?: return false
        val frameworks = root["dependencies"].obj() ?: return false
        val lines = lineIndex(text) { l -> l.trim().takeIf { it.startsWith("\"") && it.endsWith("{") }?.substringAfter('"')?.substringBefore('"') }
        val parents = HashMap<String, MutableSet<String>>()
        for ((_, deps) in frameworks) {
            deps.obj()?.forEach { (name, v) -> v.obj()?.get("dependencies").obj()?.keys?.forEach { parents.getOrPut(it.lowercase()) { HashSet() } += name } }
        }
        for ((_, deps) in frameworks) {
            deps.obj()?.forEach { (name, v) ->
                val obj = v.obj() ?: return@forEach
                val type = obj["type"].str()
                if (type == "Project") return@forEach
                out.add(name, obj["resolved"].str(), type == "Direct", false, parents[name.lowercase()].orEmpty(), lines[name])
            }
        }
        return true
    }

    private fun pubspecLock(text: String, out: Builder): Boolean {
        val root = SafeYaml.parse(text) as? YamlMap ?: return false
        val packages = root["packages"] as? YamlMap ?: return false
        for ((name, value) in packages.entries) {
            val m = value as? YamlMap ?: continue
            val kind = (m["dependency"] as? YamlScalar)?.value.orEmpty()
            val source = (m["source"] as? YamlScalar)?.value
            if (source == "sdk" || source == "path") continue
            out.add(name, (m["version"] as? YamlScalar)?.value, kind.startsWith("direct"), kind == "direct dev", emptySet(), packages.lineOf(name))
        }
        return true
    }

    private fun goMod(text: String, out: Builder): Boolean {
        var inRequire = false
        var n = 0
        var any = false
        for (raw in text.lineSequence()) {
            n++
            val t = raw.trim()
            when {
                t.startsWith("require (") || t == "require(" -> inRequire = true
                inRequire && t.startsWith(")") -> inRequire = false
                inRequire || t.startsWith("require ") -> {
                    val body = t.removePrefix("require ").substringBefore("//").trim()
                    val parts = body.split(' ', '\t').filter { it.isNotEmpty() }
                    if (parts.size >= 2) {
                        any = true
                        out.add(parts[0], parts[1], !t.contains("// indirect"), false, emptySet(), n)
                    }
                }
            }
        }
        return any || text.contains("module ")
    }

    private fun gradleLock(text: String, out: Builder): Boolean {
        var n = 0
        for (raw in text.lineSequence()) {
            n++
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("empty=")) continue
            val coords = t.substringBefore('=')
            val configurations = t.substringAfter('=', "")
            val parts = coords.split(':')
            if (parts.size != 3) continue
            val dev = configurations.isNotEmpty() && configurations.split(',').all { it.contains("test", ignoreCase = true) }
            out.add("${parts[0]}:${parts[1]}", parts[2], null, dev, emptySet(), n)
        }
        return true
    }
}

/** Utilidades de texto sin expresiones regulares de Java (en Android son de ICU y se comportan distinto). */
internal object Regexish {
    /** Cadenas entre comillas dobles de una lista TOML. */
    fun quoted(value: String): List<String> {
        val out = mutableListOf<String>()
        var i = value.indexOf('"')
        while (i >= 0) {
            val end = value.indexOf('"', i + 1)
            if (end < 0) break
            out += value.substring(i + 1, end)
            i = value.indexOf('"', end + 1)
        }
        return out
    }

    /** `name = "x"` dentro de tablas en línea: `[{ name = "a" }, { name = "b", marker = "…" }]`. */
    fun namesInTables(value: String): List<String> {
        val out = mutableListOf<String>()
        var i = value.indexOf("name")
        while (i >= 0) {
            val eq = value.indexOf('=', i)
            val q1 = value.indexOf('"', eq + 1)
            val q2 = if (q1 >= 0) value.indexOf('"', q1 + 1) else -1
            if (eq < 0 || q1 < 0 || q2 < 0) break
            if (value.substring(i + 4, eq).isBlank()) out += value.substring(q1 + 1, q2)
            i = value.indexOf("name", q2 + 1)
        }
        return out
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

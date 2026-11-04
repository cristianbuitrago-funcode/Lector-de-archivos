package com.funcodestudiocb.analyzer.engine.dependencies

import com.funcodestudiocb.analyzer.engine.api.DependencyScope
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.inventory.PathInfo
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeJson
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeXmlReader
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeYaml
import com.funcodestudiocb.analyzer.engine.inventory.parse.YamlMap
import com.funcodestudiocb.analyzer.engine.inventory.parse.YamlScalar
import com.funcodestudiocb.analyzer.engine.inventory.parse.map
import com.google.re2j.Pattern
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Dependencia leída de un manifiesto, antes de asignarle subproyecto. */
data class RawDependency(val name: String, val version: String?, val scope: DependencyScope, val line: Int?)

enum class CatalogRefKind { LIBRARY, BUNDLE, PLUGIN }

/** Referencia a un catálogo de versiones de Gradle (`libs.androidx.core.ktx`), que se resuelve al final. */
data class CatalogRef(val catalog: String, val alias: String, val kind: CatalogRefKind, val scope: DependencyScope, val line: Int)

/** Catálogo de versiones de Gradle (`gradle/libs.versions.toml`). Los alias están normalizados (ver [CatalogAlias]). */
class VersionCatalog(
    val path: String,
    val name: String,
    val libraries: Map<String, Pair<String, String?>>,
    val bundles: Map<String, List<String>>,
    val plugins: Map<String, Pair<String, String?>>,
)

/** Lo leído de un manifiesto. [readable] es `false` si el archivo no se pudo interpretar. */
class ParsedManifest(
    val path: String,
    val ecosystem: Ecosystem,
    val dependencies: List<RawDependency>,
    val catalogRefs: List<CatalogRef> = emptyList(),
    val catalog: VersionCatalog? = null,
    val readable: Boolean = true,
)

internal object CatalogAlias {
    /** `androidx-core_ktx` y `libs.androidx.core.ktx` se escriben igual: minúsculas separadas por puntos. */
    fun normalize(alias: String): String = alias.lowercase().replace('-', '.').replace('_', '.')
}

/**
 * Lectores de manifiestos de dependencias directas. Todos trabajan sobre el texto ya redactado y con expresiones
 * de tiempo lineal (RE2/J) o lectores sin recursión (JSON con límite de profundidad, XML propio, YAML por eventos).
 * Lo que no reconocen lo omiten: nunca inventan una dependencia.
 */
object ManifestParsers {
    const val MAX_PER_MANIFEST = 2_000
    private const val MAX_NAME = 200
    private const val MAX_VERSION = 100

    private val SAFE_NAME = Pattern.compile("""^[A-Za-z0-9@][A-Za-z0-9@/._:+~\-]*$""")

    /** Ecosistema del manifiesto, o `null` si el archivo no es uno que se sepa leer. */
    fun ecosystemOf(info: PathInfo): Ecosystem? = when {
        info.lower == "package.json" -> if (info.lowerDirs.contains("node_modules")) null else Ecosystem.NPM
        info.lower == "manifest.json" && info.parent == "packages" -> Ecosystem.UNITY
        info.lower == "composer.json" -> Ecosystem.PHP_COMPOSER
        info.lower in setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") -> Ecosystem.GRADLE
        info.lower.endsWith(".versions.toml") -> Ecosystem.GRADLE
        info.lower == "pom.xml" -> Ecosystem.MAVEN
        isRequirements(info) || info.lower == "pyproject.toml" || info.lower == "pipfile" -> Ecosystem.PYTHON
        info.lower == "pubspec.yaml" -> Ecosystem.DART_PUB
        info.ext in setOf("csproj", "fsproj", "vbproj") || info.lower == "packages.config" || info.lower == "directory.packages.props" -> Ecosystem.DOTNET
        info.lower == "go.mod" -> Ecosystem.GO
        info.lower == "cargo.toml" -> Ecosystem.RUST_CARGO
        info.lower == "gemfile" -> Ecosystem.RUBY
        info.lower == "podfile" -> Ecosystem.COCOAPODS
        info.lower == "cmakelists.txt" -> Ecosystem.CMAKE
        info.ext == "uproject" || info.ext == "uplugin" -> Ecosystem.UNREAL
        else -> null
    }

    private fun isRequirements(info: PathInfo): Boolean =
        info.ext == "txt" && (info.lower.startsWith("requirements") || info.lower.endsWith("requirements.txt") || info.parent == "requirements")

    fun parse(info: PathInfo, text: String): ParsedManifest? {
        val ecosystem = ecosystemOf(info) ?: return null
        val path = info.path
        return when {
            ecosystem == Ecosystem.NPM -> jsonSections(path, ecosystem, text, NPM_SECTIONS)
            ecosystem == Ecosystem.UNITY -> jsonSections(path, ecosystem, text, listOf("dependencies" to DependencyScope.RUNTIME))
            ecosystem == Ecosystem.PHP_COMPOSER -> jsonSections(path, ecosystem, text, COMPOSER_SECTIONS)
            info.lower.endsWith(".versions.toml") -> versionCatalog(path, info, text)
            ecosystem == Ecosystem.GRADLE -> Gradle.parse(path, text)
            ecosystem == Ecosystem.MAVEN -> maven(path, text)
            info.lower == "pyproject.toml" -> Toml.pyproject(path, text)
            info.lower == "pipfile" -> Toml.pipfile(path, text)
            ecosystem == Ecosystem.PYTHON -> requirements(path, info, text)
            ecosystem == Ecosystem.DART_PUB -> pubspec(path, text)
            ecosystem == Ecosystem.DOTNET -> dotnet(path, text)
            ecosystem == Ecosystem.GO -> goMod(path, text)
            ecosystem == Ecosystem.RUST_CARGO -> Toml.cargo(path, text)
            ecosystem == Ecosystem.RUBY -> gemfile(path, text)
            ecosystem == Ecosystem.COCOAPODS -> lines(path, ecosystem, text, POD) { m, _ -> m.group(1) to m.group(2) }
            ecosystem == Ecosystem.CMAKE -> cmake(path, text)
            ecosystem == Ecosystem.UNREAL -> unreal(path, text)
            else -> null
        }
    }

    // ---------------------------------------------------------------- utilidades

    internal fun dependency(name: String?, version: String?, scope: DependencyScope, line: Int?): RawDependency? {
        val n = name?.trim() ?: return null
        if (n.isEmpty() || n.length > MAX_NAME || !SAFE_NAME.matcher(n).matches()) return null
        val v = version?.trim()?.takeIf { it.isNotEmpty() && it.none { ch -> ch.isISOControl() } }?.take(MAX_VERSION)
        return RawDependency(n, v, scope, line)
    }

    internal fun MutableList<RawDependency>.addDependency(name: String?, version: String?, scope: DependencyScope, line: Int?) {
        if (size >= MAX_PER_MANIFEST) return
        dependency(name, version, scope, line)?.let { add(it) }
    }

    internal fun lineStarts(text: String): IntArray = SafeXmlReader.computeLineStarts(text)

    internal fun lineAt(starts: IntArray, offset: Int): Int {
        val i = starts.binarySearch(offset)
        return (if (i >= 0) i else -i - 2) + 1
    }

    private fun lines(
        path: String,
        ecosystem: Ecosystem,
        text: String,
        pattern: Pattern,
        scope: DependencyScope = DependencyScope.RUNTIME,
        pick: (com.google.re2j.Matcher, String) -> Pair<String?, String?>,
    ): ParsedManifest {
        val out = mutableListOf<RawDependency>()
        text.lineSequence().forEachIndexed { i, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#") || trimmed.startsWith("//")) return@forEachIndexed
            val m = pattern.matcher(line)
            if (m.find()) {
                val (name, version) = pick(m, line)
                out.addDependency(name, version, scope, i + 1)
            }
        }
        return ParsedManifest(path, ecosystem, out)
    }

    // ---------------------------------------------------------------- JSON (npm, Composer, Unity)

    private val NPM_SECTIONS = listOf(
        "dependencies" to DependencyScope.RUNTIME,
        "devDependencies" to DependencyScope.DEVELOPMENT,
        "peerDependencies" to DependencyScope.PEER,
        "optionalDependencies" to DependencyScope.OPTIONAL,
    )
    private val COMPOSER_SECTIONS = listOf("require" to DependencyScope.RUNTIME, "require-dev" to DependencyScope.DEVELOPMENT)

    private fun jsonSections(path: String, ecosystem: Ecosystem, text: String, sections: List<Pair<String, DependencyScope>>): ParsedManifest {
        val root = SafeJson.parse(text) as? JsonObject ?: return ParsedManifest(path, ecosystem, emptyList(), readable = false)
        val starts = lineStarts(text)
        val out = mutableListOf<RawDependency>()
        for ((section, defaultScope) in sections) {
            val entries = root[section] as? JsonObject ?: continue
            val sectionAt = text.indexOf("\"$section\"")
            for ((name, value) in entries) {
                val version = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                val scope = if (ecosystem == Ecosystem.PHP_COMPOSER && (name == "php" || name.startsWith("ext-") || name.startsWith("lib-"))) {
                    DependencyScope.PLATFORM
                } else {
                    defaultScope
                }
                val at = if (sectionAt >= 0) text.indexOf("\"$name\"", sectionAt) else -1
                out.addDependency(name, version, scope, if (at >= 0) lineAt(starts, at) else null)
            }
        }
        return ParsedManifest(path, ecosystem, out)
    }

    /** Complementos activados de un proyecto o complemento de Unreal (`Plugins: [{Name, Enabled}]`). */
    private fun unreal(path: String, text: String): ParsedManifest {
        val root = SafeJson.parse(text) as? JsonObject ?: return ParsedManifest(path, Ecosystem.UNREAL, emptyList(), readable = false)
        val starts = lineStarts(text)
        val out = mutableListOf<RawDependency>()
        (root["Plugins"] as? JsonArray)?.forEach { element ->
            val plugin = element as? JsonObject ?: return@forEach
            val name = (plugin["Name"] as? JsonPrimitive)?.content
            val enabled = (plugin["Enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
            if (enabled && name != null) {
                val at = text.indexOf("\"$name\"")
                out.addDependency(name, null, DependencyScope.RUNTIME, if (at >= 0) lineAt(starts, at) else null)
            }
        }
        return ParsedManifest(path, Ecosystem.UNREAL, out)
    }

    // ---------------------------------------------------------------- Gradle: catálogo de versiones

    private val QUOTED = Pattern.compile(""""([^"]*)"""")
    private val INLINE_FIELD = Pattern.compile("""\b(module|group|name|id|version|version\.ref|ref|strictly|prefer|require)\s*=\s*"([^"]*)"""")

    private fun versionCatalog(path: String, info: PathInfo, text: String): ParsedManifest {
        val versions = HashMap<String, String>()
        val libraries = LinkedHashMap<String, Pair<String, String?>>()
        val plugins = LinkedHashMap<String, Pair<String, String?>>()
        val bundles = LinkedHashMap<String, List<String>>()
        val rawLibraries = mutableListOf<Pair<String, String>>()
        val rawPlugins = mutableListOf<Pair<String, String>>()
        Toml.entries(text) { table, key, value, _ ->
            val alias = CatalogAlias.normalize(key.trim('"'))
            when (table) {
                "versions" -> {
                    val m = QUOTED.matcher(value)
                    if (m.find()) versions[key.trim('"')] = m.group(1)
                }
                "libraries" -> rawLibraries += alias to value
                "plugins" -> rawPlugins += alias to value
                "bundles" -> {
                    val m = QUOTED.matcher(value)
                    val items = mutableListOf<String>()
                    while (m.find()) items += CatalogAlias.normalize(m.group(1))
                    bundles[alias] = items
                }
            }
        }
        fun fields(value: String): Map<String, String> {
            val m = INLINE_FIELD.matcher(value)
            val out = HashMap<String, String>()
            while (m.find()) out.putIfAbsent(m.group(1), m.group(2))
            return out
        }
        fun version(f: Map<String, String>): String? =
            f["version"] ?: (f["version.ref"] ?: f["ref"])?.let { versions[it] } ?: f["strictly"] ?: f["require"] ?: f["prefer"]
        for ((alias, value) in rawLibraries) {
            if (value.trimStart().startsWith("\"")) {
                val parts = QUOTED.matcher(value).let { if (it.find()) it.group(1) else "" }.split(':')
                if (parts.size >= 2) libraries[alias] = "${parts[0]}:${parts[1]}" to parts.getOrNull(2)
            } else {
                val f = fields(value)
                val module = f["module"] ?: f["group"]?.let { g -> f["name"]?.let { "$g:$it" } }
                if (module != null) libraries[alias] = module to version(f)
            }
        }
        for ((alias, value) in rawPlugins) {
            if (value.trimStart().startsWith("\"")) {
                val parts = QUOTED.matcher(value).let { if (it.find()) it.group(1) else "" }.split(':')
                if (parts[0].isNotEmpty()) plugins[alias] = parts[0] to parts.getOrNull(1)
            } else {
                val f = fields(value)
                f["id"]?.let { plugins[alias] = it to version(f) }
            }
        }
        val name = info.lower.removeSuffix(".versions.toml")
        return ParsedManifest(path, Ecosystem.GRADLE, emptyList(), catalog = VersionCatalog(path, name, libraries, bundles, plugins))
    }

    // ---------------------------------------------------------------- Maven

    private fun maven(path: String, text: String): ParsedManifest {
        val stack = ArrayList<String>()
        val properties = HashMap<String, String>()
        val out = mutableListOf<RawDependency>()
        var current: HashMap<String, String>? = null
        var currentLine = 0
        var currentIsPlugin = false
        val ok = SafeXmlReader(text).parse(
            onStart = { start ->
                stack += start.name
                val joined = stack.joinToString("/")
                if (joined == "project/dependencies/dependency" || joined == "project/build/plugins/plugin") {
                    current = HashMap()
                    currentLine = start.line
                    currentIsPlugin = start.name == "plugin"
                }
            },
            onEnd = { name ->
                val joined = stack.joinToString("/")
                if (joined == "project/dependencies/dependency" || joined == "project/build/plugins/plugin") {
                    current?.let { d ->
                        val artifact = d["artifactId"]
                        val group = d["groupId"] ?: if (currentIsPlugin) "org.apache.maven.plugins" else null
                        if (artifact != null && group != null) {
                            var version = d["version"]
                            if (version != null && version.startsWith("\${") && version.endsWith("}")) {
                                version = properties[version.substring(2, version.length - 1)] ?: version
                            }
                            val scope = when {
                                currentIsPlugin -> DependencyScope.BUILD
                                d["optional"] == "true" -> DependencyScope.OPTIONAL
                                d["scope"] == "test" -> DependencyScope.TEST
                                d["scope"] == "provided" -> DependencyScope.BUILD
                                d["scope"] == "import" -> DependencyScope.PLATFORM
                                else -> DependencyScope.RUNTIME
                            }
                            out.addDependency("$group:$artifact", version, scope, currentLine)
                        }
                    }
                    current = null
                }
                if (stack.isNotEmpty() && stack.last() == name) stack.removeAt(stack.size - 1)
            },
            onText = { value ->
                val element = stack.lastOrNull() ?: return@parse
                if (stack.size == 3 && stack[1] == "properties") properties.putIfAbsent(element, value)
                val joined = stack.dropLast(1).joinToString("/")
                if (current != null && (joined == "project/dependencies/dependency" || joined == "project/build/plugins/plugin")) {
                    current!!.putIfAbsent(element, value)
                }
            },
        )
        // Las dependencias reunidas antes de un error de formato se conservan; si no hay ninguna, se indica.
        return ParsedManifest(path, Ecosystem.MAVEN, out, readable = ok || out.isNotEmpty())
    }

    // ---------------------------------------------------------------- Python

    internal val PEP508 = Pattern.compile("""^\s*([A-Za-z0-9][A-Za-z0-9._\-]*)\s*(\[[^\]]*\])?\s*([^;#]*)""")

    internal fun pep508(spec: String): Pair<String, String?>? {
        val m = PEP508.matcher(spec)
        if (!m.find()) return null
        val version = m.group(3).trim().removePrefix("(").removeSuffix(")").trim().takeIf { it.isNotEmpty() && !it.startsWith("@") }
        return m.group(1) to version
    }

    private fun requirements(path: String, info: PathInfo, text: String): ParsedManifest {
        val scope = when {
            info.lower.contains("test") -> DependencyScope.TEST
            info.lower.contains("dev") -> DependencyScope.DEVELOPMENT
            else -> DependencyScope.RUNTIME
        }
        val out = mutableListOf<RawDependency>()
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.substringBefore(" #").trim()
            // Opciones (-r, -e, --index-url…) y direcciones: no son nombres de paquete (y pueden llevar credenciales).
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-") || line.contains("://")) return@forEachIndexed
            pep508(line)?.let { (name, version) -> out.addDependency(name, version, scope, i + 1) }
        }
        return ParsedManifest(path, Ecosystem.PYTHON, out)
    }

    // ---------------------------------------------------------------- Dart (pubspec.yaml)

    private fun pubspec(path: String, text: String): ParsedManifest {
        val root = SafeYaml.parse(text) as? YamlMap ?: return ParsedManifest(path, Ecosystem.DART_PUB, emptyList(), readable = false)
        val out = mutableListOf<RawDependency>()
        for ((section, scope) in listOf("dependencies" to DependencyScope.RUNTIME, "dev_dependencies" to DependencyScope.DEVELOPMENT)) {
            val deps = root.map(section) ?: continue
            for ((name, value) in deps.entries) {
                val line = deps.lineOf(name)
                when (value) {
                    is YamlScalar -> out.addDependency(name, value.value.takeIf { it.isNotEmpty() && it != "null" }, scope, line)
                    is YamlMap -> {
                        val sdk = value["sdk"] as? YamlScalar
                        val version = (value["version"] as? YamlScalar)?.value
                        out.addDependency(name, version, if (sdk != null) DependencyScope.PLATFORM else scope, line)
                    }
                    else -> out.addDependency(name, null, scope, line)
                }
            }
        }
        return ParsedManifest(path, Ecosystem.DART_PUB, out)
    }

    // ---------------------------------------------------------------- .NET

    private fun dotnet(path: String, text: String): ParsedManifest {
        val out = mutableListOf<RawDependency>()
        var pending: Triple<String, Int, DependencyScope>? = null
        var inVersion = false
        val ok = SafeXmlReader(text).parse(
            onStart = { start ->
                when (start.name) {
                    "Project" -> start.attribute("Sdk")?.let { sdk -> sdk.split(';').forEach { out.addDependency(it.substringBefore('/'), null, DependencyScope.PLATFORM, start.line) } }
                    "PackageReference", "PackageVersion" -> {
                        val name = start.attribute("Include") ?: start.attribute("Update")
                        val version = start.attribute("Version") ?: start.attribute("VersionOverride")
                        val scope = if (start.attribute("PrivateAssets") == "all") DependencyScope.DEVELOPMENT else DependencyScope.RUNTIME
                        if (name != null && version == null && start.name == "PackageReference") {
                            pending = Triple(name, start.line, scope)
                        } else {
                            out.addDependency(name, version, scope, start.line)
                        }
                    }
                    "FrameworkReference" -> out.addDependency(start.attribute("Include"), null, DependencyScope.PLATFORM, start.line)
                    "package" -> {
                        val scope = if (start.attribute("developmentDependency") == "true") DependencyScope.DEVELOPMENT else DependencyScope.RUNTIME
                        out.addDependency(start.attribute("id"), start.attribute("version"), scope, start.line)
                    }
                    "Version" -> inVersion = pending != null
                }
            },
            onEnd = { name ->
                if (name == "Version") inVersion = false
                if (name == "PackageReference") {
                    pending?.let { (n, line, scope) -> out.addDependency(n, null, scope, line) }
                    pending = null
                }
            },
            onText = { value ->
                val p = pending
                if (inVersion && p != null) {
                    out.addDependency(p.first, value, p.third, p.second)
                    pending = null
                }
            },
        )
        return ParsedManifest(path, Ecosystem.DOTNET, out, readable = ok || out.isNotEmpty())
    }

    // ---------------------------------------------------------------- Go

    private val GO_REQUIRE = Pattern.compile("""^\s*(?:require\s+)?([A-Za-z0-9][A-Za-z0-9._~/\-]*\.[A-Za-z0-9._~/\-]+)\s+(v[0-9][^\s/]*)\s*(//.*)?$""")

    private fun goMod(path: String, text: String): ParsedManifest {
        val out = mutableListOf<RawDependency>()
        var inRequire = false
        text.lineSequence().forEachIndexed { i, line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("require (") || trimmed == "require(" -> inRequire = true
                inRequire && trimmed.startsWith(")") -> inRequire = false
                inRequire || trimmed.startsWith("require ") -> {
                    val m = GO_REQUIRE.matcher(trimmed)
                    // Las dependencias indirectas (transitivas) no son declaraciones del proyecto.
                    if (m.find() && m.group(3)?.contains("indirect") != true) out.addDependency(m.group(1), m.group(2), DependencyScope.RUNTIME, i + 1)
                }
            }
        }
        return ParsedManifest(path, Ecosystem.GO, out)
    }

    // ---------------------------------------------------------------- Ruby, CocoaPods, CMake

    private val GEM = Pattern.compile("""^\s*gem\s+["']([^"']+)["'](?:\s*,\s*["']([^"']+)["'])?(.*)$""")
    private val GROUP = Pattern.compile("""^\s*group\s+(.+?)\s+do\b""")
    private val POD = Pattern.compile("""^\s*pod\s+["']([^"']+)["'](?:\s*,\s*["']([^"':][^"']*)["'])?""")
    private val FIND_PACKAGE = Pattern.compile("""(?i)\bfind_package\s*\(\s*([A-Za-z0-9_.+\-]+)(?:\s+([0-9][0-9.]*))?""")
    private val FETCH_CONTENT = Pattern.compile("""(?i)\bFetchContent_Declare\s*\(\s*([A-Za-z0-9_.+\-]+)""")

    private fun gemScope(groups: String): DependencyScope = when {
        groups.contains(":test") || groups.contains("\"test\"") -> DependencyScope.TEST
        groups.contains(":development") -> DependencyScope.DEVELOPMENT
        else -> DependencyScope.RUNTIME
    }

    private fun gemfile(path: String, text: String): ParsedManifest {
        val out = mutableListOf<RawDependency>()
        var group: DependencyScope? = null
        text.lineSequence().forEachIndexed { i, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) return@forEachIndexed
            val g = GROUP.matcher(line)
            when {
                g.find() -> group = gemScope(g.group(1))
                trimmed == "end" -> group = null
                else -> {
                    val m = GEM.matcher(line)
                    if (m.find()) {
                        val inline = m.group(3).orEmpty()
                        val scope = if (inline.contains("group")) gemScope(inline) else group ?: DependencyScope.RUNTIME
                        out.addDependency(m.group(1), m.group(2), scope, i + 1)
                    }
                }
            }
        }
        return ParsedManifest(path, Ecosystem.RUBY, out)
    }

    private fun cmake(path: String, text: String): ParsedManifest {
        val out = mutableListOf<RawDependency>()
        text.lineSequence().forEachIndexed { i, line ->
            if (line.trimStart().startsWith("#")) return@forEachIndexed
            val find = FIND_PACKAGE.matcher(line)
            if (find.find()) out.addDependency(find.group(1), find.group(2), DependencyScope.RUNTIME, i + 1)
            val fetch = FETCH_CONTENT.matcher(line)
            if (fetch.find()) out.addDependency(fetch.group(1), null, DependencyScope.RUNTIME, i + 1)
        }
        return ParsedManifest(path, Ecosystem.CMAKE, out)
    }
}

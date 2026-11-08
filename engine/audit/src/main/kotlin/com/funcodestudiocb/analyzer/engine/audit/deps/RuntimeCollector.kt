package com.funcodestudiocb.analyzer.engine.audit.deps

import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.inventory.RedactedText
import com.funcodestudiocb.analyzer.engine.inventory.RedactedTextListener

/** Versión de un entorno, base de datos o herramienta escrita en el proyecto. */
class RuntimeHint(
    /** Producto de endoflife.date. */
    val product: String,
    val version: String,
    /** La versión es la mínima de un rango (`>=14`, `^7.4`): el proyecto también admite otras. */
    val minimum: Boolean,
    /** De dónde sale, para la ficha ("archivo .nvmrc", "imagen de Docker"…). */
    val source: String,
    val path: String,
    val line: Int?,
)

/**
 * Recoge, durante el recorrido del comprimido, las versiones de entornos de ejecución, bases de datos y herramientas
 * que el proyecto fija (Node.js en `.nvmrc`, Python en `runtime.txt`, imágenes de Docker, `TargetFramework` de .NET,
 * Gradle, Unity, Godot…). Las de los frameworks salen de las dependencias en la etapa.
 */
class RuntimeCollector : RedactedTextListener {

    override val timingLabel = "entornos de ejecución"
    val hints = mutableListOf<RuntimeHint>()

    override fun onText(context: StageContext, file: RedactedText) {
        if (hints.size >= MAX_HINTS) return
        val info = file.info
        val name = info.lower
        val text = file.text
        when {
            name == ".nvmrc" || name == ".node-version" -> firstLine(text)?.let { v -> exactOrNull(v)?.let { add("nodejs", it, false, "archivo $name", file, 1) } }
            name == ".python-version" -> firstLine(text)?.let { v -> exactOrNull(v)?.let { add("python", it, false, "archivo .python-version", file, 1) } }
            name == "runtime.txt" -> firstLine(text)?.takeIf { it.startsWith("python-") }?.let { add("python", it.removePrefix("python-"), false, "archivo runtime.txt", file, 1) }
            name == ".ruby-version" -> firstLine(text)?.let { v -> exactOrNull(v.removePrefix("ruby-"))?.let { add("ruby", it, false, "archivo .ruby-version", file, 1) } }
            name == "package.json" && "node_modules" !in info.lowerDirs -> packageJson(file)
            name == "composer.json" && "vendor" !in info.lowerDirs -> jsonField(file, "\"php\"", "php", "requisito «php» de composer.json")
            name == "pyproject.toml" -> lineValue(file, "requires-python")?.let { (v, l) -> minimumOf(v)?.let { add("python", it, true, "requires-python de pyproject.toml", file, l) } }
            name == "pipfile" -> lineValue(file, "python_version")?.let { (v, l) -> exactOrNull(v)?.let { add("python", it, false, "python_version de Pipfile", file, l) } }
            name == "setup.py" -> lineValue(file, "python_requires")?.let { (v, l) -> minimumOf(v)?.let { add("python", it, true, "python_requires de setup.py", file, l) } }
            name == "gemfile" -> gemfileRuby(file)
            name == "go.mod" -> goMod(file)
            info.ext in setOf("csproj", "fsproj", "vbproj") -> dotnet(file)
            name == "dockerfile" || name.startsWith("dockerfile.") || name.endsWith(".dockerfile") -> docker(file, "FROM ")
            (name.startsWith("docker-compose") || name.startsWith("compose.")) && (info.ext == "yml" || info.ext == "yaml") -> docker(file, "image:")
            name == "gradle-wrapper.properties" -> gradleWrapper(file)
            name == "projectversion.txt" && info.parent == "projectsettings" -> lineValue(file, "m_EditorVersion")?.let { (v, l) -> add("unity", v.substringBefore('f').substringBefore('b').substringBefore('a'), false, "versión del editor de Unity", file, l) }
            name == "project.godot" -> godot(file)
            name == "build.gradle" || name == "build.gradle.kts" -> javaGradle(file)
            name == "pom.xml" -> javaMaven(file)
        }
    }

    private fun add(product: String, version: String, minimum: Boolean, source: String, file: RedactedText, line: Int?) {
        val v = version.trim().trim('"', '\'').removePrefix("v")
        if (v.isEmpty() || v.length > 40 || !v.first().isDigit()) return
        if (hints.size < MAX_HINTS) hints += RuntimeHint(product, v, minimum, source, file.path, line)
    }

    private fun firstLine(text: String): String? = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    /** Versión exacta (`18.17.0`, `3.11`); `lts/hydrogen` o `latest` no lo son. */
    private fun exactOrNull(v: String): String? = v.trim().removePrefix("v").takeIf { it.isNotEmpty() && it.first().isDigit() && it.all { c -> c.isDigit() || c == '.' } }

    /** Mínimo de un rango: `>=3.8`, `^7.4 || ^8.0`, `~=3.10`, `>= 14.0.0` → la primera versión que aparece. */
    fun minimumOf(range: String): String? {
        val t = range.trim().trim('"', '\'', ',')
        var i = 0
        while (i < t.length && !t[i].isDigit()) i++
        val start = i
        while (i < t.length && (t[i].isDigit() || t[i] == '.')) i++
        return t.substring(start, i).trim('.').takeIf { it.isNotEmpty() }
    }

    /** `clave = "valor"`, `clave: valor`, `clave="valor"` en la primera línea que la contenga. */
    private fun lineValue(file: RedactedText, key: String): Pair<String, Int>? {
        var n = 0
        for (line in file.text.lineSequence()) {
            n++
            val t = line.trim()
            if (!t.startsWith(key)) continue
            val rest = t.removePrefix(key).trimStart()
            if (rest.isEmpty() || rest[0] !in "=:") continue
            val value = rest.substring(1).trim().trim(',').trim('"', '\'')
            if (value.isNotEmpty()) return value to n
        }
        return null
    }

    private fun packageJson(file: RedactedText) {
        // "engines": { "node": ">=14" } y "volta": { "node": "18.17.0" }, en una o varias líneas.
        val root = com.funcodestudiocb.analyzer.engine.inventory.parse.SafeJson.parse(file.text) as? kotlinx.serialization.json.JsonObject ?: return
        fun node(section: String): String? =
            ((root[section] as? kotlinx.serialization.json.JsonObject)?.get("node") as? kotlinx.serialization.json.JsonPrimitive)?.content
        val line = file.text.lineSequence().indexOfFirst { it.contains("\"node\"") }.let { if (it < 0) null else it + 1 }
        node("volta")?.let { v -> exactOrNull(v)?.let { add("nodejs", it, false, "versión de Node.js de Volta en package.json", file, line) } }
        node("engines")?.let { v -> minimumOf(v)?.let { add("nodejs", it, exactOrNull(v) == null, "engines.node de package.json", file, line) } }
    }

    private fun jsonField(file: RedactedText, key: String, product: String, source: String) {
        var n = 0
        for (line in file.text.lineSequence()) {
            n++
            val t = line.trim()
            if (t.startsWith(key)) {
                val v = t.substringAfter(':').trim().trim(',').trim('"')
                minimumOf(v)?.let { add(product, it, true, source, file, n) }
                return
            }
        }
    }

    private fun gemfileRuby(file: RedactedText) {
        var n = 0
        for (line in file.text.lineSequence()) {
            n++
            val t = line.trim()
            if (t.startsWith("ruby ") && (t.contains('"') || t.contains('\''))) {
                val v = t.removePrefix("ruby ").trim().trim('"', '\'').substringBefore('"').substringBefore('\'')
                minimumOf(v)?.let { add("ruby", it, !v.first().isDigit(), "versión de Ruby del Gemfile", file, n) }
                return
            }
        }
    }

    private fun goMod(file: RedactedText) {
        var n = 0
        for (line in file.text.lineSequence()) {
            n++
            val t = line.trim()
            when {
                t.startsWith("toolchain go") -> add("go", t.removePrefix("toolchain go"), false, "toolchain de go.mod", file, n)
                t.startsWith("go ") -> add("go", t.removePrefix("go ").trim(), true, "versión mínima de Go en go.mod", file, n)
            }
        }
    }

    private fun dotnet(file: RedactedText) {
        var n = 0
        for (line in file.text.lineSequence()) {
            n++
            val t = line.trim()
            val tag = when {
                t.startsWith("<TargetFramework>") -> "TargetFramework"
                t.startsWith("<TargetFrameworks>") -> "TargetFrameworks"
                else -> continue
            }
            val value = t.substringAfter("<$tag>").substringBefore("</")
            for (tfm in value.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                when {
                    tfm.startsWith("netcoreapp") -> add("dotnet", tfm.removePrefix("netcoreapp"), false, "TargetFramework del proyecto .NET", file, n)
                    tfm.startsWith("net") && tfm.length > 3 && tfm[3].isDigit() && tfm.contains('.') ->
                        add("dotnet", tfm.removePrefix("net").substringBefore('-'), false, "TargetFramework del proyecto .NET", file, n)
                    tfm.startsWith("net") && tfm.length in 5..6 && tfm.drop(3).all { it.isDigit() } ->
                        add("dotnetfx", tfm.drop(3).toCharArray().joinToString("."), false, "TargetFramework del proyecto .NET Framework", file, n)
                }
            }
        }
    }

    /** Imágenes de Docker con versión: `FROM node:18-alpine`, `image: postgres:12`. */
    private fun docker(file: RedactedText, prefix: String) {
        var n = 0
        for (line in file.text.lineSequence()) {
            n++
            val t = line.trim().removePrefix("- ")
            if (!t.startsWith(prefix, ignoreCase = true)) continue
            val image = t.substring(prefix.length).trim().trim('"', '\'').substringBefore(' ').substringBefore('@')
            val repo = image.substringBeforeLast(':').substringAfterLast('/').lowercase()
            val tag = image.substringAfterLast(':', "")
            if (tag.isEmpty() || !tag.first().isDigit()) continue
            if (repo in setOf("aspnet", "runtime", "sdk") && !image.contains("dotnet/")) continue
            val product = DOCKER_IMAGES[repo] ?: continue
            val version = tag.takeWhile { it.isDigit() || it == '.' }.trim('.')
            if (version.isNotEmpty()) add(product, version, false, "imagen de Docker «${image.take(80)}»", file, n)
        }
    }

    private fun gradleWrapper(file: RedactedText) {
        lineValue(file, "distributionUrl")?.let { (v, l) ->
            val name = v.substringAfterLast('/').removePrefix("gradle-").substringBefore("-bin").substringBefore("-all")
            add("gradle", name, false, "versión de Gradle del wrapper", file, l)
        }
    }

    private fun godot(file: RedactedText) {
        var n = 0
        for (line in file.text.lineSequence()) {
            n++
            val t = line.trim()
            if (t.startsWith("config/features=")) {
                val v = t.substringAfter("(\"").substringBefore('"')
                if (v.isNotEmpty() && v.first().isDigit()) add("godot", v, false, "versión de Godot del proyecto", file, n)
                return
            }
        }
    }

    private fun javaGradle(file: RedactedText) {
        var n = 0
        for (line in file.text.lineSequence()) {
            n++
            val t = line.trim()
            when {
                t.startsWith("jvmToolchain(") -> add("eclipse-temurin", t.substringAfter('(').substringBefore(')'), false, "JDK de compilación (jvmToolchain)", file, n)
                t.contains("JavaLanguageVersion.of(") -> add("eclipse-temurin", t.substringAfter("JavaLanguageVersion.of(").substringBefore(')'), false, "JDK de compilación (toolchain)", file, n)
                t.contains("JavaVersion.VERSION_") && (t.contains("sourceCompatibility") || t.contains("targetCompatibility")) ->
                    add("eclipse-temurin", t.substringAfter("JavaVersion.VERSION_").takeWhile { it.isDigit() || it == '_' }.replace('_', '.').removePrefix("1."), true, "versión de Java de destino", file, n)
            }
        }
    }

    private fun javaMaven(file: RedactedText) {
        var n = 0
        for (line in file.text.lineSequence()) {
            n++
            val t = line.trim()
            for (tag in listOf("maven.compiler.release", "maven.compiler.source", "java.version")) {
                if (t.startsWith("<$tag>")) {
                    add("eclipse-temurin", t.substringAfter("<$tag>").substringBefore('<').removePrefix("1."), true, "versión de Java de destino ($tag)", file, n)
                    return
                }
            }
        }
    }

    companion object {
        const val MAX_HINTS = 2_000

        private val DOCKER_IMAGES = mapOf(
            "node" to "nodejs", "python" to "python", "php" to "php", "ruby" to "ruby", "golang" to "go", "openjdk" to "eclipse-temurin",
            "eclipse-temurin" to "eclipse-temurin", "amazoncorretto" to "eclipse-temurin", "aspnet" to "dotnet", "runtime" to "dotnet", "sdk" to "dotnet",
            "postgres" to "postgresql", "mysql" to "mysql", "mariadb" to "mariadb", "mongo" to "mongodb", "redis" to "redis", "nginx" to "nginx",
            "elasticsearch" to "elasticsearch", "tomcat" to "tomcat", "drupal" to "drupal", "deno" to "deno", "bun" to "bun",
        )
    }
}

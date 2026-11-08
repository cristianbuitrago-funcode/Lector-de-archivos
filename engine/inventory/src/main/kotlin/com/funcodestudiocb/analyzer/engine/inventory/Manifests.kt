package com.funcodestudiocb.analyzer.engine.inventory

import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeJson
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeXmlReader
import com.funcodestudiocb.analyzer.engine.inventory.parse.keys
import com.funcodestudiocb.analyzer.engine.inventory.parse.obj
import com.funcodestudiocb.analyzer.engine.inventory.parse.str
import com.google.re2j.Pattern
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Una referencia declarada en un archivo: lo que dice y en qué línea. */
data class Declared(val value: String, val line: Int)

/** Componente declarado en un AndroidManifest.xml. */
data class AndroidComponent(
    val tag: String,
    val name: String,
    val line: Int,
    val launcher: Boolean,
    val exported: Boolean?,
    val hasIntentFilter: Boolean,
    val targetActivity: String? = null,
)

data class AndroidManifestFacts(
    val packageName: String?,
    val application: AndroidComponent?,
    val components: List<AndroidComponent>,
    val wellFormed: Boolean,
)

data class NpmFacts(
    val main: Declared?,
    val bins: List<Declared>,
    /** Archivos que arrancan los scripts start/dev/serve (p. ej. "node server.js"). */
    val scriptFiles: List<Pair<String, Declared>>,
    val electron: Boolean,
    val frameworks: Set<String>,
)

class GodotFacts(val mainScene: Declared?, val autoloads: List<Pair<String, Declared>>)

/** Lectores de los manifiestos que declaran puntos de entrada. Todos toleran archivos mal formados. */
internal object Manifests {

    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /**
     * Los patrones de RE2/J no usan repeticiones acotadas grandes (`{1,300}`): RE2/J las compila con un anidamiento
     * que desborda la pila de los hilos de Android (Android 15/16). Las longitudes se comprueban en el código.
     */
    private const val MAX_NAME = 200
    private const val MAX_REFERENCE = 512

    /** Nombres de clase y escena admitidos como destino (sin caracteres extraños). */
    private val SAFE_TARGET = Regex("^[A-Za-z0-9_.$/:@\\-]{1,300}$")

    fun safeTarget(value: String?): String? = value?.trim()?.takeIf { SAFE_TARGET.matches(it) }

    fun androidManifest(text: String): AndroidManifestFacts {
        var packageName: String? = null
        var application: AndroidComponent? = null
        val components = mutableListOf<AndroidComponent>()
        var current: AndroidComponent? = null
        var inFilter = false
        var hasMain = false
        var hasLauncher = false
        val ok = SafeXmlReader(text).parse(
            onStart = { e ->
                val name = e.attribute("name", ANDROID_NS, "android")
                when (e.localName) {
                    "manifest" -> packageName = safeTarget(e.attribute("package"))
                    "application" -> application = safeTarget(name)?.let { AndroidComponent("application", it, e.line, false, null, false) }
                    "activity", "activity-alias", "service", "receiver", "provider" -> {
                        current = safeTarget(name)?.let {
                            AndroidComponent(
                                tag = e.localName,
                                name = it,
                                line = e.line,
                                launcher = false,
                                exported = e.attribute("exported", ANDROID_NS, "android")?.trim()?.lowercase()?.let { v -> v == "true" },
                                hasIntentFilter = false,
                                targetActivity = safeTarget(e.attribute("targetActivity", ANDROID_NS, "android")),
                            )
                        }
                    }
                    "intent-filter" -> {
                        inFilter = true
                        hasMain = false
                        hasLauncher = false
                        current = current?.copy(hasIntentFilter = true)
                    }
                    "action" -> if (inFilter && name == "android.intent.action.MAIN") hasMain = true
                    "category" -> if (inFilter && (name == "android.intent.category.LAUNCHER" || name == "android.intent.category.LEANBACK_LAUNCHER")) hasLauncher = true
                }
            },
            onEnd = { tag ->
                when (tag.substringAfter(':')) {
                    "intent-filter" -> {
                        if (hasMain && hasLauncher) current = current?.copy(launcher = true)
                        inFilter = false
                    }
                    "activity", "activity-alias", "service", "receiver", "provider" -> {
                        current?.let { components += it }
                        current = null
                    }
                }
            },
        )
        return AndroidManifestFacts(packageName, application, components, ok)
    }

    private val SCRIPT_RUNNERS = setOf("node", "nodemon", "ts-node", "tsx", "bun", "deno", "babel-node", "ts-node-dev", "electron")
    private val INTERESTING_DEPENDENCIES = setOf("electron", "react", "react-dom", "vue", "@angular/core", "next", "nuxt", "vite", "react-scripts", "svelte", "@sveltejs/kit", "express", "@nestjs/core", "react-native", "expo")

    fun npmPackage(text: String): NpmFacts? {
        val root = SafeJson.parse(text) as? JsonObject ?: return null
        fun lineOf(key: String): Int = lineOfKey(text, key)
        val main = root.str("main")?.let { Declared(it, lineOf("main")) }
        val bins = when (val bin = root["bin"]) {
            is JsonPrimitive -> listOfNotNull(bin.takeIf { it.isString }?.content?.let { Declared(it, lineOf("bin")) })
            is JsonObject -> bin.keys.mapNotNull { k -> bin.str(k)?.let { Declared(it, lineOf(k)) } }.take(20)
            else -> emptyList()
        }
        val scripts = root.obj("scripts")
        val scriptFiles = listOf("start", "dev", "serve", "server", "main").mapNotNull { name ->
            val command = scripts.str(name) ?: return@mapNotNull null
            scriptTarget(command)?.let { name to Declared(it, lineOf(name)) }
        }
        val deps = root.obj("dependencies").keys() + root.obj("devDependencies").keys()
        return NpmFacts(main, bins, scriptFiles, "electron" in deps, deps.intersect(INTERESTING_DEPENDENCIES))
    }

    /** "node --inspect server.js && …" → "server.js". */
    internal fun scriptTarget(command: String): String? {
        val tokens = command.split(Regex("\\s+|&&|;|\\|\\|")).filter { it.isNotEmpty() }
        for (i in tokens.indices) {
            if (tokens[i] !in SCRIPT_RUNNERS) continue
            val target = tokens.drop(i + 1).firstOrNull { !it.startsWith("-") && it != "run" } ?: continue
            if (target == ".") return null
            return target.trim('"', '\'')
        }
        return null
    }

    /** angular.json: archivo principal y página de cada proyecto. */
    fun angularMains(text: String): List<Pair<String, Declared>> {
        val root = SafeJson.parse(text) as? JsonObject ?: return emptyList()
        val projects = root.obj("projects") ?: return emptyList()
        return projects.keys.take(50).flatMap { name ->
            val options = projects.obj(name).obj("architect").obj("build").obj("options")
            listOfNotNull(
                (options.str("main") ?: options.str("browser"))?.let { "main" to Declared(it, lineOfKey(text, if (options.str("main") != null) "main" else "browser")) },
                options.str("index")?.let { "index" to Declared(it, lineOfKey(text, "index")) },
            )
        }
    }

    private val GODOT_MAIN = Pattern.compile("""(?m)^\s*run/main_scene\s*=\s*"([^"\r\n]+)"""")
    private val GODOT_SECTION = Pattern.compile("""(?m)^\s*\[([A-Za-z_]+)\]\s*$""")
    private val GODOT_AUTOLOAD = Pattern.compile("""(?m)^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"\*?(res://[^"\r\n]+)"""")

    fun godotProject(text: String, lineStarts: IntArray): GodotFacts {
        val main = GODOT_MAIN.matcher(text).let { m -> if (m.find()) Declared(m.group(1), ContentSignals.lineOf(lineStarts, m.start())) else null }
        // Autoloads: solo dentro de la sección [autoload].
        val autoloads = mutableListOf<Pair<String, Declared>>()
        val sections = GODOT_SECTION.matcher(text)
        var autoloadStart = -1
        var autoloadEnd = text.length
        while (sections.find()) {
            if (autoloadStart >= 0) {
                autoloadEnd = sections.start()
                break
            }
            if (sections.group(1) == "autoload") autoloadStart = sections.end()
        }
        if (autoloadStart >= 0) {
            val m = GODOT_AUTOLOAD.matcher(text.substring(autoloadStart, autoloadEnd))
            while (m.find() && autoloads.size < 100) {
                autoloads += m.group(1) to Declared(m.group(2), ContentSignals.lineOf(lineStarts, autoloadStart + m.start()))
            }
        }
        return GodotFacts(main, autoloads)
    }

    private val UNITY_SCENE = Pattern.compile("""(?m)^\s*-\s*enabled:\s*(\d)\s*$\s*path:\s*(\S[^\r\n]*)$""")

    /** Primera escena activada de EditorBuildSettings.asset: con ella arranca el juego. */
    fun unityFirstScene(text: String, lineStarts: IntArray): Declared? {
        val m = UNITY_SCENE.matcher(text)
        while (m.find()) {
            if (m.group(1) == "1") return Declared(m.group(2).trim(), ContentSignals.lineOf(lineStarts, m.start(2)))
        }
        return null
    }

    private val UNREAL_MAP = Pattern.compile("""(?m)^\s*GameDefaultMap\s*=\s*(/Game/[^\s\r\n]+)""")

    fun unrealDefaultMap(text: String, lineStarts: IntArray): Declared? {
        val m = UNREAL_MAP.matcher(text)
        return if (m.find()) Declared(m.group(1), ContentSignals.lineOf(lineStarts, m.start(1))) else null
    }

    private val HTML_SCRIPT = Pattern.compile("""(?i)<script\b[^>]*?\bsrc\s*=\s*["']([^"'>\s]+)["']""")

    /** Scripts locales que carga una página HTML. */
    fun htmlScripts(text: String, lineStarts: IntArray): List<Declared> {
        val out = mutableListOf<Declared>()
        val m = HTML_SCRIPT.matcher(text)
        while (m.find() && out.size < 50) {
            val src = m.group(1)
            if (src.startsWith("http:") || src.startsWith("https:") || src.startsWith("//") || src.startsWith("data:")) continue
            if (src.length > MAX_REFERENCE) continue
            out += Declared(src, ContentSignals.lineOf(lineStarts, m.start(1)))
        }
        return out
    }

    private val GRADLE_NAMESPACE = Pattern.compile("""\bnamespace\s*=?\s*["']([A-Za-z0-9_.]+)["']""")
    private val GRADLE_APPLICATION_ID = Pattern.compile("""\bapplicationId\s*=?\s*["']([A-Za-z0-9_.]+)["']""")

    fun gradleNamespace(text: String): String? =
        (
            GRADLE_NAMESPACE.matcher(text).let { if (it.find()) it.group(1) else null }
                ?: GRADLE_APPLICATION_ID.matcher(text).let { if (it.find()) it.group(1) else null }
            )?.takeIf { it.length <= MAX_NAME }

    private val JVM_PACKAGE = Pattern.compile("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")

    fun jvmPackage(text: String): String? = JVM_PACKAGE.matcher(text).let { if (it.find()) it.group(1) else null }?.takeIf { it.length <= MAX_NAME }

    private val CSPROJ_EXE = Pattern.compile("""(?i)<OutputType>\s*(?:Exe|WinExe)\s*</OutputType>|Sdk\s*=\s*"Microsoft\.NET\.Sdk\.(?:Web|Worker)"""")

    fun csprojIsExecutable(text: String): Boolean = CSPROJ_EXE.matcher(text).find()

    /** Línea aproximada de la primera aparición de `"clave"` (para la evidencia). */
    private fun lineOfKey(text: String, key: String): Int {
        val i = text.indexOf("\"$key\"")
        if (i < 0) return 1
        var line = 1
        for (k in 0 until i) if (text[k] == '\n') line++
        return line
    }
}

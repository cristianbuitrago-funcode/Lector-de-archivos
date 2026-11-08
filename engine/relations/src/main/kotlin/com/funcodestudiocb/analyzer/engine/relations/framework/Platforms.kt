package com.funcodestudiocb.analyzer.engine.relations.framework

import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.google.re2j.Pattern

/** Android, Unity, Godot, escritorio, sistemas de compilación y canales de mensajes entre procesos. */
internal object Platforms {

    /** Tipos de recurso de Android que son un archivo (`res/layout/x.xml`) y no un valor. */
    val FILE_RES = setOf("layout", "drawable", "mipmap", "menu", "xml", "raw", "navigation", "font", "anim", "animator", "color", "transition", "interpolator")
    private val VALUE_TAGS = mapOf(
        "string" to "string", "color" to "color", "dimen" to "dimen", "style" to "style", "bool" to "bool", "integer" to "integer",
        "array" to "array", "string-array" to "array", "integer-array" to "array", "plurals" to "plurals", "fraction" to "fraction",
    )
    private val RES_TYPES = FILE_RES + VALUE_TAGS.values

    private val R_REF = Pattern.compile("""(?:^|[^\w.])R\.(\w+)\.(\w+)""")
    private val XML_RES_REF = Pattern.compile("""@(\w+)/([\w.]+)""")
    private val VALUE_DEF = Pattern.compile("""<(string-array|integer-array|string|color|dimen|style|bool|integer|array|plurals|fraction)(?:\s[^>]*)?\sname\s*=\s*"([^"]+)"""")
    private val COMPONENT = Pattern.compile("""<(application|activity|activity-alias|service|receiver|provider)\b[^>]*>""")
    private val ACTION = Pattern.compile("""<action\b[^>]*android:name\s*=\s*"([^"]+)"""")
    private val VIEW_TAG = Pattern.compile("""<([a-z][\w]*(?:\.[\w]+)+)[\s/>]""")
    private val FRAGMENT_TAG = Pattern.compile("""<(?:fragment|androidx\.fragment\.app\.FragmentContainerView|dialog|activity)\b[^>]*?\b(?:android:name|class)\s*=\s*"([^"]+)"""")
    private val BINDING = Pattern.compile("""\b([A-Z]\w*)Binding\.(?:inflate|bind)\b""")
    private val INTENT = Pattern.compile("""Intent\s*\(\s*[\w@.]+\s*,\s*([A-Z]\w*)(?:::class\.java|\.class)\s*\)""")
    private val SET_CLASS = Pattern.compile("""\.setClass(?:Name)?\s*\(\s*[\w@.]+\s*,\s*([A-Z]\w*)(?:::class\.java|\.class)""")
    private val GUID_LINE = Pattern.compile("""(\w+):\s*\{[^}\n]*?\bguid:\s*([0-9a-f]{32})""")
    private val META_GUID = Pattern.compile("""(?m)^guid:\s*([0-9a-f]{32})""")
    private val SCENE_GUID = Pattern.compile("""(?m)^\s*(guid):\s*([0-9a-f]{32})""")
    private val EXT_RESOURCE = Pattern.compile("""\[ext_resource\b[^\]]*\]""")
    private val GODOT_ATTR_PATH = Pattern.compile("""\bpath\s*=\s*"([^"]+)"""")
    private val GODOT_ATTR_TYPE = Pattern.compile("""\btype\s*=\s*"([^"]+)"""")
    private val CMAKE_CALL = Pattern.compile("""(?i)\b(add_executable|add_library|target_sources|qt_add_executable|qt_add_library|qt6_add_executable|add_subdirectory|set|list)\s*\(""")
    private val SOURCE_EXT = setOf("c", "cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx", "inl", "m", "mm", "cu", "rc", "ui", "qrc", "qml", "swift", "s", "asm")
    private val UNITY_TEXT_ASSETS = setOf("unity", "prefab", "asset", "mat", "controller", "anim", "overridecontroller", "playable", "spriteatlas", "mixer", "lighting", "physicmaterial", "flare", "guiskin", "fontsettings")
    private val SOCKET_BUILTINS = setOf("connect", "connection", "disconnect", "disconnecting", "error", "connect_error", "reconnect", "reconnect_attempt", "ping", "pong", "message", "data", "close", "open", "end", "finish", "listening", "request", "upgrade", "drain")

    fun extract(src: Src, out: FrameworkFacts) {
        val name = src.info.lower
        val ext = src.info.ext.lowercase()
        when {
            name == "androidmanifest.xml" -> manifest(src, out)
            ext == "xml" && src.info.lowerDirs.contains("res") -> resXml(src, out)
            name == "build.gradle" || name == "build.gradle.kts" -> gradle(src, out)
            name == "settings.gradle" || name == "settings.gradle.kts" -> gradleSettings(src, out)
            ext == "meta" -> META_GUID.matcher(src.text).let { if (it.find()) out.guid = it.group(1) }
            ext in UNITY_TEXT_ASSETS -> unityAsset(src, out)
            ext == "tscn" || ext == "tres" -> godotScene(src, out)
            name == "project.godot" -> godotProject(src, out)
            ext == "xaml" || ext == "axaml" -> attrClass(src, out, "x:Class", "code-behind (x:Class)")
            ext == "fxml" -> {
                attrClass(src, out, "fx:controller", "controlador de la vista (fx:controller)")
                Regexes.attr(src.text, "source")?.let { out.pathRefs += PathRefFact(it, 1, RelationKind.RESOURCE, "fx:include") }
            }
            name == "cmakelists.txt" || ext == "cmake" -> cmake(src, out)
            ext == "pro" || ext == "pri" -> qmake(src, out)
            ext == "qrc" -> qrc(src, out)
            name == "package.json" -> packageJson(src, out)
        }
        when (src.language) {
            "java", "kotlin" -> {
                androidCode(src, out)
                jvmResources(src, out)
            }
            "javascript", "typescript", "tsx", "vue", "svelte", "html" -> javascript(src, out)
            "csharp" -> {
                unityCode(src, out)
                if (src.has("res://")) resPaths(src, out)
            }
            "gdscript" -> resPaths(src, out)
            "dart" -> flutterChannels(src, out, send = true)
            "swift", "objc", "objective-c" -> flutterChannels(src, out, send = false)
            "rust" -> tauriCommands(src, out)
        }
    }

    // ------------------------------------------------------------------ Android

    private fun manifest(src: Src, out: FrameworkFacts) {
        val text = src.text
        Regexes.attr(text.substring(0, minOf(text.length, 4_000)).substringAfter("<manifest", ""), "package")?.let { out.androidPackage = it }
        val m = COMPONENT.matcher(text)
        while (m.find()) {
            if (!src.isCode(m.start())) continue
            val tag = m.group(0)
            val cls = Regexes.attr(tag, "android:name") ?: continue
            out.classRefs += ClassRefFact(cls, src.line(m.start()), RelationKind.REGISTRATION, "${m.group(1)} declarada en el manifiesto")
            Regexes.attr(tag, "android:targetActivity")?.let { out.classRefs += ClassRefFact(it, src.line(m.start()), RelationKind.REGISTRATION, "activity-alias") }
            if (m.group(1) == "receiver") {
                // Acciones del receptor: el otro extremo de los broadcasts.
                val end = text.indexOf("</receiver>", m.end()).let { if (it < 0) m.end() else it }
                val a = ACTION.matcher(text.substring(m.end(), end))
                while (a.find()) {
                    val action = a.group(1)
                    if (!action.startsWith("android.")) out.channels += ChannelFact("android-broadcast", action, false, src.line(m.end() + a.start()), "intent-filter del receptor", cls)
                }
            }
        }
        xmlResRefs(src, out)
    }

    private fun resXml(src: Src, out: FrameworkFacts) {
        val text = src.text
        val dir = src.info.lowerDirs.lastOrNull() ?: ""
        if (dir == "values" || dir.startsWith("values-")) {
            val m = VALUE_DEF.matcher(text)
            while (m.find() && out.resDefs.size < 5_000) {
                if (src.isCode(m.start())) out.resDefs += ResDefFact(VALUE_TAGS.getValue(m.group(1)), m.group(2).replace('.', '_'), src.line(m.start()))
            }
        }
        xmlResRefs(src, out)
        // Vistas propias en los layouts y fragmentos o destinos con su clase.
        val v = VIEW_TAG.matcher(text)
        while (v.find()) {
            val cls = v.group(1)
            if (!src.isCode(v.start()) || isLibraryClass(cls)) continue
            out.classRefs += ClassRefFact(cls, src.line(v.start()), RelationKind.REFERENCE, "vista propia en el layout")
        }
        val f = FRAGMENT_TAG.matcher(text)
        val navigation = dir == "navigation" || dir.startsWith("navigation-")
        while (f.find()) {
            val cls = f.group(1)
            if (!src.isCode(f.start()) || isLibraryClass(cls)) continue
            out.classRefs += ClassRefFact(cls, src.line(f.start()), if (navigation) RelationKind.REGISTRATION else RelationKind.REFERENCE, if (navigation) "destino del grafo de navegación" else "fragmento del layout")
        }
    }

    private fun isLibraryClass(cls: String) = listOf("android.", "androidx.", "com.google.android.", "com.google.firebase.", "com.facebook.", "kotlin.", "java.").any { cls.startsWith(it) }

    private fun xmlResRefs(src: Src, out: FrameworkFacts) {
        val m = XML_RES_REF.matcher(src.text)
        while (m.find() && out.resUses.size < 5_000) {
            val type = m.group(1)
            if (type !in RES_TYPES || !src.isCode(m.start())) continue
            if (m.start() > 0 && src.text[m.start() - 1] == '+') continue
            out.resUses += ResUseFact(type, m.group(2).replace('.', '_'), src.line(m.start()), "@$type/")
        }
    }

    private fun gradle(src: Src, out: FrameworkFacts) {
        for (key in listOf("namespace", "applicationId")) {
            src.eachWord(key) { at ->
                var k = src.skipWs(at + key.length)
                if (src.text.getOrNull(k) == '=') k++
                val lit = src.literal(k) ?: return@eachWord
                if (!lit.dynamic && out.androidPackage == null) out.androidPackage = lit.value
            }
        }
    }

    /** `include(":app", ":core:data")` → `app/build.gradle(.kts)`, `core/data/build.gradle(.kts)`. */
    private fun gradleSettings(src: Src, out: FrameworkFacts) {
        src.eachWord("include") { at ->
            val stop = src.text.indexOf('\n', at).let { if (it < 0) src.text.length else it }
            var k = at + 7
            while (k < stop) {
                val q = src.text.indexOf('"', k).let { d -> val s = src.text.indexOf('\'', k); if (d < 0) s else if (s < 0) d else minOf(d, s) }
                if (q < 0 || q >= stop) break
                val lit = src.literal(q) ?: break
                if (lit.value.startsWith(":")) {
                    out.pathRefs += PathRefFact(lit.value.trim(':').replace(':', '/') + "/build.gradle", src.line(at), RelationKind.REGISTRATION, "módulo incluido en settings.gradle")
                }
                k = lit.end
            }
        }
    }

    private fun androidCode(src: Src, out: FrameworkFacts) {
        val text = src.text
        if (text.contains("R.")) {
            val m = R_REF.matcher(text)
            while (m.find() && out.resUses.size < 5_000) {
                val type = m.group(1)
                if (type !in RES_TYPES || !src.isCode(m.start(1).coerceAtLeast(0)) || src.inString(m.start(1))) continue
                if (m.start() >= 8 && text.startsWith("android.", m.start() - 7)) continue
                out.resUses += ResUseFact(type, m.group(2), src.line(m.start()), "R.$type")
            }
        }
        if (text.contains("Binding.")) {
            val b = BINDING.matcher(text)
            while (b.find()) {
                if (!src.isCode(b.start()) || src.inString(b.start())) continue
                val base = b.group(1)
                if (base == "DataBindingUtil" || base == "View") continue
                out.resUses += ResUseFact("layout", Data.snake(base), src.line(b.start()), "ViewBinding ${base}Binding")
            }
        }
        if (text.contains("Intent")) {
            for (p in listOf(INTENT, SET_CLASS)) {
                val m = p.matcher(text)
                while (m.find()) {
                    if (src.isCode(m.start()) && !src.inString(m.start())) out.classRefs += ClassRefFact(m.group(1), src.line(m.start()), RelationKind.IPC, "Intent explícito")
                }
            }
            // Broadcasts con acción propia: sendBroadcast(Intent("com.x.ACCION")) / IntentFilter("com.x.ACCION")
            for ((call, send) in listOf("Intent(" to true, "IntentFilter(" to false)) {
                src.eachWord(call) { at ->
                    val lit = src.literal(at + call.length) ?: return@eachWord
                    if (lit.dynamic || !lit.value.contains('.') || lit.value.startsWith("android.") || src.skipWs(lit.end).let { text.getOrNull(it) } != ')') return@eachWord
                    out.channels += ChannelFact("android-broadcast", lit.value, send, src.line(at), if (send) "Intent con acción" else "IntentFilter")
                }
            }
        }
        if (src.has("MethodChannel(") || src.has("EventChannel(")) flutterChannels(src, out, send = false)
    }

    /** `getResource("/views/main.fxml")` (JavaFX y recursos de Java). */
    private fun jvmResources(src: Src, out: FrameworkFacts) {
        for (fn in listOf("getResource(", "getResourceAsStream(")) {
            src.each(fn) { at ->
                val lit = src.args(at + fn.length - 1)?.firstOrNull()?.let { src.literalArg(it) } ?: return@each
                if (!lit.dynamic && lit.value.contains('.')) out.pathRefs += PathRefFact(lit.value, src.line(at), RelationKind.RESOURCE, "recurso de Java ($fn)", relative = false)
            }
        }
    }

    // ------------------------------------------------------------------ Unity

    private fun unityAsset(src: Src, out: FrameworkFacts) {
        // EditorBuildSettings.asset: las escenas de la compilación llevan el GUID en su propia línea.
        val m = (if (src.info.lower == "editorbuildsettings.asset") SCENE_GUID else GUID_LINE).matcher(src.text)
        val seen = HashSet<String>()
        while (m.find() && out.guidUses.size < 5_000) {
            val guid = m.group(2)
            if (guid.all { it == '0' } || !seen.add(guid)) continue
            out.guidUses += GuidUseFact(guid, src.line(m.start()), m.group(1))
        }
    }

    private fun unityCode(src: Src, out: FrameworkFacts) {
        for (fn in listOf("Resources.Load(", "Resources.Load<", "Resources.LoadAsync(", "Resources.LoadAsync<", "Resources.LoadAll(", "Resources.LoadAll<")) {
            src.each(fn) { at ->
                var open = at + fn.length - 1
                if (src.text[open] == '<') open = src.close(open).let { if (it < 0) return@each else it + 1 }
                val lit = src.args(open)?.firstOrNull()?.let { src.literalArg(it) } ?: return@each
                if (!lit.dynamic) out.pathRefs += PathRefFact(lit.value, src.line(at), RelationKind.RESOURCE, "Resources.Load", relative = false)
            }
        }
        for (fn in listOf("SceneManager.LoadScene(", "SceneManager.LoadSceneAsync(", "Application.LoadLevel(")) {
            src.each(fn) { at ->
                val lit = src.args(at + fn.length - 1)?.firstOrNull()?.let { src.literalArg(it) } ?: return@each
                if (!lit.dynamic) out.pathRefs += PathRefFact(lit.value, src.line(at), RelationKind.RESOURCE, "LoadScene", relative = false)
            }
        }
    }

    // ------------------------------------------------------------------ Godot

    private fun godotScene(src: Src, out: FrameworkFacts) {
        val m = EXT_RESOURCE.matcher(src.text)
        while (m.find() && out.pathRefs.size < 2_000) {
            val tag = m.group(0)
            val path = GODOT_ATTR_PATH.matcher(tag).let { if (it.find()) it.group(1) else null } ?: continue
            val type = GODOT_ATTR_TYPE.matcher(tag).let { if (it.find()) it.group(1) else null }
            val kind = if (type == "Script") RelationKind.REFERENCE else RelationKind.RESOURCE
            out.pathRefs += PathRefFact(path, src.line(m.start()), kind, if (type == "Script") "script de la escena" else "recurso de la escena (${type ?: "?"})", relative = false)
        }
    }

    private fun godotProject(src: Src, out: FrameworkFacts) {
        var section = ""
        var start = 0
        val text = src.text
        while (start < text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val line = text.substring(start, end).trim()
            when {
                line.startsWith("[") -> section = line.trim('[', ']')
                section == "application" && line.startsWith("run/main_scene") -> godotValue(line)?.let { out.pathRefs += PathRefFact(it, src.line(start), RelationKind.REGISTRATION, "escena principal", relative = false) }
                section == "autoload" && line.contains('=') -> godotValue(line)?.let { out.pathRefs += PathRefFact(it.removePrefix("*"), src.line(start), RelationKind.REGISTRATION, "autoload «${line.substringBefore('=').trim()}»", relative = false) }
            }
            start = end + 1
        }
    }

    private fun godotValue(line: String): String? = line.substringAfter('=').trim().trim('"').takeIf { it.removePrefix("*").startsWith("res://") }

    /** Cadenas `"res://…"` en scripts (salvo `preload`/`load`, que ya trata el análisis de lenguaje). */
    private fun resPaths(src: Src, out: FrameworkFacts) {
        src.each("\"res://") { at -> resPath(src, out, at) }
        src.each("'res://") { at -> resPath(src, out, at) }
    }

    private fun resPath(src: Src, out: FrameworkFacts, at: Int) {
        val before = src.text.substring(maxOf(0, at - 10), at).trimEnd()
        if (src.language == "gdscript" && (before.endsWith("preload(") || before.endsWith("load("))) return
        val lit = src.literal(at) ?: return
        if (!lit.dynamic && out.pathRefs.size < 2_000) out.pathRefs += PathRefFact(lit.value, src.line(at), RelationKind.RESOURCE, "ruta res://", relative = false)
    }

    // ------------------------------------------------------------------ escritorio y compilación

    private fun attrClass(src: Src, out: FrameworkFacts, attr: String, via: String) {
        val i = src.text.indexOf("$attr=")
        if (i < 0) return
        val lit = src.literal(i + attr.length + 1) ?: return
        out.classRefs += ClassRefFact(lit.value, src.line(i), RelationKind.REFERENCE, via)
    }

    /**
     * CMake: fuentes de `add_executable`, `add_library` y `target_sources`, siguiendo las variables de `set()` y
     * `list(APPEND|TRANSFORM … PREPEND)` del mismo archivo. Una ruta construida con variables no se da por rota.
     */
    private fun cmake(src: Src, out: FrameworkFacts) {
        val vars = HashMap<String, List<String>>()
        listOf("CMAKE_CURRENT_SOURCE_DIR", "PROJECT_SOURCE_DIR", "CMAKE_CURRENT_LIST_DIR", "CMAKE_SOURCE_DIR").forEach { vars[it] = listOf(".") }
        val m = CMAKE_CALL.matcher(src.text)
        while (m.find() && out.pathRefs.size < 2_000) {
            if (!src.isCode(m.start())) continue
            val fn = m.group(1).lowercase()
            val open = m.end() - 1
            val close = src.close(open)
            if (close < 0) continue
            val tokens = cmakeTokens(src.text.substring(open + 1, close))
            when (fn) {
                "set" -> {
                    val name = tokens.firstOrNull() ?: continue
                    vars[name] = tokens.drop(1).filter { it !in CMAKE_KEYWORDS }.flatMap { expand(it, vars) ?: listOf("\u0000") }
                    continue
                }
                "list" -> {
                    val op = tokens.getOrNull(0)?.uppercase()
                    val name = tokens.getOrNull(1) ?: continue
                    val current = vars[name].orEmpty()
                    when {
                        op == "APPEND" -> vars[name] = current + tokens.drop(2).flatMap { expand(it, vars) ?: listOf("\u0000") }
                        op == "TRANSFORM" && tokens.getOrNull(2)?.uppercase() == "PREPEND" -> {
                            val prefix = tokens.getOrNull(3)?.let { expand(it, vars)?.singleOrNull() } ?: "\u0000"
                            vars[name] = current.map { prefix + it }
                        }
                    }
                    continue
                }
                "add_subdirectory" -> {
                    tokens.firstOrNull()?.let { out.pathRefs += PathRefFact("$it/CMakeLists.txt", src.line(m.start()), RelationKind.REGISTRATION, "add_subdirectory de CMake") }
                    continue
                }
            }
            for (t in tokens.drop(1)) {
                val usesVars = t.contains("\${")
                for (value in expand(t, vars).orEmpty()) {
                    if (value.contains("\u0000") || value.contains("$<")) continue
                    val clean = value.replace("/./", "/").removePrefix("./")
                    if (clean.substringAfterLast('.', "").lowercase() in SOURCE_EXT) {
                        out.pathRefs += PathRefFact(clean, src.line(m.start()), RelationKind.REGISTRATION, "$fn de CMake", certain = !usesVars)
                    }
                }
            }
        }
    }

    private val CMAKE_KEYWORDS = setOf("PARENT_SCOPE", "CACHE", "STRING", "PATH", "FILEPATH", "BOOL", "INTERNAL", "FORCE")

    /** Sustituye `${VAR}`; una variable que es una lista da varios valores; `null` si no se conoce. */
    private fun expand(token: String, vars: Map<String, List<String>>): List<String>? {
        var results = listOf(token)
        var guard = 0
        while (results.any { it.contains("\${") } && guard++ < 5) {
            results = results.flatMap { t ->
                val i = t.indexOf("\${")
                if (i < 0) return@flatMap listOf(t)
                val end = t.indexOf('}', i)
                if (end < 0) return null
                val values = vars[t.substring(i + 2, end)] ?: return null
                values.map { v -> t.substring(0, i) + v + t.substring(end + 1) }
            }
        }
        return results.takeIf { r -> r.none { it.contains("\${") } }
    }

    private fun cmakeTokens(args: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        for (c in args) {
            when {
                c == '"' -> quoted = !quoted
                (c.isWhitespace() && !quoted) -> {
                    if (sb.isNotEmpty()) out += sb.toString()
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out.filter { !it.startsWith("#") }
    }

    /** `SOURCES += a.cpp b.cpp \` de qmake. */
    private fun qmake(src: Src, out: FrameworkFacts) {
        var key: String? = null
        var start = 0
        val text = src.text
        while (start < text.length && out.pathRefs.size < 2_000) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            var line = text.substring(start, end).substringBefore('#')
            val cont = line.trimEnd().endsWith("\\")
            line = line.trimEnd().removeSuffix("\\")
            val eq = line.indexOf("+=").takeIf { it >= 0 } ?: line.indexOf('=').takeIf { it >= 0 && key == null }
            val values: String
            if (eq != null && key == null) {
                key = line.substring(0, eq).trim().uppercase()
                values = line.substring(eq + if (line.startsWith("+=", eq)) 2 else 1)
            } else {
                values = line
            }
            if (key in setOf("SOURCES", "HEADERS", "FORMS", "RESOURCES", "OBJECTIVE_SOURCES")) {
                values.split(' ', '\t').map { it.trim() }.filter { it.isNotEmpty() && !it.contains('$') }.forEach {
                    out.pathRefs += PathRefFact(it, src.line(start), RelationKind.REGISTRATION, "$key del proyecto de Qt")
                }
            }
            if (!cont) key = null
            start = end + 1
        }
    }

    private fun qrc(src: Src, out: FrameworkFacts) {
        var i = src.text.indexOf("<file")
        while (i >= 0 && out.pathRefs.size < 2_000) {
            val gt = src.text.indexOf('>', i)
            val close = src.text.indexOf("</file>", gt)
            if (gt < 0 || close < 0) break
            val path = src.text.substring(gt + 1, close).trim()
            if (path.isNotEmpty()) out.pathRefs += PathRefFact(path, src.line(i), RelationKind.RESOURCE, "recurso de Qt (.qrc)")
            i = src.text.indexOf("<file", close)
        }
    }

    /** `"main"`, `"module"`, `"bin"` de package.json: el archivo con el que arranca el paquete. */
    private fun packageJson(src: Src, out: FrameworkFacts) {
        for (key in listOf("main", "module", "browser", "types")) {
            val i = src.text.indexOf("\"$key\"")
            if (i < 0) continue
            val colon = src.skipWs(i + key.length + 2)
            if (src.text.getOrNull(colon) != ':') continue
            val lit = src.literal(colon + 1) ?: continue
            if (!lit.dynamic && lit.value.contains('.')) out.pathRefs += PathRefFact(lit.value.removePrefix("./"), src.line(i), RelationKind.REGISTRATION, "\"$key\" de package.json")
        }
    }

    // ------------------------------------------------------------------ JavaScript: workers, Electron, socket.io, Tauri

    private fun javascript(src: Src, out: FrameworkFacts) {
        for (fn in listOf("new Worker(", "new SharedWorker(")) {
            src.each(fn) { at ->
                val arg = src.args(at + fn.length - 1)?.firstOrNull() ?: return@each
                val s = src.skipWs(arg.first)
                val lit = if (src.text.startsWith("new URL(", s)) src.args(s + 7)?.firstOrNull()?.let { src.literalArg(it) } else src.literalArg(arg)
                if (lit != null && !lit.dynamic) out.pathRefs += PathRefFact(lit.value, src.line(at), RelationKind.RESOURCE, "Web Worker")
            }
        }
        src.each("serviceWorker.register(") { at ->
            val lit = src.args(at + "serviceWorker.register".length)?.firstOrNull()?.let { src.literalArg(it) } ?: return@each
            if (!lit.dynamic) out.pathRefs += PathRefFact(lit.value, src.line(at), RelationKind.RESOURCE, "Service Worker")
        }
        if (src.has("ipcMain") || src.has("ipcRenderer") || src.has("webContents")) {
            for ((call, bus, send) in listOf(
                Triple("ipcMain.on(", "electron", false), Triple("ipcMain.handle(", "electron", false), Triple("ipcMain.once(", "electron", false),
                Triple("ipcMain.handleOnce(", "electron", false), Triple("ipcRenderer.send(", "electron", true), Triple("ipcRenderer.invoke(", "electron", true),
                Triple("ipcRenderer.sendSync(", "electron", true), Triple("webContents.send(", "electron-renderer", true), Triple("ipcRenderer.on(", "electron-renderer", false),
                Triple("ipcRenderer.once(", "electron-renderer", false),
            )) {
                channel(src, out, call, bus, send, "Electron ${call.removeSuffix("(")}")
            }
        }
        if (src.has("contextBridge.exposeInMainWorld(")) {
            src.each("contextBridge.exposeInMainWorld(") { at ->
                val lit = src.args(at + "contextBridge.exposeInMainWorld".length)?.firstOrNull()?.let { src.literalArg(it) } ?: return@each
                if (!lit.dynamic) out.channels += ChannelFact("bridge", lit.value, false, src.line(at), "contextBridge.exposeInMainWorld")
            }
        }
        src.each("window.") { at ->
            if (at > 0 && (Src.isIdent(src.text[at - 1]) || src.text[at - 1] == '.')) return@each
            val name = src.wordAt(at + 7) ?: return@each
            if (src.text.getOrNull(at + 7 + name.length) != '.' || name in WINDOW_PROPS) return@each
            if (out.channels.none { it.bus == "bridge" && it.name == name && it.send }) out.channels += ChannelFact("bridge", name, true, src.line(at), "window.$name")
        }
        if (src.has("socket.io") || src.has("io(") && src.has("socket")) {
            for ((call, send) in listOf(".emit(" to true, ".on(" to false, ".once(" to false)) {
                src.each(call) { at ->
                    val recv = src.receiverBefore(at) ?: return@each
                    if (!(recv.contains("socket", true) || recv == "io" || recv.endsWith(".io") || recv == "client")) return@each
                    val lit = src.args(at + call.length - 1)?.firstOrNull()?.let { src.literalArg(it) } ?: return@each
                    if (lit.dynamic || lit.value in SOCKET_BUILTINS) return@each
                    out.channels += ChannelFact("socket.io", lit.value, send, src.line(at), "socket.io ${call.trim('.', '(')}")
                }
            }
        }
        if (src.has("@tauri-apps")) channel(src, out, "invoke(", "tauri", true, "Tauri invoke", word = true)
    }

    private val WINDOW_PROPS = setOf(
        "location", "document", "localStorage", "sessionStorage", "navigator", "history", "screen", "console", "performance", "crypto", "parent", "top",
        "opener", "frames", "visualViewport", "matchMedia", "getComputedStyle", "speechSynthesis", "indexedDB", "caches", "customElements",
        "addEventListener", "removeEventListener", "dispatchEvent", "innerWidth", "innerHeight", "scrollX", "scrollY", "devicePixelRatio", "self",
        "dataLayer", "gtag", "ga", "__NEXT_DATA__", "__NUXT__", "process", "electron",
    )

    private fun channel(src: Src, out: FrameworkFacts, call: String, bus: String, send: Boolean, via: String, word: Boolean = false) {
        val action: (Int) -> Unit = { at ->
            val lit = src.args(at + call.length - 1)?.firstOrNull()?.let { src.literalArg(it) }
            if (lit != null && !lit.dynamic && out.channels.size < 2_000) out.channels += ChannelFact(bus, lit.value, send, src.line(at), via)
        }
        if (word) src.eachWord(call, action) else src.each(call, action)
    }

    /** Canales de plataforma de Flutter: Dart los usa; Kotlin, Java, Swift y Objective-C los atienden. */
    private fun flutterChannels(src: Src, out: FrameworkFacts, send: Boolean) {
        for (ctor in listOf("MethodChannel(", "EventChannel(", "BasicMessageChannel(", "FlutterMethodChannel(", "FlutterEventChannel(")) {
            src.eachWord(ctor) { at ->
                val args = src.args(at + ctor.length - 1) ?: return@eachWord
                val lit = (Routes.namedArg(src, args, "name") ?: args.firstOrNull { a -> src.literalArg(a) != null })?.let { src.literalArg(it) } ?: return@eachWord
                if (!lit.dynamic) out.channels += ChannelFact("flutter-channel", lit.value, send, src.line(at), ctor.removeSuffix("("))
            }
        }
    }

    private fun tauriCommands(src: Src, out: FrameworkFacts) {
        src.each("#[tauri::command") { at ->
            val fn = src.text.indexOf("fn ", at).takeIf { it in at..at + 300 } ?: return@each
            src.wordAt(src.skipWs(fn + 3))?.let { out.channels += ChannelFact("tauri", it, false, src.line(fn), "#[tauri::command]") }
        }
    }
}

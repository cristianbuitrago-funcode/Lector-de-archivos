package com.funcodestudiocb.analyzer.engine.inventory

import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.api.EntryPoint
import com.funcodestudiocb.analyzer.engine.api.EntryPointKind
import com.funcodestudiocb.analyzer.engine.api.Evidence
import com.funcodestudiocb.analyzer.engine.api.EvidenceKind
import com.funcodestudiocb.analyzer.engine.api.ProjectRoot
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.ingest.EntryNames

/** Detecta las carpetas de subproyectos por sus manifiestos (un comprimido puede contener varios). */
internal object ProjectRoots {

    fun detect(paths: Collection<String>): List<ProjectRoot> {
        val all = paths.toHashSet()
        val roots = LinkedHashMap<Pair<String, Ecosystem>, ProjectRoot>()
        fun add(directory: String, ecosystem: Ecosystem, marker: String) {
            roots.putIfAbsent(directory to ecosystem, ProjectRoot(directory, ecosystem, marker))
        }
        for (path in paths) {
            val info = PathInfo(path)
            val dir = info.directory
            when {
                info.lower in setOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts") -> add(dir, Ecosystem.GRADLE, path)
                info.lower == "androidmanifest.xml" -> add(moduleDirOf(path), Ecosystem.ANDROID, path)
                info.lower == "pom.xml" -> add(dir, Ecosystem.MAVEN, path)
                info.lower == "package.json" -> add(dir, Ecosystem.NPM, path)
                info.lower in setOf("requirements.txt", "pyproject.toml", "setup.py", "pipfile") -> add(dir, Ecosystem.PYTHON, path)
                info.lower == "composer.json" -> add(dir, Ecosystem.PHP_COMPOSER, path)
                info.lower == "pubspec.yaml" -> add(dir, Ecosystem.DART_PUB, path)
                info.ext in setOf("csproj", "fsproj", "vbproj", "sln") -> add(dir, Ecosystem.DOTNET, path)
                info.lower == "go.mod" -> add(dir, Ecosystem.GO, path)
                info.lower == "cargo.toml" -> add(dir, Ecosystem.RUST_CARGO, path)
                info.lower == "gemfile" -> add(dir, Ecosystem.RUBY, path)
                info.lower == "podfile" -> add(dir, Ecosystem.COCOAPODS, path)
                info.lower == "cmakelists.txt" -> {
                    val parent = dir.substringBeforeLast('/', "")
                    val parentHasCmake = dir.isNotEmpty() && (if (parent.isEmpty()) "CMakeLists.txt" else "$parent/CMakeLists.txt") in all
                    if (!parentHasCmake) add(dir, Ecosystem.CMAKE, path)
                }
                info.lower == "projectversion.txt" && info.parent == "projectsettings" -> add(dir.substringBeforeLast('/', ""), Ecosystem.UNITY, path)
                info.ext == "uproject" -> add(dir, Ecosystem.UNREAL, path)
                info.lower == "project.godot" -> add(dir, Ecosystem.GODOT, path)
            }
        }
        return roots.values.toList()
    }

    /** Carpeta del módulo de un AndroidManifest.xml: lo que hay antes de "/src/" (app/src/main/… → app). */
    fun moduleDirOf(path: String): String {
        val p = "/$path"
        val i = p.lastIndexOf("/src/")
        return if (i >= 0) p.substring(1, i.coerceAtLeast(1)).let { if (i == 0) "" else it } else PathInfo(path).directory
    }
}

/** Acumula puntos de entrada y fusiona los repetidos (misma clase, archivo y destino). */
internal class EntryPointBuilder {
    private class Acc(val kind: EntryPointKind, val path: String?, val target: String?, var confidence: Confidence, val evidence: MutableList<Evidence>, val root: String?)

    private val entries = LinkedHashMap<Triple<EntryPointKind, String?, String?>, Acc>()

    fun add(kind: EntryPointKind, path: String?, target: String?, confidence: Confidence, evidence: Evidence, root: String?) {
        val key = Triple(kind, path, if (path == null) target else null)
        val acc = entries.getOrPut(key) { Acc(kind, path, target, confidence, mutableListOf(), root) }
        if (confidence.ordinal < acc.confidence.ordinal) acc.confidence = confidence
        if (acc.evidence.size < RecordValidator.MAX_EVIDENCE) acc.evidence += evidence.trimmed()
    }

    fun has(path: String, kinds: Set<EntryPointKind>): Boolean = entries.values.any { it.path == path && it.kind in kinds }

    fun build(): List<EntryPoint> = entries.values.map { EntryPoint(it.kind, it.path, it.target, it.confidence, it.evidence.toList(), it.root) }
}

internal fun Evidence.trimmed(): Evidence = if (detail.length <= RecordValidator.MAX_EVIDENCE_DETAIL) this else copy(detail = detail.take(RecordValidator.MAX_EVIDENCE_DETAIL - 1) + "…")

/**
 * Reúne los puntos de entrada: los declarados en manifiestos (certeza "confirmada"), los encontrados por
 * patrones de texto y los de convenciones de los frameworks ("probables"). Si un manifiesto declara algo que
 * no está en el comprimido, se informa sin ruta en lugar de inventar un archivo.
 */
internal class EntryPointResolver(
    private val collector: ContentCollector,
    /** Rutas (escapadas) de todos los archivos del proyecto. */
    private val paths: Set<String>,
    private val roots: List<ProjectRoot>,
    private val rootOf: (String) -> String?,
) {
    private val builder = EntryPointBuilder()
    private val jvmFilesBySimpleName: Map<String, List<String>> = paths
        .filter { it.endsWith(".kt") || it.endsWith(".java") }
        .groupBy { PathInfo(it).stem }

    fun resolve(): List<EntryPoint> {
        fromContent()
        fromAndroid()
        fromNpm()
        fromAngular()
        fromHtml()
        fromGameEngines()
        fromConventions()
        return builder.build()
    }

    private fun declaration(detail: String, path: String, line: Int?) = Evidence(EvidenceKind.DECLARATION, detail, path, line)

    private fun fromContent() {
        for ((path, facts) in collector.facts) {
            for (hit in facts.signals) {
                val kind = hit.entry ?: continue
                if (kind == EntryPointKind.PHP_FRONT_CONTROLLER && PathInfo(path).lower != "index.php") continue
                builder.add(kind, path, null, Confidence.PROBABLE, Evidence(EvidenceKind.CONTENT, "Contiene ${hit.label}", line = hit.line), rootOf(path))
            }
        }
    }

    private fun fromAndroid() {
        for ((manifest, facts) in collector.androidManifests) {
            val module = ProjectRoots.moduleDirOf(manifest)
            val pkg = facts.packageName ?: collector.gradleNamespaces[module]
            val root = rootOf(manifest)
            facts.application?.let { app ->
                addAndroid(EntryPointKind.ANDROID_APPLICATION, app.name, pkg, module, root, declaration("Clase Application declarada en el manifiesto de Android", manifest, app.line))
            }
            for (c in facts.components) {
                when {
                    c.launcher -> {
                        val cls = if (c.tag == "activity-alias") c.targetActivity ?: c.name else c.name
                        addAndroid(EntryPointKind.ANDROID_LAUNCHER, cls, pkg, module, root, declaration("Actividad del lanzador (MAIN/LAUNCHER) en el manifiesto de Android", manifest, c.line))
                    }
                    c.exported == true || (c.exported == null && c.hasIntentFilter) -> {
                        val what = when (c.tag) {
                            "activity", "activity-alias" -> "Actividad"
                            "service" -> "Servicio"
                            "receiver" -> "Receptor de avisos (BroadcastReceiver)"
                            else -> "Proveedor de contenido"
                        }
                        val why = if (c.exported == true) "exported=\"true\"" else "tiene intent-filter (exportado por defecto antes de Android 12)"
                        addAndroid(EntryPointKind.ANDROID_EXPORTED_COMPONENT, c.name, pkg, module, root, declaration("$what accesible desde otras apps o el sistema ($why)", manifest, c.line))
                    }
                }
            }
        }
    }

    private fun addAndroid(kind: EntryPointKind, name: String, pkg: String?, module: String, root: String?, evidence: Evidence) {
        val fqn = when {
            name.startsWith(".") -> pkg?.let { it + name }
            !name.contains('.') -> pkg?.let { "$it.$name" }
            else -> name
        }
        val (file, confidence) = fqn?.let { findClassFile(it, module) } ?: (null to Confidence.CONFIRMED)
        val finalEvidence = if (file == null) {
            evidence.copy(detail = evidence.detail + ". La clase no está en el comprimido (puede venir de una biblioteca o generarse al compilar)")
        } else {
            evidence
        }
        builder.add(kind, file, Manifests.safeTarget(fqn ?: name), confidence, finalEvidence, root)
    }

    /** Busca el archivo de una clase: por la ruta del paquete, por el paquete declarado o, como mínimo, por el nombre. */
    private fun findClassFile(fqn: String, module: String): Pair<String?, Confidence> {
        val simple = fqn.substringAfterLast('.').substringBefore('$')
        val pkg = fqn.substringBeforeLast('.', "")
        val candidates = jvmFilesBySimpleName[simple.lowercase()].orEmpty()
            .filter { module.isEmpty() || it.startsWith("$module/") }
        val relative = pkg.replace('.', '/') + "/" + simple
        candidates.firstOrNull { it.endsWith("/$relative.kt") || it.endsWith("/$relative.java") || it == "$relative.kt" || it == "$relative.java" }
            ?.let { return it to Confidence.CONFIRMED }
        candidates.firstOrNull { collector.jvmPackages[it] == pkg && PathInfo(it).name.substringBeforeLast('.') == simple }
            ?.let { return it to Confidence.CONFIRMED }
        candidates.firstOrNull { PathInfo(it).name.substringBeforeLast('.') == simple }?.let { return it to Confidence.PROBABLE }
        return null to Confidence.CONFIRMED
    }

    private fun fromNpm() {
        for ((manifest, npm) in collector.npmPackages) {
            val dir = PathInfo(manifest).directory
            val root = rootOf(manifest)
            npm.main?.let { main ->
                val kind = if (npm.electron) EntryPointKind.ELECTRON_MAIN else EntryPointKind.PACKAGE_MAIN
                val detail = if (npm.electron) "Proceso principal de Electron (campo \"main\" de package.json)" else "Archivo principal del paquete (campo \"main\" de package.json)"
                addDeclaredFile(kind, dir, main, detail, manifest, root, tryModuleExtensions = true)
            }
            npm.bins.forEach { addDeclaredFile(EntryPointKind.PACKAGE_BIN, dir, it, "Comando ejecutable del paquete (campo \"bin\" de package.json)", manifest, root, tryModuleExtensions = true) }
            npm.scriptFiles.forEach { (script, declared) ->
                addDeclaredFile(EntryPointKind.NPM_SCRIPT, dir, declared, "Lo arranca el script \"$script\" de package.json", manifest, root, tryModuleExtensions = true)
            }
            val conventions = mutableListOf<Pair<List<String>, String>>()
            if ("next" in npm.frameworks) {
                conventions += listOf("pages/_app", "src/pages/_app", "app/layout", "src/app/layout").flatMap { b -> listOf("$b.tsx", "$b.jsx", "$b.js", "$b.ts") } to "Convención de Next.js (página o diseño raíz)"
            }
            if ("react-scripts" in npm.frameworks) conventions += listOf("src/index.js", "src/index.jsx", "src/index.tsx", "src/index.ts") to "Convención de Create React App (src/index)"
            if ("react-native" in npm.frameworks || "expo" in npm.frameworks) conventions += listOf("index.js", "App.js", "App.tsx", "App.jsx") to "Convención de React Native / Expo"
            if ("nuxt" in npm.frameworks) conventions += listOf("app.vue", "pages/index.vue") to "Convención de Nuxt"
            if ("@sveltejs/kit" in npm.frameworks) conventions += listOf("src/routes/+page.svelte", "src/routes/+layout.svelte") to "Convención de SvelteKit"
            for ((candidates, detail) in conventions) {
                val found = candidates.map { if (dir.isEmpty()) it else "$dir/$it" }.map(EntryNames::escapeForDisplay).firstOrNull { it in paths } ?: continue
                builder.add(EntryPointKind.WEB_BOOTSTRAP, found, null, Confidence.PROBABLE, Evidence(EvidenceKind.LOCATION, detail, manifest), root)
            }
        }
    }

    private fun fromAngular() {
        for ((config, mains) in collector.angularConfigs) {
            val dir = PathInfo(config).directory
            for ((what, declared) in mains) {
                val kind = if (what == "main") EntryPointKind.WEB_BOOTSTRAP else EntryPointKind.WEB_PAGE
                val detail = if (what == "main") "Archivo principal declarado en angular.json" else "Página declarada en angular.json"
                addDeclaredFile(kind, dir, declared, detail, config, rootOf(config), tryModuleExtensions = false)
            }
        }
    }

    private fun fromHtml() {
        for ((page, scripts) in collector.htmlScripts) {
            val info = PathInfo(page)
            if (info.lower != "index.html") continue
            for (script in scripts) {
                val resolved = PathResolver.join(info.directory, script.value)?.let(EntryNames::escapeForDisplay) ?: continue
                if (resolved !in paths) continue
                builder.add(EntryPointKind.WEB_BOOTSTRAP, resolved, null, Confidence.CONFIRMED, declaration("Lo carga la página index.html", page, script.line), rootOf(resolved))
            }
        }
        // La página index.html de la raíz de un proyecto (o de su carpeta pública) es la entrada por convención.
        val rootDirs = roots.map { it.directory }.toSet() + ""
        for (path in paths) {
            val info = PathInfo(path)
            if (info.lower != "index.html") continue
            val dir = info.directory
            val parentDir = dir.substringBeforeLast('/', "")
            if (dir in rootDirs || (info.parent in setOf("public", "www", "web", "static", "src") && parentDir in rootDirs)) {
                builder.add(EntryPointKind.WEB_PAGE, path, null, Confidence.PROBABLE, Evidence(EvidenceKind.LOCATION, "Página principal por convención (index.html en la raíz del proyecto)"), rootOf(path))
            }
        }
    }

    private fun fromGameEngines() {
        for ((project, godot) in collector.godotProjects) {
            val dir = PathInfo(project).directory
            val root = rootOf(project)
            godot.mainScene?.let { scene ->
                if (scene.value.startsWith("uid://")) {
                    builder.add(EntryPointKind.GAME_MAIN_SCENE, null, Manifests.safeTarget(scene.value), Confidence.CONFIRMED, declaration("Escena principal declarada en project.godot con un identificador uid:// que solo resuelve el editor de Godot", project, scene.line), root)
                } else {
                    addDeclaredFile(EntryPointKind.GAME_MAIN_SCENE, dir, scene.copy(value = scene.value.removePrefix("res://")), "Escena principal declarada en project.godot (run/main_scene)", project, root, tryModuleExtensions = false)
                }
            }
            godot.autoloads.forEach { (name, declared) ->
                addDeclaredFile(EntryPointKind.GAME_AUTOLOAD, dir, declared.copy(value = declared.value.removePrefix("res://")), "Autoload \"$name\" declarado en project.godot (se carga siempre)", project, root, tryModuleExtensions = false)
            }
        }
        for ((settings, scene) in collector.unityFirstScenes) {
            val unityRoot = PathInfo(settings).directory.substringBeforeLast('/', "")
            addDeclaredFile(EntryPointKind.GAME_MAIN_SCENE, unityRoot, scene, "Primera escena activada en la configuración de compilación de Unity", settings, rootOf(settings), tryModuleExtensions = false)
        }
        for ((ini, map) in collector.unrealMaps) {
            val unrealRoot = PathInfo(ini).directory.substringBeforeLast('/', "")
            val asset = map.value.removePrefix("/Game/").substringBefore('.')
            addDeclaredFile(EntryPointKind.GAME_MAIN_SCENE, unrealRoot, map.copy(value = "Content/$asset.umap"), "Mapa por defecto (GameDefaultMap) en DefaultEngine.ini", ini, rootOf(ini), tryModuleExtensions = false)
        }
    }

    private fun fromConventions() {
        val mainKinds = setOf(EntryPointKind.MAIN_FUNCTION, EntryPointKind.SERVER_APP)
        for (csproj in collector.executableCsprojs) {
            val dir = PathInfo(csproj).directory
            val program = EntryNames.escapeForDisplay(if (dir.isEmpty()) "Program.cs" else "$dir/Program.cs")
            if (program in paths && !builder.has(program, mainKinds)) {
                builder.add(EntryPointKind.MAIN_FUNCTION, program, null, Confidence.PROBABLE, Evidence(EvidenceKind.LOCATION, "Program.cs de un proyecto ejecutable de .NET (instrucciones de nivel superior)", csproj), rootOf(program))
            }
        }
        for (path in paths) {
            val info = PathInfo(path)
            if (info.lower == "index.php" && info.parent == "public") {
                builder.add(EntryPointKind.PHP_FRONT_CONTROLLER, path, null, Confidence.PROBABLE, Evidence(EvidenceKind.LOCATION, "Controlador frontal por convención (public/index.php)"), rootOf(path))
            }
            if (info.lower == "artisan" && roots.any { it.ecosystem == Ecosystem.PHP_COMPOSER && it.directory == info.directory }) {
                builder.add(EntryPointKind.PHP_FRONT_CONTROLLER, path, null, Confidence.PROBABLE, Evidence(EvidenceKind.FILE_NAME, "Consola de Laravel (artisan)"), rootOf(path))
            }
        }
    }

    /**
     * Punto de entrada declarado como ruta relativa a [baseDir]. Si el archivo no existe en el comprimido, se
     * informa sin ruta (no se inventa) y con el valor declarado como destino.
     */
    private fun addDeclaredFile(kind: EntryPointKind, baseDir: String, declared: Declared, detail: String, declaringFile: String, root: String?, tryModuleExtensions: Boolean) {
        val joined = PathResolver.join(baseDir, declared.value)
        val candidates = buildList {
            if (joined != null) {
                add(joined)
                if (tryModuleExtensions) {
                    listOf(".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx").forEach { add(joined + it) }
                    listOf("/index.js", "/index.ts", "/index.mjs").forEach { add(joined + it) }
                }
            }
        }.map(EntryNames::escapeForDisplay)
        val found = candidates.firstOrNull { it in paths }
        val evidence = if (found == null) {
            declaration("$detail. El archivo declarado no está en el comprimido (puede generarse al compilar)", declaringFile, declared.line)
        } else {
            declaration(detail, declaringFile, declared.line)
        }
        builder.add(kind, found, Manifests.safeTarget(declared.value), Confidence.CONFIRMED, evidence, found?.let(rootOf) ?: root)
    }
}

/** Resolución de rutas relativas dentro del comprimido, sin salir de él. */
internal object PathResolver {
    /** Une [ref] a [dir] normalizando "." y ".."; `null` si sale del comprimido o queda vacía. */
    fun join(dir: String, ref: String): String? {
        val clean = ref.trim().replace('\\', '/').substringBefore('?').substringBefore('#')
        if (clean.isEmpty() || clean.contains(':')) return null
        val parts = dir.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (segment in clean.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        return parts.joinToString("/").takeIf { it.isNotEmpty() }
    }
}

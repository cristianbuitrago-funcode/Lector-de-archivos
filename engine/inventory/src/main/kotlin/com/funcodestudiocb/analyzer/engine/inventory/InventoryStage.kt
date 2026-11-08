package com.funcodestudiocb.analyzer.engine.inventory

import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.ContentState
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.EntryPoint
import com.funcodestudiocb.analyzer.engine.api.EntryPointKind
import com.funcodestudiocb.analyzer.engine.api.Evidence
import com.funcodestudiocb.analyzer.engine.api.EvidenceKind
import com.funcodestudiocb.analyzer.engine.api.FileEntry
import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.api.FileProfile
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.InventorySummary
import com.funcodestudiocb.analyzer.engine.api.ProjectRoot
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.RoleAssignment
import com.funcodestudiocb.analyzer.engine.core.AttributeKey
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.core.StageRegistry
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.IngestPipeline

/** Datos del inventario que usan las etapas siguientes. */
object InventoryKeys {
    /** Subproyectos detectados, de la carpeta más larga a la más corta. */
    val ROOTS = AttributeKey<List<ProjectRoot>>("inventory.roots")

    /** Puntos de entrada registrados. */
    val ENTRY_POINTS = AttributeKey<List<EntryPoint>>("inventory.entryPoints")

    /** Funciones de cada archivo. */
    val ROLES = AttributeKey<Map<String, Set<FileRole>>>("inventory.roles")
}

/** Carpeta del subproyecto al que pertenece una ruta (el más profundo), o `null`. */
fun List<ProjectRoot>.rootOf(path: String): String? =
    map { it.directory }.distinct().sortedByDescending { it.length }.firstOrNull { it.isEmpty() || path.startsWith("$it/") }

/** Ingesta, inventario y lenguajes. [listeners] reciben el texto redactado de cada archivo (fases siguientes). */
object InventoryPipeline {
    fun registry(collector: ContentCollector): StageRegistry =
        IngestPipeline.registry(listOf(collector))
            .register(InventoryStage(collector))
            .register(LanguageStatsStage(collector))

    fun stages(): List<Stage> = registry(ContentCollector()).plan()
}

/** Totales por lenguaje (líneas de código, comentario y en blanco) y cobertura del análisis sintáctico. */
class LanguageStatsStage(private val collector: ContentCollector) : Stage {
    override val id = "languages"
    override val title = "Lenguajes y líneas de código"
    override val dependsOn = setOf("inventory")

    override suspend fun run(context: StageContext) {
        collector.languages.values
            .sortedByDescending { it.code }
            .forEach { context.emit(it.toRecord()) }
        if (!collector.syntaxAvailable) {
            context.coverage(
                CoverageReason.NOT_ANALYZED,
                "No se pudo cargar tree-sitter en este dispositivo: todas las líneas se contaron con el analizador léxico y no hay resaltado de sintaxis",
            )
        }
        collector.syntaxProblems.groupBy { it.second }.forEach { (problem, files) ->
            context.coverage(
                CoverageReason.LIMIT_REACHED,
                "${files.size} archivos se contaron con el analizador léxico porque $problem",
                files.first().first,
            )
        }
        val lexicalOnly = collector.languages.values.filter { it.language.grammar == null }.map { it.language.name }
        if (lexicalOnly.isNotEmpty()) {
            context.coverage(
                CoverageReason.UNSUPPORTED,
                "Sin gramática de tree-sitter (recuento léxico, sin resaltado ni importaciones): ${lexicalOnly.take(20).joinToString()}",
            )
        }
    }
}

/** Nombre en español de cada clase de punto de entrada. */
fun EntryPointKind.label(): String = when (this) {
    EntryPointKind.ANDROID_LAUNCHER -> "actividad que abre la app desde el lanzador"
    EntryPointKind.ANDROID_APPLICATION -> "clase Application de Android"
    EntryPointKind.ANDROID_EXPORTED_COMPONENT -> "componente de Android accesible desde fuera"
    EntryPointKind.MAIN_FUNCTION -> "función principal (main)"
    EntryPointKind.SERVER_APP -> "arranque de un servidor o API"
    EntryPointKind.PACKAGE_MAIN -> "archivo principal del paquete npm"
    EntryPointKind.PACKAGE_BIN -> "comando ejecutable del paquete npm"
    EntryPointKind.NPM_SCRIPT -> "archivo que arranca un script de npm"
    EntryPointKind.WEB_PAGE -> "página web inicial"
    EntryPointKind.WEB_BOOTSTRAP -> "arranque de la aplicación web"
    EntryPointKind.PHP_FRONT_CONTROLLER -> "controlador frontal o consola de PHP"
    EntryPointKind.ELECTRON_MAIN -> "proceso principal de Electron"
    EntryPointKind.GAME_MAIN_SCENE -> "escena con la que arranca el juego"
    EntryPointKind.GAME_AUTOLOAD -> "script o escena que el motor carga siempre"
}

/**
 * E3–E4 de la Fase 3: inventario del proyecto. Asigna a cada archivo sus funciones con evidencia, detecta los
 * subproyectos, los puntos de entrada y los archivos posiblemente innecesarios, y emite una ficha por archivo.
 */
class InventoryStage(private val collector: ContentCollector) : Stage {
    override val id = "inventory"
    override val title = "Inventario: funciones de los archivos y puntos de entrada"
    override val dependsOn = setOf("ingest.duplicates")

    override suspend fun run(context: StageContext) {
        val files: List<FileEntry> = context.attributes[IngestKeys.FILES].orEmpty()
        val paths = files.mapTo(HashSet()) { it.path }

        context.progress(0.05f, "Buscando subproyectos")
        val roots = ProjectRoots.detect(files.map { it.path }).take(MAX_ROOTS)
        roots.forEach(context::emit)
        context.attributes[InventoryKeys.ROOTS] = roots
        val rootDirs = roots.map { it.directory }.distinct().sortedByDescending { it.length }
        val rootOf: (String) -> String? = { path -> rootDirs.firstOrNull { it.isEmpty() || path.startsWith("$it/") } }

        context.progress(0.2f, "Buscando puntos de entrada")
        val allEntries = EntryPointResolver(collector, paths, roots, rootOf).resolve()
        val entries = allEntries.take(MAX_ENTRY_POINTS)
        entries.forEach(context::emit)
        if (allEntries.size > entries.size) {
            context.coverage(CoverageReason.LIMIT_REACHED, "Se encontraron ${allEntries.size} puntos de entrada; solo se registran los $MAX_ENTRY_POINTS primeros")
        }
        context.attributes[InventoryKeys.ENTRY_POINTS] = entries
        val entriesByPath: Map<String, List<EntryPoint>> = entries.filter { it.path != null }.groupBy { it.path!! }
        context.checkActive()

        context.progress(0.4f, "Asignando funciones a los archivos")
        val originals = duplicateOriginals(context)
        val roleCounts = sortedMapOf<FileRole, Int>()
        var unclassified = 0
        val rolesByPath = HashMap<String, Set<FileRole>>()
        files.forEachIndexed { i, entry ->
            val profile = profile(entry, entriesByPath[entry.path].orEmpty(), originals[entry.path], rootOf(entry.path))
            context.emit(profile)
            rolesByPath[entry.path] = profile.roles.mapTo(HashSet()) { it.role }
            profile.roles.forEach { roleCounts[it.role] = (roleCounts[it.role] ?: 0) + 1 }
            if (profile.roles.isEmpty()) unclassified++
            if (i % 500 == 0) {
                context.progress(0.4f + 0.55f * i / files.size.coerceAtLeast(1), "Archivo ${i + 1} de ${files.size}")
                context.checkActive()
            }
        }

        context.attributes[InventoryKeys.ROLES] = rolesByPath
        if (collector.tooLarge > 0) {
            context.coverage(
                CoverageReason.NOT_ANALYZED,
                "${collector.tooLarge} archivos de texto superan ${context.config.inventory.maxContentBytes / (1024 * 1024)} MB: se clasificaron solo por nombre y ubicación, sin examinar su contenido ni guardar copia",
            )
        }
        context.coverage(
            CoverageReason.UNSUPPORTED,
            "Las funciones y los puntos de entrada se deducen del nombre, la ubicación, los manifiestos y patrones del código " +
                "(no los que están dentro de cadenas de texto, comentarios o carpetas de pruebas); los archivos que nada usa " +
                "los señala el análisis de relaciones.",
        )
        context.emit(
            InventorySummary(
                files = files.size,
                roleCounts = roleCounts,
                entryPoints = entries.size,
                projectRoots = roots.size,
                unclassified = unclassified,
                storedFiles = collector.storedFiles,
                storedChars = collector.storedChars,
                redactions = collector.redactionsTotal,
                copyEnabled = collector.copyEnabled && context.config.inventory.keepRedactedCopy,
            ),
        )
    }

    private fun profile(entry: FileEntry, entryPoints: List<EntryPoint>, duplicateOf: String?, root: String?): FileProfile {
        val info = PathInfo(entry.path)
        val facts = collector.facts[entry.path]
        val roles = RoleAccumulator()
        PathRules.hints(info, entry.kind).forEach(roles::add)
        facts?.signals?.forEach { hit ->
            hit.role?.let { roles.add(RoleHint(it, Confidence.PROBABLE, EvidenceKind.CONTENT, "Contiene ${hit.label}", line = hit.line)) }
        }
        entryPoints.forEach { ep ->
            val first = ep.evidence.first()
            roles.add(RoleHint(FileRole.ENTRY_POINT, ep.confidence, first.kind, "Punto de entrada (${ep.kind.label()}): ${first.detail}", first.path, first.line))
        }
        UnnecessaryRules.check(info, entry, duplicateOf).forEach(roles::add)
        val content = facts?.content ?: when {
            entry.status != FileStatus.ANALYZED -> ContentState.NOT_READ
            entry.kind == FileKind.EMPTY -> ContentState.EMPTY
            entry.kind == FileKind.TEXT -> ContentState.NOT_READ
            else -> ContentState.BINARY
        }
        return FileProfile(entry.path, roles.build(), facts?.lineCount, content, facts?.redactions ?: 0, root, facts?.language)
    }

    /** Ruta → ruta del "original" de su grupo de duplicados (la más corta y, a igualdad, la primera en orden). */
    private fun duplicateOriginals(context: StageContext): Map<String, String> {
        val hashes = context.attributes[IngestKeys.HASHES] ?: return emptyMap()
        val out = HashMap<String, String>()
        hashes.entries.filter { it.third >= MIN_DUPLICATE_BYTES }.groupBy { it.second }.values.filter { it.size > 1 }.forEach { group ->
            val sorted = group.map { it.first }.sortedWith(compareBy<String> { it.length }.thenBy { it })
            sorted.drop(1).forEach { out[it] = sorted.first() }
        }
        return out
    }

    private companion object {
        const val MAX_ROOTS = 500
        const val MAX_ENTRY_POINTS = 1_000

        /** Los archivos idénticos muy pequeños (plantillas vacías, `__init__.py`…) no se señalan. */
        const val MIN_DUPLICATE_BYTES = 64L
    }
}

/** Reúne los indicios de cada rol: se queda con la mayor certeza y hasta 8 evidencias. */
internal class RoleAccumulator {
    private val roles = LinkedHashMap<FileRole, Pair<Confidence, MutableList<Evidence>>>()

    fun add(hint: RoleHint) {
        val current = roles[hint.role]
        val evidence = Evidence(hint.kind, hint.detail, hint.path, hint.line).trimmed()
        if (current == null) {
            roles[hint.role] = hint.confidence to mutableListOf(evidence)
            return
        }
        val best = if (hint.confidence.ordinal < current.first.ordinal) hint.confidence else current.first
        val list = current.second
        if (list.size < RecordValidator.MAX_EVIDENCE && list.none { it.detail == evidence.detail }) list += evidence
        roles[hint.role] = best to list
    }

    fun build(): List<RoleAssignment> = roles.entries
        .sortedBy { it.key.ordinal }
        .map { (role, value) -> RoleAssignment(role, value.first, value.second.toList()) }
}

/**
 * Archivos que posiblemente sobran. Siempre con certeza "posible": la herramienta no puede saber si algo se usa
 * al ejecutar el proyecto, así que solo explica por qué conviene revisarlos antes de borrar nada.
 */
internal object UnnecessaryRules {
    private val BACKUP_EXTENSIONS = setOf("bak", "old", "orig", "rej", "backup")
    private val BUILD_ARTIFACTS = setOf("class", "o", "pyc", "pyo", "apk", "aab", "ipa", "pdb", "ilk", "dex", "obj")
    private val VENDOR_DIRS = setOf("libs", "lib", "plugins", "third_party", "thirdparty", "external", "vendor", "deps", "tools", "wrapper", "bin")
    private val EMPTY_ALLOWED = setOf("__init__.py", ".gitkeep", ".keep", ".nojekyll", "py.typed", ".npmignore", ".placeholder", ".empty", ".gitignore", ".dockerignore")
    /** Carpetas de pruebas y datos de ejemplo: los comprimidos, duplicados y vacíos suelen ser intencionados. */
    private val FIXTURE_DIRS = setOf("test", "tests", "__tests__", "spec", "androidtest", "testing", "fixtures", "testdata", "test-data", "corpus", "samples", "examples", "resources", "assets")
    /** Solo los comprimidos "puros"; .jar, .docx, .apk… también son ZIP pero tienen su propia función. */
    private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst")
    private val COPY_NAME = Regex("(?i)^(?:copy of |copia de )|(?: - copy| - copia|_copy|_copia|\\(\\d+\\)|\\s\\(\\d+\\))$")

    private fun possible(kind: EvidenceKind, detail: String, path: String? = null) =
        RoleHint(FileRole.POSSIBLY_UNNECESSARY, Confidence.POSSIBLE, kind, detail, path)

    fun check(info: PathInfo, entry: FileEntry, duplicateOf: String?): List<RoleHint> {
        val out = mutableListOf<RoleHint>()
        val stemForCopy = info.name.substringBeforeLast('.')
        when {
            info.ext == "log" || info.lower.startsWith("npm-debug.log") || info.lower.startsWith("yarn-error.log") ->
                out += possible(EvidenceKind.EXTENSION, "Registro (log) generado al ejecutar o compilar")
            info.ext in BACKUP_EXTENSIONS -> out += possible(EvidenceKind.EXTENSION, "Copia de seguridad o resto de una fusión de cambios (.${info.ext})")
            COPY_NAME.containsMatchIn(stemForCopy) -> out += possible(EvidenceKind.FILE_NAME, "El nombre indica que es una copia de otro archivo")
        }
        if (info.ext in BUILD_ARTIFACTS && info.lowerDirs.none { it in VENDOR_DIRS } && entry.kind != FileKind.TEXT) {
            out += possible(EvidenceKind.EXTENSION, "Artefacto compilado: normalmente se genera al compilar y no se guarda con el código")
        }
        if (info.ext in setOf("exe", "dll", "so", "dylib") && info.lowerDirs.none { it in VENDOR_DIRS }) {
            out += possible(EvidenceKind.EXTENSION, "Binario fuera de una carpeta de bibliotecas: comprueba si es necesario o se generó al compilar")
        }
        val fixture = info.lowerDirs.any { it in FIXTURE_DIRS }
        if (entry.kind == FileKind.ARCHIVE && !fixture && info.ext in ARCHIVE_EXTENSIONS) out += possible(EvidenceKind.CONTENT_TYPE, "Comprimido dentro del proyecto: suele ser una copia o una descarga olvidada")
        if (entry.kind == FileKind.EMPTY && entry.status == FileStatus.ANALYZED && info.lower !in EMPTY_ALLOWED && !fixture) {
            out += possible(EvidenceKind.CONTENT_TYPE, "Archivo vacío")
        }
        if (duplicateOf != null && !fixture && !PathRules.isWellKnownName(info)) out += possible(EvidenceKind.DUPLICATE, "Contenido idéntico a otro archivo del proyecto", duplicateOf)
        if (info.lower == "local.properties") out += possible(EvidenceKind.FILE_NAME, "Configuración propia de una máquina (ruta del SDK): no debería compartirse")
        if (info.ext == "iml") out += possible(EvidenceKind.EXTENSION, "Archivo del IDE que se regenera solo")
        if (info.lowerDirs.firstOrNull { it == ".vscode" || it == ".idea" || it == ".vs" } != null) {
            out += possible(EvidenceKind.LOCATION, "Configuración personal del editor")
        }
        return out
    }
}

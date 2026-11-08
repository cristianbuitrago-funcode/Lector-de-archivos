package com.funcodestudiocb.analyzer.engine.ingest

import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.engine.api.ExclusionReason

/** Por qué se excluye una ruta. [root] es la carpeta excluida (o el propio archivo si es temporal). */
data class ExclusionDecision(val root: String, val rule: String, val reason: ExclusionReason)

/**
 * Carpetas generadas propias de un motor, que solo se excluyen si su marcador está en la misma carpeta raíz
 * (así "Library" se excluye en un proyecto Unity, pero no en cualquier otro proyecto).
 */
private data class ContextRule(
    val engine: String,
    /** Devuelve la carpeta raíz del proyecto si [path] es el marcador del motor, o `null`. */
    val rootOf: (path: String) -> String?,
    val generatedDirectories: List<String>,
)

private val CONTEXT_RULES = listOf(
    ContextRule(
        "Unity",
        { p -> if (p == "ProjectSettings/ProjectVersion.txt") "" else p.removeSuffix("/ProjectSettings/ProjectVersion.txt").takeIf { it != p } },
        listOf("Library", "Temp", "Logs", "UserSettings"),
    ),
    ContextRule(
        "Unreal Engine",
        { p -> if (p.endsWith(".uproject")) p.substringBeforeLast('/', "") else null },
        listOf("Binaries", "Intermediate", "Saved", "DerivedDataCache"),
    ),
    ContextRule(
        "Godot",
        { p -> if (p == "project.godot" || p.endsWith("/project.godot")) p.substringBeforeLast('/', "") else null },
        listOf(".godot", ".import"),
    ),
)

private val TEMPORARY_NAMES = setOf(".DS_Store", "Thumbs.db", "ehthumbs.db", "desktop.ini")
private val TEMPORARY_SUFFIXES = listOf("~", ".swp", ".swo", ".tmp", ".temp")
private const val MAC_METADATA_DIR = "__MACOSX"

/** Decide qué se excluye del análisis a partir del listado, sin leer ningún contenido. */
class ExclusionPlanner(private val options: ExclusionOptions) {

    private val directoryNames = options.directoryNames.toSet()

    /** [files] y [directories] son rutas ya normalizadas. */
    fun plan(files: Collection<String>, directories: Collection<String>): Map<String, ExclusionDecision> {
        val contextRoots = mutableMapOf<String, String>() // carpeta generada -> motor
        if (options.contextRules) {
            for (path in files) {
                for (rule in CONTEXT_RULES) {
                    val root = rule.rootOf(path) ?: continue
                    rule.generatedDirectories.forEach { dir -> contextRoots[join(root, dir)] = rule.engine }
                }
            }
        }
        val decisions = HashMap<String, ExclusionDecision>()
        for (path in files) decide(path, isDirectory = false, contextRoots)?.let { decisions[path] = it }
        for (path in directories) decide(path, isDirectory = true, contextRoots)?.let { decisions[path] = it }
        return decisions
    }

    private fun decide(path: String, isDirectory: Boolean, contextRoots: Map<String, String>): ExclusionDecision? {
        val parts = path.split('/')
        val directoryCount = if (isDirectory) parts.size else parts.size - 1
        for (i in 0 until directoryCount) {
            val prefix = parts.subList(0, i + 1).joinToString("/")
            val name = parts[i]
            contextRoots[prefix]?.let { engine -> return ExclusionDecision(prefix, "$name ($engine)", ExclusionReason.CONTEXT_RULE) }
            if (name in directoryNames) return ExclusionDecision(prefix, name, ExclusionReason.DIRECTORY_RULE)
            if (options.temporaryFiles && name == MAC_METADATA_DIR) {
                return ExclusionDecision(prefix, MAC_METADATA_DIR, ExclusionReason.TEMPORARY_FILE)
            }
        }
        if (!isDirectory && options.temporaryFiles) {
            val fileName = parts.last()
            val rule = when {
                fileName in TEMPORARY_NAMES -> fileName
                fileName.startsWith("._") -> "._*"
                else -> TEMPORARY_SUFFIXES.firstOrNull { fileName.endsWith(it) && fileName.length > it.length }?.let { "*$it" }
            }
            if (rule != null) return ExclusionDecision(path, rule, ExclusionReason.TEMPORARY_FILE)
        }
        return null
    }

    private fun join(root: String, child: String) = if (root.isEmpty()) child else "$root/$child"
}

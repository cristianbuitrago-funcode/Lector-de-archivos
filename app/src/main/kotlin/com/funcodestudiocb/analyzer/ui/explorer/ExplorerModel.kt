package com.funcodestudiocb.analyzer.ui.explorer

import com.funcodestudiocb.analyzer.analysis.AnalysisReport
import com.funcodestudiocb.analyzer.engine.api.EntryPoint
import com.funcodestudiocb.analyzer.engine.api.ExcludedDirectory
import com.funcodestudiocb.analyzer.engine.api.FileEntry
import com.funcodestudiocb.analyzer.engine.api.FileProfile
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.Redaction

/** Un archivo del proyecto con todo lo que el análisis sabe de él. */
class ExplorerFile(
    val entry: FileEntry,
    val profile: FileProfile?,
    val redactions: List<Redaction>,
    val entryPoints: List<EntryPoint>,
) {
    val path: String get() = entry.path
    val name: String = entry.path.substringAfterLast('/')
    val directory: String = entry.path.substringBeforeLast('/', "")
    val roles: Set<FileRole> = profile?.roles?.map { it.role }?.toSet().orEmpty()

    /** Función que se muestra en el árbol (la más específica). */
    val primaryRole: FileRole? = PRIMARY_ORDER.firstOrNull { it in roles }

    companion object {
        val PRIMARY_ORDER = listOf(
            FileRole.ENTRY_POINT, FileRole.SERVER, FileRole.AUTHENTICATION, FileRole.DATABASE, FileRole.UI, FileRole.TEST,
            FileRole.CONFIGURATION, FileRole.DEPENDENCIES, FileRole.BUILD, FileRole.SOURCE, FileRole.DOCUMENTATION,
            FileRole.RESOURCE, FileRole.MEDIA, FileRole.DATA, FileRole.BINARY,
        )
    }
}

sealed interface TreeNode {
    val name: String
    val path: String
}

class DirNode(
    override val name: String,
    override val path: String,
    var excluded: ExcludedDirectory? = null,
) : TreeNode {
    val children = mutableListOf<TreeNode>()
    var fileCount = 0
    var totalBytes = 0L
}

class FileNode(override val name: String, override val path: String, val file: ExplorerFile) : TreeNode

/** Una fila visible del árbol. */
data class TreeRow(val node: TreeNode, val depth: Int, val expanded: Boolean)

/** Archivo relacionado con otro y el motivo (solo lo que se sabe con certeza en esta fase). */
data class RelatedFile(val path: String, val reason: RelationReason)

enum class RelationReason { DUPLICATE, DECLARED_BY, UNITY_META }

/**
 * Modelo del Explorador construido solo con registros ya validados: árbol de carpetas (con las excluidas),
 * filtros por función, búsqueda y relaciones conocidas.
 */
class ExplorerModel(val report: AnalysisReport) {

    val files: Map<String, ExplorerFile>
    val root = DirNode("", "")
    private val directories = HashMap<String, DirNode>().apply { put("", root) }

    /** El análisis tiene inventario (se hizo con la Fase 3 o posterior). */
    val hasInventory: Boolean = report.inventory != null || report.profiles.isNotEmpty()

    init {
        val profiles = report.profiles.associateBy { it.path }
        val redactions = report.redactions.groupBy { it.path }
        val entries = report.entryPoints.filter { it.path != null }.groupBy { it.path!! }
        files = report.files.associate { entry ->
            entry.path to ExplorerFile(entry, profiles[entry.path], redactions[entry.path].orEmpty(), entries[entry.path].orEmpty())
        }
        files.values.forEach { file ->
            val dir = directory(file.directory)
            dir.children += FileNode(file.name, file.path, file)
            var d: String? = file.directory
            while (d != null) {
                val node = directory(d)
                node.fileCount++
                node.totalBytes += file.entry.sizeBytes
                d = if (d.isEmpty()) null else d.substringBeforeLast('/', "")
            }
        }
        report.excludedDirectories.forEach { excluded -> directory(excluded.path).excluded = excluded }
        sort(root)
    }

    private fun directory(path: String): DirNode {
        directories[path]?.let { return it }
        val parent = directory(path.substringBeforeLast('/', ""))
        val node = DirNode(path.substringAfterLast('/'), path)
        parent.children += node
        directories[path] = node
        return node
    }

    private fun sort(dir: DirNode) {
        dir.children.sortWith(compareBy<TreeNode> { it !is DirNode }.thenBy { it.name.lowercase() }.thenBy { it.name })
        dir.children.filterIsInstance<DirNode>().forEach(::sort)
    }

    /** Número de archivos con cada función. */
    val roleCounts: Map<FileRole, Int> = FileRole.entries.associateWith { role -> files.values.count { role in it.roles } }.filterValues { it > 0 }

    /** Carpetas que conviene mostrar abiertas al principio (la única carpeta raíz, si la hay). */
    fun initiallyExpanded(): Set<String> {
        val single = root.children.singleOrNull() as? DirNode ?: return emptySet()
        return setOf(single.path)
    }

    /**
     * Filas visibles. Sin filtro ni búsqueda, según las carpetas abiertas. Con filtro o búsqueda, solo los archivos
     * que coinciden y sus carpetas (abiertas); las carpetas excluidas no se muestran.
     */
    fun rows(expanded: Set<String>, role: FileRole?, query: String): List<TreeRow> {
        val q = query.trim().lowercase()
        val filtering = role != null || q.isNotEmpty()
        val matches: Set<String>? = if (!filtering) {
            null
        } else {
            files.values.filter { f -> (role == null || role in f.roles) && (q.isEmpty() || f.path.lowercase().contains(q)) }.mapTo(HashSet()) { it.path }
        }
        val visibleDirs: Set<String>? = matches?.let { m ->
            val dirs = HashSet<String>()
            m.forEach { p ->
                var d = p.substringBeforeLast('/', "")
                while (d.isNotEmpty() && dirs.add(d)) d = d.substringBeforeLast('/', "")
            }
            dirs
        }
        val out = mutableListOf<TreeRow>()
        fun walk(dir: DirNode, depth: Int) {
            for (child in dir.children) {
                when (child) {
                    is DirNode -> {
                        if (visibleDirs != null) {
                            if (child.path !in visibleDirs) continue
                            out += TreeRow(child, depth, expanded = true)
                            walk(child, depth + 1)
                        } else {
                            val open = child.path in expanded && child.excluded == null
                            out += TreeRow(child, depth, open)
                            if (open) walk(child, depth + 1)
                        }
                    }
                    is FileNode -> if (matches == null || child.path in matches) out += TreeRow(child, depth, expanded = false)
                }
            }
        }
        walk(root, 0)
        return out
    }

    /** Relaciones que ya se conocen en esta fase (las de código llegan en la Fase 5). */
    fun related(path: String): List<RelatedFile> {
        val file = files[path] ?: return emptyList()
        val out = LinkedHashSet<RelatedFile>()
        report.duplicates.filter { path in it.paths }.forEach { group ->
            group.paths.filter { it != path }.forEach { out += RelatedFile(it, RelationReason.DUPLICATE) }
        }
        file.entryPoints.flatMap { it.evidence }.mapNotNull { it.path }.filter { it != path && it in files }.forEach {
            out += RelatedFile(it, RelationReason.DECLARED_BY)
        }
        val meta = if (path.endsWith(".meta")) path.removeSuffix(".meta") else "$path.meta"
        if (meta in files) out += RelatedFile(meta, RelationReason.UNITY_META)
        return out.toList()
    }
}

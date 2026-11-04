package com.funcodestudiocb.analyzer.engine.relations

import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.DependencyCycle
import com.funcodestudiocb.analyzer.engine.api.Evidence
import com.funcodestudiocb.analyzer.engine.api.EvidenceKind
import com.funcodestudiocb.analyzer.engine.api.FileEntry
import com.funcodestudiocb.analyzer.engine.api.FileGraph
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.Relation
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.RelationImpact
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.api.RelationsSummary
import com.funcodestudiocb.analyzer.engine.api.SymbolCategory
import com.funcodestudiocb.analyzer.engine.api.SymbolInfo
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyKeys
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.inventory.InventoryKeys
import com.funcodestudiocb.analyzer.engine.inventory.PathInfo
import com.funcodestudiocb.analyzer.engine.languages.Languages
import com.funcodestudiocb.analyzer.engine.languages.SymbolKind

/** Relación antes de calcular su impacto. */
internal class Draft(
    val source: String,
    val line: Int?,
    val target: String?,
    val external: String?,
    val reference: String,
    val kind: RelationKind,
    val exchanged: MutableList<String>,
    val exchangedNote: String?,
    val confidence: RelationConfidence,
    val evidence: MutableList<Evidence>,
)

/**
 * Fase 5A: relaciones entre archivos. Resuelve las importaciones, inclusiones, herencias y referencias de cada
 * archivo, construye el grafo y calcula, para cada conexión, sus cinco atributos: origen y destino, tipo, información
 * intercambiada, impacto de modificarla o eliminarla, y confianza con su evidencia.
 */
class RelationStage(private val collector: RelationCollector) : Stage {
    override val id = "relations"
    override val title = "Relaciones entre archivos"
    override val dependsOn = setOf("dependencies")

    override suspend fun run(context: StageContext) {
        val files: List<FileEntry> = context.attributes[IngestKeys.FILES].orEmpty()
        val paths = files.filter { it.status == FileStatus.ANALYZED }.mapTo(HashSet()) { it.path }
        val index = ProjectIndex(paths, collector.facts, collector.configs, context.attributes[DependencyKeys.DECLARED].orEmpty())
        val resolver = Resolver(index)

        context.progress(0.1f, "Resolviendo importaciones")
        val drafts = LinkedHashMap<String, Draft>()
        var systemIncludes = 0
        collector.facts.values.forEachIndexed { i, file ->
            if (i % 500 == 0) context.checkActive()
            val imported = HashSet<String>()
            for (ref in file.imports) {
                val resolutions = resolver.resolve(file, ref)
                if (resolutions.isEmpty() && ref.module.startsWith("<")) systemIncludes++
                ref.symbols.forEach { imported += it }
                for (r in resolutions) {
                    val kind = if (ref.extends) RelationKind.INHERITANCE else r.kind
                    add(drafts, file.path, ref.line, r, ref.module, kind, "${verb(kind, file.language)} «${short(ref.module)}»")
                }
            }
            for (res in file.resources) {
                val r = resolver.resolveResource(file, res)
                add(drafts, file.path, res.line, r, res.value, res.kind, "${res.label.replaceFirstChar { it.uppercase() }} «${short(res.value)}»")
            }
            inheritance(file, index, drafts)
            for (r in resolver.samePackage(file, imported)) {
                add(drafts, file.path, null, r, r.exchanged.joinToString(", "), RelationKind.REFERENCE, "Usa ${r.exchanged.take(5).joinToString(", ")} sin importarlo")
            }
        }

        context.progress(0.6f, "Construyendo el grafo")
        val all = drafts.values.toList()
        val internal = all.filter { it.target != null && it.confidence != RelationConfidence.BROKEN }
        val nodes = (collector.facts.keys + internal.mapNotNull { it.target }).distinct()
        val graph = DependencyGraph(nodes, internal.map { it.source to it.target!! })
        val comp = graph.components()
        val compSize = HashMap<Int, Int>().also { m -> comp.forEach { m[it] = (m[it] ?: 0) + 1 } }
        val rank = graph.pageRank()
        val (dependents, capped) = graph.transitiveDependents(comp)
        val entryPoints = context.attributes[InventoryKeys.ENTRY_POINTS].orEmpty().mapNotNull { it.path }.distinct()
        val reached = graph.reachedFrom(entryPoints)
        context.checkActive()

        // Ciclos: componentes de más de un archivo.
        val cycles = compSize.filter { it.value > 1 }.keys.sortedByDescending { compSize[it] }.take(MAX_CYCLES)
        val cycleIndex = cycles.withIndex().associate { (i, c) -> c to i }
        cycles.forEachIndexed { i, c ->
            val members = nodes.indices.filter { comp[it] == c }.map { nodes[it] }.sorted()
            context.emit(DependencyCycle(i, members.take(RecordValidator.MAX_CYCLE_PATHS), members.size))
        }

        context.progress(0.75f, "Calculando el impacto de cada conexión")
        val externalUsers = all.filter { it.external != null }.groupBy { it.external!! }.mapValues { (_, list) -> list.map { it.source }.distinct().size }
        val emitted = prioritize(all).take(MAX_RELATIONS)
        for (d in emitted) {
            context.emit(relation(d, graph, index, dependents, capped, reached, entryPoints, externalUsers))
        }
        if (all.size > emitted.size) {
            context.coverage(CoverageReason.LIMIT_REACHED, "Se encontraron ${all.size} relaciones; se registran las $MAX_RELATIONS más relevantes (internas y rotas primero)")
        }

        context.progress(0.9f, "Ficha de cada archivo en el grafo")
        val roles = context.attributes[InventoryKeys.ROLES].orEmpty()
        val order = nodes.indices.sortedByDescending { rank[it] }
        val position = IntArray(nodes.size).also { arr -> order.forEachIndexed { pos, v -> arr[v] = pos + 1 } }
        val maxRank = rank.maxOrNull() ?: 1.0
        val externalsBySource = all.filter { it.external != null }.groupBy { it.source }.mapValues { (_, l) -> l.map { it.external }.distinct().size }
        var unreferencedCount = 0
        // Si faltan las importaciones de algún archivo de un ecosistema, nadie de ese ecosistema puede darse por no usado.
        val incomplete = collector.withoutImports.keys.mapTo(HashSet()) { ecosystem(it) }
        for ((v, path) in nodes.withIndex()) {
            val facts = collector.facts[path]
            val unreferenced = graph.inc[v].isEmpty() && facts != null && path !in entryPoints &&
                ecosystem(facts.language) !in incomplete && candidateForUnused(facts, roles[path].orEmpty())
            if (unreferenced) unreferencedCount++
            context.emit(
                FileGraph(
                    path = path,
                    outgoing = graph.out[v].size,
                    incoming = graph.inc[v].size,
                    externals = externalsBySource[path] ?: 0,
                    importance = if (maxRank > 0) (rank[v] / maxRank).coerceIn(0.0, 1.0) else 0.0,
                    rank = position[v],
                    transitiveDependents = dependents[v],
                    transitiveCapped = capped[v],
                    cycle = cycleIndex[comp[v]],
                    reachableFromEntryPoint = if (entryPoints.isEmpty()) null else reached[v].isNotEmpty(),
                    unreferenced = unreferenced,
                    symbols = facts?.declarations.orEmpty().take(RecordValidator.MAX_SYMBOLS).map { SymbolInfo(it.name, category(it.kind), it.line) },
                ),
            )
        }

        context.emit(
            RelationsSummary(
                files = nodes.size,
                relations = all.size,
                internal = internal.size,
                external = all.count { it.external != null },
                broken = all.count { it.confidence == RelationConfidence.BROKEN },
                cycles = cycles.size,
                unreferencedFiles = unreferencedCount,
                byKind = all.groupingBy { it.kind }.eachCount(),
                byConfidence = all.groupingBy { it.confidence }.eachCount(),
            ),
        )
        coverage(context, files, systemIncludes)
    }

    // ------------------------------------------------------------------ construcción

    private fun short(text: String) = text.filterNot { it.isISOControl() }.let { if (it.length > 150) it.take(147) + "…" else it }

    private fun verb(kind: RelationKind, language: String): String = when {
        kind == RelationKind.INHERITANCE -> "Hereda de"
        language == "c" || language == "cpp" -> "Incluye"
        language == "php" -> "Usa o incluye"
        language == "csharp" -> "Usa el espacio de nombres"
        else -> "Importa"
    }

    private fun add(drafts: MutableMap<String, Draft>, source: String, line: Int?, r: Resolution, reference: String, kind: RelationKind, what: String) {
        val key = listOf(source, r.target ?: r.external ?: "rota:$reference", kind.name).joinToString("|")
        val detail = "$what: ${r.how}".take(RecordValidator.MAX_EVIDENCE_DETAIL)
        val existing = drafts[key]
        if (existing != null) {
            r.exchanged.forEach { if (it !in existing.exchanged && existing.exchanged.size < RecordValidator.MAX_EXCHANGED) existing.exchanged += it }
            if (existing.evidence.size < RecordValidator.MAX_EVIDENCE && existing.evidence.none { it.line == line && it.detail == detail }) {
                existing.evidence += Evidence(EvidenceKind.IMPORT, detail, source, line)
            }
            return
        }
        if (drafts.size >= MAX_DRAFTS) return
        drafts[key] = Draft(
            source, line, r.target, r.external, short(reference), kind,
            r.exchanged.filter { it.isNotEmpty() && it.length <= RecordValidator.MAX_NAME_LENGTH }.take(RecordValidator.MAX_EXCHANGED).toMutableList(),
            r.exchangedNote, r.confidence, mutableListOf(Evidence(EvidenceKind.IMPORT, detail, source, line)),
        )
    }

    /** Herencia e implementación: el supertipo se busca entre lo importado, el mismo paquete y los nombres completos. */
    private fun inheritance(file: SourceFacts, index: ProjectIndex, drafts: MutableMap<String, Draft>) {
        if (file.declarations.none { it.supertypes.isNotEmpty() }) return
        // Nombre importado → archivo, a partir de las importaciones ya resueltas de este archivo.
        val importedFrom = HashMap<String, String>()
        drafts.values.filter { it.source == file.path && it.kind == RelationKind.IMPORT }.forEach { d ->
            val t = d.target ?: return@forEach
            d.exchanged.forEach { importedFrom.putIfAbsent(it, t) }
            if (d.exchanged.isEmpty()) index.declared[t].orEmpty().keys.forEach { name -> importedFrom.putIfAbsent(name, t) }
        }
        for (decl in file.declarations) {
            for (st in decl.supertypes) {
                val simple = st.name.substringAfterLast('.').substringAfterLast('\\').substringAfterLast(':')
                val qualified = st.name.trim('\\').replace("::", ".")
                var target: String? = null
                var how = ""
                var confidence = RelationConfidence.VERIFIED
                importedFrom[simple]?.let {
                    target = it
                    how = "el tipo se importa de ese archivo"
                }
                if (target == null) {
                    val candidates = when (file.language) {
                        "java", "kotlin" -> index.jvmSymbols[qualified] ?: index.jvmSymbols["${file.namespaces.firstOrNull() ?: ""}.$simple".trimStart('.')]
                        "csharp" -> (file.namespaces.map { "$it.$simple" } + qualified).firstNotNullOfOrNull { index.csSymbols[it] }
                        "php" -> index.phpSymbols[qualified] ?: index.phpSymbols["${file.namespaces.firstOrNull()?.trim('\\') ?: ""}\\$simple".trimStart('\\')]
                        else -> null
                    }
                    candidates?.firstOrNull { it != file.path }?.let {
                        target = it
                        how = if (qualified.contains('.') || qualified.contains('\\')) "nombre completo del tipo" else "tipo declarado en el mismo paquete o espacio de nombres"
                        confidence = if (qualified.contains('.') || qualified.contains('\\')) RelationConfidence.VERIFIED else RelationConfidence.PROBABLE
                    }
                }
                val t = target ?: continue
                val targetKind = index.declared[t]?.get(simple)
                val kind = if (st.implementation || targetKind == SymbolKind.INTERFACE) RelationKind.IMPLEMENTATION else RelationKind.INHERITANCE
                val verb = if (kind == RelationKind.IMPLEMENTATION) "implementa" else "hereda de"
                add(
                    drafts, file.path, decl.line,
                    Resolution(t, confidence = confidence, how = how, kind = kind, exchanged = listOf(simple)),
                    st.name, kind, "${decl.name} $verb $simple",
                )
            }
        }
    }

    private fun prioritize(all: List<Draft>): List<Draft> = all.sortedBy {
        when {
            it.confidence == RelationConfidence.BROKEN -> 0
            it.target != null -> 1
            else -> 2
        }
    }

    private fun relation(
        d: Draft,
        graph: DependencyGraph,
        index: ProjectIndex,
        dependents: IntArray,
        capped: BooleanArray,
        reached: Array<MutableList<Int>>,
        entryPoints: List<String>,
        externalUsers: Map<String, Int>,
    ): Relation {
        val src = graph.indexOf(d.source)
        val entries = src?.let { reached[it] }.orEmpty().map { entryPoints[it] }
        val impact = when {
            d.target != null && d.confidence != RelationConfidence.BROKEN -> {
                val t = graph.indexOf(d.target)
                val declared = index.declared[d.target].orEmpty().keys
                RelationImpact(
                    undefinedSymbols = d.exchanged.filter { it in declared }.take(RecordValidator.MAX_EXCHANGED),
                    directDependents = t?.let { graph.inc[it].size } ?: 0,
                    transitiveDependents = t?.let { dependents[it] } ?: 0,
                    transitiveCapped = t?.let { capped[it] } ?: false,
                    entryPoints = entries.take(RecordValidator.MAX_IMPACT_ENTRY_POINTS),
                    entryPointCount = entries.size,
                )
            }
            d.external != null -> {
                val users = externalUsers[d.external] ?: 1
                RelationImpact(directDependents = users, transitiveDependents = users, entryPoints = entries.take(3), entryPointCount = entries.size)
            }
            else -> RelationImpact(
                // Rota: lo afectado es el propio origen y lo que depende de él.
                directDependents = src?.let { graph.inc[it].size } ?: 0,
                transitiveDependents = src?.let { dependents[it] } ?: 0,
                transitiveCapped = src?.let { capped[it] } ?: false,
                entryPoints = entries.take(3),
                entryPointCount = entries.size,
            )
        }
        return Relation(
            source = d.source,
            line = d.line,
            target = if (d.confidence == RelationConfidence.BROKEN) null else d.target,
            external = if (d.confidence == RelationConfidence.BROKEN) null else d.external,
            reference = d.reference.ifEmpty { "?" },
            kind = d.kind,
            exchanged = d.exchanged,
            exchangedNote = d.exchangedNote,
            confidence = d.confidence,
            evidence = d.evidence,
            impact = impact,
        )
    }

    /**
     * Un archivo sin referencias solo se señala si debería tenerlas: código de un lenguaje cuyas importaciones se
     * resuelven, que no es punto de entrada, prueba, configuración ni algo que un framework registra por su cuenta
     * (actividades, controladores, scripts de escenas…), cuyas relaciones llegan en la Fase 5B.
     */
    private fun candidateForUnused(facts: SourceFacts, roles: Set<FileRole>): Boolean {
        if (facts.language !in UNUSED_LANGUAGES) return false
        // En C y C++ los archivos de código los usa el sistema de compilación, no otro archivo: solo cuentan las cabeceras.
        if ((facts.language == "c" || facts.language == "cpp") && PathInfo(facts.path).ext !in setOf("h", "hh", "hpp", "hxx", "inl")) return false
        if (roles.any { it in UNUSED_EXCLUDED_ROLES }) return false
        val info = PathInfo(facts.path)
        if (info.lowerDirs.any { it in TEST_DIRS } || info.stem.endsWith("test") || info.stem.startsWith("test_") || info.lower.contains(".spec.") || info.lower.contains(".test.")) return false
        if (info.lower in UNUSED_EXCLUDED_NAMES || info.lower.contains(".config.") || info.stem == "index" || info.stem == "main" || info.lowerDirs.contains("migrations")) return false
        if (facts.declarations.any { d -> d.supertypes.any { st -> FRAMEWORK_SUFFIXES.any { st.name.substringAfterLast('.').endsWith(it) } } }) return false
        return true
    }

    /** Lenguajes que pueden importarse entre sí. */
    private fun ecosystem(language: String): String = when (language) {
        "javascript", "typescript", "tsx", "vue", "svelte", "html", "css", "scss" -> "web"
        "java", "kotlin" -> "jvm"
        "c", "cpp" -> "c"
        else -> language
    }

    private fun category(kind: SymbolKind): SymbolCategory = when (kind) {
        SymbolKind.CLASS -> SymbolCategory.CLASS
        SymbolKind.INTERFACE -> SymbolCategory.INTERFACE
        SymbolKind.ENUM -> SymbolCategory.ENUM
        SymbolKind.OBJECT -> SymbolCategory.OBJECT
        SymbolKind.FUNCTION -> SymbolCategory.FUNCTION
        SymbolKind.TYPE -> SymbolCategory.TYPE
        SymbolKind.CONSTANT -> SymbolCategory.CONSTANT
    }

    private fun coverage(context: StageContext, files: List<FileEntry>, systemIncludes: Int) {
        val withoutResolver = files.mapNotNull { f -> PathInfo(f.path).ext.takeIf { it in NO_RESOLVER } }.groupingBy { it }.eachCount()
        if (withoutResolver.isNotEmpty()) {
            context.coverage(
                CoverageReason.UNSUPPORTED,
                "Sin relaciones para los archivos de lenguajes sin gramática (${withoutResolver.entries.sortedByDescending { it.value }.joinToString { "${it.value} .${it.key}" }}): solo se cuentan sus líneas",
            )
        }
        if (collector.withoutImports.isNotEmpty()) {
            val total = collector.withoutImports.values.sum()
            val detail = collector.withoutImports.entries.sortedByDescending { it.value }.joinToString { "${it.value} de ${Languages.byId(it.key)?.name ?: it.key}" }
            context.coverage(
                CoverageReason.NOT_ANALYZED,
                "No se extrajeron las importaciones de $total archivos ($detail) porque no se pudieron analizar sintácticamente " +
                    "(tree-sitter no disponible o tiempo agotado): sus relaciones están incompletas y en esos lenguajes no se señalan archivos «sin referencias»",
            )
        }
        if (systemIncludes > 0) {
            context.coverage(CoverageReason.UNSUPPORTED, "$systemIncludes inclusiones de cabeceras del sistema o de bibliotecas (#include <…>) no se registran como relación")
        }
        if (collector.identifierBudgetExhausted) {
            context.coverage(CoverageReason.LIMIT_REACHED, "Proyecto muy grande: en parte de los archivos no se buscó el uso por nombre sin importación (mismo paquete)")
        }
        if (collector.facts.size >= RelationCollector.MAX_FILES) {
            context.coverage(CoverageReason.LIMIT_REACHED, "Se analizaron las relaciones de los ${RelationCollector.MAX_FILES} primeros archivos de código")
        }
        context.coverage(
            CoverageReason.UNSUPPORTED,
            "Relaciones de lenguaje (importaciones, inclusiones, herencia, uso en el mismo paquete y recursos de páginas y estilos). " +
                "Las importaciones dinámicas, la reflexión y la inyección de dependencias no se resuelven. Las relaciones propias de cada framework " +
                "(rutas, controladores, manifiesto de Android, escenas, llamadas HTTP entre frontend y backend) llegan en la Fase 5B, así que un archivo " +
                "«sin referencias» puede usarse a través de ellas.",
        )
    }

    private companion object {
        const val MAX_RELATIONS = 60_000
        const val MAX_DRAFTS = 200_000
        const val MAX_CYCLES = 200
        val UNUSED_LANGUAGES = setOf("javascript", "typescript", "tsx", "python", "java", "kotlin", "php", "dart", "css", "scss", "vue", "svelte", "c", "cpp")
        val UNUSED_EXCLUDED_ROLES = setOf(FileRole.ENTRY_POINT, FileRole.TEST, FileRole.CONFIGURATION, FileRole.BUILD, FileRole.DOCUMENTATION, FileRole.DEPENDENCIES)
        val UNUSED_EXCLUDED_NAMES = setOf("__init__.py", "manage.py", "setup.py", "conftest.py", "wsgi.py", "asgi.py", "settings.py", "urls.py", "admin.py", "apps.py")
        val TEST_DIRS = setOf("test", "tests", "__tests__", "androidtest", "spec", "specs", "testing", "e2e", "cypress")
        val FRAMEWORK_SUFFIXES = listOf(
            "Activity", "Fragment", "Service", "Receiver", "Provider", "Application", "Worker", "ViewModel", "Controller", "ControllerBase",
            "Middleware", "Command", "Migration", "Seeder", "MonoBehaviour", "ScriptableObject", "Component", "Module", "Hub", "Job",
            "Listener", "Handler", "Callback", "Plugin", "Extension", "Test", "TestCase", "Policy", "Request", "Resource", "Event",
        )
        val NO_RESOLVER = setOf("go", "rs", "rb", "swift", "scala", "lua", "pl", "ex", "exs", "hs", "clj", "fs", "vb", "m", "mm", "r")
    }
}

package com.funcodestudiocb.analyzer.engine.architecture

import com.funcodestudiocb.analyzer.engine.api.ArchLayer
import com.funcodestudiocb.analyzer.engine.api.ArchitectureSummary
import com.funcodestudiocb.analyzer.engine.api.DataEntity
import com.funcodestudiocb.analyzer.engine.api.Diagram
import com.funcodestudiocb.analyzer.engine.api.DiagramEdge
import com.funcodestudiocb.analyzer.engine.api.DiagramKind
import com.funcodestudiocb.analyzer.engine.api.DiagramNode
import com.funcodestudiocb.analyzer.engine.api.DiagramNodeKind
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.api.Endpoint
import com.funcodestudiocb.analyzer.engine.api.EndpointKind
import com.funcodestudiocb.analyzer.engine.api.FileLayer
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.InformationFlow
import com.funcodestudiocb.analyzer.engine.api.InformationStep
import com.funcodestudiocb.analyzer.engine.api.ProjectRoot
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.api.ResolvedDependency
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.DependencyScope
import com.funcodestudiocb.analyzer.engine.audit.deps.AuditKeys
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.inventory.InventoryKeys
import com.funcodestudiocb.analyzer.engine.inventory.PathInfo
import com.funcodestudiocb.analyzer.engine.inventory.rootOf
import com.funcodestudiocb.analyzer.engine.layout.LayeredLayout
import com.funcodestudiocb.analyzer.engine.layout.Layout
import com.funcodestudiocb.analyzer.engine.layout.LayoutEdge
import com.funcodestudiocb.analyzer.engine.layout.LayoutNode
import com.funcodestudiocb.analyzer.engine.layout.LayoutOptions
import com.funcodestudiocb.analyzer.engine.relations.Link
import com.funcodestudiocb.analyzer.engine.relations.RelationKeys
import java.util.EnumSet

/**
 * Fase 8 (E11): capa de arquitectura de cada archivo, diagramas de capas, módulos y proyectos ya dispuestos (la misma
 * disposición para la pantalla, el PDF y el SVG), dependencias que van contra las capas y flujos de información de
 * cada ruta de la API (Frontend → API → Controlador → Servicio → Modelo → BD). Cada paso de un flujo es una conexión
 * encontrada en el código: si no la hay, el flujo termina ahí en lugar de suponerla.
 */
class ArchitectureStage(private val collector: ArchitectureCollector) : Stage {
    override val id = "architecture"
    override val title = "Arquitectura y diagramas"
    override val dependsOn = setOf("relations")

    /** Totales de una arista agregada. */
    private class Agg {
        var count = 0
        val kinds: EnumSet<RelationKind> = EnumSet.noneOf(RelationKind::class.java)
        var best = RelationConfidence.BROKEN
        var weakest = RelationConfidence.VERIFIED
        var inverted = 0
        val samples = mutableListOf<String>()

        fun add(l: Link, inverted: Boolean = false) {
            count++
            kinds += l.kind
            if (l.confidence.ordinal < best.ordinal) best = l.confidence
            if (l.confidence.ordinal > weakest.ordinal) weakest = l.confidence
            if (inverted) this.inverted++
            if (samples.size < RecordValidator.MAX_EDGE_SAMPLES) samples += sample(l)
        }
    }

    override suspend fun run(context: StageContext) {
        val attrs = context.attributes
        val roles = attrs[InventoryKeys.ROLES].orEmpty()
        val roots = attrs[InventoryKeys.ROOTS].orEmpty()
        val endpoints = attrs[RelationKeys.ENDPOINTS].orEmpty()
        val entities = attrs[RelationKeys.DATA_ENTITIES].orEmpty()
        val links = attrs[RelationKeys.LINKS]
        val notes = mutableListOf(
            "Las capas se deducen de marcas del código (anotaciones, clases base), de las rutas y tablas encontradas y de las convenciones de carpetas y nombres. Un archivo con varias funciones aparece en la principal.",
        )

        context.progress(0.05f, "Capa de cada archivo")
        val httpCallers = links.orEmpty().filter { it.kind == RelationKind.HTTP && it.target != null }.mapTo(HashSet()) { it.source }
        val classifier = LayerClassifier(roles, collector.marks, endpoints, entities, httpCallers)
        val candidates = sortedSetOf<String>()
        candidates += collector.codeFiles.keys
        roles.forEach { (path, r) -> if (r.any { it in CONFIG_ROLES || it == FileRole.TEST }) candidates += path }
        val layerOf = LinkedHashMap<String, Assignment>()
        for (path in candidates) {
            if (layerOf.size >= MAX_CLASSIFIED) break
            layerOf[path] = classifier.classify(path)
        }
        if (collector.limitReached || candidates.size > layerOf.size) notes += "Proyecto muy grande: solo se asignó capa a los primeros ${layerOf.size} archivos."
        layerOf.forEach { (path, a) -> context.emit(FileLayer(path, a.layer, a.confidence, a.reason.take(RecordValidator.MAX_EVIDENCE_DETAIL))) }
        context.checkActive()

        // Conexiones entre archivos con capa (sin las rotas ni las de un archivo consigo mismo).
        val internal = links.orEmpty().filter { l ->
            l.target != null && l.target != l.source && l.confidence != RelationConfidence.BROKEN && l.source in layerOf && l.target in layerOf
        }
        if (links == null) notes += "No se analizaron las relaciones entre archivos: los diagramas solo muestran las capas, sin conexiones."

        context.progress(0.3f, "Diagrama de capas")
        val invertedSamples = mutableListOf<String>()
        var invertedCount = 0
        val layerEdges = LinkedHashMap<Pair<ArchLayer, ArchLayer>, Agg>()
        for (l in internal) {
            val a = layerOf[l.source]!!.layer
            val b = layerOf[l.target]!!.layer
            if (a == b || a == ArchLayer.TEST || b == ArchLayer.TEST) continue
            val inverted = isInverted(a, b, l.kind)
            if (inverted) {
                invertedCount++
                if (invertedSamples.size < RecordValidator.MAX_SAMPLES) invertedSamples += "${sample(l)} (${label(a)} → ${label(b)})"
            }
            layerEdges.getOrPut(a to b) { Agg() }.add(l, inverted)
        }
        val byLayer = ArchLayer.entries.associateWith { l -> layerOf.values.count { it.layer == l } }.filterValues { it > 0 }
        context.emit(layersDiagram(byLayer, layerEdges))
        context.checkActive()

        context.progress(0.5f, "Diagrama de módulos")
        val (modules, moduleCount) = modulesDiagram(layerOf, internal)
        context.emit(modules)
        context.checkActive()

        context.progress(0.65f, "Proyectos, servicios externos y dependencias")
        context.emit(projectsDiagram(roots, layerOf, internal, links.orEmpty(), attrs[AuditKeys.RESOLVED]))
        if (attrs[AuditKeys.RESOLVED] == null) notes += "Sin el análisis de dependencias: el diagrama de proyectos no muestra paquetes."
        context.checkActive()

        context.progress(0.8f, "Flujos de información")
        val flows = flows(endpoints, layerOf, links.orEmpty(), entities)
        flows.forEach { context.emit(it) }
        val apis = endpoints.count { it.kind == EndpointKind.API }
        when {
            apis == 0 -> notes += "No se encontraron rutas de una API: no hay flujos de petición que mostrar."
            flows.isEmpty() -> notes += "Se encontraron $apis rutas, pero sin conexiones con otros archivos que permitan seguir un flujo."
            flows.size < apis -> notes += "${apis - flows.size} rutas no se conectan con ningún otro archivo (el flujo se quedaría en la ruta) y no se muestran."
        }
        val shared = byLayer[ArchLayer.SHARED] ?: 0
        if (shared > 0) notes += "$shared archivos de código sin una capa clara aparecen como «Compartido»."
        if (invertedCount > 0) notes += "Las dependencias inversas (una capa interior que usa otra exterior) pueden ser intencionadas; conviene revisarlas."

        val counts = flows.size
        context.emit(
            ArchitectureSummary(
                byLayer = byLayer,
                classified = layerOf.values.count { it.layer != ArchLayer.SHARED },
                files = layerOf.size,
                inverted = invertedCount,
                invertedSamples = invertedSamples,
                flows = counts,
                completeFlows = flows.count { it.reachesDatabase },
                modules = moduleCount,
                pattern = pattern(byLayer),
                notes = notes.map { it.take(RecordValidator.MAX_EVIDENCE_DETAIL) }.take(RecordValidator.MAX_ARCH_NOTES),
            ),
        )
    }

    // ------------------------------------------------------------------ capas

    private fun isInverted(from: ArchLayer, to: ArchLayer, kind: RelationKind): Boolean {
        if (kind !in CODE_KINDS) return false
        val a = ArchLayer.FLOW.indexOf(from)
        val b = ArchLayer.FLOW.indexOf(to)
        return a >= 0 && b >= 0 && b < a
    }

    private fun pattern(byLayer: Map<ArchLayer, Int>): String? {
        fun has(l: ArchLayer) = (byLayer[l] ?: 0) > 0
        return when {
            collector.viewModels.isNotEmpty() -> "MVVM (vista, ViewModel y modelo)"
            has(ArchLayer.CONTROLLER) && has(ArchLayer.SERVICE) && has(ArchLayer.MODEL) -> "Capas: controlador, servicio y modelo"
            has(ArchLayer.CONTROLLER) && has(ArchLayer.MODEL) -> "MVC (controlador y modelo)"
            else -> null
        }
    }

    private fun layersDiagram(byLayer: Map<ArchLayer, Int>, edges: Map<Pair<ArchLayer, ArchLayer>, Agg>): Diagram {
        val shown = byLayer.keys.filter { it != ArchLayer.TEST }
        val nodes = shown.map { LayoutNode(it.name, 168f, 60f, LAYER_ROW.getValue(it)) }
        val shownEdges = edges.filterKeys { (a, b) -> a in shown && b in shown }
        val layout = LayeredLayout.layout(nodes, shownEdges.keys.map { (a, b) -> LayoutEdge(a.name, b.name) }, LayoutOptions(nodeGap = 32f, layerGap = 56f))
        val diagramNodes = layout.nodes.map { n ->
            val layer = ArchLayer.valueOf(n.id)
            DiagramNode(n.id, label(layer), DiagramNodeKind.LAYER, layer, n.x, n.y, n.width, n.height, files = byLayer[layer] ?: 0, detail = "${byLayer[layer] ?: 0} archivos")
        }
        val tests = byLayer[ArchLayer.TEST] ?: 0
        return Diagram(
            DiagramKind.LAYERS, "Capas de la arquitectura", layout.width, layout.height, diagramNodes,
            edges(layout, shownEdges.mapKeys { (k, _) -> k.first.name to k.second.name }),
            note = when {
                tests == 1 -> "El archivo de pruebas no se dibuja: las pruebas dependen de casi todo."
                tests > 1 -> "Los $tests archivos de pruebas no se dibujan: dependen de casi todo."
                else -> null
            },
        )
    }

    // ------------------------------------------------------------------ módulos

    private fun modulesDiagram(layerOf: Map<String, Assignment>, internal: List<Link>): Pair<Diagram, Int> {
        val files = layerOf.filterValues { it.layer != ArchLayer.TEST && it.layer != ArchLayer.CONFIG }.keys
        val dirsOf = files.associateWith { PathInfo(it).dirs }
        val maxDepth = dirsOf.values.maxOfOrNull { it.size } ?: 0
        fun keyAt(path: String, depth: Int) = dirsOf.getValue(path).take(depth).joinToString("/")
        // La profundidad más fina que deja un número legible de módulos.
        var depth = 1
        while (depth < maxDepth && files.map { keyAt(it, depth + 1) }.distinct().size <= MAX_MODULES) depth++
        var keyOf = files.associateWith { keyAt(it, depth) }
        val sizes = keyOf.values.groupingBy { it }.eachCount()
        var truncated = false
        if (sizes.size > MAX_MODULES) {
            truncated = true
            val keep = sizes.entries.sortedByDescending { it.value }.take(MAX_MODULES - 1).map { it.key }.toSet()
            keyOf = keyOf.mapValues { (_, k) -> if (k in keep) k else OTHERS }
        }
        val members = keyOf.entries.groupBy({ it.value }, { it.key })
        val agg = LinkedHashMap<Pair<String, String>, Agg>()
        for (l in internal) {
            val a = keyOf[l.source] ?: continue
            val b = keyOf[l.target!!] ?: continue
            if (a == b) continue
            agg.getOrPut(a to b) { Agg() }.add(l)
        }
        val kept = agg.entries.sortedByDescending { it.value.count }.take(RecordValidator.MAX_DIAGRAM_EDGES).associate { it.key to it.value }
        if (kept.size < agg.size) truncated = true
        val ids = members.keys.sorted()
        val index = ids.withIndex().associate { (i, k) -> k to i }
        // Ciclos entre módulos: alcanzabilidad (como mucho 30 módulos).
        val reach = Array(ids.size) { BooleanArray(ids.size) }
        kept.keys.forEach { (a, b) -> reach[index.getValue(a)][index.getValue(b)] = true }
        for (k in ids.indices) for (i in ids.indices) if (reach[i][k]) for (j in ids.indices) if (reach[k][j]) reach[i][j] = true
        val nodes = ids.map { k -> LayoutNode(nodeId(k), widthFor(moduleLabel(k)), 60f) }
        val layout = LayeredLayout.layout(nodes, kept.keys.map { (a, b) -> LayoutEdge(nodeId(a), nodeId(b)) })
        val diagramNodes = layout.nodes.map { n ->
            val key = ids.first { nodeId(it) == n.id }
            val list = members.getValue(key)
            val dominant = list.groupingBy { layerOf.getValue(it).layer }.eachCount().maxByOrNull { it.value }?.key
            DiagramNode(
                n.id, moduleLabel(key), DiagramNodeKind.MODULE, dominant, n.x, n.y, n.width, n.height, files = list.size,
                path = key.takeIf { it.isNotEmpty() && it != OTHERS },
                detail = "${list.size} archivos" + (dominant?.let { " · ${label(it)}" } ?: ""),
            )
        }
        val cycles = kept.keys.filter { (a, b) -> reach[index.getValue(b)][index.getValue(a)] }.toSet()
        val diagramEdges = edges(layout, kept.mapKeys { (k, _) -> nodeId(k.first) to nodeId(k.second) }) { from, to ->
            (ids.first { nodeId(it) == from } to ids.first { nodeId(it) == to }) in cycles
        }
        val note = when {
            truncated -> "Para que se pueda leer, se muestran los ${MAX_MODULES - 1} módulos más grandes y las conexiones más frecuentes; el resto está en «otros»."
            ids.size == 1 -> "Todo el código está en una sola carpeta."
            else -> null
        }
        return Diagram(DiagramKind.MODULES, "Módulos y sus dependencias", layout.width, layout.height, diagramNodes, diagramEdges, truncated, note) to ids.size
    }

    private fun nodeId(key: String) = if (key.isEmpty()) "(raíz)" else key

    private fun moduleLabel(key: String): String = when {
        key.isEmpty() -> "(raíz)"
        key == OTHERS -> "otros"
        else -> key.split('/').takeLast(2).joinToString("/")
    }

    private fun widthFor(label: String) = (label.length * 7f + 40f).coerceIn(120f, 240f)

    // ------------------------------------------------------------------ proyectos y dependencias

    private fun projectsDiagram(
        roots: List<ProjectRoot>,
        layerOf: Map<String, Assignment>,
        internal: List<Link>,
        links: List<Link>,
        resolved: List<ResolvedDependency>?,
    ): Diagram {
        val dirs = roots.map { it.directory }.distinct().sorted()
        fun projectOf(path: String): String = if (dirs.isEmpty()) "" else roots.rootOf(path) ?: OTHERS
        val ecosystems = roots.groupBy({ it.directory }, { ECOSYSTEM_LABEL[it.ecosystem] ?: it.ecosystem.name })
        val fileCount = layerOf.keys.groupingBy { projectOf(it) }.eachCount()
        val projects = (dirs.ifEmpty { listOf("") } + fileCount.keys).distinct().filter { (fileCount[it] ?: 0) > 0 || it in dirs }.take(MAX_PROJECTS)
        val agg = LinkedHashMap<Pair<String, String>, Agg>()
        for (l in internal) {
            val a = projectOf(l.source)
            val b = projectOf(l.target!!)
            if (a != b && a in projects && b in projects) agg.getOrPut(pid(a) to pid(b)) { Agg() }.add(l)
        }
        // Servicios externos: URLs con servidor escritas en el código.
        val hosts = links.filter { it.kind == RelationKind.HTTP && it.target == null && it.external != null && isHost(it.external!!) }
        val topHosts = hosts.groupingBy { it.external!! }.eachCount().entries.sortedByDescending { it.value }.take(MAX_HOSTS).map { it.key }
        for (l in hosts) {
            val host = l.external!!
            if (host !in topHosts) continue
            val a = projectOf(l.source)
            if (a in projects) agg.getOrPut(pid(a) to "srv:$host") { Agg() }.add(l)
        }
        // Dependencias directas de ejecución: las vulnerables y las más usadas.
        val packages = LinkedHashMap<String, ResolvedDependency>()
        val packageEdges = LinkedHashMap<Pair<String, String>, ResolvedDependency>()
        resolved.orEmpty().filter { it.direct && it.scope !in DEV_SCOPES }
            .groupBy { it.projectRoot ?: "" }
            .forEach { (root, deps) ->
                val project = if (dirs.isEmpty()) "" else root
                if (project !in projects) return@forEach
                deps.sortedWith(compareBy({ it.vulnerabilities.isEmpty() }, { worst(it)?.ordinal ?: Int.MAX_VALUE }, { -it.usedCount }, { it.name }))
                    .take(MAX_PACKAGES_PER_PROJECT)
                    .forEach { d ->
                        val id = "pkg:${d.ecosystem.name}:${d.name}"
                        packages.putIfAbsent(id, d)
                        packageEdges[pid(project) to id] = d
                    }
            }
        val nodes = ArrayList<LayoutNode>()
        projects.forEach { nodes += LayoutNode(pid(it), widthFor(projectLabel(it)), 60f) }
        topHosts.forEach { nodes += LayoutNode("srv:$it", widthFor(it), 52f) }
        packages.keys.take((RecordValidator.MAX_DIAGRAM_NODES - nodes.size).coerceAtLeast(0)).forEach { id -> nodes += LayoutNode(id, widthFor(packages.getValue(id).name), 52f) }
        val nodeIds = nodes.mapTo(HashSet()) { it.id }
        val layoutEdges = agg.keys.filter { it.first in nodeIds && it.second in nodeIds }.map { LayoutEdge(it.first, it.second) } +
            packageEdges.keys.filter { it.first in nodeIds && it.second in nodeIds }.map { LayoutEdge(it.first, it.second) }
        val layout = LayeredLayout.layout(nodes, layoutEdges)
        val diagramNodes = layout.nodes.map { n ->
            when {
                n.id.startsWith("srv:") -> DiagramNode(n.id, n.id.removePrefix("srv:"), DiagramNodeKind.EXTERNAL_SERVICE, null, n.x, n.y, n.width, n.height, detail = "servicio externo")
                n.id.startsWith("pkg:") -> {
                    val d = packages.getValue(n.id)
                    val vulns = d.vulnerabilityCount
                    DiagramNode(
                        n.id, d.name.take(RecordValidator.MAX_TARGET_LENGTH), DiagramNodeKind.PACKAGE, null, n.x, n.y, n.width, n.height,
                        path = d.name.take(RecordValidator.MAX_TARGET_LENGTH),
                        detail = listOfNotNull(d.version, if (vulns > 0) "$vulns vulnerabilidades" else null).joinToString(" · ").ifEmpty { null },
                        severity = worst(d),
                    )
                }
                else -> {
                    val dir = projects.first { pid(it) == n.id }
                    DiagramNode(
                        n.id, projectLabel(dir), DiagramNodeKind.PROJECT, null, n.x, n.y, n.width, n.height, files = fileCount[dir] ?: 0,
                        path = dir.takeIf { it.isNotEmpty() && it != OTHERS },
                        detail = (ecosystems[dir]?.distinct()?.joinToString(", ") ?: "${fileCount[dir] ?: 0} archivos").take(RecordValidator.MAX_EVIDENCE_DETAIL),
                    )
                }
            }
        }
        val routed = layout.edges.associateBy { it.from to it.to }
        val diagramEdges = ArrayList<DiagramEdge>()
        for ((key, a) in agg) {
            val r = routed[key] ?: continue
            diagramEdges += DiagramEdge(key.first, key.second, a.count, a.kinds.toList(), a.best, a.weakest, points(r.points), samples = a.samples)
        }
        for ((key, d) in packageEdges) {
            val r = routed[key] ?: continue
            // Declarada en el manifiesto: conexión verificada (y "usada" si el código la importa).
            diagramEdges += DiagramEdge(
                key.first, key.second, maxOf(1, d.usedCount), listOf(RelationKind.IMPORT), RelationConfidence.VERIFIED, RelationConfidence.VERIFIED,
                points(r.points), samples = d.usedIn.take(RecordValidator.MAX_EDGE_SAMPLES),
            )
        }
        val omitted = packages.size - packages.keys.count { it in nodeIds }
        return Diagram(
            DiagramKind.PROJECTS, "Proyectos, servicios externos y dependencias", layout.width, layout.height, diagramNodes,
            diagramEdges.take(RecordValidator.MAX_DIAGRAM_EDGES), truncated = omitted > 0 || projects.size >= MAX_PROJECTS,
            note = "Por proyecto se muestran como mucho $MAX_PACKAGES_PER_PROJECT dependencias directas: primero las vulnerables y luego las más usadas.",
        )
    }

    private fun pid(dir: String) = "prj:" + dir.ifEmpty { "/" }

    private fun projectLabel(dir: String) = when {
        dir.isEmpty() -> "(raíz)"
        dir == OTHERS -> "otros"
        else -> dir.split('/').takeLast(2).joinToString("/")
    }

    private fun isHost(value: String) = ' ' !in value && '.' in value && value.length <= RecordValidator.MAX_NAME_LENGTH

    private fun worst(d: ResolvedDependency): Severity? = d.vulnerabilities.minByOrNull { it.severity.ordinal }?.severity

    // ------------------------------------------------------------------ flujos

    private fun flows(endpoints: List<Endpoint>, layerOf: Map<String, Assignment>, links: List<Link>, entities: List<DataEntity>): List<InformationFlow> {
        val usable = links.filter { it.confidence != RelationConfidence.BROKEN }
        val codeOut = usable.filter { it.kind in FLOW_KINDS && it.target != null && it.target in layerOf }.groupBy { it.source }
        val dbOut = usable.filter { it.kind == RelationKind.DB_ACCESS }.groupBy { it.source }
        val http = usable.filter { it.kind == RelationKind.HTTP && it.target != null }.associateBy { it.source to it.target }
        val handlers = usable.filter { it.kind == RelationKind.ROUTE_HANDLER && it.target != null }.associateBy { it.source to it.target }
        val entityByFile = entities.filter { it.file != null }.groupBy { it.file!! }
        fun layer(path: String) = layerOf[path]?.layer
        fun name(path: String) = PathInfo(path).name

        val out = ArrayList<InformationFlow>()
        for (ep in endpoints.filter { it.kind == EndpointKind.API }.sortedWith(compareBy({ it.path }, { it.method })).take(MAX_FLOW_ENDPOINTS)) {
            val steps = ArrayList<InformationStep>()
            var alternatives = 0
            ep.callers.firstOrNull()?.let { caller ->
                val file = caller.substringBeforeLast(':')
                val line = caller.substringAfterLast(':', "").toIntOrNull()
                steps += InformationStep(layer(file) ?: ArchLayer.FRONTEND, "${name(file)}${line?.let { ":$it" } ?: ""}", file, line)
                alternatives += (maxOf(ep.callerCount, ep.callers.size) - 1).coerceAtLeast(0)
            }
            val apiConfidence = steps.firstOrNull()?.path?.let { http[it to ep.file]?.confidence ?: RelationConfidence.PROBABLE }
            steps += InformationStep(
                ArchLayer.API, "${ep.method} ${ep.path}".take(RecordValidator.MAX_ROUTE_LENGTH), ep.file, ep.line,
                via = if (steps.isEmpty()) null else RelationKind.HTTP, confidence = apiConfidence,
            )
            var current = ep.file
            val handler = ep.handlerFile
            if (handler != null && handler != ep.file) {
                val l = handlers[ep.file to handler]
                steps += InformationStep(
                    layer(handler)?.takeIf { it in BACKEND_STEPS } ?: ArchLayer.CONTROLLER,
                    name(handler) + (ep.handler?.let { " · $it" } ?: ""), handler, null,
                    via = RelationKind.ROUTE_HANDLER, confidence = l?.confidence ?: RelationConfidence.PROBABLE,
                )
                current = handler
            }
            val seen = steps.mapNotNullTo(HashSet()) { it.path }
            // Servicio: una conexión directa desde el controlador (o la ruta) a un archivo de la capa de servicios.
            val services = codeOut[current].orEmpty().filter { layer(it.target!!) == ArchLayer.SERVICE && it.target !in seen }
                .sortedWith(compareBy({ it.confidence.ordinal }, { it.target }))
            services.firstOrNull()?.let { l ->
                val t = l.target!!
                steps += InformationStep(ArchLayer.SERVICE, name(t), t, null, via = l.kind, confidence = l.confidence)
                seen += t
                alternatives += services.distinctBy { it.target }.size - 1
                current = t
            }
            // Modelo: preferentemente el acceso a datos (lleva la tabla); si no, una conexión directa a un modelo.
            var reachedVia: Link? = null
            val toModel = (dbOut[current].orEmpty().filter { it.target != null && it.target !in seen } +
                codeOut[current].orEmpty().filter { layer(it.target!!) == ArchLayer.MODEL && it.target !in seen })
                .sortedWith(compareBy({ if (it.kind == RelationKind.DB_ACCESS) 0 else 1 }, { it.confidence.ordinal }, { it.target }))
            toModel.firstOrNull()?.let { l ->
                val t = l.target!!
                steps += InformationStep(layer(t)?.takeIf { it == ArchLayer.MODEL || it == ArchLayer.DATABASE } ?: ArchLayer.MODEL, name(t), t, null, via = l.kind, confidence = l.confidence)
                seen += t
                alternatives += toModel.distinctBy { it.target }.size - 1
                current = t
                reachedVia = l
            }
            // Base de datos: la tabla del acceso que llevó al modelo, la que define el modelo o un acceso sin definición.
            val viaDb = reachedVia?.takeIf { it.kind == RelationKind.DB_ACCESS }
            val defined = entityByFile[current]
            val external = dbOut[current].orEmpty().firstOrNull { it.target == null && it.external != null }
            when {
                viaDb != null -> steps += InformationStep(ArchLayer.DATABASE, tableLabel(viaDb.label, defined), null, null, RelationKind.DB_ACCESS, viaDb.confidence)
                defined != null && steps.last().layer == ArchLayer.MODEL ->
                    steps += InformationStep(ArchLayer.DATABASE, tableLabel(null, defined), null, null, RelationKind.DB_ACCESS, RelationConfidence.PROBABLE)
                external != null -> steps += InformationStep(ArchLayer.DATABASE, external.external!!.take(RecordValidator.MAX_NAME_LENGTH), null, null, RelationKind.DB_ACCESS, external.confidence)
            }
            if (steps.size < 2) continue
            val weakest = steps.mapNotNull { it.confidence }.maxByOrNull { it.ordinal } ?: RelationConfidence.VERIFIED
            out += InformationFlow(0, ep.method.take(16), ep.path.take(RecordValidator.MAX_ROUTE_LENGTH), ep.framework, steps, weakest, steps.last().layer == ArchLayer.DATABASE, alternatives)
        }
        return out.sortedWith(compareBy({ !it.reachesDatabase }, { -it.steps.size }, { it.path }, { it.method }))
            .take(MAX_FLOWS)
            .mapIndexed { i, f -> f.copy(index = i) }
    }

    private fun tableLabel(linkLabel: String?, defined: List<DataEntity>?): String {
        linkLabel?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(RecordValidator.MAX_NAME_LENGTH) }
        val e = defined?.firstOrNull() ?: return "base de datos"
        val what = if (e.kind == com.funcodestudiocb.analyzer.engine.api.DataEntityKind.COLLECTION) "colección" else "tabla"
        return "$what ${e.name}".take(RecordValidator.MAX_NAME_LENGTH)
    }

    // ------------------------------------------------------------------ aristas

    private fun edges(layout: Layout, agg: Map<Pair<String, String>, Agg>, cycle: (String, String) -> Boolean = { _, _ -> false }): List<DiagramEdge> {
        val routed = layout.edges.associateBy { it.from to it.to }
        return agg.mapNotNull { (key, a) ->
            val r = routed[key] ?: return@mapNotNull null
            DiagramEdge(
                key.first, key.second, a.count, a.kinds.toList(), a.best, a.weakest, points(r.points),
                inverted = a.inverted > 0, cycle = cycle(key.first, key.second), samples = a.samples,
            )
        }.take(RecordValidator.MAX_DIAGRAM_EDGES)
    }

    /** Polilínea acotada: si tiene demasiados puntos, se conservan los extremos y una muestra uniforme. */
    private fun points(p: FloatArray): List<Float> {
        val n = p.size / 2
        val max = RecordValidator.MAX_EDGE_POINTS / 2
        if (n <= max) return p.toList()
        val out = ArrayList<Float>(max * 2)
        for (k in 0 until max) {
            val i = (k.toLong() * (n - 1) / (max - 1)).toInt()
            out += p[2 * i]
            out += p[2 * i + 1]
        }
        return out
    }

    companion object {
        const val MAX_CLASSIFIED = 60_000
        const val MAX_MODULES = 30
        const val MAX_PROJECTS = 40
        const val MAX_HOSTS = 8
        const val MAX_PACKAGES_PER_PROJECT = 6
        const val MAX_FLOW_ENDPOINTS = 500
        const val MAX_FLOWS = 200
        private const val OTHERS = "(otros)"

        private val CONFIG_ROLES = setOf(FileRole.CONFIGURATION, FileRole.BUILD, FileRole.DEPENDENCIES)
        private val DEV_SCOPES = setOf(DependencyScope.DEVELOPMENT, DependencyScope.TEST, DependencyScope.BUILD)

        /** Conexiones de código: las que cuentan para las dependencias inversas. */
        private val CODE_KINDS = setOf(RelationKind.IMPORT, RelationKind.CALL, RelationKind.INJECTION, RelationKind.REFERENCE, RelationKind.INHERITANCE)

        /** Conexiones que se siguen en un flujo de información. */
        private val FLOW_KINDS = setOf(RelationKind.IMPORT, RelationKind.CALL, RelationKind.INJECTION, RelationKind.REFERENCE)

        private val BACKEND_STEPS = setOf(ArchLayer.CONTROLLER, ArchLayer.SERVICE, ArchLayer.API)

        /** Fila de cada capa en el diagrama de capas. */
        private val LAYER_ROW = mapOf(
            ArchLayer.FRONTEND to 0, ArchLayer.API to 1, ArchLayer.CONTROLLER to 2, ArchLayer.SERVICE to 3, ArchLayer.SHARED to 3,
            ArchLayer.MODEL to 4, ArchLayer.DATABASE to 5, ArchLayer.CONFIG to 5, ArchLayer.TEST to 6,
        )

        private val ECOSYSTEM_LABEL = mapOf(
            Ecosystem.ANDROID to "Android", Ecosystem.GRADLE to "Gradle", Ecosystem.MAVEN to "Maven", Ecosystem.NPM to "npm",
            Ecosystem.PYTHON to "Python", Ecosystem.PHP_COMPOSER to "PHP", Ecosystem.DART_PUB to "Dart", Ecosystem.DOTNET to ".NET",
            Ecosystem.GO to "Go", Ecosystem.RUST_CARGO to "Rust", Ecosystem.RUBY to "Ruby", Ecosystem.COCOAPODS to "CocoaPods",
            Ecosystem.CMAKE to "CMake", Ecosystem.UNITY to "Unity", Ecosystem.UNREAL to "Unreal", Ecosystem.GODOT to "Godot",
        )

        /** Nombre de la capa en español (también lo usa la app). */
        fun label(layer: ArchLayer): String = when (layer) {
            ArchLayer.FRONTEND -> "Frontend"
            ArchLayer.API -> "API"
            ArchLayer.CONTROLLER -> "Controlador"
            ArchLayer.SERVICE -> "Servicio"
            ArchLayer.MODEL -> "Modelo"
            ArchLayer.DATABASE -> "Base de datos"
            ArchLayer.SHARED -> "Compartido"
            ArchLayer.CONFIG -> "Configuración"
            ArchLayer.TEST -> "Pruebas"
        }

        private fun sample(l: Link): String =
            ("${l.source}${l.line?.let { ":$it" } ?: ""} → ${l.target ?: l.external.orEmpty()}").take(RecordValidator.MAX_REFERENCE_LENGTH)
    }
}

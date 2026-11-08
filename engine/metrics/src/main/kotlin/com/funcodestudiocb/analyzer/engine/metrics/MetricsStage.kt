package com.funcodestudiocb.analyzer.engine.metrics

import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.DebtKind
import com.funcodestudiocb.analyzer.engine.api.DuplicateBlock
import com.funcodestudiocb.analyzer.engine.api.FileMetrics
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.FileSize
import com.funcodestudiocb.analyzer.engine.api.FunctionRef
import com.funcodestudiocb.analyzer.engine.api.MetricsSummary
import com.funcodestudiocb.analyzer.engine.api.QualityTool
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.audit.deps.AuditKeys
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.inventory.InventoryKeys
import com.funcodestudiocb.analyzer.engine.relations.RelationKeys

/**
 * Fase 9: métricas de mantenimiento del código de producción (las pruebas se cuentan aparte) y el índice de
 * mantenimiento. La complejidad sale del árbol sintáctico de cada función; la duplicación, de comparar bloques de líneas;
 * la estructura, de las relaciones entre archivos (5A/5B), y la salud de las dependencias, de la Fase 7. Lo que no se
 * pudo medir se dice en el resumen y no cuenta en el índice.
 */
class MetricsStage(private val collector: MetricsCollector) : Stage {
    override val id = "metrics"
    override val title = "Métricas e índice de mantenimiento"
    override val dependsOn = setOf("inventory")

    override suspend fun run(context: StageContext) {
        val attrs = context.attributes
        val roles = attrs[InventoryKeys.ROLES].orEmpty()
        val isTest = { path: String -> FileRole.TEST in roles[path].orEmpty() }
        val files = collector.files.values.toList()
        val production = files.filterNot { isTest(it.path) }
        val tests = files.filter { isTest(it.path) }
        val notes = mutableListOf<String>()

        context.progress(0.1f, "Complejidad y tamaño")
        val functions = production.filter { it.measured }.flatMap { f -> f.functions.map { f.path to it } }
        val complexities = functions.map { it.second.complexity }.sorted()
        val dupCounts = collector.duplicates.counts()
        val linksAttr = attrs[RelationKeys.LINKS]
        val (cycleFiles, cycles) = cycles(linksAttr, collector.files.keys)
        val fanOut = fanOut(linksAttr, collector.files.keys)

        // Registros por archivo (código de producción y pruebas).
        for (f in files) {
            val dup = dupCounts[f.path]?.second ?: 0
            val fnComplexity = f.functions.sumOf { it.complexity }
            context.emit(
                FileMetrics(
                    path = f.path,
                    language = f.languageId,
                    codeLines = f.codeLines,
                    commentLines = f.commentLines,
                    test = isTest(f.path),
                    measured = f.measured,
                    functions = f.functions.size,
                    complexity = if (f.measured) fnComplexity + f.topLevelDecisions else 0,
                    maxComplexity = f.functions.maxOfOrNull { it.complexity } ?: 0,
                    longestFunction = f.functions.maxOfOrNull { it.lines } ?: 0,
                    duplicatedLines = minOf(dup, f.codeLines),
                    debtMarkers = f.debt.sum(),
                ),
            )
        }
        context.checkActive()

        context.progress(0.5f, "Duplicación, deuda y herramientas")
        val productionPaths = production.mapTo(HashSet()) { it.path }
        var base = 0L
        var duplicated = 0L
        dupCounts.forEach { (path, counts) ->
            if (path in productionPaths) {
                base += counts.first
                duplicated += counts.second
            }
        }
        if (collector.duplicates.saturated) notes += "Proyecto muy grande: la duplicación se buscó en una parte del código (las primeras ${DuplicateFinder.MAX_WINDOWS} ventanas de líneas)."
        val blocks = collector.duplicates.topBlocks(RecordValidator.MAX_METRIC_LIST).map { (lines, places) ->
            DuplicateBlock(lines, places.take(RecordValidator.MAX_DUPLICATE_OCCURRENCES).map { it.take(RecordValidator.MAX_REFERENCE_LENGTH) }, places.size)
        }.filter { it.occurrences.size >= 2 }.take(20)

        val debtByKind = DebtKind.entries.associateWith { k -> production.sumOf { it.debt[k.ordinal] } }.filterValues { it > 0 }
        val debtSamples = collector.debtSamples.filter { it.path in productionPaths }
            .sortedWith(compareBy({ if (it.kind == DebtKind.FIXME || it.kind == DebtKind.HACK) 0 else 1 }, { it.path }, { it.line }))
            .take(RecordValidator.MAX_METRIC_LIST)

        val allPaths = (roles.keys + collector.files.keys).toSortedSet()
        val tools = (allPaths.mapNotNull { QualityTools.byPath(it) } + collector.projectTools)
            .distinctBy { it.kind to it.name }
            .sortedWith(compareBy<QualityTool>({ it.kind.ordinal }, { it.name }))
            .take(RecordValidator.MAX_QUALITY_TOOLS)
        val readme = allPaths.filter { README.matches(it.substringAfterLast('/').lowercase()) }
            .minWithOrNull(compareBy({ it.count { c -> c == '/' } }, { it }))

        // Lo que no se pudo medir.
        val notMeasured = production.filterNot { it.measured }.groupBy { it.languageName }
            .map { (name, list) -> name to list.size }.sortedByDescending { it.second }
            .map { (name, n) -> if (n == 1) "$name (1 archivo)" else "$name ($n archivos)" }
        if (notMeasured.isNotEmpty()) {
            context.coverage(CoverageReason.NOT_ANALYZED, "Complejidad no medida (sin gramática o análisis léxico): ${notMeasured.joinToString()}.")
        }
        val withErrors = production.count { it.measured && it.syntaxErrors > 0 }
        if (withErrors > 0) {
            notes += if (withErrors == 1) "1 archivo con errores de sintaxis: su complejidad puede estar incompleta."
            else "$withErrors archivos con errores de sintaxis: su complejidad puede estar incompleta."
        }
        if (collector.limitReached) notes += "Proyecto muy grande: solo se midieron los primeros ${collector.files.size} archivos de código."
        if (collector.generated > 0) {
            notes += if (collector.generated == 1) "1 archivo generado (minificado o automático) no cuenta en las métricas."
            else "${collector.generated} archivos generados (minificados o automáticos) no cuentan en las métricas."
        }
        if (linksAttr == null) notes += "No se analizaron las relaciones entre archivos: la estructura no se evalúa."

        val productionLines = production.sumOf { it.codeLines.toLong() }
        val highCoupling = fanOut.filterKeys { it in productionPaths }.filterValues { it >= HIGH_FAN_OUT }
        val summary = MetricsSummary(
            codeFiles = files.size,
            codeLines = files.sumOf { it.codeLines.toLong() },
            commentLines = production.sumOf { it.commentLines.toLong() },
            testFiles = tests.size,
            testLines = tests.sumOf { it.codeLines.toLong() },
            generatedFiles = collector.generated,
            measuredFiles = production.count { it.measured },
            functions = complexities.size,
            averageComplexity = if (complexities.isEmpty()) null else complexities.average().coerceAtLeast(1.0),
            medianComplexity = complexities.getOrNull(complexities.size / 2),
            p90Complexity = complexities.getOrNull(((complexities.size - 1) * 0.9).toInt()),
            maxComplexity = complexities.lastOrNull(),
            functionsOver10 = complexities.count { it > 10 },
            functionsOver20 = complexities.count { it > 20 },
            longFunctions = functions.count { it.second.lines > LONG_FUNCTION },
            largeFiles = production.count { it.codeLines > LARGE_FILE },
            complexFunctionList = functions.sortedWith(compareBy({ -it.second.complexity }, { it.first }, { it.second.line }))
                .take(20).map { (path, f) -> FunctionRef(path, f.name, f.line, f.lines, f.complexity) },
            longFunctionList = functions.filter { it.second.lines > LONG_FUNCTION }
                .sortedWith(compareBy({ -it.second.lines }, { it.first }, { it.second.line }))
                .take(10).map { (path, f) -> FunctionRef(path, f.name, f.line, f.lines, f.complexity) },
            largeFileList = production.filter { it.codeLines > LARGE_FILE }.sortedByDescending { it.codeLines }
                .take(10).map { FileSize(it.path, it.codeLines) },
            duplicatedLines = duplicated,
            duplicationBase = base,
            duplicates = blocks,
            debtByKind = debtByKind,
            debtSamples = debtSamples,
            qualityTools = tools,
            readme = readme,
            filesInCycles = cycleFiles.count { it in productionPaths },
            cycles = cycles,
            highCoupling = highCoupling.size,
            highCouplingSamples = highCoupling.entries.sortedByDescending { it.value }.take(RecordValidator.MAX_SAMPLES)
                .map { (path, n) -> "$path ($n archivos)" },
            notMeasured = notMeasured.take(RecordValidator.MAX_METRICS_NOTES),
            notes = notes.take(RecordValidator.MAX_METRICS_NOTES),
        )
        context.emit(summary)

        context.progress(0.9f, "Índice de mantenimiento")
        val index = MaintenanceCalculator.compute(
            MaintenanceInputs(
                functions = summary.functions,
                functionsOver10 = summary.functionsOver10,
                functionsOver20 = summary.functionsOver20,
                longFunctions = summary.longFunctions,
                codeFiles = production.size,
                largeFiles = summary.largeFiles,
                duplicationBase = base,
                duplicatedLines = duplicated,
                productionLines = productionLines,
                commentLines = summary.commentLines,
                testFiles = tests.size,
                testLines = summary.testLines,
                readme = readme != null,
                dependencies = attrs[AuditKeys.SUMMARY],
                tools = tools.mapTo(HashSet()) { it.kind },
                debt = debtByKind,
                relationsAnalyzed = linksAttr != null,
                filesInCycles = summary.filesInCycles,
                highCoupling = summary.highCoupling,
            ),
        )
        context.emit(index)
    }

    // ------------------------------------------------------------------ estructura

    private fun internalEdges(links: List<com.funcodestudiocb.analyzer.engine.relations.Link>?, files: Set<String>): Map<String, Set<String>> {
        if (links == null) return emptyMap()
        val out = HashMap<String, MutableSet<String>>()
        for (l in links) {
            val target = l.target ?: continue
            if (target == l.source || l.kind !in CODE_KINDS || l.confidence == RelationConfidence.BROKEN) continue
            if (l.source !in files || target !in files) continue
            out.getOrPut(l.source) { HashSet() } += target
        }
        return out
    }

    private fun fanOut(links: List<com.funcodestudiocb.analyzer.engine.relations.Link>?, files: Set<String>): Map<String, Int> =
        internalEdges(links, files).mapValues { it.value.size }

    /** Archivos en ciclos de dependencias (componentes fuertemente conexos de 2 o más, Tarjan iterativo) y cuántos ciclos. */
    private fun cycles(links: List<com.funcodestudiocb.analyzer.engine.relations.Link>?, files: Set<String>): Pair<Set<String>, Int> {
        val edges = internalEdges(links, files)
        if (edges.isEmpty()) return emptySet<String>() to 0
        val nodes = (edges.keys + edges.values.flatten()).toList()
        val id = nodes.withIndex().associate { it.value to it.index }
        val adj = Array(nodes.size) { i -> edges[nodes[i]].orEmpty().map { id.getValue(it) }.toIntArray() }
        val index = IntArray(nodes.size) { -1 }
        val low = IntArray(nodes.size)
        val onStack = BooleanArray(nodes.size)
        val stack = ArrayDeque<Int>()
        var counter = 0
        val inCycles = HashSet<String>()
        var count = 0
        for (root in nodes.indices) {
            if (index[root] >= 0) continue
            val work = ArrayDeque<IntArray>() // (nodo, siguiente arista)
            work.addLast(intArrayOf(root, 0))
            index[root] = counter; low[root] = counter; counter++
            stack.addLast(root); onStack[root] = true
            while (work.isNotEmpty()) {
                val top = work.last()
                val v = top[0]
                if (top[1] < adj[v].size) {
                    val w = adj[v][top[1]++]
                    if (index[w] < 0) {
                        index[w] = counter; low[w] = counter; counter++
                        stack.addLast(w); onStack[w] = true
                        work.addLast(intArrayOf(w, 0))
                    } else if (onStack[w]) {
                        low[v] = minOf(low[v], index[w])
                    }
                } else {
                    work.removeLast()
                    if (work.isNotEmpty()) {
                        val parent = work.last()[0]
                        low[parent] = minOf(low[parent], low[v])
                    }
                    if (low[v] == index[v]) {
                        val component = ArrayList<Int>()
                        do {
                            val w = stack.removeLast()
                            onStack[w] = false
                            component += w
                        } while (w != v)
                        if (component.size >= 2) {
                            count++
                            component.forEach { inCycles += nodes[it] }
                        }
                    }
                }
            }
        }
        return inCycles to count
    }

    private companion object {
        const val LONG_FUNCTION = 60
        const val LARGE_FILE = 500
        const val HIGH_FAN_OUT = 20
        val README = Regex("^readme(\\.[a-z0-9]+)?$")
        val CODE_KINDS = setOf(RelationKind.IMPORT, RelationKind.CALL, RelationKind.INJECTION, RelationKind.REFERENCE, RelationKind.INHERITANCE)
    }
}

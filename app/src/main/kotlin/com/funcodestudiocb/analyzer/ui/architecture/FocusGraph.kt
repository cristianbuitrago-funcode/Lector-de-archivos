package com.funcodestudiocb.analyzer.ui.architecture

import com.funcodestudiocb.analyzer.engine.api.Diagram
import com.funcodestudiocb.analyzer.engine.api.DiagramEdge
import com.funcodestudiocb.analyzer.engine.api.DiagramKind
import com.funcodestudiocb.analyzer.engine.api.DiagramNode
import com.funcodestudiocb.analyzer.engine.api.DiagramNodeKind
import com.funcodestudiocb.analyzer.engine.api.FileLayer
import com.funcodestudiocb.analyzer.engine.api.Relation
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.layout.LayeredLayout
import com.funcodestudiocb.analyzer.engine.layout.LayoutDirection
import com.funcodestudiocb.analyzer.engine.layout.LayoutEdge
import com.funcodestudiocb.analyzer.engine.layout.LayoutNode
import com.funcodestudiocb.analyzer.engine.layout.LayoutOptions
import java.util.EnumSet

enum class FocusDirection { BOTH, OUTGOING, INCOMING }

/** Qué conexiones del archivo con foco se dibujan. */
data class FocusOptions(
    /** Saltos desde el archivo (1 o 2). */
    val depth: Int = 1,
    val direction: FocusDirection = FocusDirection.BOTH,
    /** Solo conexiones verificadas o probables. */
    val onlyStrong: Boolean = false,
)

/**
 * Grafo de un archivo con foco: sus vecinos (a uno o dos saltos) y las conexiones entre ellos, dispuesto con el mismo
 * algoritmo que los diagramas del motor. Se calcula en el teléfono a partir de las relaciones ya guardadas y validadas.
 */
object FocusGraph {
    const val MAX_NODES = 60

    fun build(focus: String, relations: List<Relation>, layers: Map<String, FileLayer>, options: FocusOptions): Diagram {
        val usable = relations.filter { r ->
            r.target != null && r.target != r.source && r.confidence != RelationConfidence.BROKEN &&
                (!options.onlyStrong || r.confidence == RelationConfidence.VERIFIED || r.confidence == RelationConfidence.PROBABLE)
        }
        val out = usable.groupBy { it.source }
        val inc = usable.groupBy { it.target!! }
        val nodes = linkedSetOf(focus)
        var truncated = false
        var frontier = listOf(focus)
        repeat(options.depth.coerceIn(1, 2)) {
            val next = ArrayList<String>()
            for (v in frontier) {
                val neighbors = buildList {
                    if (options.direction != FocusDirection.INCOMING) out[v].orEmpty().forEach { add(it.target!!) }
                    if (options.direction != FocusDirection.OUTGOING) inc[v].orEmpty().forEach { add(it.source) }
                }
                for (w in neighbors.distinct().sorted()) {
                    if (w in nodes) continue
                    if (nodes.size >= MAX_NODES) {
                        truncated = true
                        break
                    }
                    nodes += w
                    next += w
                }
            }
            frontier = next
        }
        class Agg {
            var count = 0
            val kinds: EnumSet<RelationKind> = EnumSet.noneOf(RelationKind::class.java)
            var best = RelationConfidence.BROKEN
            var weakest = RelationConfidence.VERIFIED
            val samples = ArrayList<String>()
        }
        val agg = LinkedHashMap<Pair<String, String>, Agg>()
        for (r in usable) {
            val t = r.target!!
            if (r.source !in nodes || t !in nodes) continue
            val a = agg.getOrPut(r.source to t) { Agg() }
            a.count++
            a.kinds += r.kind
            if (r.confidence.ordinal < a.best.ordinal) a.best = r.confidence
            if (r.confidence.ordinal > a.weakest.ordinal) a.weakest = r.confidence
            if (a.samples.size < 3) a.samples += "${r.source}${r.line?.let { ":$it" } ?: ""} → $t"
        }
        val layout = LayeredLayout.layout(
            nodes.map { LayoutNode(it, widthFor(it.substringAfterLast('/')), 52f) },
            agg.keys.map { LayoutEdge(it.first, it.second) },
            LayoutOptions(direction = LayoutDirection.LEFT_RIGHT, nodeGap = 18f, layerGap = 56f),
        )
        val routed = layout.edges.associateBy { it.from to it.to }
        return Diagram(
            kind = DiagramKind.FOCUS,
            title = focus.substringAfterLast('/'),
            width = layout.width,
            height = layout.height,
            nodes = layout.nodes.map { n ->
                DiagramNode(
                    n.id, n.id.substringAfterLast('/'), DiagramNodeKind.FILE, layers[n.id]?.layer, n.x, n.y, n.width, n.height,
                    path = n.id, detail = n.id.substringBeforeLast('/', "").split('/').takeLast(2).joinToString("/").ifEmpty { null },
                )
            },
            edges = agg.mapNotNull { (key, a) ->
                val r = routed[key] ?: return@mapNotNull null
                DiagramEdge(key.first, key.second, a.count, a.kinds.toList(), a.best, a.weakest, r.points.toList(), samples = a.samples)
            },
            truncated = truncated,
        )
    }

    private fun widthFor(label: String) = (label.length * 7f + 40f).coerceIn(110f, 230f)
}

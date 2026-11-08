package com.funcodestudiocb.analyzer.engine.layout

/** Nodo a colocar. Si se da [layer] a todos los nodos, las capas son esas (por ejemplo, las capas de arquitectura). */
class LayoutNode(val id: String, val width: Float, val height: Float, val layer: Int? = null)

/** Arista dirigida entre dos nodos por su identificador. */
class LayoutEdge(val from: String, val to: String)

enum class LayoutDirection {
    /** Capas de arriba abajo (las aristas bajan). */
    TOP_DOWN,

    /** Capas de izquierda a derecha (las aristas van a la derecha). */
    LEFT_RIGHT,
}

data class LayoutOptions(
    val direction: LayoutDirection = LayoutDirection.TOP_DOWN,
    /** Separación entre nodos de una misma capa. */
    val nodeGap: Float = 24f,
    /** Separación entre capas. */
    val layerGap: Float = 64f,
    val margin: Float = 16f,
    /** Pasadas de reducción de cruces (alternando hacia abajo y hacia arriba). */
    val sweeps: Int = 12,
    /** Pasadas de ajuste de coordenadas. */
    val refinements: Int = 8,
)

/** Nodo colocado: esquina superior izquierda, tamaño, capa y posición dentro de la capa. */
class PlacedNode(val id: String, val x: Float, val y: Float, val width: Float, val height: Float, val layer: Int, val order: Int) {
    val centerX: Float get() = x + width / 2
    val centerY: Float get() = y + height / 2
}

/**
 * Arista trazada como polilínea (x0, y0, x1, y1…), siempre del origen al destino.
 * [reversed]: va "contra" las capas (de una capa posterior a una anterior); [sameLayer]: une dos nodos de la misma capa.
 */
class RoutedEdge(val from: String, val to: String, val points: FloatArray, val reversed: Boolean, val sameLayer: Boolean)

class Layout(val nodes: List<PlacedNode>, val edges: List<RoutedEdge>, val width: Float, val height: Float, val crossings: Int)

/**
 * Disposición por capas al estilo de Sugiyama, propia y determinista (sin azar: la misma entrada da siempre el mismo
 * dibujo, en pantalla, en el PDF y en el SVG):
 *
 * 1. Se rompen los ciclos invirtiendo las aristas de retroceso de un recorrido en profundidad (si las capas no vienen
 *    dadas) y se asigna a cada nodo la capa del camino más largo, acercando las fuentes a sus sucesores.
 * 2. Las aristas que saltan varias capas reciben nodos ficticios, uno por capa, para poder trazarlas sin atravesar nodos.
 * 3. Los cruces se reducen ordenando cada capa por el baricentro de sus vecinos, en pasadas alternas, y se conserva el
 *    mejor orden encontrado.
 * 4. Las coordenadas acercan cada nodo a sus vecinos sin solapar los de su capa.
 *
 * Los tamaños están acotados ([MAX_NODES], [MAX_EDGES], [MAX_DUMMIES]): quien llama agrega antes de dibujar.
 */
object LayeredLayout {
    const val MAX_NODES = 1_000
    const val MAX_EDGES = 5_000
    const val MAX_DUMMIES = 6_000
    private const val DUMMY_WIDTH = 2f

    fun layout(nodes: List<LayoutNode>, edges: List<LayoutEdge>, options: LayoutOptions = LayoutOptions()): Layout {
        require(nodes.size <= MAX_NODES) { "Demasiados nodos para dibujar (${nodes.size})" }
        val unique = nodes.distinctBy { it.id }
        val transposed = options.direction == LayoutDirection.LEFT_RIGHT
        // Se calcula siempre de arriba abajo; de izquierda a derecha es la misma disposición traspuesta.
        val input = if (transposed) unique.map { LayoutNode(it.id, it.height, it.width, it.layer) } else unique
        val td = TopDown(input, edges, options).run()
        if (!transposed) return td
        return Layout(
            td.nodes.map { PlacedNode(it.id, it.y, it.x, it.height, it.width, it.layer, it.order) },
            td.edges.map { e -> RoutedEdge(e.from, e.to, FloatArray(e.points.size) { i -> e.points[i xor 1] }, e.reversed, e.sameLayer) },
            td.height,
            td.width,
            td.crossings,
        )
    }

    /** Cálculo de arriba abajo. Los vértices 0..n-1 son los nodos reales; los siguientes, los ficticios. */
    private class TopDown(private val nodes: List<LayoutNode>, edges: List<LayoutEdge>, private val o: LayoutOptions) {
        private val n = nodes.size
        private val index = HashMap<String, Int>().also { m -> nodes.forEachIndexed { i, node -> m[node.id] = i } }
        private val pairs: List<IntArray> = run {
            val seen = HashSet<Long>()
            val out = ArrayList<IntArray>()
            for (e in edges) {
                val a = index[e.from] ?: continue
                val b = index[e.to] ?: continue
                if (a == b) continue
                if (seen.add(a.toLong() shl 32 or b.toLong())) out += intArrayOf(a, b)
                if (out.size >= MAX_EDGES) break
            }
            out
        }

        // Datos de los vértices (reales y ficticios).
        private val width = ArrayList<Float>()
        private val height = ArrayList<Float>()
        private val layerOf = ArrayList<Int>()
        private val up = ArrayList<MutableList<Int>>()
        private val down = ArrayList<MutableList<Int>>()

        fun run(): Layout {
            if (n == 0) return Layout(emptyList(), emptyList(), o.margin * 2, o.margin * 2, 0)
            val layers = assignLayers()
            nodes.forEachIndexed { i, node ->
                width += node.width.coerceAtLeast(1f)
                height += node.height.coerceAtLeast(1f)
                layerOf += layers[i]
                up.add(mutableListOf())
                down.add(mutableListOf())
            }
            // Aristas orientadas hacia abajo, con sus vértices ficticios.
            class Chain(val from: Int, val to: Int, val path: IntArray, val reversed: Boolean, val sameLayer: Boolean)
            val chains = ArrayList<Chain>()
            var dummies = 0
            for ((a, b) in pairs.map { it[0] to it[1] }) {
                val la = layers[a]
                val lb = layers[b]
                if (la == lb) {
                    chains += Chain(a, b, intArrayOf(a, b), reversed = false, sameLayer = true)
                    continue
                }
                val reversed = la > lb
                val top = if (reversed) b else a
                val bottom = if (reversed) a else b
                val span = kotlin.math.abs(la - lb)
                val path = ArrayList<Int>()
                path += top
                if (span > 1 && dummies + span - 1 <= MAX_DUMMIES) {
                    var prev = top
                    for (l in layerOf[top] + 1 until layerOf[bottom]) {
                        val d = width.size
                        width += DUMMY_WIDTH
                        height += 0f
                        layerOf += l
                        up.add(mutableListOf())
                        down.add(mutableListOf())
                        link(prev, d)
                        path += d
                        prev = d
                        dummies++
                    }
                    link(prev, bottom)
                } else if (span == 1) {
                    link(top, bottom)
                }
                path += bottom
                chains += Chain(a, b, path.toIntArray(), reversed, sameLayer = false)
            }
            val total = width.size
            val layerCount = (layerOf.maxOrNull() ?: 0) + 1
            val order = Array(layerCount) { ArrayList<Int>() }
            for (v in 0 until total) order[layerOf[v]] += v
            val pos = IntArray(total)
            fun reindex() = order.forEach { layer -> layer.forEachIndexed { i, v -> pos[v] = i } }
            reindex()

            // Reducción de cruces por baricentro, conservando el mejor orden.
            var best = order.map { it.toIntArray() }
            var bestCrossings = crossings(order, pos)
            repeat(o.sweeps) { sweep ->
                val downward = sweep % 2 == 0
                val range = if (downward) 1 until layerCount else (layerCount - 2) downTo 0
                for (l in range) {
                    val neighbors = if (downward) up else down
                    val bary = HashMap<Int, Double>()
                    for (v in order[l]) {
                        val ns = neighbors[v]
                        bary[v] = if (ns.isEmpty()) pos[v].toDouble() else ns.sumOf { pos[it] }.toDouble() / ns.size
                    }
                    order[l].sortWith(compareBy<Int>({ bary[it]!! }, { pos[it] }))
                    order[l].forEachIndexed { i, v -> pos[v] = i }
                }
                val c = crossings(order, pos)
                if (c < bestCrossings) {
                    bestCrossings = c
                    best = order.map { it.toIntArray() }
                }
            }
            for (l in 0 until layerCount) {
                order[l].clear()
                best[l].forEach { order[l] += it }
            }
            reindex()

            // Coordenadas: y por capa; x acercando cada vértice a sus vecinos sin solapes.
            val layerHeight = FloatArray(layerCount)
            for (v in 0 until total) layerHeight[layerOf[v]] = maxOf(layerHeight[layerOf[v]], height[v])
            val layerTop = FloatArray(layerCount)
            var y = o.margin
            for (l in 0 until layerCount) {
                layerTop[l] = y
                y += layerHeight[l] + o.layerGap
            }
            val cx = DoubleArray(total)
            for (layer in order) {
                var x = o.margin.toDouble()
                for (v in layer) {
                    cx[v] = x + width[v] / 2
                    x += width[v] + gap(v)
                }
            }
            repeat(o.refinements) { iteration ->
                val downward = iteration % 2 == 0
                val range = if (downward) 1 until layerCount else (layerCount - 2) downTo 0
                for (l in range) {
                    val neighbors = if (downward) up else down
                    val layer = order[l]
                    val desired = DoubleArray(layer.size) { i ->
                        val v = layer[i]
                        val ns = neighbors[v]
                        if (ns.isEmpty()) cx[v] else median(ns.map { cx[it] })
                    }
                    place(layer, desired, cx)
                }
            }
            val minX = (0 until total).minOf { cx[it] - width[it] / 2 }
            val shift = o.margin - minX
            for (v in 0 until total) cx[v] += shift
            val maxX = (0 until total).maxOf { cx[it] + width[it] / 2 }

            val placed = (0 until n).map { v ->
                val l = layerOf[v]
                PlacedNode(
                    nodes[v].id, (cx[v] - width[v] / 2).toFloat(), layerTop[l] + (layerHeight[l] - height[v]) / 2,
                    width[v], height[v], l, pos[v],
                )
            }
            fun bottom(v: Int) = layerTop[layerOf[v]] + (layerHeight[layerOf[v]] + height[v]) / 2
            fun top(v: Int) = layerTop[layerOf[v]] + (layerHeight[layerOf[v]] - height[v]) / 2
            val routed = chains.map { c ->
                val pts = ArrayList<Float>()
                if (c.sameLayer) {
                    val a = c.from
                    val b = c.to
                    val ya = bottom(a)
                    val yb = bottom(b)
                    pts += listOf(cx[a].toFloat(), ya, ((cx[a] + cx[b]) / 2).toFloat(), maxOf(ya, yb) + o.layerGap / 3, cx[b].toFloat(), yb)
                } else {
                    c.path.forEachIndexed { i, v ->
                        when (i) {
                            0 -> pts += listOf(cx[v].toFloat(), bottom(v))
                            c.path.lastIndex -> pts += listOf(cx[v].toFloat(), top(v))
                            else -> pts += listOf(cx[v].toFloat(), layerTop[layerOf[v]] + layerHeight[layerOf[v]] / 2)
                        }
                    }
                }
                var array = pts.toFloatArray()
                if (c.reversed) {
                    // Del origen real al destino real.
                    array = FloatArray(array.size).also { r ->
                        for (i in 0 until array.size / 2) {
                            r[2 * i] = array[array.size - 2 - 2 * i]
                            r[2 * i + 1] = array[array.size - 1 - 2 * i]
                        }
                    }
                }
                RoutedEdge(nodes[c.from].id, nodes[c.to].id, array, c.reversed, c.sameLayer)
            }
            // El dibujo abarca nodos y trazados (un arco entre nodos de la última capa baja algo más que ellos).
            var right = (maxX + o.margin).toFloat()
            var bottomEdge = (y - o.layerGap + o.margin).coerceAtLeast(o.margin * 2)
            for (e in routed) {
                for (i in e.points.indices step 2) {
                    right = maxOf(right, e.points[i] + o.margin)
                    bottomEdge = maxOf(bottomEdge, e.points[i + 1] + o.margin)
                }
            }
            return Layout(placed, routed, right, bottomEdge, bestCrossings)
        }

        private fun gap(v: Int): Float = if (v >= n) o.nodeGap / 2 else o.nodeGap

        private fun link(a: Int, b: Int) {
            down[a] += b
            up[b] += a
        }

        /** Capas dadas (comprimidas) o por el camino más largo tras romper los ciclos. */
        private fun assignLayers(): IntArray {
            if (nodes.all { it.layer != null }) {
                val distinct = nodes.map { it.layer!! }.distinct().sorted()
                val rank = distinct.withIndex().associate { (i, l) -> l to i }
                return IntArray(n) { rank[nodes[it].layer!!]!! }
            }
            val out = Array(n) { ArrayList<Int>() }
            pairs.forEach { (a, b) -> out[a] += b }
            // Aristas de retroceso de un recorrido en profundidad (iterativo, en el orden de entrada).
            val state = IntArray(n)
            val back = HashSet<Long>()
            for (s in 0 until n) {
                if (state[s] != 0) continue
                val stack = ArrayDeque<IntArray>()
                stack += intArrayOf(s, 0)
                state[s] = 1
                while (stack.isNotEmpty()) {
                    val top = stack.last()
                    val v = top[0]
                    if (top[1] < out[v].size) {
                        val w = out[v][top[1]++]
                        when (state[w]) {
                            0 -> {
                                state[w] = 1
                                stack += intArrayOf(w, 0)
                            }
                            1 -> back += v.toLong() shl 32 or w.toLong()
                        }
                    } else {
                        state[v] = 2
                        stack.removeLast()
                    }
                }
            }
            val succ = Array(n) { ArrayList<Int>() }
            val indeg = IntArray(n)
            for ((a, b) in pairs.map { it[0] to it[1] }) {
                val (x, y) = if ((a.toLong() shl 32 or b.toLong()) in back) b to a else a to b
                succ[x] += y
                indeg[y]++
            }
            // Orden topológico estable (Kahn con la cola en orden de índice).
            val queue = java.util.PriorityQueue<Int>()
            val remaining = indeg.copyOf()
            for (v in 0 until n) if (remaining[v] == 0) queue += v
            val topo = ArrayList<Int>(n)
            while (queue.isNotEmpty()) {
                val v = queue.poll()
                topo += v
                for (w in succ[v]) if (--remaining[w] == 0) queue += w
            }
            val layer = IntArray(n)
            for (v in topo) for (w in succ[v]) layer[w] = maxOf(layer[w], layer[v] + 1)
            // Las fuentes bajan hasta justo encima de su sucesor más alto (aristas más cortas).
            for (v in topo.asReversed()) {
                if (indeg[v] == 0 && succ[v].isNotEmpty()) layer[v] = succ[v].minOf { layer[it] } - 1
            }
            return layer
        }

        /** Cruces entre capas consecutivas (inversiones contadas con un árbol de Fenwick). */
        private fun crossings(order: Array<ArrayList<Int>>, pos: IntArray): Int {
            var total = 0L
            for (l in 0 until order.size - 1) {
                val edges = ArrayList<Long>()
                for (u in order[l]) for (w in down[u]) edges += pos[u].toLong() shl 32 or pos[w].toLong()
                if (edges.size < 2) continue
                edges.sort()
                val size = order[l + 1].size
                val tree = IntArray(size + 1)
                var seen = 0
                for (e in edges) {
                    val p = (e and 0xffffffffL).toInt()
                    // Aristas ya vistas que llegan más a la derecha: se cruzan con esta.
                    var i = p + 1
                    var le = 0
                    while (i > 0) {
                        le += tree[i]
                        i -= i and -i
                    }
                    total += seen - le
                    i = p + 1
                    while (i <= size) {
                        tree[i]++
                        i += i and -i
                    }
                    seen++
                }
            }
            return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /**
         * Coloca una capa lo más cerca posible de [desired] respetando el orden y la separación: media de la colocación
         * empujando a la derecha y la empujando a la izquierda, y una última pasada que quita los solapes.
         */
        private fun place(layer: List<Int>, desired: DoubleArray, cx: DoubleArray) {
            val k = layer.size
            if (k == 0) return
            fun sep(i: Int) = width[layer[i - 1]] / 2.0 + gap(layer[i - 1]) + width[layer[i]] / 2.0
            val right = DoubleArray(k)
            for (i in 0 until k) right[i] = if (i == 0) desired[0] else maxOf(desired[i], right[i - 1] + sep(i))
            val left = DoubleArray(k)
            for (i in k - 1 downTo 0) left[i] = if (i == k - 1) desired[k - 1] else minOf(desired[i], left[i + 1] - sep(i + 1))
            for (i in 0 until k) {
                var x = (left[i] + right[i]) / 2
                if (i > 0) x = maxOf(x, cx[layer[i - 1]] + sep(i))
                cx[layer[i]] = x
            }
        }

        private fun median(values: List<Double>): Double {
            val s = values.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }
    }
}

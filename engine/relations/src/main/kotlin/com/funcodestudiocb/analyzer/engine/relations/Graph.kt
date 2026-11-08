package com.funcodestudiocb.analyzer.engine.relations

import java.util.BitSet

/**
 * Grafo dirigido de dependencias entre archivos: una arista A → B significa "A usa B". Los algoritmos son
 * iterativos (sin recursión: los hilos de Android tienen pilas pequeñas) y acotados.
 */
class DependencyGraph(val nodes: List<String>, edges: Collection<Pair<String, String>>) {
    private val id: Map<String, Int> = nodes.withIndex().associate { (i, n) -> n to i }
    val out: Array<IntArray>
    val inc: Array<IntArray>

    init {
        val o = Array(nodes.size) { HashSet<Int>() }
        val i = Array(nodes.size) { HashSet<Int>() }
        for ((a, b) in edges) {
            val x = id[a] ?: continue
            val y = id[b] ?: continue
            if (x == y) continue
            o[x] += y
            i[y] += x
        }
        out = Array(nodes.size) { o[it].toIntArray().also { arr -> arr.sort() } }
        inc = Array(nodes.size) { i[it].toIntArray().also { arr -> arr.sort() } }
    }

    fun indexOf(node: String): Int? = id[node]

    /**
     * Componentes fuertemente conexos (Tarjan, iterativo). Devuelve el componente de cada nodo; los componentes se
     * numeran en orden topológico inverso (primero los que no dependen de nadie del grafo).
     */
    fun components(): IntArray {
        val n = nodes.size
        val index = IntArray(n) { -1 }
        val low = IntArray(n)
        val onStack = BooleanArray(n)
        val comp = IntArray(n) { -1 }
        val stack = IntArray(n)
        var sp = 0
        var counter = 0
        var components = 0
        val callNode = IntArray(n)
        val callEdge = IntArray(n)
        for (start in 0 until n) {
            if (index[start] != -1) continue
            var depth = 0
            callNode[0] = start
            callEdge[0] = 0
            index[start] = counter
            low[start] = counter
            counter++
            stack[sp++] = start
            onStack[start] = true
            while (depth >= 0) {
                val v = callNode[depth]
                val e = callEdge[depth]
                if (e < out[v].size) {
                    callEdge[depth] = e + 1
                    val w = out[v][e]
                    if (index[w] == -1) {
                        index[w] = counter
                        low[w] = counter
                        counter++
                        stack[sp++] = w
                        onStack[w] = true
                        depth++
                        callNode[depth] = w
                        callEdge[depth] = 0
                    } else if (onStack[w]) {
                        low[v] = minOf(low[v], index[w])
                    }
                } else {
                    if (low[v] == index[v]) {
                        while (true) {
                            val w = stack[--sp]
                            onStack[w] = false
                            comp[w] = components
                            if (w == v) break
                        }
                        components++
                    }
                    depth--
                    if (depth >= 0) {
                        val parent = callNode[depth]
                        low[parent] = minOf(low[parent], low[v])
                    }
                }
            }
        }
        return comp
    }

    /** PageRank (amortiguación 0,85): la importancia fluye hacia los archivos de los que dependen otros. */
    fun pageRank(iterations: Int = 30): DoubleArray {
        val n = nodes.size
        if (n == 0) return DoubleArray(0)
        var rank = DoubleArray(n) { 1.0 / n }
        repeat(iterations) {
            val next = DoubleArray(n) { 0.15 / n }
            var dangling = 0.0
            for (v in 0 until n) {
                if (out[v].isEmpty()) {
                    dangling += rank[v]
                } else {
                    val share = 0.85 * rank[v] / out[v].size
                    for (w in out[v]) next[w] += share
                }
            }
            val spread = 0.85 * dangling / n
            for (v in 0 until n) next[v] += spread
            rank = next
        }
        return rank
    }

    /**
     * Cuántos archivos dependen de cada uno, directa o indirectamente. Exacto con conjuntos de bits sobre los
     * componentes si el grafo es razonable; si no, un recorrido acotado a [cap] archivos (y se indica).
     */
    fun transitiveDependents(comp: IntArray, cap: Int = 2_000, maxExact: Int = 6_000): Pair<IntArray, BooleanArray> {
        val n = nodes.size
        val result = IntArray(n)
        val capped = BooleanArray(n)
        val count = (comp.maxOrNull() ?: -1) + 1
        if (count in 1..maxExact) {
            val size = IntArray(count)
            comp.forEach { size[it]++ }
            // Predecesores entre componentes.
            val preds = Array(count) { HashSet<Int>() }
            for (v in 0 until n) for (w in out[v]) if (comp[v] != comp[w]) preds[comp[w]] += comp[v]
            // Tarjan numera primero los sumideros: los que llegan a un componente tienen número mayor.
            val reach = arrayOfNulls<BitSet>(count)
            for (c in count - 1 downTo 0) {
                val bits = BitSet(count)
                for (p in preds[c]) {
                    bits.set(p)
                    reach[p]?.let { bits.or(it) }
                }
                reach[c] = bits
            }
            val compTotals = IntArray(count) { c ->
                var total = size[c] - 1
                val bits = reach[c]!!
                var p = bits.nextSetBit(0)
                while (p >= 0) {
                    total += size[p]
                    p = bits.nextSetBit(p + 1)
                }
                total
            }
            for (v in 0 until n) result[v] = compTotals[comp[v]]
            return result to capped
        }
        val seen = IntArray(n) { -1 }
        val queue = IntArray(n)
        for (v in 0 until n) {
            var head = 0
            var tail = 0
            queue[tail++] = v
            seen[v] = v
            var found = 0
            while (head < tail && found < cap) {
                val x = queue[head++]
                for (p in inc[x]) {
                    if (seen[p] == v) continue
                    seen[p] = v
                    queue[tail++] = p
                    found++
                }
            }
            result[v] = minOf(found, cap)
            capped[v] = found >= cap
        }
        return result to capped
    }

    /** Para cada nodo, los puntos de entrada desde los que se llega a él. */
    fun reachedFrom(entries: List<String>, maxEntries: Int = 100): Array<MutableList<Int>> {
        val n = nodes.size
        val reached = Array(n) { mutableListOf<Int>() }
        val seen = IntArray(n) { -1 }
        val queue = IntArray(n)
        entries.take(maxEntries).forEachIndexed { e, entry ->
            val start = id[entry] ?: return@forEachIndexed
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = e
            while (head < tail) {
                val x = queue[head++]
                reached[x] += e
                for (w in out[x]) {
                    if (seen[w] == e) continue
                    seen[w] = e
                    queue[tail++] = w
                }
            }
        }
        return reached
    }
}

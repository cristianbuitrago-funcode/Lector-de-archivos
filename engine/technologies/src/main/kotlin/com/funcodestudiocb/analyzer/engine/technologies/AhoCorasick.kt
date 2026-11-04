package com.funcodestudiocb.analyzer.engine.technologies

/**
 * Búsqueda de muchos textos literales a la vez en una sola pasada (autómata de Aho–Corasick): el tiempo depende del
 * tamaño del texto y del número de coincidencias, no del número de textos buscados. Con [ignoreCase] compara cada
 * carácter en minúsculas (sin cambiar posiciones).
 */
internal class AhoCorasick(patterns: List<String>, private val ignoreCase: Boolean = false) {

    private val edges = ArrayList<HashMap<Char, Int>>()
    private val fail = ArrayList<Int>()

    /** Textos que terminan en cada estado (incluidos los que terminan en sus sufijos). */
    private val output = ArrayList<IntArray>()
    private val lengths = IntArray(patterns.size) { patterns[it].length }

    init {
        edges += HashMap()
        fail += 0
        val ends = ArrayList<MutableList<Int>>().apply { add(mutableListOf()) }
        patterns.forEachIndexed { index, pattern ->
            require(pattern.isNotEmpty()) { "Texto vacío" }
            var state = 0
            for (raw in pattern) {
                val ch = fold(raw)
                state = edges[state].getOrPut(ch) {
                    edges += HashMap()
                    fail += 0
                    ends.add(mutableListOf())
                    edges.size - 1
                }
            }
            ends[state] += index
        }
        // Enlaces de fallo por anchura; cada estado hereda las salidas de su enlace.
        val queue = ArrayDeque<Int>()
        repeat(edges.size) { output += IntArray(0) }
        output[0] = ends[0].toIntArray()
        for ((_, child) in edges[0]) {
            fail[child] = 0
            queue += child
        }
        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            output[state] = (ends[state] + output[fail[state]].toList()).toIntArray()
            for ((ch, child) in edges[state]) {
                var f = fail[state]
                while (f != 0 && ch !in edges[f]) f = fail[f]
                fail[child] = edges[f][ch]?.takeIf { it != child } ?: 0
                queue += child
            }
        }
    }

    private fun fold(ch: Char): Char = if (ignoreCase) ch.lowercaseChar() else ch

    /** Llama a [onMatch] con (índice del texto, posición de inicio) por cada coincidencia; si devuelve `false`, se detiene. */
    fun scan(text: CharSequence, onMatch: (Int, Int) -> Boolean) {
        var state = 0
        for (i in text.indices) {
            val ch = fold(text[i])
            while (state != 0 && ch !in edges[state]) state = fail[state]
            state = edges[state][ch] ?: 0
            for (pattern in output[state]) {
                if (!onMatch(pattern, i - lengths[pattern] + 1)) return
            }
        }
    }
}

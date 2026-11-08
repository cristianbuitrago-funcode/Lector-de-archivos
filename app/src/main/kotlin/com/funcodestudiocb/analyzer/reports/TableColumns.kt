package com.funcodestudiocb.analyzer.reports

import kotlin.math.ceil

/**
 * Anchos de las columnas de una tabla del PDF, como hace un navegador con una tabla automática: cada columna recibe al
 * menos el ancho de su palabra más larga (para no partir «Probable» en dos líneas) y el espacio que sobra se reparte
 * según lo que cada una necesitaría para no partir sus celdas. Una palabra muy larga (una ruta) no puede quedarse más
 * de [MAX_MIN_SHARE] del ancho: esa sí se parte. Si ni los mínimos caben, se reducen en proporción.
 */
internal object TableColumns {

    const val PADDING = 8f
    const val MIN_WIDTH = 30f
    const val MAX_MIN_SHARE = 0.4f
    private const val MEASURED_CHARS = 120
    private val SPACES = Regex("\\s+")

    fun widths(
        headers: List<String>,
        rows: List<List<String>>,
        available: Float,
        header: (String) -> Float,
        cell: (String) -> Float,
    ): List<Float> {
        val cols = headers.size
        val min = FloatArray(cols)
        val want = FloatArray(cols)
        for (i in 0 until cols) {
            var word = 0f
            var full = 0f
            fun look(text: String, measure: (String) -> Float) {
                val t = text.take(MEASURED_CHARS)
                full = maxOf(full, measure(t))
                t.split(SPACES).forEach { if (it.isNotEmpty()) word = maxOf(word, measure(it)) }
            }
            look(headers[i], header)
            rows.forEach { look(it.getOrElse(i) { "" }, cell) }
            // +1: el texto se compone con un ancho entero y no debe quedarse a una fracción de punto de caber.
            min[i] = (ceil(word) + PADDING + 1f).coerceIn(MIN_WIDTH, maxOf(MIN_WIDTH, available * MAX_MIN_SHARE))
            want[i] = maxOf(ceil(full) + PADDING + 1f, min[i])
        }
        val sumMin = min.sum()
        if (sumMin >= available) return min.map { it * available / sumMin }
        val sumWant = want.sum()
        if (sumWant <= available) return want.map { it * available / sumWant }
        val spare = available - sumMin
        val flex = sumWant - sumMin
        return (0 until cols).map { min[it] + spare * (want[it] - min[it]) / flex }
    }
}

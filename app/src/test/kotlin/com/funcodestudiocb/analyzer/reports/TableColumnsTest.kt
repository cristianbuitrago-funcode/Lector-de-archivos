package com.funcodestudiocb.analyzer.reports

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableColumnsTest {

    private val measure: (String) -> Float = { it.length * 5f } // 5 puntos por carácter

    private fun widths(headers: List<String>, rows: List<List<String>>, available: Float) =
        TableColumns.widths(headers, rows, available, measure, measure)

    private fun assertFits(word: String, width: Float) =
        assertTrue(width - TableColumns.PADDING >= measure(word), "«$word» no cabe en $width")

    @Test
    fun `ninguna palabra corta se parte aunque otra columna tenga textos largos`() {
        val headers = listOf("Severidad", "Hallazgo", "Ubicación", "Certeza", "Regla")
        val rows = listOf(
            listOf("Alta", "Respuesta con datos del usuario sin escapar en la plantilla del servidor", "web/server.js:3", "Probable", "FC-XSS-02"),
            listOf("Baja", "Datos de la petición sin validación", "web/server.js:3", "Posible", "FC-VALID-01"),
        )
        val w = widths(headers, rows, 499f)
        assertEquals(499f, w.sum(), 0.01f)
        listOf("Severidad", "web/server.js:3", "Probable", "FC-VALID-01").forEachIndexed { i, word ->
            assertFits(word, w[if (i == 0) 0 else i + 1])
        }
        assertTrue(w[1] > w[3], "El hallazgo, que necesita más, recibe el espacio sobrante")
    }

    @Test
    fun `si todo cabe cada columna recibe lo que necesita y el resto se reparte`() {
        val w = widths(listOf("A", "B"), listOf(listOf("uno dos", "tres")), 200f)
        assertEquals(200f, w.sum(), 0.01f)
        assertTrue(w[0] > w[1])
    }

    @Test
    fun `una ruta muy larga no se queda con toda la tabla y si los minimos no caben se reducen`() {
        val path = "app/src/main/kotlin/com/funcodestudiocb/analyzer/reports/PdfReportRenderer.kt"
        val w = widths(listOf("Archivo", "Nota"), listOf(listOf(path, "uno dos tres cuatro cinco seis siete ocho nueve diez")), 300f)
        assertEquals(300f, w.sum(), 0.01f)
        assertFits("cuatro", w[1])
        assertTrue(w[0] < 300f * 0.7f, "La ruta se parte; la nota conserva sus palabras: $w")
        val tight = widths(listOf("Columna", "Columna", "Columna"), emptyList(), 60f)
        assertEquals(60f, tight.sum(), 0.01f)
        assertEquals(tight[0], tight[2], 0.01f)
    }
}

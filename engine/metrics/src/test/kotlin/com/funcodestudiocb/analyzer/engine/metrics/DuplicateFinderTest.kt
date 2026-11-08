package com.funcodestudiocb.analyzer.engine.metrics

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateFinderTest {

    private val block = """
        const total = pedido.lineas.reduce((s, l) => s + l.precio * l.cantidad, 0);
        const impuestos = total * configuracion.iva;
        const envio = total > 50 ? 0 : tarifas.envioBasico;
        registro.info('calculado', { total, impuestos, envio });
        const resultado = { total, impuestos, envio, moneda: 'EUR' };
        cache.guardar(pedido.id, resultado);
        notificaciones.enviar(pedido.cliente, resultado);
        return resultado;
    """.trimIndent()

    @Test
    fun `un bloque copiado en otro archivo marca las dos copias`() {
        val finder = DuplicateFinder()
        finder.add("src/a.js", "function a(pedido) {\n$block\n}\n") { false }
        finder.add("src/b.js", "// otra cosa\nconst x = require('x');\n\nfunction b(pedido) {\n$block\n}\n") { false }
        val counts = finder.counts()
        assertEquals(8, counts.getValue("src/a.js").second, "Las 8 líneas del bloque")
        assertEquals(8, counts.getValue("src/b.js").second)
        assertEquals(9, counts.getValue("src/a.js").first, "function a(...) { y el bloque; la llave no cuenta")
        val (lines, places) = finder.topBlocks(5).single()
        assertEquals(8, lines)
        assertEquals(listOf("src/a.js:2", "src/b.js:5"), places)
    }

    @Test
    fun `comentarios, lineas triviales e importaciones no cuentan`() {
        val finder = DuplicateFinder()
        val commented = block.lines().joinToString("\n") { "// $it" }
        finder.add("a.js", commented) { true }
        finder.add("b.js", commented) { true }
        val imports = (1..10).joinToString("\n") { "import { modulo$it } from './modulo$it';" }
        finder.add("c.js", imports) { false }
        finder.add("d.js", imports) { false }
        val braces = (1..10).joinToString("\n") { "}" }
        finder.add("e.js", braces) { false }
        finder.add("f.js", braces) { false }
        assertTrue(finder.counts().values.all { it.second == 0 }, finder.counts().toString())
        assertTrue(finder.topBlocks(5).isEmpty())
    }

    @Test
    fun `dentro del mismo archivo cuenta si no se solapa`() {
        val finder = DuplicateFinder()
        finder.add("a.js", "$block\nconsole.log('separador');\n$block\n") { false }
        assertEquals(16, finder.counts().getValue("a.js").second)
        val single = DuplicateFinder()
        single.add("b.js", block) { false }
        assertEquals(0, single.counts().getValue("b.js").second)
    }
}

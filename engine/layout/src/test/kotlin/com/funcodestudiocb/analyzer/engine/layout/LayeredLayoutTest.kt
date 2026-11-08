package com.funcodestudiocb.analyzer.engine.layout

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayeredLayoutTest {

    private fun node(id: String, layer: Int? = null, w: Float = 80f, h: Float = 30f) = LayoutNode(id, w, h, layer)
    private fun edge(a: String, b: String) = LayoutEdge(a, b)
    private fun Layout.node(id: String) = nodes.single { it.id == id }
    private fun Layout.edge(a: String, b: String) = edges.single { it.from == a && it.to == b }

    /** Ningún nodo se sale del dibujo ni se solapa con otro de su capa. */
    private fun assertWellFormed(l: Layout, gap: Float = LayoutOptions().nodeGap) {
        for (n in l.nodes) {
            assertTrue(n.x >= 0 && n.y >= 0 && n.x + n.width <= l.width + 0.01f && n.y + n.height <= l.height + 0.01f, "Fuera del dibujo: ${n.id}")
            assertTrue(n.x.isFinite() && n.y.isFinite())
        }
        l.nodes.groupBy { it.layer }.values.forEach { layer ->
            layer.sortedBy { it.order }.zipWithNext { a, b -> assertTrue(a.x + a.width + gap - 0.01f <= b.x, "Solape en la capa ${a.layer}: ${a.id} y ${b.id}") }
        }
        for (e in l.edges) {
            assertTrue(e.points.size >= 4 && e.points.size % 2 == 0)
            assertTrue(e.points.all { it.isFinite() && it >= -0.01f }, "Punto fuera: ${e.from}→${e.to}")
        }
    }

    @Test
    fun `una cadena queda en capas sucesivas, alineada y con las aristas pegadas a sus nodos`() {
        val l = LayeredLayout.layout(listOf(node("a"), node("b"), node("c")), listOf(edge("a", "b"), edge("b", "c")))
        assertWellFormed(l)
        assertEquals(listOf(0, 1, 2), listOf("a", "b", "c").map { l.node(it).layer })
        assertTrue(l.node("a").y < l.node("b").y && l.node("b").y < l.node("c").y)
        assertEquals(l.node("a").centerX, l.node("c").centerX, 0.5f)
        val ab = l.edge("a", "b")
        assertEquals(l.node("a").centerX, ab.points[0], 0.5f)
        assertEquals(l.node("a").y + l.node("a").height, ab.points[1], 0.5f)
        assertEquals(l.node("b").y, ab.points[ab.points.size - 1], 0.5f)
        assertEquals(0, l.crossings)
    }

    @Test
    fun `la misma entrada da siempre el mismo dibujo`() {
        val nodes = (0 until 30).map { node("n$it") }
        val edges = (0 until 30).flatMap { i -> listOf(edge("n$i", "n${(i * 7 + 3) % 30}"), edge("n$i", "n${(i * 11 + 5) % 30}")) }
        val a = LayeredLayout.layout(nodes, edges)
        val b = LayeredLayout.layout(nodes, edges)
        assertEquals(a.nodes.map { Triple(it.id, it.x, it.y) }, b.nodes.map { Triple(it.id, it.x, it.y) })
        a.edges.zip(b.edges).forEach { (x, y) -> assertContentEquals(x.points, y.points) }
        assertWellFormed(a)
    }

    @Test
    fun `los cruces se eliminan cuando se puede`() {
        // Con el orden de entrada, a→d y b→c se cruzan; reordenando la segunda capa, no.
        val l = LayeredLayout.layout(
            listOf(node("a", 0), node("b", 0), node("c", 1), node("d", 1)),
            listOf(edge("a", "d"), edge("b", "c")),
        )
        assertEquals(0, l.crossings)
        assertTrue(l.node("d").x < l.node("c").x)
        assertWellFormed(l)
    }

    @Test
    fun `un ciclo se dibuja invirtiendo una arista y sin repetir capas`() {
        val l = LayeredLayout.layout(listOf(node("a"), node("b"), node("c")), listOf(edge("a", "b"), edge("b", "c"), edge("c", "a")))
        assertWellFormed(l)
        assertEquals(1, l.edges.count { it.reversed })
        assertEquals(3, l.nodes.map { it.layer }.distinct().size)
        // La arista invertida sigue yendo de su origen a su destino.
        val ca = l.edge("c", "a")
        assertTrue(ca.reversed)
        assertEquals(l.node("c").centerX, ca.points[0], 0.5f)
        assertTrue(ca.points[1] <= l.node("c").y + 0.5f, "Sale del borde superior de c (que está debajo)")
    }

    @Test
    fun `capas fijas con aristas hacia atras y dentro de la misma capa`() {
        val l = LayeredLayout.layout(
            listOf(node("ui", 0), node("api", 1), node("api2", 1), node("db", 5)),
            listOf(edge("ui", "api"), edge("db", "ui"), edge("api", "api2"), edge("api", "db")),
        )
        assertWellFormed(l)
        assertEquals(listOf(0, 1, 1, 2), listOf("ui", "api", "api2", "db").map { l.node(it).layer }, "Las capas vacías se comprimen")
        assertTrue(l.edge("db", "ui").reversed)
        assertFalse(l.edge("ui", "api").reversed)
        val same = l.edge("api", "api2")
        assertTrue(same.sameLayer)
        assertEquals(6, same.points.size)
    }

    @Test
    fun `una arista larga pasa por un punto en cada capa intermedia`() {
        val l = LayeredLayout.layout(
            listOf(node("a"), node("b"), node("c"), node("d")),
            listOf(edge("a", "b"), edge("b", "c"), edge("c", "d"), edge("a", "d")),
        )
        assertWellFormed(l)
        assertEquals(8, l.edge("a", "d").points.size, "Origen, dos capas intermedias y destino")
        // La arista larga no atraviesa los nodos intermedios.
        val ad = l.edge("a", "d")
        for (i in 1..2) {
            val mid = l.nodes.single { it.layer == i }
            val x = ad.points[2 * i]
            assertTrue(x < mid.x || x > mid.x + mid.width, "La arista larga atraviesa ${mid.id}")
        }
    }

    @Test
    fun `de izquierda a derecha es la misma disposicion traspuesta`() {
        val l = LayeredLayout.layout(listOf(node("a"), node("b")), listOf(edge("a", "b")), LayoutOptions(direction = LayoutDirection.LEFT_RIGHT))
        assertTrue(l.node("a").x + l.node("a").width < l.node("b").x)
        assertEquals(l.node("a").centerY, l.node("b").centerY, 0.5f)
        assertEquals(80f, l.node("a").width)
        val ab = l.edge("a", "b")
        assertEquals(l.node("a").x + l.node("a").width, ab.points[0], 0.5f)
        assertTrue(abs(l.node("b").x - ab.points[ab.points.size - 2]) < 0.5f)
    }

    @Test
    fun `grafo mediano sin solapes, aristas desconocidas y tamanos limitados`() {
        var seed = 42L
        fun next(bound: Int): Int {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return ((seed ushr 33) % bound).toInt()
        }
        val nodes = (0 until 120).map { node("m$it", w = 40f + next(80)) }
        val edges = (0 until 300).map { edge("m${next(120)}", "m${next(120)}") } + edge("m1", "no-existe") + edge("m2", "m2")
        val l = LayeredLayout.layout(nodes, edges)
        assertWellFormed(l)
        assertEquals(120, l.nodes.size)
        assertTrue(l.edges.none { it.to == "no-existe" || it.from == it.to })
        assertTrue(LayeredLayout.layout(emptyList(), emptyList()).nodes.isEmpty())
        assertFailsWith<IllegalArgumentException> { LayeredLayout.layout((0..LayeredLayout.MAX_NODES).map { node("x$it") }, emptyList()) }
    }
}

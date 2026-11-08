package com.funcodestudiocb.analyzer.engine.languages

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Complejidad ciclomática por función medida en el árbol sintáctico: 1 + condiciones, bucles, casos (sin el caso por
 * defecto), capturas, ternarios y operadores lógicos. Los valores esperados están calculados a mano en cada ejemplo.
 */
class ComplexityTest {

    private fun measure(language: Language, text: String): LanguageAnalysis {
        val a = LanguageAnalyzer.analyze(language, text.trimIndent() + "\n", highlight = false)
        assertTrue(a.complexityMeasured, "Se mide en el árbol sintáctico (${language.id})")
        return a
    }

    private fun LanguageAnalysis.cc(): Map<String, Int> = functions.associate { it.name to it.complexity }

    @Test
    fun `JavaScript - condiciones, bucles, casos, capturas, ternarios y operadores logicos`() {
        val a = measure(
            Languages.JAVASCRIPT,
            """
            function validar(usuario) {
              if (!usuario) return false;
              for (const r of usuario.roles) {
                if (r === 'admin' && usuario.activo) return true;
              }
              return usuario.edad > 18 ? true : false;
            }
            const Usuarios = () => {
              const lista = datos ?? [];
              return lista.map(u => u.nombre);
            };
            class Servicio {
              guardar(x) {
                try { db.save(x); } catch (e) { log(e); }
                switch (x.tipo) { case 'a': break; case 'b': break; default: break; }
              }
            }
            const texto = "if (a && b) { for (;;) {} }"; // if && en una cadena: no cuenta
            """,
        )
        assertEquals(mapOf("validar" to 6, "Usuarios" to 2, "(anónima)" to 1, "guardar" to 4), a.cc())
        val validar = a.functions.first { it.name == "validar" }
        assertEquals(1, validar.line)
        assertEquals(7, validar.endLine)
        assertEquals(7, validar.lines)
        assertEquals(0, a.topLevelDecisions)
    }

    @Test
    fun `TypeScript - el parametro opcional no es una decision`() {
        val a = measure(
            Languages.TYPESCRIPT,
            """
            export function suma(xs: number[]): number {
              let t = 0;
              for (let i = 0; i < xs.length; i++) { t += xs[i]; }
              return t;
            }
            function opcional(x?: number): number { return x ?? 0; }
            """,
        )
        assertEquals(mapOf("suma" to 2, "opcional" to 2), a.cc())
    }

    @Test
    fun `Python - elif, except, comprensiones, and, or y codigo de nivel superior`() {
        val a = measure(
            Languages.PYTHON,
            """
            def procesar(items, limite=10):
                total = 0
                for i in items:
                    if i > limite and i % 2 == 0:
                        total += i
                    elif i < 0 or i is None:
                        continue
                try:
                    pass
                except ValueError:
                    pass
                pares = [x for x in items if x % 2]
                return total if total else None

            ordenar = lambda xs: sorted(xs)

            if __name__ == "__main__":
                procesar([])
            """,
        )
        assertEquals(mapOf("procesar" to 10, "ordenar" to 1), a.cc())
        assertEquals(1, a.topLevelDecisions)
    }

    @Test
    fun `Java - casos sin el default, for mejorado y lambdas aparte`() {
        val a = measure(
            Languages.JAVA,
            """
            class Pedido {
                Pedido(int x) { if (x < 0) throw new IllegalArgumentException(); }
                int precio(int n, boolean vip) {
                    switch (n) {
                        case 1: return 10;
                        case 2: return 20;
                        default: return vip ? 5 : 0;
                    }
                }
                void recorrer(java.util.List<String> xs) {
                    for (String s : xs) {
                        while (s.isEmpty() || s.length() > 3) { break; }
                    }
                    xs.forEach(s -> { if (s != null) System.out.println(s); });
                }
            }
            """,
        )
        assertEquals(mapOf("Pedido" to 2, "precio" to 4, "recorrer" to 4, "(anónima)" to 2), a.cc())
    }

    @Test
    fun `Kotlin - when sin else, catch y lambdas`() {
        val a = measure(
            Languages.KOTLIN,
            """
            fun clasificar(n: Int): String {
                return when {
                    n < 0 -> "negativo"
                    n == 0 -> "cero"
                    else -> "positivo"
                }
            }
            fun revisar(lista: List<Int>) {
                for (x in lista) {
                    if (x > 1 && x < 10) println(x)
                }
                try { lista.first() } catch (e: Exception) { }
                lista.forEach { if (it > 0) println(it) }
            }
            """,
        )
        assertEquals(mapOf("clasificar" to 3, "revisar" to 5, "(anónima)" to 2), a.cc())
    }

    @Test
    fun `C# - secciones de switch sin default, foreach y operadores`() {
        val a = measure(
            Languages.CSHARP,
            """
            class Calc {
                public int Max(int a, int b) { return a > b ? a : b; }
                public string Tipo(object o) {
                    switch (o) {
                        case int i: return "int";
                        case string s when s.Length > 0: return "str";
                        default: return "otro";
                    }
                }
                public int Suma(int[] xs) {
                    int t = 0;
                    foreach (var x in xs) { if (x > 0 && x < 100) t += x; }
                    return t;
                }
            }
            """,
        )
        assertEquals(mapOf("Max" to 2, "Tipo" to 3, "Suma" to 4), a.cc())
    }

    @Test
    fun `PHP - elseif, foreach y fusion nula`() {
        val a = measure(
            Languages.PHP,
            """
            <?php
            function nota(${'$'}n) {
                if (${'$'}n >= 9) { return 'A'; }
                elseif (${'$'}n >= 7) { return 'B'; }
                foreach ([1, 2] as ${'$'}x) { }
                return ${'$'}n ?? 0;
            }
            """,
        )
        assertEquals(mapOf("nota" to 5), a.cc())
    }

    @Test
    fun `C y C++ - casos sin default, for de rango, capturas y lambdas con nombre`() {
        val c = measure(
            Languages.C,
            """
            int clasifica(int x) {
                if (x < 0 || x > 100) return -1;
                switch (x) {
                    case 1: return 1;
                    default: return 0;
                }
            }
            """,
        )
        assertEquals(mapOf("clasifica" to 4), c.cc())
        val cpp = measure(
            Languages.CPP,
            """
            int contar(std::vector<int> v) {
                int n = 0;
                for (int x : v) { if (x > 0) n++; }
                auto f = [](int a) { return a > 0 ? a : 0; };
                try { n++; } catch (...) { }
                return n;
            }
            """,
        )
        assertEquals(mapOf("contar" to 4, "f" to 2), cpp.cc())
    }

    @Test
    fun `Dart y GDScript - el nombre sale de la firma y elif cuenta`() {
        val dart = measure(
            Languages.DART,
            """
            int puntos(int n) {
              if (n > 10 && n < 20) {
                return 1;
              }
              return n > 0 ? n : 0;
            }
            class A {
              void saludar(String? nombre) {
                print(nombre ?? 'mundo');
              }
            }
            """,
        )
        assertEquals(mapOf("puntos" to 4, "saludar" to 2), dart.cc())
        val gd = measure(
            Languages.GDSCRIPT,
            """
            func mover(delta):
            	if delta > 0 and visible:
            		position.x += 1
            	elif delta < 0:
            		position.x -= 1
            	for i in range(3):
            		pass
            """,
        )
        assertEquals(mapOf("mover" to 5), gd.cc())
    }

    @Test
    fun `sin gramatica o con analisis lexico no se mide`() {
        val go = LanguageAnalyzer.analyze(Languages.GO, "package main\nfunc main() { if true {} }\n", highlight = false)
        assertFalse(go.complexityMeasured)
        assertTrue(go.functions.isEmpty())
        val lexical = LanguageAnalyzer.analyze(Languages.JAVASCRIPT, "function f() { if (a) {} }\n", highlight = false, syntax = false)
        assertFalse(lexical.complexityMeasured)
    }

    @Test
    fun `los importes y las declaraciones no cambian al pedir los nodos de complejidad`() {
        val a = measure(
            Languages.KOTLIN,
            """
            package app.datos
            import kotlinx.coroutines.flow.Flow
            class Repositorio { fun todos(): Flow<Int> = TODO() }
            """,
        )
        assertEquals(listOf("kotlinx.coroutines.flow.Flow"), a.imports.map { it.module })
        assertEquals(listOf("Repositorio"), a.declarations.map { it.name })
        assertEquals(listOf("app.datos"), a.namespaces)
    }
}

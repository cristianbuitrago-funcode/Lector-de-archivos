package com.funcodestudiocb.analyzer.engine.inventory

import com.funcodestudiocb.analyzer.engine.security.SecretRedactor
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import kotlin.test.assertNull

/** Código que se ejecuta dentro del cargador de clases aislado de [SmallStackTest]. */
object SmallStackProbe {
    /** Carga RE2/J y la biblioteca de Kotlin sin tocar los patrones del motor. */
    @JvmStatic
    fun warmUp() {
        com.google.re2j.Pattern.compile("(?i)a{2,5}[b-z]+\\b(x|y)*?").matcher("abc").find()
        listOf(1, 2).map { it + 1 }.toSet().sorted()
        buildMap { put("a", listOf(1)) }.mapValues { it.value.size }
        "Ab".lowercase().split('b').joinToString()
    }

    /** Inicializa y usa todos los patrones del motor. */
    @JvmStatic
    fun run() {
        SecretRedactor.redact("val apiKey = \"abcd1234efgh5678ijkl\"\nDB_PASSWORD=x1y2z3w4\n<string name=\"api_key\">s3cr3tValue</string>\n", "a.env")
        SecretRedactor.redact("<add key=\"ApiKey\" value=\"abcdefgh12345\" />\npostgres://u:secreto123@h", "web.config")
        Manifests.jvmPackage("package com.demo\n")
        Manifests.gradleNamespace("namespace = \"com.demo\"")
        Manifests.htmlScripts("<script src=\"/src/main.ts\"></script>", intArrayOf(0))
        Manifests.godotProject("[application]\nrun/main_scene=\"res://a.tscn\"\n[autoload]\nA=\"*res://a.gd\"\n", intArrayOf(0))
        Manifests.unityFirstScene("  - enabled: 1\n    path: Assets/A.unity\n", intArrayOf(0, 15))
        Manifests.unrealDefaultMap("GameDefaultMap=/Game/A.A\n", intArrayOf(0))
        for (ext in listOf("kt", "js", "py", "php", "cs", "go", "rb", "dart", "swift", "c", "rs")) {
            ContentSignals.scan(PathInfo("a/main.$ext"), "fun main() {}\n", intArrayOf(0))
        }
    }
}

/**
 * En Android los hilos tienen pilas de ~1 MB y el intérprete usa marcos más grandes que la JVM del PC. RE2/J
 * compila de forma recursiva y convierte una repetición acotada grande (`{1,300}`) en un anidamiento profundo:
 * en Android 15/16 eso desbordaba la pila al inicializar los patrones. Esta prueba los inicializa y usa en un
 * hilo con 256 KB, donde los patrones antiguos fallaban.
 *
 * Se ejecuta en un cargador de clases propio para que un fallo de inicialización no afecte a las demás pruebas.
 */
class SmallStackTest {

    @Test
    fun `los patrones de RE2J se compilan y usan con una pila pequena`() {
        val urls = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotEmpty() }.map { File(it).toURI().toURL() }
        URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val probe = loader.loadClass(SmallStackProbe::class.java.name)
            probe.getMethod("warmUp").invoke(null)
            var failure: Throwable? = null
            val thread = Thread(null, {
                try {
                    probe.getMethod("run").invoke(null)
                } catch (e: InvocationTargetException) {
                    failure = e.targetException
                } catch (e: Throwable) {
                    failure = e
                }
            }, "pila-pequena", SMALL_STACK_BYTES)
            thread.start()
            thread.join()
            assertNull(failure, "Falló con una pila pequeña: $failure")
        }
    }

    private companion object {
        const val SMALL_STACK_BYTES = 256L * 1024
    }
}

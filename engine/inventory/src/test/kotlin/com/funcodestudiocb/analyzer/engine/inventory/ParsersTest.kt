package com.funcodestudiocb.analyzer.engine.inventory

import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeJson
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeXmlReader
import com.funcodestudiocb.analyzer.engine.inventory.parse.XmlStart
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class ParsersTest {

    private fun starts(xml: String): Pair<List<XmlStart>, Boolean> {
        val out = mutableListOf<XmlStart>()
        val ok = SafeXmlReader(xml).parse(onStart = { out += it })
        return out to ok
    }

    @Test
    fun `XML - las entidades de un DTD nunca se expanden (sin XXE ni billion laughs)`() {
        val laughs = buildString {
            append("<?xml version=\"1.0\"?>\n<!DOCTYPE lolz [\n<!ENTITY lol \"lol\">\n")
            for (i in 1..9) append("<!ENTITY lol$i \"${"&lol${if (i == 1) "" else i - 1};".repeat(10)}\">\n")
            append("<!ENTITY xxe SYSTEM \"file:///etc/passwd\">\n]>\n<root a=\"&lol9;\" b=\"&xxe;\" c=\"&lt;ok&gt; &#65;&#x42;\"/>")
        }
        lateinit var root: XmlStart
        val time = measureTime { root = starts(laughs).first.single() }
        assertTrue(time.inWholeMilliseconds < 2_000)
        assertEquals("&lol9;", root.attribute("a"), "La entidad queda tal cual")
        assertEquals("&xxe;", root.attribute("b"), "Nunca se lee un recurso externo")
        assertEquals("<ok> AB", root.attribute("c"), "Solo las predefinidas y numéricas")
    }

    @Test
    fun `XML - espacios de nombres, comentarios, CDATA y lineas`() {
        val xml = "<?xml version=\"1.0\"?>\n<!-- <falso a=\"1\"/> -->\n<m xmlns:x=\"http://schemas.android.com/apk/res/android\">\n  <![CDATA[ <tampoco/> ]]>\n  <a x:name=\".Main\" name=\"sin\"/>\n</m>\n"
        val (events, ok) = starts(xml)
        assertTrue(ok)
        assertEquals(listOf("m", "a"), events.map { it.name })
        val a = events[1]
        assertEquals(5, a.line)
        assertEquals(".Main", a.attribute("name", Manifests.ANDROID_NS, "android"))
        assertEquals("sin", a.attribute("name"))
    }

    @Test
    fun `XML mal formado o demasiado profundo se detiene conservando lo leido`() {
        val (events, ok) = starts("<a><b c=\"1\"><d></a>")
        assertFalse(ok)
        assertEquals(listOf("a", "b", "d"), events.map { it.name })
        val deep = "<x>".repeat(10_000)
        val (deepEvents, deepOk) = starts(deep)
        assertFalse(deepOk)
        assertTrue(deepEvents.size <= 256)
        assertFalse(starts("<a b=sin-comillas/>").second)
        assertFalse(starts("<a><!-- sin cerrar").second)
    }

    @Test
    fun `JSON - profundidad limitada, comentarios y comas finales`() {
        assertNull(SafeJson.parse("[".repeat(100_000)), "Anidamiento excesivo: se rechaza sin construir nada")
        assertNull(SafeJson.parse("{\"a\": "))
        val ok = SafeJson.parse("{\n // comentario\n \"a\": [1, 2,],\n \"s\": \"[[[[\",\n}")
        assertNotNull(ok)
        assertTrue(SafeJson.depthWithin("{\"a\":\"{{{{{{{{\"}", 1), "Las llaves dentro de cadenas no cuentan")
    }

    @Test
    fun `manifiestos - scripts de npm y manifiesto de Android con alias`() {
        assertEquals("server/index.js", Manifests.scriptTarget("cross-env NODE_ENV=production node --inspect server/index.js"))
        assertEquals("src/app.ts", Manifests.scriptTarget("nodemon src/app.ts && echo ok"))
        assertNull(Manifests.scriptTarget("vite --port 3000"))
        assertNull(Manifests.scriptTarget("electron ."))
        val manifest = Manifests.androidManifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.x">
              <application>
                <activity android:name=".Real" />
                <activity-alias android:name=".Alias" android:targetActivity=".Real">
                  <intent-filter>
                    <action android:name="android.intent.action.MAIN"/>
                    <category android:name="android.intent.category.LAUNCHER"/>
                  </intent-filter>
                </activity-alias>
                <activity android:name="evil name with spaces" />
              </application>
            </manifest>
            """.trimIndent(),
        )
        assertEquals("com.x", manifest.packageName)
        val alias = manifest.components.single { it.tag == "activity-alias" }
        assertTrue(alias.launcher)
        assertEquals(".Real", alias.targetActivity)
        assertEquals(2, manifest.components.size, "Un nombre con caracteres no válidos se ignora")
    }

    @Test
    fun `reglas de nombre - palabras completas, sin falsos amigos`() {
        fun roles(path: String) = PathRules.hints(PathInfo(path), FileKind.TEXT).map { it.role }.toSet()
        assertTrue(FileRole.UI in roles("src/MainActivity.kt"))
        assertFalse(FileRole.UI in roles("src/Review.kt"), "Review no es …View")
        assertTrue(FileRole.TEST in roles("src/UserServiceTest.kt"))
        assertFalse(FileRole.TEST in roles("src/latest.py"), "latest no es …test")
        assertTrue(FileRole.SERVER in roles("src/UserController.java"))
        assertTrue(FileRole.DATABASE in roles("src/UserRepository.kt"))
        assertTrue(FileRole.AUTHENTICATION in roles("src/SignInViewModel.kt"))
        assertFalse(FileRole.AUTHENTICATION in roles("src/Author.kt"))
        assertTrue(FileRole.UI in roles("src/SignInViewModel.kt"))
        assertEquals(listOf("user", "login", "controller"), PathRules.words("UserLoginController"))
        assertEquals(listOf("api", "v2", "client"), PathRules.words("api_v2Client"))
        assertTrue(FileRole.MEDIA in roles("assets/logo.png"))
        assertTrue(FileRole.CONFIGURATION in roles(".env.production"))
    }
}

package com.funcodestudiocb.analyzer.engine.inventory

import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.ContentState
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.api.EntryPointKind
import com.funcodestudiocb.analyzer.engine.api.EvidenceKind
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.InventoryOptions
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.SecretType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryTest {

    @TempDir
    lateinit var dir: File

    private val googleKey = "AIza" + "SyD-1234567890abcdefghijklmnopqrstu"

    private val mainActivity = """
        package com.funcode.demo

        import androidx.activity.ComponentActivity
        import androidx.compose.runtime.Composable

        class MainActivity : ComponentActivity() {
            override fun onCreate(state: android.os.Bundle?) { super.onCreate(state) }
        }

        @Composable
        fun Greeting() {}
    """.trimIndent()

    private val android = mapOf(
        "MyApp/settings.gradle.kts" to "include(\":app\")\n",
        "MyApp/app/build.gradle.kts" to "android {\n    namespace = \"com.funcode.demo\"\n}\n",
        "MyApp/app/src/main/AndroidManifest.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE manifest>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:name=".DemoApp" android:label="Demo">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                    <activity android:name=".SettingsActivity" android:exported="false" />
                    <service android:name=".SyncService" android:exported="false" />
                    <receiver android:name="com.funcode.demo.BootReceiver">
                        <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
                    </receiver>
                    <service android:name="com.google.firebase.messaging.FirebaseMessagingService" android:exported="true" />
                </application>
            </manifest>
        """.trimIndent(),
        "MyApp/app/src/main/java/com/funcode/demo/MainActivity.kt" to mainActivity,
        "MyApp/app/src/main/java/com/funcode/demo/MainActivity - copia.kt" to mainActivity,
        "MyApp/app/src/main/java/com/funcode/demo/DemoApp.kt" to "package com.funcode.demo\nclass DemoApp : android.app.Application()\n",
        "MyApp/app/src/main/java/com/funcode/demo/BootReceiver.kt" to "package com.funcode.demo\nclass BootReceiver\n",
        "MyApp/app/src/main/java/com/funcode/demo/data/UserDao.kt" to "package com.funcode.demo.data\n@Dao\ninterface UserDao {\n  @Query(\"SELECT * FROM user\") fun all(): List<User>\n}\n",
        "MyApp/app/src/main/java/com/funcode/demo/auth/LoginScreen.kt" to "package com.funcode.demo.auth\n@Composable\nfun LoginScreen() {}\n",
        "MyApp/app/src/main/res/layout/activity_main.xml" to "<LinearLayout/>\n",
        "MyApp/app/src/main/res/values/strings.xml" to "<resources>\n    <string name=\"app_name\">Demo</string>\n    <string name=\"maps_key\">$googleKey</string>\n</resources>\n",
        "MyApp/app/src/test/java/com/funcode/demo/MainActivityTest.kt" to "package com.funcode.demo\nclass MainActivityTest\n",
        "MyApp/local.properties" to "sdk.dir=/home/yo/Android/Sdk\n",
        "MyApp/README.md" to "# Demo\n",
        "MyApp/build/outputs/apk/app.apk" to "excluido",
        "MyApp/notas.log" to "registro\n",
        "MyApp/vacio.txt" to "",
    )

    @Test
    fun `proyecto Android - subproyectos, puntos de entrada y funciones con evidencia`() {
        val run = runFull(zipOf(dir, android))
        val base = "MyApp/app/src/main/java/com/funcode/demo"

        val roots = run.roots().map { it.directory to it.ecosystem }.toSet()
        assertTrue(roots.containsAll(setOf("MyApp" to Ecosystem.GRADLE, "MyApp/app" to Ecosystem.GRADLE, "MyApp/app" to Ecosystem.ANDROID)), "$roots")

        val launcher = run.entries(EntryPointKind.ANDROID_LAUNCHER).single()
        assertEquals("$base/MainActivity.kt", launcher.path)
        assertEquals("com.funcode.demo.MainActivity", launcher.target)
        assertEquals(Confidence.CONFIRMED, launcher.confidence)
        assertEquals(EvidenceKind.DECLARATION, launcher.evidence.first().kind)
        assertEquals("MyApp/app/src/main/AndroidManifest.xml", launcher.evidence.first().path)
        assertEquals(5, launcher.evidence.first().line)
        assertEquals("MyApp/app", launcher.projectRoot)

        assertEquals("$base/DemoApp.kt", run.entries(EntryPointKind.ANDROID_APPLICATION).single().path)
        val exported = run.entries(EntryPointKind.ANDROID_EXPORTED_COMPONENT)
        assertEquals("$base/BootReceiver.kt", exported.single { it.target == "com.funcode.demo.BootReceiver" }.path, "Con intent-filter y sin exported: accesible")
        val library = exported.single { it.target == "com.google.firebase.messaging.FirebaseMessagingService" }
        assertNull(library.path, "Lo que no está en el comprimido no se inventa")
        assertTrue(library.evidence.first().detail.contains("no está en el comprimido"))
        assertTrue(exported.none { it.target?.endsWith("SyncService") == true || it.target?.endsWith("SettingsActivity") == true }, "exported=false no es punto de entrada")

        assertTrue(run.roles("$base/MainActivity.kt").containsAll(setOf(FileRole.ENTRY_POINT, FileRole.SOURCE, FileRole.UI)))
        val ui = run.profile("$base/MainActivity.kt").roles.single { it.role == FileRole.UI }
        assertTrue(ui.evidence.any { it.kind == EvidenceKind.CONTENT && it.line != null }, "La interfaz se deduce del contenido, con línea")
        assertTrue(FileRole.DATABASE in run.roles("$base/data/UserDao.kt"))
        assertTrue(run.roles("$base/auth/LoginScreen.kt").containsAll(setOf(FileRole.AUTHENTICATION, FileRole.UI)))
        assertTrue(FileRole.UI in run.roles("MyApp/app/src/main/res/layout/activity_main.xml"))
        assertTrue(FileRole.RESOURCE in run.roles("MyApp/app/src/main/res/values/strings.xml"))
        assertTrue(FileRole.TEST in run.roles("MyApp/app/src/test/java/com/funcode/demo/MainActivityTest.kt"))
        assertTrue(FileRole.CONFIGURATION in run.roles("MyApp/app/src/main/AndroidManifest.xml"))
        assertTrue(FileRole.DOCUMENTATION in run.roles("MyApp/README.md"))

        // Posiblemente innecesarios, siempre como "posible" y con el motivo.
        val copy = run.profile("$base/MainActivity - copia.kt").roles.single { it.role == FileRole.POSSIBLY_UNNECESSARY }
        assertEquals(Confidence.POSSIBLE, copy.confidence)
        assertTrue(copy.evidence.any { it.kind == EvidenceKind.DUPLICATE && it.path == "$base/MainActivity.kt" })
        assertTrue(copy.evidence.any { it.kind == EvidenceKind.FILE_NAME })
        assertFalse(FileRole.POSSIBLY_UNNECESSARY in run.roles("$base/MainActivity.kt"), "El original no se señala")
        assertTrue(FileRole.POSSIBLY_UNNECESSARY in run.roles("MyApp/local.properties"))
        assertTrue(FileRole.POSSIBLY_UNNECESSARY in run.roles("MyApp/notas.log"))
        assertTrue(FileRole.POSSIBLY_UNNECESSARY in run.roles("MyApp/vacio.txt"))
        assertTrue(run.all<com.funcodestudiocb.analyzer.engine.api.FileProfile>().none { it.path.startsWith("MyApp/build/") }, "Lo excluido no tiene ficha")

        // Secreto ocultado en la copia y registrado sin el valor.
        val strings = "MyApp/app/src/main/res/values/strings.xml"
        val redaction = run.redactions(strings).single()
        assertEquals(SecretType.GOOGLE_API_KEY, redaction.type)
        assertEquals(3, redaction.line)
        assertEquals(39, redaction.length)
        assertEquals(1, run.profile(strings).redactions)
        val copyText = assertNotNull(run.copy(strings))
        assertFalse(copyText.contains(googleKey))
        assertEquals(android.getValue(strings).length, copyText.length)
        assertEquals(mainActivity, run.copy("$base/MainActivity.kt"), "Sin secretos, la copia es idéntica")

        val summary = run.summary()
        assertEquals(run.all<com.funcodestudiocb.analyzer.engine.api.FileProfile>().size, summary.files)
        assertEquals(run.all<com.funcodestudiocb.analyzer.engine.api.EntryPoint>().size, summary.entryPoints)
        assertEquals(1, summary.redactions)
        assertTrue(summary.copyEnabled)
        assertEquals(run.profile("$base/MainActivity.kt").lineCount, mainActivity.lines().size)
    }

    @Test
    fun `proyecto web y Node - manifiestos, scripts, convenciones y archivos generados`() {
        val project = mapOf(
            "web/package.json" to """
                {
                  // comentario permitido
                  "name": "demo",
                  "main": "server.js",
                  "bin": { "demo-cli": "bin/cli.js" },
                  "scripts": { "start": "node server.js", "dev": "vite" },
                  "dependencies": { "express": "^4.0.0", "vue": "^3.0.0" },
                  "devDependencies": { "vite": "^5.0.0" },
                }
            """.trimIndent(),
            "web/package-lock.json" to "{ \"lockfileVersion\": 3 }\n",
            "web/server.js" to "const express = require('express')\nconst app = express()\napp.get('/api/users', (q, r) => r.json([]))\n// app.listen(1)\napp.listen(3000)\n",
            "web/index.html" to "<!doctype html>\n<html><body><div id=\"app\"></div>\n<script type=\"module\" src=\"/src/main.ts\"></script>\n<script src=\"https://cdn.example.com/x.js\"></script></body></html>\n",
            "web/src/main.ts" to "import { createApp } from 'vue'\nimport App from './App.vue'\ncreateApp(App).mount('#app')\n",
            "web/src/App.vue" to "<template><div/></template>\n",
            "web/src/api/users.js" to "export const users = []\n",
            "web/.env" to "VITE_TITLE=Demo\nAPI_SECRET=s3cr3t-valor-largo\n",
            "web/public/vendor.min.js" to "!function(){var a=1;}();\n",
            "web/node_modules/vue/index.js" to "excluido\n",
        )
        val run = runFull(zipOf(dir, project))

        val main = run.entries(EntryPointKind.PACKAGE_MAIN).single()
        assertEquals("web/server.js", main.path)
        assertEquals(Confidence.CONFIRMED, main.confidence)
        assertEquals("web/server.js", run.entries(EntryPointKind.NPM_SCRIPT).single().path)
        val server = run.entries(EntryPointKind.SERVER_APP).single()
        assertEquals("web/server.js", server.path)
        assertEquals(Confidence.PROBABLE, server.confidence)
        assertEquals(5, server.evidence.single().line, "La línea comentada no cuenta")

        val missing = run.entries(EntryPointKind.PACKAGE_BIN).single()
        assertNull(missing.path)
        assertEquals("bin/cli.js", missing.target)

        val bootstrap = run.entries(EntryPointKind.WEB_BOOTSTRAP).single()
        assertEquals("web/src/main.ts", bootstrap.path)
        assertEquals(Confidence.CONFIRMED, bootstrap.confidence, "Cargado por index.html (confirmado) y montaje en el código (probable)")
        assertEquals(2, bootstrap.evidence.size)
        assertEquals("web/index.html", run.entries(EntryPointKind.WEB_PAGE).single().path)

        assertTrue(FileRole.SERVER in run.roles("web/server.js"))
        assertTrue(FileRole.SERVER in run.roles("web/src/api/users.js"))
        assertTrue(FileRole.UI in run.roles("web/src/App.vue"))
        assertTrue(run.roles("web/package.json").containsAll(setOf(FileRole.DEPENDENCIES, FileRole.CONFIGURATION)))
        assertFalse(FileRole.DATA in run.roles("web/package.json"), "El indicio genérico \"datos JSON\" no se añade a un nombre conocido")

        assertEquals(ContentState.NOT_STORED_GENERATED, run.profile("web/package-lock.json").content)
        assertEquals(ContentState.NOT_STORED_GENERATED, run.profile("web/public/vendor.min.js").content)
        assertNull(run.copy("web/package-lock.json"))
        val env = assertNotNull(run.copy("web/.env"))
        assertFalse(env.contains("s3cr3t-valor-largo"))
        assertTrue(env.contains("VITE_TITLE=Demo"))
        assertEquals(SecretType.SECRET, run.redactions("web/.env").single().type)
    }

    @Test
    fun `motores de juego - escenas principales y autoloads`() {
        val project = mapOf(
            "godot/project.godot" to "[application]\nconfig/name=\"Demo\"\nrun/main_scene=\"res://scenes/main.tscn\"\n\n[autoload]\nGlobals=\"*res://globals.gd\"\nFalta=\"*res://no_existe.gd\"\n\n[display]\nwindow/size=1\n",
            "godot/scenes/main.tscn" to "[gd_scene format=3]\n",
            "godot/globals.gd" to "extends Node\n",
            "unity/ProjectSettings/ProjectVersion.txt" to "m_EditorVersion: 2022.3.0f1\n",
            "unity/ProjectSettings/EditorBuildSettings.asset" to "EditorBuildSettings:\n  m_Scenes:\n  - enabled: 0\n    path: Assets/Scenes/Old.unity\n  - enabled: 1\n    path: Assets/Scenes/Menu.unity\n    guid: abc\n",
            "unity/Assets/Scenes/Menu.unity" to "%YAML 1.1\n",
            "unity/Assets/Scenes/Old.unity" to "%YAML 1.1\n",
            "unreal/Demo.uproject" to "{ \"FileVersion\": 3 }\n",
            "unreal/Config/DefaultEngine.ini" to "[/Script/EngineSettings.GameMapsSettings]\nGameDefaultMap=/Game/Maps/Inicio.Inicio\n",
            "unreal/Content/Maps/Inicio.umap" to "binario\u0000",
        )
        val run = runFull(zipOf(dir, project))
        val scenes = run.entries(EntryPointKind.GAME_MAIN_SCENE).associateBy { it.path }
        assertTrue(scenes.containsKey("godot/scenes/main.tscn"))
        assertTrue(scenes.containsKey("unity/Assets/Scenes/Menu.unity"), "Primera escena ACTIVADA")
        assertTrue(scenes.containsKey("unreal/Content/Maps/Inicio.umap"))
        assertTrue(scenes.values.all { it.confidence == Confidence.CONFIRMED })
        val autoloads = run.entries(EntryPointKind.GAME_AUTOLOAD)
        assertEquals("godot/globals.gd", autoloads.single { it.path != null }.path)
        assertEquals("no_existe.gd", autoloads.single { it.path == null }.target)
        val roots = run.roots().map { it.ecosystem to it.directory }.toSet()
        assertTrue(roots.containsAll(setOf(Ecosystem.GODOT to "godot", Ecosystem.UNITY to "unity", Ecosystem.UNREAL to "unreal")), "$roots")
        assertTrue(FileRole.UI in run.roles("godot/scenes/main.tscn"))
    }

    @Test
    fun `funciones main en varios lenguajes y lineas comentadas`() {
        val project = mapOf(
            "py/main.py" to "import sys\n\nif __name__ == \"__main__\":\n    print(1)\n",
            "py/util.py" to "# if __name__ == \"__main__\":\ndef f(): pass\n",
            "java/App.java" to "public class App {\n  public static void main(String[] args) {}\n}\n",
            "java/Notes.java" to "/**\n * public static void main(String[] args)\n */\nclass Notes {}\n",
            "kt/Main.kt" to "fun main() {\n  println(1)\n}\n",
            "kt/Other.kt" to "class Other {\n  fun main() {}\n}\n",
            "go/cmd/main.go" to "package main\n\nfunc main() {\n}\n",
            "dotnet/app.csproj" to "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><OutputType>Exe</OutputType></PropertyGroup></Project>\n",
            "dotnet/Program.cs" to "Console.WriteLine(\"hola\");\n",
            "c/main.c" to "#include <stdio.h>\nint main(void) { return 0; }\n",
            "flask/app.py" to "from flask import Flask\napp = Flask(__name__)\n@app.route('/')\ndef index(): return 'ok'\n",
        )
        val run = runFull(zipOf(dir, project))
        val mains = run.entries(EntryPointKind.MAIN_FUNCTION).mapNotNull { it.path }.toSet()
        assertEquals(setOf("py/main.py", "java/App.java", "kt/Main.kt", "go/cmd/main.go", "dotnet/Program.cs", "c/main.c"), mains)
        assertEquals(3, run.entries(EntryPointKind.MAIN_FUNCTION).single { it.path == "py/main.py" }.evidence.single().line)
        assertTrue(run.entries(EntryPointKind.MAIN_FUNCTION).all { it.confidence == Confidence.PROBABLE }, "Patrón de texto: probable")
        assertEquals("flask/app.py", run.entries(EntryPointKind.SERVER_APP).single().path)
        assertTrue(FileRole.SERVER in run.roles("flask/app.py"))
    }

    @Test
    fun `copia desactivada, limites de la copia y archivos demasiado grandes`() {
        val project = mapOf(
            "a/Config.kt" to "val apiKey = \"abcd1234efgh5678ijkl\"\n",
            "a/b.txt" to "texto\n".repeat(50),
            "a/grande.txt" to "x".repeat(4096),
        )
        val disabled = runFull(zipOf(dir, project), InventoryOptions(keepRedactedCopy = false))
        assertTrue(disabled.all<com.funcodestudiocb.analyzer.engine.api.ContentChunk>().isEmpty(), "Sin copia")
        assertEquals(ContentState.NOT_STORED_DISABLED, disabled.profile("a/Config.kt").content)
        assertEquals(1, disabled.redactions("a/Config.kt").size, "Los secretos se siguen señalando (sin su valor)")
        assertFalse(disabled.summary().copyEnabled)

        val limited = runFull(zipOf(dir, project), InventoryOptions(maxStoredChars = 50, maxContentBytes = 1024))
        assertEquals(ContentState.STORED, limited.profile("a/Config.kt").content)
        assertEquals(ContentState.NOT_STORED_LIMIT, limited.profile("a/b.txt").content)
        assertEquals(ContentState.TOO_LARGE, limited.profile("a/grande.txt").content)
        assertTrue(limited.coverage().any { it.reason == CoverageReason.LIMIT_REACHED })
        assertTrue(limited.coverage().any { it.reason == CoverageReason.NOT_ANALYZED && it.detail.contains("1 archivos de texto superan") })
        assertEquals(1, limited.summary().storedFiles)
    }

    @Test
    fun `archivo grande se trocea por lineas sin perder nada`() {
        val text = (1..20_000).joinToString("\n") { "línea número $it con algo de texto ✓" } + "\n"
        val run = runFull(zipOf(dir, mapOf("big.txt" to text)))
        val chunks = run.all<com.funcodestudiocb.analyzer.engine.api.ContentChunk>()
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= RecordValidator.MAX_CHUNK_CHARS })
        assertTrue(chunks.dropLast(1).all { it.text.endsWith("\n") }, "Se corta al final de una línea")
        assertEquals(text, run.copy("big.txt"))
    }

    @Test
    fun `nombres hostiles y evidencias largas siguen siendo registros validos`() {
        val longDir = "d".repeat(700)
        val project = mapOf(
            "$longDir/tests/Main.kt" to "fun main() {}\n",
            "evil‮txt.kt" to "class Evil\n",
            "nulos.txt" to "a\u0000b\n",
        )
        val run = runFull(zipOf(dir, project))
        val profile = run.profile("$longDir/tests/Main.kt")
        assertTrue(profile.roles.flatMap { it.evidence }.all { it.detail.length <= RecordValidator.MAX_EVIDENCE_DETAIL })
        assertTrue(run.all<com.funcodestudiocb.analyzer.engine.api.FileProfile>().any { it.path == "evil\\u202Etxt.kt" }, "Ruta escapada")
    }
}

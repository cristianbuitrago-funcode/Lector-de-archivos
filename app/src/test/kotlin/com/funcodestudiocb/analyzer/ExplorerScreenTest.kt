package com.funcodestudiocb.analyzer

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.funcodestudiocb.analyzer.analysis.AnalysisState
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.ui.FuncodeRoot
import com.funcodestudiocb.analyzer.ui.theme.FuncodeTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ExplorerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainer
    private val secret = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"

    @Before
    fun setUp() {
        File(context.noBackupFilesDir, "vault").deleteRecursively()
        container = testContainer(context)
        container.session.unlock(container.vault.setup("Funcode-2026".toCharArray()))
        compose.setContent { FuncodeTheme { FuncodeRoot(container) } }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condición no cumplida; estado: ${container.coordinator.state.value}" }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    private fun analyze(entries: Map<String, String>? = null) {
        val file = File(context.cacheDir, "demo.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            (entries ?: mapOf(
                "demo/app/src/main/AndroidManifest.xml" to """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.demo">
                      <application>
                        <activity android:name=".MainActivity">
                          <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                          </intent-filter>
                        </activity>
                      </application>
                    </manifest>
                """.trimIndent(),
                "demo/app/src/main/java/com/demo/MainActivity.kt" to "package com.demo\n\nclass MainActivity : ComponentActivity() {\n\tval token = \"$secret\"\n}\n",
                "demo/app/src/main/java/com/demo/data/UserDao.kt" to "package com.demo.data\n@Dao\ninterface UserDao\n",
                "demo/README.md" to "# Demo\n",
            )).forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        container.coordinator.select(Uri.fromFile(file))
        awaitCondition { container.coordinator.state.value is AnalysisState.Ready }
        container.coordinator.start(ExclusionOptions())
        awaitCondition { (container.coordinator.state.value as? AnalysisState.Finished)?.saved == true }
    }

    @Test
    fun recorridoDelExplorador() {
        analyze()
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_explorador").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("explorer_tree").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag("screen_title") and hasText("Explorador")).assertIsDisplayed()
        compose.onNodeWithText("demo.zip").assertIsDisplayed()

        // Filtro por función: solo el punto de entrada y sus carpetas.
        compose.onNodeWithTag("explorer_tree").performScrollToNode(hasTestTag("role_ENTRY_POINT"))
        compose.onNodeWithTag("role_ENTRY_POINT").performClick()
        compose.waitForIdle()
        val activity = "demo/app/src/main/java/com/demo/MainActivity.kt"
        compose.onNodeWithTag("explorer_tree").performScrollToNode(hasTestTag("row:$activity"))
        compose.onAllNodesWithTag("row:demo/README.md").assertCountEquals(0)
        compose.onNodeWithTag("row:$activity").performClick()
        compose.waitForIdle()

        // Ficha del archivo: función con certeza y evidencia, punto de entrada y secreto enmascarado.
        compose.onNodeWithTag("file_detail").assertIsDisplayed()
        compose.onNodeWithText("MainActivity.kt").assertIsDisplayed()
        compose.onAllNodesWithText("Punto de entrada").fetchSemanticsNodes().isNotEmpty().let { check(it) }
        compose.onNodeWithTag("file_language").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Kotlin · soporte de nivel 1").assertIsDisplayed()
        compose.onNodeWithText("POSIBLES SECRETOS OCULTADOS").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Token de GitHub", substring = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(secret, substring = true).assertCountEquals(0)

        // Visor: la línea del secreto se ve enmascarada y nunca completa.
        compose.onNodeWithTag("file_view_code").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("code_line_4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("ghp_", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText(secret, substring = true).assertCountEquals(0)
        compose.onNodeWithText("    val token", substring = true).assertIsDisplayed()

        // Búsqueda tras volver al árbol.
        compose.onNodeWithTag("code_viewer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("file_detail").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("role_ENTRY_POINT").performClick()
        compose.onNodeWithTag("explorer_search").performTextInput("userdao")
        compose.waitForIdle()
        compose.onNodeWithTag("explorer_tree").performScrollToNode(hasTestTag("row:demo/app/src/main/java/com/demo/data/UserDao.kt"))
        compose.onAllNodesWithTag("row:$activity").assertCountEquals(0)
    }

    @Test
    fun informeDeTecnologiasConDondeSeEncontroYSuConfianza() {
        analyze(
            mapOf(
                "web/package.json" to "{\"name\": \"web\", \"dependencies\": {\"express\": \"^4.19.0\"}}",
                "web/server.js" to "const express = require(\"express\");\nconst app = express();\n",
                "web/README.md" to "Más adelante: Stripe.\n",
            ),
        )
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_nuevo").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("report_technologies").fetchSemanticsNodes().isNotEmpty() }

        // Tecnología confirmada, con su categoría, versión y confianza.
        compose.onNodeWithTag("tech_express").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Versiones declaradas: ^4.19.0").assertIsDisplayed()
        compose.onAllNodesWithText("Confirmado").fetchSemanticsNodes().isNotEmpty().let { check(it) }
        // Las menciones van aparte.
        compose.onNodeWithTag("report_mentions").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tech_stripe").performScrollTo().assertIsDisplayed()
        // Lenguajes.
        compose.onNodeWithTag("report_languages").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("JavaScript").assertIsDisplayed()

        // Dónde se encontró: al tocarla se ven las evidencias con archivo y línea, y se abre el archivo.
        compose.onNodeWithTag("tech_express").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("web/package.json:1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("web/server.js:1").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("file_detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("server.js").assertIsDisplayed()
        compose.onNodeWithText("TECNOLOGÍAS ENCONTRADAS AQUÍ").performScrollTo().assertIsDisplayed()

        // Visor con resaltado de sintaxis.
        compose.onNodeWithTag("file_view_code").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("code_line_1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("const express = require", substring = true).assertIsDisplayed()
    }

    @Test
    fun relacionesConSusCincoAtributos() {
        analyze(
            mapOf(
                "web/package.json" to "{\"name\": \"web\", \"dependencies\": {\"react\": \"^18.0.0\"}}",
                "web/src/main.js" to "import { api } from './api.js';\nimport { nada } from './falta.js';\napi();\n",
                "web/src/api.js" to "export function api() {}\n",
                "web/src/sobra.js" to "export const x = 1;\n",
            ),
        )
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_nuevo").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("report_relations").fetchSemanticsNodes().isNotEmpty() }

        // Informe: posibles errores y archivos sin referencias.
        compose.onNodeWithTag("report_relations").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Posibles errores (1)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sin referencias encontradas", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("web/src/sobra.js").performScrollTo().assertIsDisplayed()

        // Del posible error a la ficha del archivo y a sus conexiones.
        compose.onNodeWithText("web/src/main.js:2").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("file_detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("file_relations").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Usa (2)").assertIsDisplayed()
        compose.onNodeWithText("web/src/api.js").performScrollTo().performClick()
        compose.waitForIdle()

        // Los cinco atributos de la conexión.
        compose.onNodeWithTag("relation_detail").assertIsDisplayed()
        compose.onNodeWithText("ORIGEN Y DESTINO").assertIsDisplayed()
        compose.onNodeWithText("web/src/main.js:1").assertIsDisplayed()
        compose.onNodeWithText("Importación").assertIsDisplayed()
        compose.onNodeWithTag("relation_exchanged").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("api").assertIsDisplayed()
        compose.onNodeWithTag("relation_impact").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("dejarían de estar definidos: api", substring = true).assertIsDisplayed()
        compose.onNodeWithText("CONFIANZA Y EVIDENCIA").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ruta relativa", substring = true).performScrollTo().assertIsDisplayed()

        // El destino abre su ficha, donde la conexión aparece como entrante.
        compose.onNodeWithTag("relation_destination").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("file_detail").assertIsDisplayed()
        compose.onNodeWithText("api.js").assertIsDisplayed()
        compose.onNodeWithTag("file_relations").performScrollTo()
        compose.onNodeWithText("Lo usan (1)").assertIsDisplayed()
    }

    @Test
    fun rutasLlamadasDatosYConfiguracionDeFramework() {
        analyze(
            mapOf(
                "server/package.json" to "{\"name\": \"server\", \"dependencies\": {\"express\": \"^4.0.0\"}}",
                "server/.env.example" to "DATABASE_URL=x\n",
                "server/app.js" to "const express = require('express');\nconst ctrl = require('./users');\nconst app = express();\napp.get('/api/users', ctrl.list);\n",
                "server/users.js" to "exports.list = (req, res) => db.query('SELECT * FROM users WHERE id = ?', [1]);\nconst url = process.env.DATABASE_URL;\nconst key = process.env.STRIPE_KEY;\n",
                "web/api.js" to "export const users = () => fetch('/api/users');\nexport const orders = () => fetch('/api/orders');\n",
            ),
        )
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_nuevo").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("report_framework").fetchSemanticsNodes().isNotEmpty() }

        // Informe: rutas con su controlador y sus llamadas, llamada sin ruta, tablas y claves sin definir.
        compose.onNodeWithTag("report_framework").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 rutas del backend", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("GET /api/users") and hasText("→ ctrl.list", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("→ ctrl.list · Express · 1 llamada", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Llamadas HTTP sin ruta en el backend analizado (1)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("GET /api/orders").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tablas y colecciones (1)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Se configuran fuera del comprimido (1)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("STRIPE_KEY").performScrollTo().assertIsDisplayed()

        // La ruta abre el archivo que la atiende; la conexión desde el cliente muestra sus cinco atributos.
        compose.onNode(hasText("GET /api/users") and hasText("→ ctrl.list", substring = true)).performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("file_detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("users.js").assertIsDisplayed()
        compose.onNodeWithTag("file_relations").performScrollTo()
        compose.onNodeWithText("web/api.js").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("relation_detail").assertIsDisplayed()
        compose.onNodeWithText("Llamada HTTP").assertIsDisplayed()
        compose.onNodeWithTag("relation_exchanged").performScrollTo()
        compose.onNodeWithText("GET /api/users").assertIsDisplayed()
        compose.onNodeWithTag("relation_impact").performScrollTo()
        compose.onNodeWithText("Si la ruta del backend cambia o desaparece, esta petición falla", substring = true).assertIsDisplayed()
        compose.onNodeWithText("ruta HTTP literal idéntica en el backend", substring = true).performScrollTo().assertIsDisplayed()
    }
}

package com.funcodestudiocb.analyzer

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.funcodestudiocb.analyzer.analysis.AnalysisState
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.ui.FuncodeRoot
import com.funcodestudiocb.analyzer.ui.theme.FuncodeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SecurityScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainer

    // Clave con formato real construida al ejecutar: el código fuente no contiene ninguna.
    private val secret = "sk_" + "live_" + "51Hq8Zx2Lm4Nv6Bc8Df0Gh2Jk4"

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

    private fun analyze() {
        val file = File(context.cacheDir, "tienda.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            mapOf(
                "tienda/server.js" to """
                    const express = require('express');
                    const db = require('./db');
                    const router = express.Router();
                    router.get('/users/:id', (req, res) => {
                      const sql = "SELECT * FROM users WHERE id = " + req.params.id;
                      db.query(sql, (e, rows) => res.json(rows));
                    });
                    module.exports = router;
                """.trimIndent(),
                "tienda/config.js" to "module.exports = { stripe: \"$secret\" };\n",
                "tienda/README.md" to "# Tienda\n",
            ).forEach { (name, text) ->
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
    fun pantallaSeguridadConFichaCompletaYSinSecretosCompletos() {
        analyze()
        // La clave de huellas se crea una vez y se guarda en la base de datos cifrada.
        val key = runBlocking { container.repository.fingerprintKey() }
        assertNotNull(key)
        assertEquals(key, runBlocking { container.repository.fingerprintKey() })
        val (_, report) = runBlocking { container.repository.load(container.repository.recent(1).first().first().id)!! }
        val stripe = report.security.single { it.ruleId == "FC-SECRET-02" }
        assertTrue(stripe.fingerprint!!.length == 32 && stripe.masked!!.startsWith("sk_l"))

        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_seguridad").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("security_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag("screen_title") and hasText("Seguridad")).assertIsDisplayed()
        compose.onNodeWithTag("security_disclaimer").assertIsDisplayed()
        compose.onNodeWithText("La ausencia de hallazgos no implica ausencia de vulnerabilidades", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("security_totals").assertIsDisplayed()

        // Filtro por certeza: solo los probables (la inyección SQL).
        compose.onNodeWithTag("security_list").performScrollToNode(hasTestTag("filter_certainty_PROBABLE"))
        compose.onNodeWithTag("filter_certainty_PROBABLE").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("security_list").performScrollToNode(hasText("Consulta SQL construida con datos variables"))
        compose.onAllNodes(hasText("Clave o token de un servicio en el código")).assertCountEquals(0)
        compose.onNodeWithText("Consulta SQL construida con datos variables").performClick()
        compose.waitForIdle()

        // Ficha completa: regla, CWE, OWASP, ubicación, explicación, fragmento, recorrido, recomendación y ejemplo.
        compose.onNodeWithTag("finding_detail").assertIsDisplayed()
        compose.onNodeWithText("FC-SQLI-01 (versión 1)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("CWE-89").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("A03:2021 Inyección").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("tienda/server.js, línea 6").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("finding_snippet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Línea 5: Se construye el valor con un dato de la petición (req.params)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("RECOMENDACIÓN").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("finding_example").performScrollTo().assertIsDisplayed()

        // Volver a la lista, quitar filtros y abrir el secreto: enmascarado, con huella y nunca completo.
        compose.onNodeWithTag("finding_back").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("security_list").performScrollToNode(hasText("Quitar filtros"))
        compose.onNodeWithText("Quitar filtros").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("security_list").performScrollToNode(hasText("Clave o token de un servicio en el código"))
        compose.onNodeWithText("Clave o token de un servicio en el código").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Huella local").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText(secret, substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText(secret.take(12), substring = true)).assertCountEquals(0)

        // Desde la ficha se abre el archivo en el Explorador, con su panel de seguridad.
        compose.onNodeWithTag("finding_open_file").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("file_detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("file_security").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("file_view_code").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("code_finding_1").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(secret, substring = true)).assertCountEquals(0)
    }
}

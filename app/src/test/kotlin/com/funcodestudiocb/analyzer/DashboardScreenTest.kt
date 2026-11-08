package com.funcodestudiocb.analyzer

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        File(context.noBackupFilesDir, "vault").deleteRecursively()
        container = testContainer(context)
        container.session.unlock(container.vault.setup("Funcode-2026".toCharArray()))
        compose.setContent { FuncodeTheme { FuncodeRoot(container) } }
    }

    private fun awaitCondition(what: String = "", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condición no cumplida ($what); estado: ${container.coordinator.state.value}" }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun scrollTo(tag: String) = compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag(tag))

    /** Analiza la tienda de prueba con el nombre de archivo indicado; la versión 2 añade una dependencia. */
    private fun analyze(fileName: String, version: Int): String {
        val file = File(context.cacheDir, fileName)
        val entries = ArchitectureFixtures.PROJECT.toMutableMap()
        if (version == 2) {
            entries["tienda/server/package.json"] = """{"name": "server", "dependencies": {"express": "^4.19.0", "sequelize": "^6.0.0", "helmet": "^7.1.0"}}"""
        }
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        container.coordinator.select(Uri.fromFile(file))
        awaitCondition("importado") { container.coordinator.state.value is AnalysisState.Ready }
        container.coordinator.start(ExclusionOptions())
        awaitCondition("guardado") { (container.coordinator.state.value as? AnalysisState.Finished)?.saved == true }
        val id = runBlocking { container.repository.recent(1).first().first().id }
        container.coordinator.reset()
        return id
    }

    @Test
    fun sinAnalisisMuestraLaBienvenidaYLlevaANuevoAnalisis() {
        awaitCondition("bienvenida") { exists("dash_welcome") }
        compose.onNodeWithText("Bienvenido a Funcode Code Analyzer").assertIsDisplayed()
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("dash_installation"))
        compose.onNodeWithText("Sin base de vulnerabilidades descargada").assertExists()
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("dash_welcome"))
        compose.onNodeWithTag("dash_new_analysis").performClick()
        awaitCondition("nuevo análisis") { exists("screen_title") && compose.onAllNodesWithTag("dash_welcome").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasTestTag("screen_title") and hasText("Nuevo análisis")).assertIsDisplayed()
    }

    @Test
    fun dosVersionesDelMismoProyectoSeAgrupanConTendenciaYTotales() {
        val first = analyze("tienda.zip", 1)
        val second = analyze("tienda-v2.zip", 2)
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_panel").performClick()
        awaitCondition("panel con datos") { exists("dash_totals") }

        // Un proyecto con dos análisis: las versiones se reconocen como el mismo proyecto.
        compose.onNodeWithTag("dash_tile_projects").assertIsDisplayed()
        compose.onNode(hasTestTag("dash_tile_projects") and hasText("proyecto")).assertExists()
        compose.onNode(hasTestTag("dash_tile_analyses") and hasText("2")).assertExists()

        // Hallazgos del último análisis (no la suma de los dos) y vulnerabilidades sin base: no comprobadas.
        val findings = runBlocking { container.repository.loadWithRecords(second)!!.report.securitySummary!!.findings }
        compose.onNode(hasTestTag("dash_tile_findings") and hasText(findings.toString())).assertExists()
        scrollTo("dash_vulnerabilities")
        compose.onNodeWithText("no se pudieron comprobar", substring = true).assertExists()

        // El proyecto, con su índice y su tendencia (dos índices).
        scrollTo("dash_project_tienda")
        scrollTo("dash_trends")
        scrollTo("dash_trend_tienda")
        scrollTo("dash_usage")
        compose.onNodeWithTag("dash_technology_Express").assertExists()
        scrollTo("dash_recent_$first")
        scrollTo("dash_recent_$second")

        // El proyecto abre el informe de su último análisis.
        scrollTo("dash_project_tienda")
        compose.onNodeWithTag("dash_project_tienda").performClick()
        awaitCondition("informe") { exists("reports_index") }
    }
}

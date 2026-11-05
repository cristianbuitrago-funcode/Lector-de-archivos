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
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.funcodestudiocb.analyzer.analysis.AnalysisState
import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.EolStatus
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.engine.api.UsageStatus
import com.funcodestudiocb.analyzer.ui.FuncodeRoot
import com.funcodestudiocb.analyzer.ui.theme.FuncodeTheme
import com.funcodestudiocb.analyzer.vulndb.UpdateState
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class DependenciesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainer
    private val downloader = FakeDownloader()

    @Before
    fun setUp() {
        File(context.noBackupFilesDir, "vault").deleteRecursively()
        File(context.noBackupFilesDir, "vulnerabilidades").deleteRecursively()
        container = testContainer(context, downloader = downloader)
        container.session.unlock(container.vault.setup("Funcode-2026".toCharArray()))
        compose.setContent { FuncodeTheme { FuncodeRoot(container) } }
    }

    private fun awaitCondition(what: String = "", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condición no cumplida ($what); análisis: ${container.coordinator.state.value}; base: ${container.vulnDbUpdater.state.value}" }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    private fun open(route: String) {
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_$route").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun analyze() {
        val file = File(context.cacheDir, "tienda.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            VulnDbFixtures.PROJECT.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        container.coordinator.select(Uri.fromFile(file))
        awaitCondition("importado") { container.coordinator.state.value is AnalysisState.Ready }
        container.coordinator.start(ExclusionOptions())
        awaitCondition("guardado") { (container.coordinator.state.value as? AnalysisState.Finished)?.saved == true }
    }

    @Test
    fun ajustesDescargaLaBaseConConsentimientoYLaVistaMuestraVulnerabilidadesYUso() {
        // 1. Ajustes: sin base; descargar pide confirmación antes de conectarse.
        open("ajustes")
        compose.onNodeWithTag("vulndb_section").performScrollTo()
        compose.onNodeWithTag("vulndb_none").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("vulndb_eco_npm").performScrollTo().performClick() // npm no viene elegido por defecto
        compose.onNodeWithTag("vulndb_update").performScrollTo().performClick()
        awaitCondition("diálogo de consentimiento") { compose.onAllNodesWithTag("vulndb_confirm").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No se envía nada de tus proyectos", substring = true).assertExists()
        assertTrue(downloader.requested.isEmpty(), "Nada se descarga antes de aceptar")
        compose.onNodeWithTag("vulndb_confirm").performClick()
        awaitCondition("base descargada") { container.vulnDbUpdater.state.value is UpdateState.Done }
        compose.onNodeWithTag("vulndb_done").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("vulndb_status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("npm: 3 avisos de 3 paquetes", substring = true).performScrollTo().assertIsDisplayed()
        assertNotNull(container.vulnDbStore.indexOrNull())

        // 2. Analizar: el proceso de análisis recibe la base y el informe trae las dependencias.
        analyze()
        val id = runBlocking { container.repository.recent(1).first().first().id }
        val (_, report) = runBlocking { container.repository.load(id)!! }
        val summary = assertNotNull(report.dependencySummary)
        assertEquals(4, summary.direct)
        assertEquals(1, summary.indirect)
        assertEquals(3, summary.vulnerablePackages)
        assertEquals(1, summary.malicious)
        assertTrue(summary.databases.any { it.ecosystem == "npm" && it.date != null })
        val lodash = report.resolvedDependencies.single { it.name == "lodash" }
        assertEquals(UsageStatus.USED, lodash.usage)
        assertEquals(Certainty.CONFIRMED, lodash.vulnerabilities.single().certainty)
        assertEquals(EolStatus.UNSUPPORTED, report.endOfLife.single { it.product == "nodejs" }.status)
        assertTrue(report.security.any { it.ruleId == "FC-DEP-02" }, "El paquete malicioso es un hallazgo de seguridad")

        // 3. Seguridad → Dependencias.
        open("seguridad")
        awaitCondition("seguridad") { compose.onAllNodesWithTag("security_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("security_list").performScrollToNode(hasTestTag("security_open_dependencies"))
        compose.onNodeWithTag("security_open_dependencies").performClick()
        awaitCondition("dependencias") { compose.onAllNodesWithTag("dependency_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag("screen_title") and hasText("Dependencias")).assertIsDisplayed()
        compose.onNodeWithTag("dependency_disclaimer").assertIsDisplayed()
        compose.onNodeWithTag("dependency_totals").assertIsDisplayed()
        compose.onNodeWithText("Directas: 4 · indirectas: 1 · archivos de bloqueo: 1").assertIsDisplayed()

        // Filtro: solo indirectas → qs, introducida por express.
        compose.onNodeWithTag("dependency_list").performScrollToNode(hasTestTag("dep_filter_INDIRECT"))
        compose.onNodeWithTag("dep_filter_INDIRECT").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText("lodash 4.17.15")).assertCountEquals(0)
        compose.onNodeWithTag("dependency_list").performScrollToNode(hasText("qs 6.5.1"))
        compose.onNodeWithText("qs 6.5.1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("dependency_detail").assertIsDisplayed()
        compose.onNodeWithTag("dependency_chain").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("• express › qs").assertIsDisplayed()
        compose.onNodeWithText("GHSA-hrpp-h998-j3pp").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Corregida en: 6.5.3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dependency_back").performScrollTo().performClick()
        compose.waitForIdle()

        // Búsqueda: lodash, usada en el código, con vulnerabilidad confirmada y el archivo que la importa.
        compose.onNodeWithTag("dependency_list").performScrollToNode(hasText("Quitar filtros"))
        compose.onNodeWithText("Quitar filtros").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("dependency_list").performScrollToNode(hasTestTag("dependency_search"))
        compose.onNodeWithTag("dependency_search").performTextInput("loda")
        compose.waitForIdle()
        compose.onNodeWithTag("dependency_list").performScrollToNode(hasText("lodash 4.17.15"))
        compose.onNodeWithText("lodash 4.17.15").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("dependency_usage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Usada en el código").assertIsDisplayed()
        compose.onNodeWithText("• tienda/src/server.js:2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirmada: la versión exacta está afectada.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("CVE-2020-8203").performScrollTo().assertIsDisplayed()

        // El archivo que la usa se abre en el Explorador.
        compose.onNodeWithText("• tienda/src/server.js:2").performScrollTo().performClick()
        awaitCondition("explorador") { compose.onAllNodesWithTag("file_detail").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun sinBaseElInformeLoDiceYNoInventaResultados() {
        analyze()
        val id = runBlocking { container.repository.recent(1).first().first().id }
        val (_, report) = runBlocking { container.repository.load(id)!! }
        val summary = assertNotNull(report.dependencySummary)
        assertEquals(0, summary.vulnerabilities)
        assertTrue(report.resolvedDependencies.all { it.vulnerabilities.isEmpty() })
        assertTrue(summary.notEvaluated.any { "no hay base de vulnerabilidades descargada" in it }, summary.notEvaluated.toString())
        assertTrue(report.endOfLife.isEmpty())
        assertFalse(report.security.any { it.ruleId.startsWith("FC-DEP") })
        assertTrue(downloader.requested.isEmpty(), "Analizar nunca se conecta a Internet")

        // Explorador: la ficha del archivo de bloqueo lleva a sus dependencias (solo las de ese archivo).
        open("explorador")
        awaitCondition("explorador") { compose.onAllNodesWithTag("explorer_tree").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explorer_search").performTextInput("package-lock")
        compose.waitForIdle()
        compose.onNodeWithTag("explorer_tree").performScrollToNode(hasTestTag("row:tienda/package-lock.json"))
        compose.onNodeWithTag("row:tienda/package-lock.json").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("file_dependencies").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dependencias: 5 · con vulnerabilidades: 0").assertIsDisplayed()
        compose.onNodeWithTag("file_open_dependencies").performScrollTo().performClick()
        awaitCondition("dependencias del archivo") { compose.onAllNodesWithTag("dependency_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dependency_list").performScrollToNode(hasTestTag("dependency_file_filter"))
        compose.onNodeWithText("Solo las de tienda/package-lock.json").assertIsDisplayed()
        compose.onNodeWithTag("dependency_list").performScrollToNode(hasTestTag("dep_not_evaluated"))
        compose.onNodeWithText("no hay base de vulnerabilidades descargada", substring = true).assertIsDisplayed()
    }
}

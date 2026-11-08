package com.funcodestudiocb.analyzer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.funcodestudiocb.analyzer.analysis.AnalysisState
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.reports.ReportExporter
import com.funcodestudiocb.analyzer.reports.ReportFormat
import com.funcodestudiocb.analyzer.ui.FuncodeRoot
import com.funcodestudiocb.analyzer.ui.theme.FuncodeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportsScreenTest {

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

    private fun open(route: String) {
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_$route").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Analiza la tienda de prueba; la versión 2 añade un archivo y una dependencia. */
    private fun analyze(version: Int): String {
        val file = File(context.cacheDir, "tienda.zip")
        val entries = ArchitectureFixtures.PROJECT.toMutableMap()
        if (version == 2) {
            entries["tienda/server/package.json"] = """{"name": "server", "dependencies": {"express": "^4.19.0", "sequelize": "^6.0.0", "helmet": "^7.1.0"}}"""
            entries["tienda/server/health.js"] = "module.exports = () => 'ok';\n"
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
    fun historialInformeCompletoExportacionYCompartir() {
        val id = analyze(1)

        // Historial con el análisis seleccionado, su índice y el resumen ejecutivo.
        open("informes")
        awaitCondition("informe preparado") { exists("reports_index") }
        compose.onNode(hasTestTag("screen_title") and hasText("Informes")).assertIsDisplayed()
        compose.onNodeWithTag("reports_privacy").assertIsDisplayed()
        compose.onNodeWithText("Índice de mantenimiento:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("§2", substring = true).assertExists() // cada frase del resumen cita su sección
        // Un análisis completo se presenta como completo (el resultado se guarda en el historial, no como registro).
        assertTrue(compose.onAllNodesWithText("no terminó", substring = true).fetchSemanticsNodes().isEmpty())
        val doc = assertNotNull(runBlocking { container.reports.document(id) })
        val facts = doc.sections.first { it.number == "2" }.blocks.filterIsInstance<com.funcodestudiocb.analyzer.engine.report.Block.Facts>().first().items.toMap()
        assertEquals("Completo", facts["Resultado"])
        compose.onNodeWithTag("reports_list").performScrollToNode(hasTestTag("reports_item_$id"))

        // Informe completo en pantalla: las 15 secciones y los anexos.
        compose.onNodeWithTag("reports_list").performScrollToNode(hasTestTag("reports_read"))
        compose.onNodeWithTag("reports_read").performClick()
        awaitCondition("lector") { exists("reports_reader") }
        listOf("s1", "s4", "s10", "s14", "s15", "anexo-a", "anexo-b").forEach { section ->
            compose.onNodeWithTag("reports_reader").performScrollToNode(hasTestTag("report_section_$section"))
        }
        compose.onNodeWithTag("reports_reader").performScrollToNode(hasText("Huella SHA-256 del comprimido", substring = true))
        compose.onNodeWithTag("reports_reader").performScrollToNode(hasTestTag("report_section_s1"))
        compose.onNodeWithTag("reports_back").performClick()
        awaitCondition("historial") { exists("reports_list") }

        // Los tres formatos, generados en el teléfono a partir de lo guardado.
        val html = runBlocking { container.reports.export(id, ReportFormat.HTML)!! }
        val text = html.bytes.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("<!DOCTYPE html>") && "Content-Security-Policy" in text && "<script" !in text.lowercase())
        assertTrue(html.fileName.startsWith("informe-tienda-") && html.fileName.endsWith(".html"), html.fileName)
        val json = runBlocking { container.reports.export(id, ReportFormat.JSON)!! }
        val root = Json.parseToJsonElement(json.bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals("funcode-report/1", root["schema_version"]!!.jsonPrimitive.content)
        assertEquals(id, root["metadata"]!!.jsonObject["analysis_id"]!!.jsonPrimitive.content)
        // El PDF usa PdfDocument de Android, que la JVM de pruebas no tiene: se comprueba en el emulador
        // (FullAnalysisInstrumentedTest.informesPdfHtmlYJsonEnElDispositivo).

        // Guardar en el destino elegido (aquí, un archivo).
        val target = File(context.cacheDir, "destino.html")
        runBlocking { container.reports.write(html, Uri.fromFile(target)) }
        assertTrue(target.readBytes().contentEquals(html.bytes))

        // Compartir: solo tras pulsarlo, con una copia temporal servida por el FileProvider.
        compose.onNodeWithTag("reports_list").performScrollToNode(hasTestTag("reports_share"))
        compose.onNodeWithTag("reports_share").performClick()
        compose.onNodeWithTag("reports_share_HTML").performClick()
        val app = ApplicationProvider.getApplicationContext<Application>()
        var started: Intent? = null
        awaitCondition("selector de compartir") {
            started = Shadows.shadowOf(app).nextStartedActivity
            started != null
        }
        val chooser = assertNotNull(started)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = assertNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals("text/html", send.type)
        @Suppress("DEPRECATION")
        val stream = assertNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("${context.packageName}.informes", stream.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val shared = File(context.cacheDir, ReportExporter.SHARE_DIR).listFiles().orEmpty()
        assertEquals(1, shared.size, "Una sola copia temporal")
        container.reports.clearShared()
        assertTrue(!File(context.cacheDir, ReportExporter.SHARE_DIR).exists())
    }

    @Test
    fun comparacionEntreDosAnalisisDelMismoProyecto() {
        val first = analyze(1)
        analyze(2)
        open("informes")
        awaitCondition("informe preparado") { exists("reports_index") }
        compose.onNodeWithTag("reports_list").performScrollToNode(hasTestTag("reports_compare"))
        compose.onNodeWithTag("reports_compare").performClick()
        compose.onNodeWithTag("reports_compare_$first").performClick()
        awaitCondition("comparación") { exists("report_section_s1") }
        compose.onNodeWithText("1. Resumen de cambios").assertExists()
        compose.onNodeWithText("Dependencias: 1 añadida,", substring = true).assertExists()
        compose.onNodeWithTag("reports_comparison").performScrollToNode(hasText("tienda/server/health.js", substring = true))
        compose.onNodeWithTag("reports_comparison").performScrollToNode(hasTestTag("report_section_s2"))
        compose.onNodeWithText("Índice de mantenimiento", substring = true).assertExists()
        compose.onNodeWithTag("reports_comparison").performScrollToNode(hasTestTag("reports_back"))
        compose.onNodeWithTag("reports_back").performClick()
        awaitCondition("historial") { exists("reports_list") }
    }
}

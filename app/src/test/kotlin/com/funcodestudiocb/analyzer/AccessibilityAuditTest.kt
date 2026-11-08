package com.funcodestudiocb.analyzer

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertTrue

/**
 * Auditoría de accesibilidad (Fase 10) de las pantallas principales con un análisis real: todo lo que se puede pulsar
 * tiene un nombre que un lector de pantalla puede leer y un área táctil de al menos 48 × 48 dp.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AccessibilityAuditTest {

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

    private fun awaitCondition(what: String, condition: () -> Boolean) {
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

    private fun analyze() {
        val file = File(context.cacheDir, "tienda.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            ArchitectureFixtures.PROJECT.forEach { (name, text) ->
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

    private fun open(route: String, ready: String) {
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_$route").performScrollTo().performClick()
        awaitCondition(route) { exists(ready) }
    }

    /** Nombre accesible del nodo (con lo que TalkBack fusiona de sus hijos). */
    private fun name(node: SemanticsNode): String = listOfNotNull(
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
        node.config.getOrNull(SemanticsProperties.EditableText)?.text,
        node.config.getOrNull(SemanticsProperties.StateDescription),
    ).joinToString(" ").trim()

    private val problems = mutableListOf<String>()

    private fun audit(screen: String) {
        val density = Density(context)
        val minPx = with(density) { 48f * this.density } - 1f // tolerancia de redondeo
        compose.onAllNodes(hasClickAction() and SemanticsMatcher("visible") { !it.boundsInRoot.isEmpty })
            .fetchSemanticsNodes()
            .forEach { node ->
                val bounds = node.boundsInRoot
                val label = name(node)
                if (label.isEmpty()) problems += "$screen: elemento pulsable sin nombre en $bounds"
                // La medida se toma del área táctil: Material la amplía a 48 dp aunque el dibujo sea menor.
                val touch = node.touchBoundsInRoot
                if (touch.width < minPx || touch.height < minPx) {
                    problems += "$screen: «${label.take(40)}» mide ${(touch.width / density.density).toInt()}×${(touch.height / density.density).toInt()} dp"
                }
            }
    }

    @Test
    fun pantallasPrincipalesConNombreYAreaTactil() {
        audit("Panel vacío")
        analyze()
        audit("Nuevo análisis (informe)")
        container.coordinator.reset()
        open("panel", "dash_totals")
        audit("Panel")
        open("explorador", "screen_title")
        audit("Explorador")
        open("arquitectura", "screen_title")
        audit("Arquitectura")
        open("seguridad", "screen_title")
        audit("Seguridad")
        open("informes", "reports_index")
        audit("Informes")
        open("ajustes", "screen_title")
        audit("Ajustes")
        compose.onNodeWithTag("open_menu").performClick()
        compose.waitForIdle()
        audit("Menú")
        assertTrue(problems.isEmpty(), "Problemas de accesibilidad:\n" + problems.distinct().joinToString("\n"))
    }
}

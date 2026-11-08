package com.funcodestudiocb.analyzer

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.funcodestudiocb.analyzer.analysis.AnalysisState
import com.funcodestudiocb.analyzer.data.session.SessionState
import com.funcodestudiocb.analyzer.engine.api.LineMethod
import com.funcodestudiocb.analyzer.engine.report.AnalysisOutcome
import com.funcodestudiocb.analyzer.reports.ReportFormat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recorridos completos en un dispositivo real o emulador: crear el acceso, entrar y ejecutar el diagnóstico de
 * aislamiento (debe pasar); y el criterio de aceptación de la Fase 10, un proyecto desconocido con su diagnóstico
 * completo en el teléfono.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FuncodeApp

    /** Borra cualquier dato previo antes de abrir la actividad. */
    private val cleanState = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                app.container.vault.wipe()
                // Android 13+: «Analizar» pide antes el permiso de notificaciones y el diálogo del sistema queda fuera de
                // la app (la prueba no puede pulsarlo). Se concede antes, como si el usuario ya lo hubiera aceptado.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    InstrumentationRegistry.getInstrumentation().uiAutomation
                        .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
                }
                base.evaluate()
            }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(cleanState).around(compose)

    private fun keyboardVisible(): Boolean {
        var visible = false
        compose.activityRule.scenario.onActivity { activity ->
            visible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    /**
     * Cierra el teclado y espera a que siga cerrado un rato: si se pulsa mientras el teclado se mueve, el botón se
     * desplaza entre el cálculo de la posición y la pulsación y esta se pierde (fallo intermitente en Android 15).
     * En un emulador lento, una petición de mostrarlo que quedó pendiente al escribir puede volver a abrirlo justo
     * después de cerrarlo, así que no basta con verlo cerrado un instante.
     */
    private fun hideKeyboard() {
        var hiddenSince = 0L
        compose.waitUntil(20_000) {
            if (keyboardVisible()) {
                hiddenSince = 0L
                compose.activityRule.scenario.onActivity { activity ->
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
                }
                false
            } else {
                val now = System.currentTimeMillis()
                if (hiddenSince == 0L) hiddenSince = now
                now - hiddenSince >= KEYBOARD_STABLE_MS
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitForIdle()
    }

    private fun unlocked() = app.container.session.state.value == SessionState.Unlocked

    /**
     * Pulsa «Crear acceso» y espera a entrar. Si la pulsación se pierde (el teclado se movió en medio), se repite:
     * mientras se crea el acceso el botón está desactivado, así que una pulsación de más no hace nada.
     */
    private fun createAccess() {
        repeat(CREATE_ATTEMPTS) {
            if (unlocked()) return
            hideKeyboard()
            if (unlocked()) return
            compose.onNodeWithTag("setup_create").performScrollTo().performClick()
            try {
                compose.waitUntil(CREATE_WAIT_MS) { unlocked() }
                return
            } catch (_: ComposeTimeoutException) {
                // Se vuelve a intentar con el teclado cerrado.
            }
        }
        compose.waitUntil(30_000) { unlocked() }
    }

    @Test
    fun crearAccesoYVerificarElAislamiento() {
        compose.onNodeWithTag("setup_password").performTextInput("Funcode-2026")
        compose.onNodeWithTag("setup_confirm").performTextInput("Funcode-2026")
        createAccess()

        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_ajustes").performScrollTo().performClick()
        compose.onNodeWithTag("settings_diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("diag_run").performClick()
        compose.waitUntil(90_000) {
            compose.onAllNodes(hasTestTag("diag_ok")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("diag_fail")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("diag_ok").assertExists("El diagnóstico de aislamiento debe pasar")
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun open(route: String, ready: String) {
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_$route").performScrollTo().performClick()
        compose.waitUntil(60_000) { exists(ready) }
    }

    /**
     * Proyecto desconocido (código real que no se escribió para las pruebas: UnRAR en C++ y un módulo Kotlin de este
     * repositorio, ver la tarea `proyectoDesconocido`) → diagnóstico completo en el teléfono, recorriendo la app.
     */
    @Test
    fun proyectoDesconocidoDiagnosticoCompletoEnElTelefono() {
        compose.onNodeWithTag("setup_password").performTextInput("Funcode-2026")
        compose.onNodeWithTag("setup_confirm").performTextInput("Funcode-2026")
        createAccess()
        hideKeyboard()

        // El selector de documentos de Android no se puede manejar desde la prueba: el comprimido se entrega como si
        // el usuario lo hubiera elegido en «Nuevo análisis».
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val zip = File(instrumentation.targetContext.cacheDir, "proyecto-desconocido.zip")
        instrumentation.context.assets.open("proyecto-desconocido.zip").use { input -> zip.outputStream().use { input.copyTo(it) } }
        open("nuevo", "analysis_drop_zone")
        app.container.coordinator.select(Uri.fromFile(zip))
        compose.waitUntil(60_000) { exists("analysis_start") }
        startAnalysis()
        compose.waitUntil(ANALYSIS_MS) { (app.container.coordinator.state.value as? AnalysisState.Finished)?.saved == true }
        compose.waitUntil(30_000) { exists("report_outcome") }
        val finished = app.container.coordinator.state.value as AnalysisState.Finished
        val report = finished.report
        val diagnosis = "etapas=${report.stages}; cobertura=${report.coverage}"
        assertEquals(AnalysisOutcome.COMPLETED, finished.outcome, diagnosis)
        assertTrue(report.files.size > 150, diagnosis)
        assertEquals(LineMethod.SYNTAX, report.profiles.first { it.path.endsWith(".cpp") }.language?.method, "C++ con su gramática")
        assertTrue(report.languages.any { it.id == "kotlin" } && report.languages.any { it.id == "cpp" })
        assertTrue(report.relations.isNotEmpty(), "Inclusiones e importaciones entre archivos")
        assertTrue((report.metrics?.functions ?: 0) > 100 && report.maintenance?.score != null, diagnosis)

        // Cada pantalla muestra el análisis.
        open("panel", "dash_totals")
        // La fila del proyecto está más abajo: la lista solo la dibuja al llegar a ella.
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("dash_project_proyecto-desconocido"))
        open("explorador", "explorer_tree")
        open("arquitectura", "arch_summary")
        open("seguridad", "security_disclaimer")
        open("informes", "reports_index")

        // Los tres informes, generados en el teléfono.
        val id = finished.analysisId
        val document = runBlocking { app.container.reports.document(id) } ?: error("Sin informe")
        assertEquals(17, document.sections.size, "15 secciones y 2 anexos")
        val pdf = runBlocking { app.container.reports.export(id, ReportFormat.PDF) } ?: error("Sin PDF")
        assertEquals("%PDF-", pdf.bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII))
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { dir ->
            runCatching { File(dir, "informe-proyecto-desconocido.pdf").writeBytes(pdf.bytes) }
        }
        assertTrue(runBlocking { app.container.reports.export(id, ReportFormat.HTML) } != null)
        assertTrue(runBlocking { app.container.reports.export(id, ReportFormat.JSON) } != null)
        zip.delete()
    }

    /**
     * Pulsa «Analizar» y espera a que el análisis empiece. Si la pulsación se pierde (el teclado aún se estaba
     * ocultando y el botón se movió), se repite: en cuanto empieza, el botón desaparece, así que no hay doble análisis.
     */
    private fun startAnalysis() {
        fun started() = app.container.coordinator.state.value !is AnalysisState.Ready
        repeat(CREATE_ATTEMPTS) {
            if (started()) return
            compose.onNodeWithTag("analysis_start").performScrollTo().performClick()
            try {
                compose.waitUntil(CREATE_WAIT_MS) { started() }
                return
            } catch (_: ComposeTimeoutException) {
                // Se vuelve a pulsar.
            }
        }
        compose.waitUntil(30_000) { started() }
    }

    private companion object {
        const val ANALYSIS_MS = 10L * 60 * 1000
        const val KEYBOARD_STABLE_MS = 1_000L
        const val CREATE_ATTEMPTS = 3
        const val CREATE_WAIT_MS = 15_000L
    }
}

package com.funcodestudiocb.analyzer

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.funcodestudiocb.analyzer.data.session.SessionState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Recorrido completo en un dispositivo real o emulador: crear el acceso, entrar y ejecutar el
 * diagnóstico de aislamiento, que debe pasar.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FuncodeApp

    /** Borra cualquier dato previo antes de abrir la actividad. */
    private val cleanState = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                app.container.vault.wipe()
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

    private companion object {
        const val KEYBOARD_STABLE_MS = 1_000L
        const val CREATE_ATTEMPTS = 3
        const val CREATE_WAIT_MS = 15_000L
    }
}

package com.funcodestudiocb.analyzer

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

    /**
     * Cierra el teclado y espera a que termine su animación: si se pulsa mientras el teclado se mueve, el botón se
     * desplaza entre el cálculo de la posición y la pulsación y esta se pierde (fallo intermitente en Android 15).
     */
    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) {
            var visible = false
            compose.activityRule.scenario.onActivity { activity ->
                visible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            !visible
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitForIdle()
    }

    @Test
    fun crearAccesoYVerificarElAislamiento() {
        compose.onNodeWithTag("setup_password").performTextInput("Funcode-2026")
        compose.onNodeWithTag("setup_confirm").performTextInput("Funcode-2026")
        hideKeyboard()
        compose.onNodeWithTag("setup_create").performScrollTo().performClick()
        compose.waitUntil(30_000) { app.container.session.state.value == SessionState.Unlocked }

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
}

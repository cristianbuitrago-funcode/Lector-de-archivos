package com.funcodestudiocb.analyzer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.funcodestudiocb.analyzer.data.session.SessionState
import com.funcodestudiocb.analyzer.ui.FuncodeRoot
import com.funcodestudiocb.analyzer.ui.theme.FuncodeTheme
import android.os.Looper
import org.junit.Before
import org.robolectric.Shadows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class AccessFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        File(context.noBackupFilesDir, "vault").deleteRecursively()
        container = testContainer(context)
        compose.setContent { FuncodeTheme { FuncodeRoot(container) } }
    }

    /**
     * Espera a que se cumpla [condition] procesando la cola del hilo principal: los ViewModel derivan
     * Argon2 a un hilo en segundo plano y reanudan en el principal, que Robolectric no procesa solo.
     */
    private fun awaitCondition(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "La condición no se cumplió en $timeoutMs ms" }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    private fun createAccess(password: String = "Funcode-2026") {
        compose.onNodeWithTag("setup_password").performTextInput(password)
        compose.onNodeWithTag("setup_confirm").performTextInput(password)
        compose.onNodeWithTag("setup_create").performScrollTo().performClick()
        awaitCondition { container.session.state.value == SessionState.Unlocked }
        compose.waitForIdle()
    }

    @Test
    fun laPrimeraVezSePideCrearElAccesoYSeRechazanContrasenasDebiles() {
        compose.onNodeWithText("Configura tu acceso").assertIsDisplayed()
        compose.onNodeWithTag("setup_password").performTextInput("abc")
        compose.onNodeWithTag("setup_confirm").performTextInput("abc")
        compose.onNodeWithTag("setup_create").performScrollTo().performClick()
        compose.onNodeWithText("Debe tener al menos 8 caracteres.").assertIsDisplayed()
        assertFalse(container.vault.isConfigured())
    }

    @Test
    fun crearAccesoEntraEnLaAppConLasPantallasMarcadasPorFase() {
        createAccess()
        compose.onNodeWithTag("screen_title").assertIsDisplayed()
        compose.onNode(hasTestTag("screen_title") and hasText("Panel")).assertIsDisplayed()
        compose.onNodeWithText("Estado de la instalación").assertIsDisplayed()

        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_informes").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("screen_title") and hasText("Informes")).assertIsDisplayed()
        // Informes ya está construida (Fase 9): sin análisis, lo dice.
        awaitCondition { compose.onAllNodesWithTagExists("reports_no_analyses") }
        compose.onNodeWithTag("reports_no_analyses").assertIsDisplayed()

        // Arquitectura ya está construida (Fase 8): sin análisis, lo dice.
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_arquitectura").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("screen_title") and hasText("Arquitectura")).assertIsDisplayed()
        // La consulta a la base de datos cifrada va en segundo plano: se espera a que responda.
        awaitCondition { compose.onAllNodesWithTagExists("arch_no_analyses") }
        compose.onNodeWithTag("arch_no_analyses").assertIsDisplayed()

        // Seguridad ya está construida (Fase 6): sin análisis, lo dice.
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_seguridad").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("screen_title") and hasText("Seguridad")).assertIsDisplayed()
        awaitCondition { compose.onAllNodesWithText("Todavía no hay ningún análisis", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Todavía no hay ningún análisis", substring = true).assertIsDisplayed()
    }

    @Test
    fun bloquearYDesbloquearConContrasenaIncorrectaYCorrecta() {
        createAccess()
        compose.onNodeWithTag("lock_now").performClick()
        compose.waitForIdle()
        assertEquals(SessionState.Locked, container.session.state.value)
        compose.onNodeWithText("Acceso restringido").assertIsDisplayed()

        compose.onNodeWithTag("unlock_password").performTextInput("incorrecta-1")
        compose.onNodeWithTag("unlock_submit").performClick()
        awaitCondition { compose.onAllNodesWithTagExists("unlock_message") }
        compose.onNodeWithTag("unlock_message").assertIsDisplayed()

        compose.onNodeWithTag("unlock_password").performTextInput("Funcode-2026")
        compose.onNodeWithTag("unlock_submit").performClick()
        awaitCondition { container.session.state.value == SessionState.Unlocked }
    }

    @Test
    fun borrarTodoDesdeAjustesVuelveALaConfiguracionInicial() {
        createAccess()
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_ajustes").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings_wipe_all").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_password").performTextInput("Funcode-2026")
        compose.onNodeWithText("Borrar").performClick()
        awaitCondition { container.session.state.value == SessionState.NotConfigured }
        assertFalse(container.vault.isConfigured())
        compose.waitForIdle()
        compose.onNodeWithText("Configura tu acceso").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

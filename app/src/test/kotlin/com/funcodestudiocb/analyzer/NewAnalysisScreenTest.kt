package com.funcodestudiocb.analyzer

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class NewAnalysisScreenTest {

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

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condición no cumplida; estado: ${container.coordinator.state.value}" }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    @Test
    fun recorridoDeNuevoAnalisis() {
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_nuevo").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("screen_title") and hasText("Nuevo análisis")).assertIsDisplayed()
        compose.onNodeWithText("Analiza un proyecto").assertIsDisplayed()
        compose.onNodeWithTag("analysis_drop_zone").assertIsDisplayed()

        val file = File(context.cacheDir, "mi-proyecto.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("src/App.kt"))
            out.write("fun main() {}".toByteArray())
            out.closeEntry()
        }
        container.coordinator.select(Uri.fromFile(file))
        awaitCondition { container.coordinator.state.value is AnalysisState.Ready }
        compose.onNodeWithTag("analysis_file_name").assertIsDisplayed()
        compose.onNodeWithText("Exclusiones".uppercase()).performScrollTo().assertIsDisplayed()

        container.coordinator.start(ExclusionOptions())
        awaitCondition { (container.coordinator.state.value as? AnalysisState.Finished)?.saved == true }
        compose.onNodeWithTag("report_outcome").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Completo").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Análisis recientes".uppercase()).performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("analysis_new").performScrollTo().performClick()
        awaitCondition { container.coordinator.state.value == AnalysisState.Idle }
        compose.onNodeWithText("Analiza un proyecto").performScrollTo().assertIsDisplayed()
    }
}

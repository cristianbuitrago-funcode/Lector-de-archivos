package com.funcodestudiocb.analyzer

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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
class ExplorerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainer
    private val secret = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"

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
        val file = File(context.cacheDir, "demo.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            mapOf(
                "demo/app/src/main/AndroidManifest.xml" to """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.demo">
                      <application>
                        <activity android:name=".MainActivity">
                          <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                          </intent-filter>
                        </activity>
                      </application>
                    </manifest>
                """.trimIndent(),
                "demo/app/src/main/java/com/demo/MainActivity.kt" to "package com.demo\n\nclass MainActivity : ComponentActivity() {\n\tval token = \"$secret\"\n}\n",
                "demo/app/src/main/java/com/demo/data/UserDao.kt" to "package com.demo.data\n@Dao\ninterface UserDao\n",
                "demo/README.md" to "# Demo\n",
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
    fun recorridoDelExplorador() {
        analyze()
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_explorador").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("explorer_tree").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag("screen_title") and hasText("Explorador")).assertIsDisplayed()
        compose.onNodeWithText("demo.zip").assertIsDisplayed()

        // Filtro por función: solo el punto de entrada y sus carpetas.
        compose.onNodeWithTag("explorer_tree").performScrollToNode(hasTestTag("role_ENTRY_POINT"))
        compose.onNodeWithTag("role_ENTRY_POINT").performClick()
        compose.waitForIdle()
        val activity = "demo/app/src/main/java/com/demo/MainActivity.kt"
        compose.onNodeWithTag("explorer_tree").performScrollToNode(hasTestTag("row:$activity"))
        compose.onAllNodesWithTag("row:demo/README.md").assertCountEquals(0)
        compose.onNodeWithTag("row:$activity").performClick()
        compose.waitForIdle()

        // Ficha del archivo: función con certeza y evidencia, punto de entrada y secreto enmascarado.
        compose.onNodeWithTag("file_detail").assertIsDisplayed()
        compose.onNodeWithText("MainActivity.kt").assertIsDisplayed()
        compose.onAllNodesWithText("Punto de entrada").fetchSemanticsNodes().isNotEmpty().let { check(it) }
        compose.onNodeWithText("POSIBLES SECRETOS OCULTADOS").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Token de GitHub", substring = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(secret, substring = true).assertCountEquals(0)

        // Visor: la línea del secreto se ve enmascarada y nunca completa.
        compose.onNodeWithTag("file_view_code").performScrollTo().performClick()
        awaitCondition { compose.onAllNodesWithTag("code_line_4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("ghp_", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText(secret, substring = true).assertCountEquals(0)
        compose.onNodeWithText("    val token", substring = true).assertIsDisplayed()

        // Búsqueda tras volver al árbol.
        compose.onNodeWithTag("code_viewer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("file_detail").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("role_ENTRY_POINT").performClick()
        compose.onNodeWithTag("explorer_search").performTextInput("userdao")
        compose.waitForIdle()
        compose.onNodeWithTag("explorer_tree").performScrollToNode(hasTestTag("row:demo/app/src/main/java/com/demo/data/UserDao.kt"))
        compose.onAllNodesWithTag("row:$activity").assertCountEquals(0)
    }
}

package com.funcodestudiocb.analyzer

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.funcodestudiocb.analyzer.analysis.AnalysisOutcome
import com.funcodestudiocb.analyzer.analysis.AnalysisState
import com.funcodestudiocb.analyzer.analysis.FailureReason
import com.funcodestudiocb.analyzer.engine.api.ArchiveFormat
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnalysisCoordinatorTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        File(context.noBackupFilesDir, "vault").deleteRecursively()
        container = testContainer(context)
        container.session.unlock(container.vault.setup("Funcode-2026".toCharArray()))
    }

    private fun zip(name: String, entries: Map<String, String>): Uri {
        val file = File(context.cacheDir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (n, text) ->
                out.putNextEntry(ZipEntry(n))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return Uri.fromFile(file)
    }

    private fun <T : AnalysisState> await(type: Class<T>): T = runBlocking {
        val result = withTimeoutOrNull(AWAIT_MS) {
            @Suppress("UNCHECKED_CAST")
            container.coordinator.state.first { type.isInstance(it) } as T
        }
        result ?: error(
            "No se llegó a ${type.simpleName}; estado actual: ${container.coordinator.state.value}\n" +
                Thread.getAllStackTraces().entries
                    .filter { (_, st) -> st.any { it.className.contains("funcodestudiocb") || it.className.contains("compress") } }
                    .joinToString("\n\n") { (th, st) -> th.name + "\n" + st.take(40).joinToString("\n") { "  at $it" } },
        )
    }

    /** Espera a que el análisis terminado conste como guardado en la BD cifrada. */
    private fun awaitSaved() = runBlocking {
        withTimeoutOrNull(AWAIT_MS) { container.coordinator.state.first { (it as? AnalysisState.Finished)?.saved == true } }
            ?: error("El análisis no se guardó; estado actual: ${container.coordinator.state.value}")
    }

    private fun importedFiles() = File(context.noBackupFilesDir, "import").listFiles()?.toList().orEmpty()

    @Test
    fun analisisCompletoSeGuardaYBorraLaCopiaTemporal() {
        val coordinator = container.coordinator
        coordinator.select(zip("proyecto.zip", mapOf("src/App.kt" to "fun main() {}", "node_modules/x.js" to "x", "README.md" to "# hola")))
        val ready = await(AnalysisState.Ready::class.java)
        assertEquals(ArchiveFormat.ZIP, ready.archive.format)
        assertEquals("proyecto.zip", ready.archive.displayName)
        assertEquals(1, importedFiles().size, "La copia temporal existe mientras se espera al usuario")

        coordinator.start(ExclusionOptions())
        val finished = await(AnalysisState.Finished::class.java)
        assertEquals(AnalysisOutcome.COMPLETED, finished.outcome)
        assertEquals(setOf("src/App.kt", "README.md"), finished.report.files.map { it.path }.toSet())
        assertTrue(finished.report.files.all { it.status == FileStatus.ANALYZED })
        assertEquals("node_modules", finished.report.excludedDirectories.single().path)
        assertTrue(importedFiles().isEmpty(), "La copia temporal se borra al terminar")

        assertIs<AnalysisState.Finished>(awaitSaved())
        val loaded = runBlocking { container.repository.load(finished.analysisId) }
        assertNotNull(loaded)
        assertEquals(2, loaded.second.files.size)
        assertEquals(AnalysisOutcome.COMPLETED.name, loaded.first.status)
    }

    @Test
    fun siLaSesionSeBloqueaElResultadoSeGuardaAlDesbloquear() {
        val coordinator = container.coordinator
        coordinator.select(zip("p.zip", mapOf("a.txt" to "a")))
        await(AnalysisState.Ready::class.java)
        container.session.lock()
        coordinator.start(ExclusionOptions())
        val finished = await(AnalysisState.Finished::class.java)
        assertFalse(finished.saved)

        val key = (container.vault.unlockWithPassword("Funcode-2026".toCharArray()) as com.funcodestudiocb.analyzer.data.vault.UnlockResult.Success).databaseKey
        // Un bloqueo inmediato tras desbloquear (incluso a mitad del guardado) no debe perder el resultado: se
        // reintenta en el siguiente desbloqueo. (En estas pruebas cada apertura crea una BD en memoria nueva.)
        container.session.unlock(key)
        container.session.lock()
        val again = (container.vault.unlockWithPassword("Funcode-2026".toCharArray()) as com.funcodestudiocb.analyzer.data.vault.UnlockResult.Success).databaseKey
        container.session.unlock(again)
        awaitSaved()
    }

    @Test
    fun formatosNoAdmitidosSeRechazanSinCopiaTemporal() {
        val file = File(context.cacheDir, "falso.zip").apply { writeText("esto no es un zip") }
        container.coordinator.select(Uri.fromFile(file))
        val failed = await(AnalysisState.Failed::class.java)
        assertEquals(FailureReason.UNSUPPORTED, failed.reason)
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun descartarBorraLaCopiaTemporal() {
        container.coordinator.select(zip("d.zip", mapOf("a.txt" to "a")))
        await(AnalysisState.Ready::class.java)
        container.coordinator.discard()
        assertEquals(AnalysisState.Idle, container.coordinator.state.value)
        assertTrue(importedFiles().isEmpty())
    }

    @Test
    fun lasExclusionesElegidasSeRecuerdan() {
        container.coordinator.select(zip("e.zip", mapOf("gen/a.kt" to "a", "src/b.kt" to "b")))
        await(AnalysisState.Ready::class.java)
        val custom = ExclusionOptions(directoryNames = listOf("gen"))
        container.coordinator.start(custom)
        val finished = await(AnalysisState.Finished::class.java)
        assertEquals("gen", finished.report.excludedDirectories.single().path)
        assertEquals(custom, runBlocking { container.repository.loadExclusions() })
    }

    @Test
    fun laCopiaDelCodigoSeGuardaSinSecretosYElTemporalSeBorra() {
        val secret = "abcd1234efgh5678ijkl"
        container.coordinator.select(
            zip(
                "app.zip",
                mapOf(
                    "demo/src/Config.kt" to "package demo\nval apiKey = \"$secret\"\n",
                    "demo/src/Main.kt" to "fun main() {\n  println(1)\n}\n",
                    "demo/package.json" to "{ \"main\": \"index.js\" }",
                ),
            ),
        )
        await(AnalysisState.Ready::class.java)
        container.coordinator.start(ExclusionOptions())
        val finished = await(AnalysisState.Finished::class.java)
        awaitSaved()
        val report = finished.report
        assertNotNull(report.inventory)
        assertTrue(report.profiles.isNotEmpty())
        assertEquals("demo/src/Main.kt", report.entryPoints.single { it.kind == com.funcodestudiocb.analyzer.engine.api.EntryPointKind.MAIN_FUNCTION }.path)
        assertNull(report.entryPoints.single { it.kind == com.funcodestudiocb.analyzer.engine.api.EntryPointKind.PACKAGE_MAIN }.path, "index.js no existe: no se inventa")
        assertEquals(1, report.redactions.size)

        val copy = runBlocking { container.repository.content(finished.analysisId, "demo/src/Config.kt") }
        assertNotNull(copy)
        assertFalse(copy.contains(secret), "La copia guardada no contiene el secreto")
        assertTrue(copy.contains("abcd") && copy.contains("kl"))
        assertEquals("fun main() {\n  println(1)\n}\n", runBlocking { container.repository.content(finished.analysisId, "demo/src/Main.kt") })
        // El resaltado de sintaxis se guarda con la copia y vuelve con posiciones válidas.
        val code = assertNotNull(runBlocking { container.repository.code(finished.analysisId, "demo/src/Main.kt") })
        assertTrue(code.highlight.isNotEmpty(), "Sin resaltado")
        for (i in code.highlight.indices step 3) assertTrue(code.highlight[i] + code.highlight[i + 1] <= code.text.length)
        assertEquals("fun", code.text.substring(code.highlight[0], code.highlight[0] + code.highlight[1]))
        assertEquals("Kotlin", report.languages.single { it.id == "kotlin" }.name)
        assertTrue(File(context.noBackupFilesDir, "copia-codigo").listFiles().isNullOrEmpty(), "El almacén temporal cifrado se borra al guardar")

        // Al cargar el análisis guardado se recupera el inventario.
        val loaded = runBlocking { container.repository.load(finished.analysisId) }
        assertNotNull(loaded)
        assertEquals(report.profiles.size, loaded.second.profiles.size)
    }

    @Test
    fun sinCopiaDelCodigoSiSeDesactivaEnAjustes() {
        runBlocking { container.repository.saveKeepCodeCopy(false) }
        container.coordinator.select(zip("nc.zip", mapOf("src/Config.kt" to "val apiKey = \"abcd1234efgh5678ijkl\"\n")))
        await(AnalysisState.Ready::class.java)
        container.coordinator.start(ExclusionOptions())
        val finished = await(AnalysisState.Finished::class.java)
        awaitSaved()
        assertNull(runBlocking { container.repository.content(finished.analysisId, "src/Config.kt") })
        assertEquals(com.funcodestudiocb.analyzer.engine.api.ContentState.NOT_STORED_DISABLED, finished.report.profiles.single().content)
        assertEquals(1, finished.report.redactions.size, "Los posibles secretos se siguen señalando")
        assertFalse(finished.report.inventory!!.copyEnabled)
    }

    @Test
    fun unMotorQueRepiteOExcedeFragmentosNoImpideGuardar() {
        // Motor defectuoso: repite un fragmento y envía una copia aunque esté desactivada.
        val faulty = object : com.funcodestudiocb.analyzer.analysis.AnalysisEngine {
            override suspend fun analyze(
                config: com.funcodestudiocb.analyzer.engine.api.AnalysisConfig,
                archive: File,
                password: CharArray?,
                onProgress: (com.funcodestudiocb.analyzer.engine.core.ProgressUpdate) -> Unit,
                onRecord: (com.funcodestudiocb.analyzer.engine.api.ResultRecord) -> Unit,
            ): com.funcodestudiocb.analyzer.analysis.EngineRun {
                val real = InProcessAnalysisEngine()
                return real.analyze(config, archive, password, onProgress) { record ->
                    onRecord(record)
                    if (record is com.funcodestudiocb.analyzer.engine.api.ContentChunk) onRecord(record)
                }
            }
        }
        File(context.noBackupFilesDir, "vault").deleteRecursively()
        container = testContainer(context, faulty)
        container.session.unlock(container.vault.setup("Funcode-2026".toCharArray()))
        container.coordinator.select(zip("dup.zip", mapOf("a.kt" to "fun main() {}\n")))
        await(AnalysisState.Ready::class.java)
        container.coordinator.start(ExclusionOptions())
        val finished = await(AnalysisState.Finished::class.java)
        awaitSaved()
        assertEquals("fun main() {}\n", runBlocking { container.repository.content(finished.analysisId, "a.kt") }, "El fragmento repetido se ignora")
    }

    private companion object {
        /** Margen amplio: con el equipo cargado, la primera apertura de SQLite en Robolectric tarda varios segundos. */
        const val AWAIT_MS = 30_000L
    }
}

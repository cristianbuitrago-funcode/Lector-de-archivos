package com.funcodestudiocb.analyzer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.funcodestudiocb.analyzer.analysis.AnalysisOutcome
import com.funcodestudiocb.analyzer.analysis.AnalysisState
import com.funcodestudiocb.analyzer.data.vault.UnlockResult
import com.funcodestudiocb.analyzer.engine.api.ArchiveFormat
import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.EvidenceKind
import com.funcodestudiocb.analyzer.engine.api.LineMethod
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.EntryPointKind
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.IngestFindingKind
import com.funcodestudiocb.analyzer.engine.api.SecretType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Análisis reales en un dispositivo o emulador: el comprimido viaja como descriptor al proceso aislado,
 * que lo lee con Commons Compress (ZIP) o con UnRAR nativo (RAR).
 */
@RunWith(AndroidJUnit4::class)
class FullAnalysisInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as FuncodeApp).container
    private val coordinator get() = container.coordinator

    @Before
    fun setUp() {
        container.vault.wipe()
        val key = container.vault.setup("Funcode-2026".toCharArray())
        container.session.unlock(key)
    }

    @After
    fun tearDown() {
        coordinator.reset()
        container.vault.wipe()
    }

    private fun <T : AnalysisState> await(type: Class<T>, timeoutMs: Long = 120_000): T = runBlocking {
        @Suppress("UNCHECKED_CAST")
        withTimeoutOrNull(timeoutMs) { coordinator.state.first { type.isInstance(it) } as T }
            ?: fail("No se llegó a ${type.simpleName}; estado: ${coordinator.state.value}")
    }

    private fun asset(name: String): Uri {
        val file = File(context.cacheDir, name)
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    private fun analyze(uri: Uri): AnalysisState {
        coordinator.select(uri)
        await(AnalysisState.Ready::class.java)
        coordinator.start(ExclusionOptions())
        return runBlocking {
            withTimeoutOrNull(120_000) {
                coordinator.state.first { it is AnalysisState.Finished || it is AnalysisState.NeedsPassword || it is AnalysisState.Failed }
            } ?: fail("El análisis no terminó; estado: ${coordinator.state.value}")
        }
    }

    @Test
    fun zipCompletoEnElProcesoAislado() {
        val file = File(context.cacheDir, "proyecto.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            mapOf(
                "src/App.kt" to "fun main() = println(\"hola\")",
                "src/copia/App.kt" to "fun main() = println(\"hola\")",
                "src/Config.kt" to "val apiKey = \"abcd1234efgh5678ijkl\"\n",
                "web/package.json" to "{\"dependencies\": {\"express\": \"^4.19.0\"}}",
                "web/server.js" to "// servidor\nconst express = require(\"express\");\nconst util = require(\"./util\");\n",
                "web/util.js" to "module.exports = {};\n",
                "node_modules/react/index.js" to "x",
                "../fuera.txt" to "malicioso",
            ).forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        val finished = analyze(Uri.fromFile(file)) as? AnalysisState.Finished ?: fail("Estado inesperado: ${coordinator.state.value}")
        val diagnosis = "problema=${finished.report.engineProblem}; resultado=${finished.outcome}; etapas=${finished.report.stages}; cobertura=${finished.report.coverage}"
        assertEquals(ArchiveFormat.ZIP, finished.report.archive?.format)
        assertEquals(setOf("src/App.kt", "src/copia/App.kt", "src/Config.kt", "web/package.json", "web/server.js", "web/util.js"), finished.report.files.map { it.path }.toSet(), diagnosis)
        assertTrue(finished.report.files.all { it.status == FileStatus.ANALYZED })
        assertEquals(1, finished.report.duplicates.size)
        assertEquals("node_modules", finished.report.excludedDirectories.single().path)
        assertTrue(finished.report.findings.any { it.kind == IngestFindingKind.DANGEROUS_PATH })
        assertTrue(File(context.noBackupFilesDir, "import").listFiles().isNullOrEmpty(), "La copia temporal debe borrarse")
        runBlocking { withTimeoutOrNull(10_000) { coordinator.state.first { (it as? AnalysisState.Finished)?.saved == true } } }
            ?: fail("No se guardó en el historial")

        // Inventario y copia redactada producidos en el proceso aislado (Fase 3).
        assertTrue(finished.report.entryPoints.any { it.kind == EntryPointKind.MAIN_FUNCTION && it.path == "src/App.kt" })
        assertTrue(finished.report.profiles.single { it.path == "src/App.kt" }.roles.any { it.role == FileRole.SOURCE })
        assertEquals(SecretType.SECRET, finished.report.redactions.single().type)
        val copy = runBlocking { container.repository.content(finished.analysisId, "src/Config.kt") } ?: fail("Sin copia del código")
        assertFalse(copy.contains("abcd1234efgh5678ijkl"), "El secreto no sale del proceso aislado")
        assertTrue(File(context.noBackupFilesDir, "copia-codigo").listFiles().isNullOrEmpty(), "El almacén temporal cifrado debe borrarse")

        // Lenguajes con tree-sitter nativo y tecnologías, en el proceso aislado (Fase 4).
        val kotlin = finished.report.languages.single { it.id == "kotlin" }
        assertEquals(3, kotlin.syntaxFiles, "tree-sitter no se cargó en el proceso aislado: ${finished.report.coverage}")
        val server = finished.report.profiles.single { it.path == "web/server.js" }.language ?: fail("Sin lenguaje")
        assertEquals(LineMethod.SYNTAX, server.method)
        assertEquals(1, server.comment)
        val express = finished.report.technologies.single { it.id == "express" }
        assertEquals(Confidence.CONFIRMED, express.confidence)
        assertTrue(express.evidence.any { it.kind == EvidenceKind.IMPORT && it.path == "web/server.js" && it.line == 2 }, express.toString())
        val code = runBlocking { container.repository.code(finished.analysisId, "web/server.js") } ?: fail("Sin copia")
        assertTrue(code.highlight.isNotEmpty(), "Sin resaltado de sintaxis")

        // Relaciones entre archivos calculadas en el proceso aislado (Fase 5A).
        val util = finished.report.relations.single { it.source == "web/server.js" && it.target == "web/util.js" }
        assertEquals(RelationConfidence.VERIFIED, util.confidence)
        assertEquals(3, util.line)
        assertEquals(1, util.impact.directDependents)
        assertTrue(finished.report.relations.any { it.source == "web/server.js" && it.external == "express" && it.confidence == RelationConfidence.VERIFIED })
        assertEquals(1, finished.report.fileGraphs.single { it.path == "web/util.js" }.incoming)
    }

    @Test
    fun rarConUnrarNativoEnElProcesoAislado() {
        val finished = analyze(asset("test_read_format_rar5_compressed.rar")) as? AnalysisState.Finished
            ?: fail("Estado inesperado: ${coordinator.state.value}")
        assertEquals(ArchiveFormat.RAR5, finished.report.archive?.format)
        assertTrue(finished.report.files.isNotEmpty())
        assertTrue(finished.report.files.all { it.status == FileStatus.ANALYZED && it.sha256 != null }, finished.report.files.toString())
        assertEquals(AnalysisOutcome.COMPLETED, finished.outcome)
    }

    @Test
    fun rarConNombresCifradosPideLaContrasena() {
        val needs = analyze(asset("test_read_format_rar5_encrypted_filenames.rar")) as? AnalysisState.NeedsPassword
            ?: fail("Se esperaba la petición de contraseña; estado: ${coordinator.state.value}")
        assertEquals(false, needs.wrongPassword)

        coordinator.start(ExclusionOptions(), "password".toCharArray())
        val finished = await(AnalysisState.Finished::class.java)
        assertEquals(AnalysisOutcome.COMPLETED, finished.outcome)
        assertTrue(finished.report.files.all { it.status == FileStatus.ANALYZED })
    }

    @Suppress("unused")
    private fun unlock() = (container.vault.unlockWithPassword("Funcode-2026".toCharArray()) as UnlockResult.Success).databaseKey
}

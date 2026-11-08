package com.funcodestudiocb.analyzer.engine.ingest

import com.funcodestudiocb.analyzer.engine.api.ArchiveFormat
import com.funcodestudiocb.analyzer.engine.api.ArchiveSummary
import com.funcodestudiocb.analyzer.engine.api.EncryptionState
import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.IngestFindingKind
import com.funcodestudiocb.analyzer.engine.api.PasswordRequired
import com.funcodestudiocb.analyzer.engine.api.RejectionReason
import com.funcodestudiocb.analyzer.engine.api.SessionOutcome
import com.funcodestudiocb.analyzer.engine.ingest.rar.UnrarNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Lector RAR contra el corpus de libarchive (ver test/resources/corpus/rar/README.md). */
class RarIngestTest {

    private fun rar(name: String) = resource("corpus/rar/$name")

    @Test
    fun `la biblioteca nativa carga y es UnRAR`() {
        UnrarNative.ensureLoaded()
        assertTrue(UnrarNative.nativeDllVersion() >= 8)
    }

    @Test
    fun `rar5 comprimido se lee completo`() {
        val run = runIngest(rar("test_read_format_rar5_compressed.rar"))
        assertEquals(SessionOutcome.COMPLETED, run.outcome)
        val summary = run.all<ArchiveSummary>().single()
        assertEquals(ArchiveFormat.RAR5, summary.format)
        val files = run.files().values
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { it.status == FileStatus.ANALYZED && it.sha256 != null }, files.toString())
    }

    @Test
    fun `rar4 con enlace simbolico`() {
        val run = runIngest(rar("test_read_format_rar.rar"))
        assertEquals(ArchiveFormat.RAR4, run.all<ArchiveSummary>().single().format)
        assertTrue(run.findings(IngestFindingKind.SYMLINK).isNotEmpty(), run.records.toString())
        assertTrue(run.files().values.any { it.status == FileStatus.ANALYZED })
    }

    @Test
    fun `rar5 con enlaces simbolicos y duros que nunca se siguen`() {
        assertTrue(runIngest(rar("test_read_format_rar5_symlink.rar")).findings(IngestFindingKind.SYMLINK).isNotEmpty())
        assertTrue(runIngest(rar("test_read_format_rar5_hardlink.rar")).findings(IngestFindingKind.HARDLINK).isNotEmpty())
    }

    @Test
    fun `apertura por descriptor heredado (parche de UnRAR para el proceso aislado)`() {
        val byPath = runIngest(rar("test_read_format_rar5_compressed.rar"))
        val byFd = runIngest(rar("test_read_format_rar5_compressed.rar"), useFileDescriptor = true)
        assertEquals(byPath.files(), byFd.files())
    }

    @Test
    fun `rar4 parcialmente cifrado sin contrasena, con la correcta y con otra`() {
        val file = rar("test_read_format_rar4_encrypted.rar")
        val none = runIngest(file)
        assertEquals(EncryptionState.PARTIAL, none.all<ArchiveSummary>().single().encryption)
        assertEquals(FileStatus.ANALYZED, none.files().getValue("a.txt").status)
        assertEquals(FileStatus.ENCRYPTED, none.files().getValue("b.txt").status)
        assertEquals(false, none.all<PasswordRequired>().single().wrongPassword)

        val right = runIngest(file, password = "password")
        assertEquals(FileStatus.ANALYZED, right.files().getValue("b.txt").status)
        // d.txt usa otra contraseña ("password2"): con RAR4 no se distingue de datos dañados.
        assertTrue(right.files().getValue("d.txt").status in setOf(FileStatus.CORRUPT, FileStatus.ENCRYPTED))
    }

    @Test
    fun `rar5 con nombres cifrados exige la contrasena para listar`() {
        val file = rar("test_read_format_rar5_encrypted_filenames.rar")
        val none = runIngest(file)
        assertEquals(RejectionReason.PASSWORD_REQUIRED, none.rejection()?.reason)
        assertEquals(false, none.all<PasswordRequired>().single().wrongPassword)

        val wrong = runIngest(file, password = "otra")
        assertEquals(RejectionReason.WRONG_PASSWORD, wrong.rejection()?.reason)
        assertEquals(true, wrong.all<PasswordRequired>().single().wrongPassword)

        val right = runIngest(file, password = "password")
        assertEquals(EncryptionState.HEADERS, right.all<ArchiveSummary>().single().encryption)
        assertTrue(right.files().values.all { it.status == FileStatus.ANALYZED }, right.files().toString())
    }

    @Test
    fun `rar5 solido y cifrado se lee en orden con la contrasena`() {
        val run = runIngest(rar("test_read_format_rar5_solid_encrypted.rar"), password = "password")
        assertTrue(run.all<ArchiveSummary>().single().solid)
        assertTrue(run.files().values.all { it.status == FileStatus.ANALYZED }, run.files().toString())
    }

    @Test
    fun `multivolumen se rechaza`() {
        assertEquals(RejectionReason.MULTI_VOLUME, runIngest(rar("test_read_format_rar5_multiarchive.part01.rar")).rejection()?.reason)
        assertEquals(RejectionReason.MULTI_VOLUME, runIngest(rar("test_read_format_rar_multivolume.part0001.rar")).rejection()?.reason)
    }

    @Test
    fun `zip dentro de rar se registra sin expandirse`() {
        val run = runIngest(rar("test_read_format_rar5_zip_in_rar.rar"))
        assertTrue(run.files().values.any { it.kind == FileKind.ARCHIVE })
        assertTrue(run.findings(IngestFindingKind.NESTED_ARCHIVE).isNotEmpty())
    }

    @Test
    fun `archivos danados o disenados para provocar fallos no tumban el motor`() {
        val hostile = listOf(
            "test_read_format_rar5_truncated_huff.rar",
            "test_read_format_rar5_distance_overflow.rar",
            "test_read_format_rar5_invalid_dict_reference.rar",
            "test_read_format_rar5_window_buf_and_size_desync.rar",
            "test_read_format_rar_ppmd_use_after_free.rar",
            "test_read_format_rar5_unpacked_size_exceeds_declared.rar",
        )
        for (name in hostile) {
            // Cualquier resultado es aceptable salvo un fallo del proceso: el flujo debe ser válido y completo.
            val run = runIngest(rar(name))
            assertNotNull(run.outcome, name)
            val damaged = run.rejection() != null ||
                run.files().values.any { it.status == FileStatus.CORRUPT || it.status == FileStatus.UNSUPPORTED_METHOD } ||
                run.findings(IngestFindingKind.SIZE_MISMATCH).isNotEmpty() ||
                run.findings(IngestFindingKind.CORRUPT_ENTRY).isNotEmpty()
            assertTrue(damaged, "Se esperaba detectar el daño en $name: ${run.records}")
        }
    }

    @Test
    fun `rar sin marca de final pero con datos intactos se lee`() {
        val run = runIngest(rar("test_read_format_rar_noeof.rar"))
        assertEquals(FileStatus.ANALYZED, run.files().getValue("test.txt").status)
    }

    @Test
    fun `rar5 almacenado con texto y binario`() {
        val run = runIngest(rar("test_read_format_rar5_stored_manyfiles.rar"))
        val files = run.files()
        assertEquals(setOf("make_uue.tcl", "cebula.txt", "test.bin"), files.keys)
        assertTrue(files.values.all { it.status == FileStatus.ANALYZED })
        assertEquals(FileKind.BINARY, files.getValue("test.bin").kind)
        assertEquals("UTF-8", files.getValue("cebula.txt").encoding)
    }
}

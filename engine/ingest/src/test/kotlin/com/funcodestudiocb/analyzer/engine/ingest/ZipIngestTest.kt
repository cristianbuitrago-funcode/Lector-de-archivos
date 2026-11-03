package com.funcodestudiocb.analyzer.engine.ingest

import com.funcodestudiocb.analyzer.engine.api.ArchiveSummary
import com.funcodestudiocb.analyzer.engine.api.DuplicateGroup
import com.funcodestudiocb.analyzer.engine.api.EncryptionState
import com.funcodestudiocb.analyzer.engine.api.ExcludedDirectory
import com.funcodestudiocb.analyzer.engine.api.ExcludedFile
import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.IngestFindingKind
import com.funcodestudiocb.analyzer.engine.api.IngestLimits
import com.funcodestudiocb.analyzer.engine.api.IngestSummary
import com.funcodestudiocb.analyzer.engine.api.PasswordRequired
import com.funcodestudiocb.analyzer.engine.api.RejectionReason
import com.funcodestudiocb.analyzer.engine.api.SessionOutcome
import com.funcodestudiocb.analyzer.engine.api.StageEvent
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.AesVersion
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipIngestTest {

    @TempDir
    lateinit var dir: File

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (n, data) ->
                out.putNextEntry(ZipEntry(n))
                out.write(data)
                out.closeEntry()
            }
        }
        return file
    }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun `proyecto normal con exclusiones, temporales y duplicados`() {
        val app = "fun main() = println(\"hola\")".toByteArray()
        val file = zip(
            "proyecto.zip",
            mapOf(
                "src/App.kt" to app,
                "src/copia/App.kt" to app,
                "README.md" to "# Proyecto ñ".toByteArray(),
                "logo.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0),
                "vacio.txt" to ByteArray(0),
                "node_modules/react/index.js" to ByteArray(1000) { 'x'.code.toByte() },
                "node_modules/react/package.json" to "{}".toByteArray(),
                ".git/config" to "[core]".toByteArray(),
                "src/.DS_Store" to ByteArray(10),
            ),
        )
        val run = runIngest(file)
        assertEquals(SessionOutcome.COMPLETED, run.outcome)

        val files = run.files()
        assertEquals(setOf("src/App.kt", "src/copia/App.kt", "README.md", "logo.png", "vacio.txt"), files.keys)
        val appEntry = files.getValue("src/App.kt")
        assertEquals(FileStatus.ANALYZED, appEntry.status)
        assertEquals(sha256(app), appEntry.sha256)
        assertEquals(FileKind.TEXT, appEntry.kind)
        assertEquals("UTF-8", files.getValue("README.md").encoding)
        assertEquals(FileKind.BINARY, files.getValue("logo.png").kind)
        assertEquals(FileKind.EMPTY, files.getValue("vacio.txt").kind)

        val excluded = run.all<ExcludedDirectory>().associateBy { it.path }
        assertEquals(2, excluded.getValue("node_modules").fileCount)
        assertEquals(1002, excluded.getValue("node_modules").totalBytes)
        assertEquals(1, excluded.getValue(".git").fileCount)
        assertEquals(listOf("src/.DS_Store"), run.all<ExcludedFile>().map { it.path })

        val duplicates = run.all<DuplicateGroup>().single()
        assertEquals(setOf("src/App.kt", "src/copia/App.kt"), duplicates.paths.toSet())

        val summary = run.all<IngestSummary>().single()
        assertEquals(5, summary.files)
        assertEquals(5, summary.analyzedFiles)
        assertEquals(4, summary.excludedFiles)
        assertEquals(2, summary.excludedDirectories)
        assertEquals(1, summary.duplicateGroups)
        assertEquals(EncryptionState.NONE, run.all<ArchiveSummary>().single().encryption)
    }

    @Test
    fun `nombres peligrosos, enlaces, rutas repetidas y colisiones se registran y no se analizan`() {
        val bytes = RawZip()
            .stored("../../evil.sh", "rm -rf /".toByteArray())
            .stored("/etc/passwd", "root".toByteArray())
            .stored("C:\\Windows\\x.dll", "x".toByteArray())
            .stored("ok/a.txt", "a".toByteArray())
            .stored("ok/a.txt", "otra".toByteArray())
            .stored("ok/A.txt", "b".toByteArray())
            .stored("enlace", "/etc/shadow".toByteArray(), unixMode = 0xA1FF)
            .stored("factura\u202Efdp.exe", "x".toByteArray())
            .build()
        val run = runIngest(write("malicioso.zip", bytes))
        assertEquals(3, run.findings(IngestFindingKind.DANGEROUS_PATH).size)
        assertEquals(1, run.findings(IngestFindingKind.SYMLINK).size)
        assertEquals(1, run.findings(IngestFindingKind.DUPLICATE_PATH).size)
        assertEquals(1, run.findings(IngestFindingKind.CASE_COLLISION).size)
        assertEquals(1, run.findings(IngestFindingKind.DECEPTIVE_NAME).size)
        val files = run.files()
        assertEquals(setOf("ok/a.txt", "ok/A.txt", "factura\\u202Efdp.exe"), files.keys)
        assertEquals(sha256("a".toByteArray()), files.getValue("ok/a.txt").sha256, "Solo se analiza la primera aparición")
    }

    @Test
    fun `entradas solapadas (bomba no recursiva) se rechazan`() {
        val bytes = RawZip().stored("a.txt", ByteArray(100)).stored("b.txt", ByteArray(100)).build(pointAtFirst = setOf(1))
        val run = runIngest(write("solapado.zip", bytes))
        assertEquals(RejectionReason.OVERLAPPING_ENTRIES, run.rejection()?.reason)
        assertTrue(run.all<StageEvent>().any { it.stageId == "ingest.read" && it.status == StageStatus.SKIPPED })
        assertTrue(run.files().isEmpty())
    }

    @Test
    fun `bomba de compresion se detiene durante la lectura`() {
        val file = zip("bomba.zip", mapOf("ceros.bin" to ByteArray(5 * 1024 * 1024), "ok.txt" to "ok".toByteArray()))
        val run = runIngest(file)
        assertEquals(FileStatus.SKIPPED_BOMB, run.files().getValue("ceros.bin").status)
        assertEquals(1, run.findings(IngestFindingKind.COMPRESSION_BOMB).size)
        assertEquals(FileStatus.ANALYZED, run.files().getValue("ok.txt").status)
    }

    @Test
    fun `tamanos declarados falsos no se creen`() {
        // Declara 10 bytes pero contiene 3 MiB.
        val bytes = RawZip().deflated("mentira.txt", ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() }, declaredSize = 10).build()
        val limits = IngestLimits(maxEntryBytes = 1024 * 1024, maxEntryRatio = 100_000)
        val run = runIngest(write("mentira.zip", bytes), limits = limits)
        assertEquals(FileStatus.SKIPPED_TOO_LARGE, run.files().getValue("mentira.txt").status)
        assertTrue(run.findings(IngestFindingKind.SIZE_MISMATCH).isNotEmpty())
    }

    @Test
    fun `el limite total de bytes reales se aplica`() {
        val entries = (1..5).associate { "f$it.bin" to ByteArray(400 * 1024) { i -> (i * 31 + it).toByte() } }
        val limits = IngestLimits(maxTotalUncompressedBytes = 1024 * 1024, maxEntryBytes = 1024 * 1024)
        val run = runIngest(zip("total.zip", entries), limits = limits)
        val statuses = run.files().values.map { it.status }
        assertTrue(statuses.count { it == FileStatus.ANALYZED } in 1..2)
        assertTrue(statuses.count { it == FileStatus.SKIPPED_LIMIT } >= 3)
        assertEquals(1, run.findings(IngestFindingKind.TOTAL_LIMIT_REACHED).size)
    }

    @Test
    fun `proporcion global declarada anomala se rechaza antes de leer`() {
        val bytes = RawZip().stored("a.bin", ByteArray(10), declaredSize = 900L * 1024 * 1024).build()
        val run = runIngest(write("global.zip", bytes))
        assertEquals(RejectionReason.COMPRESSION_BOMB, run.rejection()?.reason)
    }

    @Test
    fun `registro final manipulado, multidisco, truncado y formatos no admitidos`() {
        val many = RawZip().stored("a.txt", "a".toByteArray()).build(totalEntriesOverride = 65_000)
        assertEquals(RejectionReason.TOO_MANY_ENTRIES, runIngest(write("muchas.zip", many), limits = IngestLimits(maxEntries = 1000)).rejection()?.reason)

        val multi = RawZip().stored("a.txt", "a".toByteArray()).build(diskNumber = 2)
        assertEquals(RejectionReason.MULTI_VOLUME, runIngest(write("multi.zip", multi)).rejection()?.reason)

        val good = zip("bueno.zip", mapOf("a.txt" to ByteArray(5000) { 'a'.code.toByte() }))
        val truncated = write("truncado.zip", good.readBytes().copyOf(good.length().toInt() - 30))
        assertEquals(RejectionReason.CORRUPT, runIngest(truncated).rejection()?.reason)

        val sevenZip = write("falso.zip", byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4, 1, 2, 3))
        assertEquals(RejectionReason.UNSUPPORTED_FORMAT, runIngest(sevenZip).rejection()?.reason)
        assertEquals(RejectionReason.UNSUPPORTED_FORMAT, runIngest(write("texto.rar", "no soy un rar".toByteArray())).rejection()?.reason)
    }

    @Test
    fun `cabecera local distinta del directorio central`() {
        val bytes = RawZip().stored("inocente.txt", "x".toByteArray(), localName = "malvado.sh").build()
        val run = runIngest(write("cabecera.zip", bytes))
        assertEquals(1, run.findings(IngestFindingKind.LOCAL_HEADER_MISMATCH).size)
    }

    @Test
    fun `comprimido anidado se registra sin expandirse`() {
        val inner = zip("interno.zip", mapOf("x.txt" to "x".toByteArray())).readBytes()
        val run = runIngest(zip("externo.zip", mapOf("libs/interno.zip" to inner)))
        assertEquals(FileKind.ARCHIVE, run.files().getValue("libs/interno.zip").kind)
        assertEquals(1, run.findings(IngestFindingKind.NESTED_ARCHIVE).size)
        assertNull(run.files()["x.txt"])
    }

    private fun encrypted(name: String, method: EncryptionMethod, aesVersion: AesVersion = AesVersion.TWO, strength: AesKeyStrength = AesKeyStrength.KEY_STRENGTH_256): File {
        val file = File(dir, name)
        val zip = ZipFile(file, "Secreta-123".toCharArray())
        val secret = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = method
            aesKeyStrength = strength
            this.aesVersion = aesVersion
            compressionMethod = CompressionMethod.DEFLATE
            fileNameInZip = "secreto.kt"
        }
        zip.addStream("val clave = 42\n".repeat(50).byteInputStream(), secret)
        val plain = ZipParameters().apply { fileNameInZip = "publico.txt" }
        zip.addStream("visible".byteInputStream(), plain)
        zip.close()
        return file
    }

    @Test
    fun `zip cifrados ZipCrypto, AES-1 y AES-2 con y sin contrasena`() {
        val expected = sha256("val clave = 42\n".repeat(50).toByteArray())
        val variants = listOf(
            encrypted("zipcrypto.zip", EncryptionMethod.ZIP_STANDARD),
            encrypted("aes1-128.zip", EncryptionMethod.AES, AesVersion.ONE, AesKeyStrength.KEY_STRENGTH_128),
            encrypted("aes2-256.zip", EncryptionMethod.AES, AesVersion.TWO, AesKeyStrength.KEY_STRENGTH_256),
        )
        for (file in variants) {
            val none = runIngest(file)
            assertEquals(EncryptionState.PARTIAL, none.all<ArchiveSummary>().single().encryption, file.name)
            assertEquals(FileStatus.ENCRYPTED, none.files().getValue("secreto.kt").status, file.name)
            assertEquals(FileStatus.ANALYZED, none.files().getValue("publico.txt").status, file.name)
            assertEquals(false, none.all<PasswordRequired>().single().wrongPassword, file.name)

            val wrong = runIngest(file, password = "incorrecta")
            assertEquals(FileStatus.ENCRYPTED, wrong.files().getValue("secreto.kt").status, file.name)
            assertEquals(true, wrong.all<PasswordRequired>().single().wrongPassword, file.name)

            val right = runIngest(file, password = "Secreta-123")
            val entry = right.files().getValue("secreto.kt")
            assertEquals(FileStatus.ANALYZED, entry.status, file.name)
            assertEquals(expected, entry.sha256, file.name)
            assertTrue(right.all<PasswordRequired>().isEmpty(), file.name)
        }
    }

    @Test
    fun `todos los registros llevan rutas seguras`() {
        val run = runIngest(write("raro.zip", RawZip().stored("dir/a\u202Eb.txt", "x".toByteArray()).build()))
        assertNotNull(run.files()["dir/a\\u202Eb.txt"])
    }
}

package com.funcodestudiocb.analyzer

import com.funcodestudiocb.analyzer.analysis.ContentSpool
import com.funcodestudiocb.analyzer.analysis.SpoolUnreadableException
import com.funcodestudiocb.analyzer.engine.api.ContentChunk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentSpoolTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val chunks = listOf(
        ContentChunk("src/A.kt", 0, "fun main() {\n", last = false),
        ContentChunk("src/A.kt", 1, "  println(\"ñandú ✓\")\n}\n", last = true),
        ContentChunk("README.md", 0, "# Demo\n", last = true),
    )

    @Test
    fun guardaCifradoYDevuelveLoMismoEnOrden() {
        val dir = folder.newFolder("spool")
        val spool = ContentSpool.create(dir)
        chunks.forEach(spool::append)
        assertEquals(3, spool.chunks)
        val file = dir.listFiles()!!.single()
        val bytes = file.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("println"), "En disco no hay texto en claro")
        assertEquals(chunks, spool.read().toList())
        assertEquals(chunks, spool.read().toList(), "Se puede leer más de una vez (reintentos de guardado)")
        spool.close()
        assertFalse(file.exists(), "Al cerrar se borra el archivo")
        assertFailsWith<SpoolUnreadableException> { spool.read().toList() }
    }

    @Test
    fun cualquierAlteracionSeDetecta() {
        val dir = folder.newFolder("spool2")
        val spool = ContentSpool.create(dir)
        chunks.forEach(spool::append)
        spool.finishWriting()
        val file = dir.listFiles()!!.single()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(10)
            val b = raf.read()
            raf.seek(10)
            raf.write(b xor 0x01)
        }
        assertFailsWith<SpoolUnreadableException> { spool.read().toList() }
        // Truncado.
        val spool2 = ContentSpool.create(dir)
        chunks.forEach(spool2::append)
        spool2.finishWriting()
        val file2 = dir.listFiles()!!.first { it != file }
        RandomAccessFile(file2, "rw").use { it.setLength(it.length() - 5) }
        assertFailsWith<SpoolUnreadableException> { spool2.read().toList() }
        spool.close()
        spool2.close()
    }

    @Test
    fun losHuerfanosSeBorran() {
        val dir = folder.newFolder("spool3")
        val spool = ContentSpool.create(dir)
        spool.append(chunks[0])
        spool.finishWriting()
        assertTrue(dir.listFiles()!!.isNotEmpty())
        ContentSpool.cleanupOrphans(dir)
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}

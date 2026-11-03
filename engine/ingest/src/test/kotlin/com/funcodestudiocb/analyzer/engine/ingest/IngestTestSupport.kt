package com.funcodestudiocb.analyzer.engine.ingest

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.ArchiveRejected
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.engine.api.FileEntry
import com.funcodestudiocb.analyzer.engine.api.FrameReader
import com.funcodestudiocb.analyzer.engine.api.FrameWriter
import com.funcodestudiocb.analyzer.engine.api.IngestFinding
import com.funcodestudiocb.analyzer.engine.api.IngestFindingKind
import com.funcodestudiocb.analyzer.engine.api.IngestLimits
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionOutcome
import com.funcodestudiocb.analyzer.engine.api.SessionReader
import com.funcodestudiocb.analyzer.engine.core.Attributes
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.test.assertTrue
import kotlin.test.fail

/** Resultado de ejecutar la ingesta sobre un archivo, ya validado como lo haría el proceso principal. */
class IngestRun(val records: List<ResultRecord>, val outcome: SessionOutcome?) {
    inline fun <reified T : ResultRecord> all(): List<T> = records.filterIsInstance<T>()
    fun files(): Map<String, FileEntry> = all<FileEntry>().associateBy { it.path }
    fun findings(kind: IngestFindingKind): List<IngestFinding> = all<IngestFinding>().filter { it.kind == kind }
    fun rejection(): ArchiveRejected? = all<ArchiveRejected>().firstOrNull()
}

fun runIngest(
    file: File,
    password: String? = null,
    limits: IngestLimits = IngestLimits(),
    exclusions: ExclusionOptions = ExclusionOptions(),
    useFileDescriptor: Boolean = false,
): IngestRun {
    val stream = FileInputStream(file)
    val rarName = if (useFileDescriptor) "funcode-fd:${fdNumber(stream)}" else file.absolutePath
    val attributes = Attributes()
    attributes[IngestKeys.SOURCE] = ArchiveSource(stream.channel, rarName)
    attributes[IngestKeys.PASSWORD] = attributes.closeLater(PasswordHolder(password?.toCharArray()))
    attributes.closeLater(stream)
    val config = AnalysisConfig("prueba", AnalysisMode.FULL, ingest = IngestOptions(file.name, limits, exclusions))

    // Se pasa por el mismo protocolo y validación que en la app: tramas -> lector de sesión.
    val bytes = ByteArrayOutputStream()
    val writer = FrameWriter(bytes)
    runBlocking { PipelineRunner("test").run(config, IngestPipeline.stages(), writer::write, attributes) }
    writer.close()
    val session = SessionReader(FrameReader(ByteArrayInputStream(bytes.toByteArray()))).readAll()
    if (session.violation != null) fail("El motor produjo un flujo inválido: ${session.violation}")
    session.records.forEach { r -> assertTrue(RecordValidator.validate(r).isEmpty(), "Registro inválido: $r") }
    return IngestRun(session.records, session.end?.outcome)
}

private fun fdNumber(stream: FileInputStream): Int {
    val field = java.io.FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }
    return field.getInt(stream.fd)
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun resource(path: String): File =
    File(requireNotNull(IngestRun::class.java.classLoader.getResource(path)) { "Falta el recurso $path" }.toURI())

/**
 * Constructor de ZIP "a mano" para fabricar archivos malformados o maliciosos que las bibliotecas normales
 * se niegan a crear (nombres peligrosos, tamaños falsos, entradas solapadas, registros finales alterados).
 */
class RawZip {
    class Entry(
        val name: ByteArray,
        val data: ByteArray,
        val method: Int,
        val crc: Long,
        val uncompressedSize: Long,
        val unixMode: Int? = null,
        val localName: ByteArray? = null,
    )

    val entries = mutableListOf<Entry>()

    fun stored(name: String, content: ByteArray, declaredSize: Long = content.size.toLong(), unixMode: Int? = null, localName: String? = null, method: Int = 0) = apply {
        entries += Entry(name.toByteArray(), content, method, crc(content), declaredSize, unixMode, localName?.toByteArray())
    }

    fun deflated(name: String, content: ByteArray, declaredSize: Long = content.size.toLong()) = apply {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(content)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        entries += Entry(name.toByteArray(), out.toByteArray(), 8, crc(content), declaredSize)
    }

    /**
     * @param pointAtFirst índices de entradas cuyo registro central apunta a la cabecera local de la primera
     *   (técnica de las bombas no recursivas con entradas solapadas).
     */
    fun build(pointAtFirst: Set<Int> = emptySet(), diskNumber: Int = 0, totalEntriesOverride: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Long>()
        for (e in entries) {
            offsets += out.size().toLong()
            val name = e.localName ?: e.name
            out.write(le32(0x04034b50)); out.write(le16(20)); out.write(le16(0x0800)); out.write(le16(e.method))
            out.write(le16(0)); out.write(le16(0x21)); out.write(le32(e.crc.toInt()))
            out.write(le32(e.data.size)); out.write(le32(e.uncompressedSize.toInt()))
            out.write(le16(name.size)); out.write(le16(0)); out.write(name); out.write(e.data)
        }
        val cdStart = out.size()
        entries.forEachIndexed { i, e ->
            val versionMadeBy = if (e.unixMode != null) (3 shl 8) or 20 else 20
            val external = (e.unixMode ?: 0).toLong() shl 16
            out.write(le32(0x02014b50)); out.write(le16(versionMadeBy)); out.write(le16(20)); out.write(le16(0x0800))
            out.write(le16(e.method)); out.write(le16(0)); out.write(le16(0x21)); out.write(le32(e.crc.toInt()))
            out.write(le32(e.data.size)); out.write(le32(e.uncompressedSize.toInt())); out.write(le16(e.name.size))
            out.write(le16(0)); out.write(le16(0)); out.write(le16(0)); out.write(le16(0)); out.write(le32(external.toInt()))
            out.write(le32((if (i in pointAtFirst) offsets[0] else offsets[i]).toInt())); out.write(e.name)
        }
        val cdSize = out.size() - cdStart
        val total = totalEntriesOverride ?: entries.size
        out.write(le32(0x06054b50)); out.write(le16(diskNumber)); out.write(le16(diskNumber))
        out.write(le16(total)); out.write(le16(total)); out.write(le32(cdSize)); out.write(le32(cdStart)); out.write(le16(0))
        return out.toByteArray()
    }

    private fun crc(data: ByteArray) = CRC32().apply { update(data) }.value
    private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}

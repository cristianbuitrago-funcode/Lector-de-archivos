package com.funcodestudiocb.analyzer.engine.vulndb

import com.funcodestudiocb.analyzer.engine.api.Severity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Aviso de vulnerabilidad de un paquete, tal como se guarda en el índice. */
class Advisory(
    val id: String,
    val aliases: List<String>,
    val summary: String,
    /** `null`: la base de datos no indica severidad ni hay vector CVSS 3.x. */
    val severity: Severity?,
    val score: Double?,
    val vector: String?,
    /** Fecha de publicación (AAAA-MM-DD) o `null`. */
    val published: String?,
    /** Paquete malicioso (avisos MAL- de OSV): cualquier versión es un problema. */
    val malicious: Boolean,
    val cwes: List<String>,
    val ranges: List<AffectedRange>,
    /** Versiones afectadas enumeradas (solo si no hay rangos utilizables). */
    val versions: List<String>,
) {
    val fixed: List<String> get() = Affected.fixedVersions(ranges)
}

/** Ciclo de una versión de un producto según endoflife.date. Las fechas, como AAAA-MM-DD; `true`/`false` si no hay fecha. */
class EolCycle(val cycle: String, val releaseDate: String?, val eol: String?, val support: String?, val latest: String?, val lts: String?)

class EolProduct(val name: String, val label: String, val cycles: List<EolCycle>)

/** Contenido de un ecosistema en el índice. */
class EcosystemInfo(val name: String, val sourceDate: Long, val advisories: Int, val packages: Int)

class IndexInfo(val createdAt: Long, val ecosystems: List<EcosystemInfo>, val eolDate: Long, val eolProducts: Int, val sizeBytes: Long, val sha256: String)

class InvalidIndexException(message: String) : IOException(message)

/**
 * Formato del índice (`.fcv`), pensado para leerse desde un descriptor de solo lectura en el proceso aislado sin
 * cargarlo entero en memoria:
 *
 * ```
 * cabecera   "FCVDB\u0001\r\n", versión, fecha, ecosistemas, productos de fin de soporte, nº de entradas, posición del directorio
 * bloques    un bloque por paquete: clave, nombre y sus avisos
 * directorio entradas de 20 bytes (huella FNV-1a de 64 bits de la clave, posición, longitud), ordenadas por huella
 * cola       SHA-256 de todo lo anterior
 * ```
 *
 * Cada número y cada cadena se comprueban al leer: un archivo dañado o manipulado se rechaza, nunca se interpreta
 * a medias.
 */
object VulnIndex {
    val MAGIC = byteArrayOf('F'.code.toByte(), 'C'.code.toByte(), 'V'.code.toByte(), 'D'.code.toByte(), 'B'.code.toByte(), 1, '\r'.code.toByte(), '\n'.code.toByte())
    const val FORMAT_VERSION = 1
    const val DIRECTORY_ENTRY = 20
    const val MAX_BLOCK = 16 * 1024 * 1024
    const val MAX_ADVISORIES_PER_PACKAGE = 20_000
    const val MAX_HEADER = 4 * 1024 * 1024
    const val MAX_SUMMARY = 300
    const val MAX_VERSIONS = 3_000

    fun hash(key: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64
        for (b in key.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h
    }

    // ------------------------------------------------------------------ codificación de un aviso

    internal fun writeAdvisory(out: DataOutputStream, a: Advisory) {
        out.writeUTF(a.id.take(100))
        out.writeByte(minOf(a.aliases.size, 16))
        a.aliases.take(16).forEach { out.writeUTF(it.take(100)) }
        out.writeUTF(a.summary.take(MAX_SUMMARY))
        out.writeByte(a.severity?.let { 1 + Severity.entries.indexOf(it) } ?: 0)
        out.writeShort(a.score?.let { (it * 10).toInt().coerceIn(0, 100) } ?: -1)
        out.writeUTF(a.vector?.take(200).orEmpty())
        out.writeUTF(a.published.orEmpty())
        out.writeByte(if (a.malicious) 1 else 0)
        out.writeByte(minOf(a.cwes.size, 8))
        a.cwes.take(8).forEach { out.writeUTF(it.take(20)) }
        out.writeByte(minOf(a.ranges.size, 32))
        a.ranges.take(32).forEach { r ->
            out.writeShort(minOf(r.events.size, 256))
            r.events.take(256).forEach { e ->
                out.writeByte(e.kind.ordinal)
                out.writeUTF(e.version.take(100))
            }
        }
        out.writeShort(minOf(a.versions.size, MAX_VERSIONS))
        a.versions.take(MAX_VERSIONS).forEach { out.writeUTF(it.take(100)) }
    }

    internal fun readAdvisory(input: DataInputStream): Advisory {
        val id = input.readUTF()
        val aliases = List(input.readUnsignedByte().also { check(it <= 16) }) { input.readUTF() }
        val summary = input.readUTF()
        val sev = input.readUnsignedByte()
        val severity = if (sev == 0) null else Severity.entries.getOrNull(sev - 1) ?: throw InvalidIndexException("Severidad desconocida")
        val score = input.readShort().toInt().let { if (it < 0) null else it / 10.0 }
        val vector = input.readUTF().ifEmpty { null }
        val published = input.readUTF().ifEmpty { null }
        val malicious = input.readUnsignedByte() == 1
        val cwes = List(input.readUnsignedByte().also { check(it <= 8) }) { input.readUTF() }
        val ranges = List(input.readUnsignedByte().also { check(it <= 32) }) {
            val events = List(input.readUnsignedShort().also { check(it <= 256) }) {
                val kind = EventKind.entries.getOrNull(input.readUnsignedByte()) ?: throw InvalidIndexException("Evento desconocido")
                RangeEvent(kind, input.readUTF())
            }
            AffectedRange(events)
        }
        val versions = List(input.readUnsignedShort().also { check(it <= MAX_VERSIONS) }) { input.readUTF() }
        return Advisory(id, aliases, summary, severity, score, vector, published, malicious, cwes, ranges, versions)
    }

    private fun check(ok: Boolean) {
        if (!ok) throw InvalidIndexException("Recuento fuera de rango")
    }

    // ------------------------------------------------------------------ cabecera

    internal fun writeHeader(out: DataOutputStream, createdAt: Long, ecosystems: List<EcosystemInfo>, eol: List<EolProduct>, eolDate: Long, entries: Int, directoryOffset: Long) {
        out.write(MAGIC)
        out.writeInt(FORMAT_VERSION)
        out.writeLong(createdAt)
        out.writeShort(ecosystems.size)
        ecosystems.forEach {
            out.writeUTF(it.name)
            out.writeLong(it.sourceDate)
            out.writeInt(it.advisories)
            out.writeInt(it.packages)
        }
        out.writeLong(eolDate)
        out.writeShort(eol.size)
        eol.forEach { p ->
            out.writeUTF(p.name)
            out.writeUTF(p.label)
            out.writeShort(p.cycles.size)
            p.cycles.forEach { c -> listOf(c.cycle, c.releaseDate, c.eol, c.support, c.latest, c.lts).forEach { out.writeUTF(it.orEmpty().take(60)) } }
        }
        out.writeInt(entries)
        out.writeLong(directoryOffset)
    }

    internal class Header(val createdAt: Long, val ecosystems: List<EcosystemInfo>, val eolDate: Long, val eol: List<EolProduct>, val entries: Int, val directoryOffset: Long)

    internal fun readHeader(input: DataInputStream): Header {
        val magic = ByteArray(8)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw InvalidIndexException("No es una base de vulnerabilidades de Funcode")
        val version = input.readInt()
        if (version != FORMAT_VERSION) throw InvalidIndexException("Versión de la base no compatible ($version)")
        val createdAt = input.readLong()
        val ecosystems = List(input.readUnsignedShort().also { check(it <= 64) }) { EcosystemInfo(input.readUTF(), input.readLong(), input.readInt(), input.readInt()) }
        val eolDate = input.readLong()
        val eol = List(input.readUnsignedShort().also { check(it <= 1_000) }) {
            val name = input.readUTF()
            val label = input.readUTF()
            val cycles = List(input.readUnsignedShort().also { check(it <= 2_000) }) {
                val f = List(6) { input.readUTF().ifEmpty { null } }
                EolCycle(f[0].orEmpty(), f[1], f[2], f[3], f[4], f[5])
            }
            EolProduct(name, label, cycles)
        }
        val entries = input.readInt()
        val directoryOffset = input.readLong()
        if (entries < 0 || directoryOffset < 0) throw InvalidIndexException("Cabecera dañada")
        return Header(createdAt, ecosystems, eolDate, eol, entries, directoryOffset)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * Base de vulnerabilidades abierta para consultar. Lee del canal por posiciones (no carga el archivo): sirve igual con
 * un archivo del PC que con el descriptor de solo lectura que recibe el proceso aislado.
 */
class VulnDatabase private constructor(
    private val channel: FileChannel,
    private val header: VulnIndex.Header,
    val info: IndexInfo,
) : Closeable {

    val eol: List<EolProduct> get() = header.eol

    /** Avisos del paquete [name] del ecosistema de OSV [osvEcosystem] (vacío si no tiene ninguno). */
    fun advisories(osvEcosystem: String, name: String): List<Advisory> {
        val key = OsvEcosystems.key(osvEcosystem, name)
        val hash = VulnIndex.hash(key)
        var lo = 0
        var hi = header.entries - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val h = entry(mid).first
            when {
                h < hash -> lo = mid + 1
                h > hash -> hi = mid - 1
                else -> {
                    found = mid
                    break
                }
            }
        }
        if (found < 0) return emptyList()
        // Puede haber varias entradas con la misma huella: se revisan las vecinas.
        var first = found
        while (first > 0 && entry(first - 1).first == hash) first--
        var i = first
        while (i < header.entries) {
            val (h, offset, length) = entry(i)
            if (h != hash) break
            readBlock(offset, length)?.let { (k, list) -> if (k == key) return list }
            i++
        }
        return emptyList()
    }

    private fun entry(index: Int): Triple<Long, Long, Int> {
        val buf = ByteBuffer.allocate(VulnIndex.DIRECTORY_ENTRY)
        readFully(buf, header.directoryOffset + index.toLong() * VulnIndex.DIRECTORY_ENTRY)
        buf.flip()
        return Triple(buf.long, buf.long, buf.int)
    }

    private fun readBlock(offset: Long, length: Int): Pair<String, List<Advisory>>? {
        if (length <= 0 || length > VulnIndex.MAX_BLOCK || offset < 0 || offset + length > header.directoryOffset) throw InvalidIndexException("Bloque fuera de la base")
        val buf = ByteBuffer.allocate(length)
        readFully(buf, offset)
        val input = DataInputStream(ByteArrayInputStream(buf.array()))
        return try {
            val key = input.readUTF()
            input.readUTF() // nombre tal como lo publica OSV
            val count = input.readInt()
            if (count < 0 || count > VulnIndex.MAX_ADVISORIES_PER_PACKAGE) throw InvalidIndexException("Demasiados avisos en un paquete")
            key to List(count) { VulnIndex.readAdvisory(input) }
        } catch (e: EOFException) {
            throw InvalidIndexException("Bloque incompleto")
        } catch (e: java.io.UTFDataFormatException) {
            throw InvalidIndexException("Texto dañado en la base")
        }
    }

    private fun readFully(buf: ByteBuffer, position: Long) {
        var pos = position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw InvalidIndexException("La base termina antes de tiempo")
            pos += n
        }
    }

    override fun close() = channel.close()

    companion object {
        /**
         * Abre y comprueba la base: cabecera, tamaños y la suma SHA-256 de la cola (detecta un archivo truncado,
         * dañado o modificado). El canal se cierra si la base no es válida.
         */
        fun open(channel: FileChannel): VulnDatabase {
            try {
                val size = channel.size()
                if (size < 64) throw InvalidIndexException("La base está vacía o incompleta")
                val digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteBuffer.allocate(1 shl 20)
                var pos = 0L
                val end = size - 32
                while (pos < end) {
                    buf.clear()
                    buf.limit(minOf(buf.capacity().toLong(), end - pos).toInt())
                    val n = channel.read(buf, pos)
                    if (n <= 0) throw InvalidIndexException("No se pudo leer la base")
                    buf.flip()
                    digest.update(buf)
                    pos += n
                }
                val expected = ByteBuffer.allocate(32)
                var tp = end
                while (expected.hasRemaining()) {
                    val n = channel.read(expected, tp)
                    if (n < 0) throw InvalidIndexException("La base termina antes de tiempo")
                    tp += n
                }
                val actual = digest.digest()
                if (!actual.contentEquals(expected.array())) throw InvalidIndexException("La base está dañada o se modificó (la suma de comprobación no coincide)")
                val headLength = minOf(end, VulnIndex.MAX_HEADER.toLong()).toInt()
                val head = ByteBuffer.allocate(headLength)
                var hp = 0L
                while (head.hasRemaining()) {
                    val n = channel.read(head, hp)
                    if (n < 0) break
                    hp += n
                }
                val header = try {
                    VulnIndex.readHeader(DataInputStream(ByteArrayInputStream(head.array())))
                } catch (e: EOFException) {
                    throw InvalidIndexException("Cabecera incompleta")
                }
                if (header.directoryOffset + header.entries.toLong() * VulnIndex.DIRECTORY_ENTRY != end) throw InvalidIndexException("El directorio de la base no cuadra con su tamaño")
                val info = IndexInfo(header.createdAt, header.ecosystems, header.eolDate, header.eol.size, size, VulnIndex.hex(actual))
                return VulnDatabase(channel, header, info)
            } catch (e: Exception) {
                runCatching { channel.close() }
                throw if (e is InvalidIndexException) e else InvalidIndexException("No se pudo abrir la base: ${e.javaClass.simpleName}")
            }
        }

        /** Base vacía de una sola pieza (para pruebas y para el CLI): bloques ya codificados. */
        internal fun encodeBlock(key: String, displayName: String, advisories: List<ByteArray>): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeUTF(key)
                out.writeUTF(displayName.take(200))
                out.writeInt(advisories.size)
                advisories.forEach { out.write(it) }
            }
            return bytes.toByteArray()
        }
    }
}

package com.funcodestudiocb.analyzer.engine.ingest.zip

import com.funcodestudiocb.analyzer.engine.api.ArchiveFormat
import com.funcodestudiocb.analyzer.engine.api.IngestLimits
import com.funcodestudiocb.analyzer.engine.api.RejectionReason
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveEntry
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveListing
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveRejectedException
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveSession
import com.funcodestudiocb.analyzer.engine.ingest.DataReceiver
import com.funcodestudiocb.analyzer.engine.ingest.EntryVisitor
import com.funcodestudiocb.analyzer.engine.ingest.ReadOutcome
import com.funcodestudiocb.analyzer.engine.ingest.StructuralWarning
import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipShort
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Lector ZIP. Usa Apache Commons Compress para el directorio central y la descompresión, con comprobaciones
 * propias previas (registro final, entradas solapadas, cabeceras locales) y descifrado ZipCrypto / WinZip AES.
 */
class ZipArchiveSession private constructor(
    private val channel: FileChannel,
    private val zip: ZipFile,
    private val zipEntries: List<ZipArchiveEntry>,
    private val localHeaders: List<LocalHeader>,
    override val listing: ArchiveListing,
) : ArchiveSession {

    internal data class LocalHeader(val dataOffset: Long, val dosTime: Int)

    override fun readEntries(
        password: CharArray?,
        visitor: EntryVisitor,
        onResult: (ArchiveEntry, ReadOutcome) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        listing.entries.forEachIndexed { i, entry ->
            val receiver = visitor.receiverFor(entry) ?: return@forEachIndexed
            val zipEntry = zipEntries[i]
            val outcome = try {
                val stream = openStream(zipEntry, localHeaders[i], password)
                if (stream == null) {
                    unsupported(zipEntry)
                } else {
                    stream.use { pump(it, zipEntry, receiver, buffer) }
                }
            } catch (e: WrongPasswordException) {
                ReadOutcome.WrongPassword
            } catch (e: MissingPasswordException) {
                ReadOutcome.MissingPassword
            } catch (e: UnsupportedZipFeatureException) {
                ReadOutcome.Unsupported("Característica ZIP no admitida")
            } catch (e: IntegrityException) {
                ReadOutcome.Corrupt(e.message ?: "Integridad incorrecta")
            } catch (e: IOException) {
                ReadOutcome.Corrupt("Datos ilegibles (${e::class.java.simpleName})")
            } catch (e: RuntimeException) {
                ReadOutcome.Corrupt("Datos ilegibles (${e::class.java.simpleName})")
            } catch (e: LinkageError) {
                // Defensa adicional: un método de compresión que requiera una clase no incluida.
                ReadOutcome.Unsupported("Método de compresión ${zipEntry.method} no admitido")
            }
            onResult(entry, outcome)
        }
    }

    private class MissingPasswordException : IOException("Falta la contraseña")

    private fun aesParams(entry: ZipArchiveEntry): WinZipAes.Params? {
        val extra = entry.getExtraField(ZipShort(WinZipAes.EXTRA_FIELD_ID)) ?: return null
        val data = extra.centralDirectoryData ?: extra.localFileDataData ?: return null
        return WinZipAes.parseExtra(data)
    }

    private fun unsupported(entry: ZipArchiveEntry) = ReadOutcome.Unsupported("Método de compresión ${entry.method} no admitido")

    private fun openStream(entry: ZipArchiveEntry, local: LocalHeader, password: CharArray?): InputStream? {
        val gpb = entry.generalPurposeBit
        if (!gpb.usesEncryption()) {
            // Lista blanca: los métodos que necesitan bibliotecas opcionales (zstd, xz…) no están incluidos.
            return if (entry.method in READABLE_METHODS && zip.canReadEntryData(entry)) zip.getInputStream(entry) else null
        }
        if (gpb.usesStrongEncryption()) return null
        if (password == null) throw MissingPasswordException()
        val raw = zip.getRawInputStream(entry) ?: return null
        val (decrypted, method) = if (entry.method == AES_METHOD) {
            val params = aesParams(entry) ?: throw IntegrityException("Falta o es inválido el campo AES")
            WinZipAes.decrypt(raw, password.utf8(), params, entry.compressedSize) to params.actualMethod
        } else {
            val check = if (gpb.usesDataDescriptor()) (local.dosTime ushr 8) and 0xFF else ((entry.crc ushr 24) and 0xFF).toInt()
            ZipCrypto.decrypt(raw, listOf(password.utf8(), password.latin1()), check) to entry.method
        }
        return when (method) {
            STORED -> decrypted
            // Inflater sin cabecera necesita un byte extra al final de la entrada.
            DEFLATED -> InflaterInputStream(SequenceInputStream(decrypted, ByteArrayInputStream(ByteArray(1))), Inflater(true))
            else -> {
                decrypted.close()
                null
            }
        }
    }

    private fun pump(stream: InputStream, entry: ZipArchiveEntry, receiver: DataReceiver, buffer: ByteArray): ReadOutcome {
        val crc = CRC32()
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            crc.update(buffer, 0, n)
            if (!receiver.onData(buffer, n)) return ReadOutcome.StoppedByReceiver
        }
        // En AE-2 el CRC se omite a propósito (vale 0): la integridad la garantiza el HMAC.
        val aesV2 = entry.method == AES_METHOD && aesParams(entry)?.vendorVersion == 2
        val expected = entry.crc
        if (!aesV2 && expected >= 0 && crc.value != expected) {
            return ReadOutcome.Corrupt("El CRC no coincide")
        }
        return ReadOutcome.Completed
    }

    override fun close() {
        runCatching { zip.close() }
        runCatching { channel.close() }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val STORED = 0
        private const val DEFLATED = 8
        private const val AES_METHOD = 99

        /** Almacenado, implode, shrink, deflate, deflate64 y bzip2: implementados en Commons Compress sin dependencias. */
        private val READABLE_METHODS = setOf(0, 1, 6, 8, 9, 12)
        private const val LOCAL_HEADER_SIG = 0x04034b50
        private const val EOCD_SIG = 0x06054b50
        private const val ZIP64_LOCATOR_SIG = 0x07064b50
        private const val ZIP64_EOCD_SIG = 0x06064b50
        private const val UNIX_SYMLINK = 0xA000

        fun open(channel: FileChannel, limits: IngestLimits): ZipArchiveSession {
            preflight(channel, limits)
            val zip = try {
                ZipFile.builder()
                    .setSeekableByteChannel(channel)
                    .setCharset(Charsets.UTF_8)
                    .setUseUnicodeExtraFields(true)
                    .setIgnoreLocalFileHeader(true)
                    .get()
            } catch (e: IOException) {
                throw ArchiveRejectedException(RejectionReason.CORRUPT, "El ZIP está dañado o no es válido", e)
            }
            try {
                return build(channel, zip, limits)
            } catch (e: Exception) {
                runCatching { zip.close() }
                throw e
            }
        }

        private fun build(channel: FileChannel, zip: ZipFile, limits: IngestLimits): ZipArchiveSession {
            val zipEntries = zip.entriesInPhysicalOrder.toList()
            if (zipEntries.size > limits.maxEntries) {
                throw ArchiveRejectedException(RejectionReason.TOO_MANY_ENTRIES, "El ZIP tiene ${zipEntries.size} entradas")
            }
            val warnings = mutableListOf<StructuralWarning>()
            val fileSize = channel.size()
            val locals = zipEntries.map { readLocalHeader(channel, it, fileSize, warnings) }

            // Entradas solapadas: los datos de una entrada no pueden invadir la siguiente (bombas no recursivas).
            for (i in 0 until zipEntries.size - 1) {
                val end = locals[i].dataOffset + zipEntries[i].compressedSize.coerceAtLeast(0)
                if (end > zipEntries[i + 1].localHeaderOffset) {
                    throw ArchiveRejectedException(
                        RejectionReason.OVERLAPPING_ENTRIES,
                        "Los datos de '${zipEntries[i].name}' se solapan con la entrada siguiente",
                    )
                }
            }

            val entries = zipEntries.mapIndexed { i, e ->
                ArchiveEntry(
                    index = i,
                    rawName = e.name,
                    isDirectory = e.isDirectory,
                    declaredSize = e.size,
                    compressedSize = e.compressedSize.coerceAtLeast(0),
                    encrypted = e.generalPurposeBit.usesEncryption(),
                    symlink = e.isUnixSymlink || (e.unixMode and 0xF000) == UNIX_SYMLINK,
                    modifiedEpochMs = e.time.takeIf { it > 0 },
                )
            }
            return ZipArchiveSession(channel, zip, zipEntries, locals, ArchiveListing(ArchiveFormat.ZIP, entries, solid = false, headersEncrypted = false, structuralWarnings = warnings))
        }

        private fun readLocalHeader(channel: FileChannel, entry: ZipArchiveEntry, fileSize: Long, warnings: MutableList<StructuralWarning>): LocalHeader {
            val offset = entry.localHeaderOffset
            val fixed = readAt(channel, offset, 30)
            if (fixed.size < 30 || fixed.int(0) != LOCAL_HEADER_SIG) {
                throw ArchiveRejectedException(RejectionReason.CORRUPT, "Cabecera local inválida para '${entry.name}'")
            }
            val nameLength = fixed.short(26)
            val extraLength = fixed.short(28)
            val dataOffset = offset + 30 + nameLength + extraLength
            if (dataOffset + entry.compressedSize.coerceAtLeast(0) > fileSize) {
                throw ArchiveRejectedException(RejectionReason.CORRUPT, "Los datos de '${entry.name}' quedan fuera del archivo (truncado)")
            }
            val localName = readAt(channel, offset + 30, nameLength)
            if (!localName.contentEquals(entry.rawName)) {
                warnings += StructuralWarning(entry.name, "El nombre de la cabecera local no coincide con el del directorio central")
            }
            return LocalHeader(dataOffset, fixed.short(10))
        }

        /**
         * Lee el registro final (EOCD, y el ZIP64 si existe) antes de procesar el directorio central:
         * rechaza ZIP multidisco y los que declaran más entradas de las permitidas.
         */
        internal fun preflight(channel: FileChannel, limits: IngestLimits) {
            val size = channel.size()
            if (size < 22) throw ArchiveRejectedException(RejectionReason.CORRUPT, "El archivo es demasiado pequeño para ser un ZIP")
            val tailLength = minOf(size, 22L + 65_535L).toInt()
            val tail = readAt(channel, size - tailLength, tailLength)
            var eocd = -1
            for (i in tail.size - 22 downTo 0) {
                if (tail.int(i) == EOCD_SIG) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) throw ArchiveRejectedException(RejectionReason.CORRUPT, "No se encontró el final del ZIP (archivo dañado o truncado)")
            val disk = tail.short(eocd + 4)
            val cdDisk = tail.short(eocd + 6)
            var total = tail.short(eocd + 10).toLong()
            if (total == 0xFFFFL && eocd >= 20 && tail.int(eocd - 20) == ZIP64_LOCATOR_SIG) {
                val zip64Offset = ByteBuffer.wrap(tail, eocd - 12, 8).order(ByteOrder.LITTLE_ENDIAN).long
                if (zip64Offset in 0 until size) {
                    val record = readAt(channel, zip64Offset, 56)
                    if (record.size == 56 && record.int(0) == ZIP64_EOCD_SIG) {
                        total = ByteBuffer.wrap(record, 32, 8).order(ByteOrder.LITTLE_ENDIAN).long
                    }
                }
            }
            if (disk != 0 || cdDisk != 0) {
                throw ArchiveRejectedException(RejectionReason.MULTI_VOLUME, "ZIP dividido en varios discos o partes")
            }
            if (total < 0 || total > limits.maxEntries) {
                throw ArchiveRejectedException(RejectionReason.TOO_MANY_ENTRIES, "El ZIP declara $total entradas (máximo ${limits.maxEntries})")
            }
        }

        private fun readAt(channel: FileChannel, position: Long, length: Int): ByteArray {
            if (position < 0 || length <= 0) return ByteArray(0)
            val buffer = ByteBuffer.allocate(length)
            var pos = position
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, pos)
                if (n <= 0) break
                pos += n
            }
            return buffer.array().copyOf(buffer.position())
        }

        private fun ByteArray.int(at: Int): Int = ByteBuffer.wrap(this, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
        private fun ByteArray.short(at: Int): Int = ByteBuffer.wrap(this, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }
}

internal fun CharArray.utf8(): ByteArray {
    val buffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(this))
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}

internal fun CharArray.latin1(): ByteArray = ByteArray(size) { i -> this[i].code.coerceAtMost(0xFF).toByte() }

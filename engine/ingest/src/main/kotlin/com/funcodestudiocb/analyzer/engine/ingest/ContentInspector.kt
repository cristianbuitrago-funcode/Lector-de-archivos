package com.funcodestudiocb.analyzer.engine.ingest

import com.funcodestudiocb.analyzer.engine.api.FileKind
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Por qué se detuvo la lectura de una entrada. */
enum class StopReason { ENTRY_TOO_LARGE, TOTAL_LIMIT, COMPRESSION_BOMB, CANCELLED }

/** Presupuesto de bytes descomprimidos de todo el análisis (bytes reales, no declarados). */
class ByteBudget(private val limit: Long) {
    var used: Long = 0
        private set
    val exhausted: Boolean get() = used >= limit

    fun take(bytes: Int): Boolean {
        used += bytes
        return used <= limit
    }
}

/**
 * Recibe los datos de una entrada y calcula su huella SHA-256, su tamaño real, su tipo y su codificación,
 * aplicando los límites durante la lectura (los tamaños declarados en el comprimido no se creen).
 */
class ContentInspector(
    private val compressedSize: Long,
    private val maxEntryBytes: Long,
    private val ratioThresholdBytes: Long,
    private val maxRatio: Int,
    private val budget: ByteBudget,
    private val isCancelled: () -> Boolean,
) : DataReceiver {

    private val digest = MessageDigest.getInstance("SHA-256")
    private val sample = ByteArray(SAMPLE_SIZE)
    private var sampleLength = 0

    var bytes: Long = 0
        private set
    var stopReason: StopReason? = null
        private set

    override fun onData(buffer: ByteArray, length: Int): Boolean {
        bytes += length
        when {
            isCancelled() -> stopReason = StopReason.CANCELLED
            bytes > maxEntryBytes -> stopReason = StopReason.ENTRY_TOO_LARGE
            !budget.take(length) -> stopReason = StopReason.TOTAL_LIMIT
            bytes > ratioThresholdBytes && bytes > compressedSize.coerceAtLeast(1) * maxRatio -> stopReason = StopReason.COMPRESSION_BOMB
        }
        if (stopReason != null) return false
        digest.update(buffer, 0, length)
        if (sampleLength < SAMPLE_SIZE) {
            val n = minOf(length, SAMPLE_SIZE - sampleLength)
            System.arraycopy(buffer, 0, sample, sampleLength, n)
            sampleLength += n
        }
        return true
    }

    fun sha256(): String = digest.digest().joinToString("") { "%02x".format(it) }

    fun classify(): Pair<FileKind, String?> = ContentClassifier.classify(sample.copyOf(sampleLength), bytes)

    companion object {
        const val SAMPLE_SIZE = 8 * 1024
    }
}

/** Clasificación heurística a partir de los primeros bytes: binario o texto y su codificación probable. */
object ContentClassifier {

    private val BINARY_MAGIC = listOf(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), // PNG
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), // JPEG
        "GIF8".toByteArray(),
        "%PDF".toByteArray(),
        byteArrayOf(0x7F, 0x45, 0x4C, 0x46), // ELF
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()), // .class
        "dex\n".toByteArray(),
        "RIFF".toByteArray(),
        "OggS".toByteArray(),
        "ID3".toByteArray(),
        byteArrayOf(0x00, 0x00, 0x01, 0x00), // .ico
        "wOFF".toByteArray(),
        "wOF2".toByteArray(),
        byteArrayOf(0x00, 0x61, 0x73, 0x6D), // WebAssembly
        "SQLite format 3".toByteArray(),
    )

    fun classify(sample: ByteArray, totalSize: Long): Pair<FileKind, String?> {
        if (totalSize == 0L) return FileKind.EMPTY to null
        if (FormatDetector.isArchiveSignature(sample)) return FileKind.ARCHIVE to null
        when {
            sample.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> return FileKind.TEXT to "UTF-8 (BOM)"
            sample.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> return FileKind.TEXT to "UTF-16LE"
            sample.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> return FileKind.TEXT to "UTF-16BE"
        }
        if (BINARY_MAGIC.any { sample.startsWith(it) }) return FileKind.BINARY to null
        if (sample.any { it == 0.toByte() }) return FileKind.BINARY to null
        if (sample.all { it >= 0 }) return FileKind.TEXT to "ASCII"
        if (isUtf8(sample, truncated = totalSize > sample.size)) return FileKind.TEXT to "UTF-8"
        val controls = sample.count { b ->
            val v = b.toInt() and 0xFF
            v < 0x20 && v !in setOf(0x09, 0x0A, 0x0C, 0x0D, 0x1B, 0x08)
        }
        return if (controls * 100 <= sample.size) FileKind.TEXT to "ISO-8859-1 (probable)" else FileKind.BINARY to null
    }

    /** UTF-8 estricto; si la muestra está cortada, se toleran hasta 3 bytes incompletos al final. */
    private fun isUtf8(sample: ByteArray, truncated: Boolean): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val trims = if (truncated) 0..3 else 0..0
        return trims.any { trim ->
            sample.size - trim >= 0 && try {
                decoder.reset()
                decoder.decode(ByteBuffer.wrap(sample, 0, sample.size - trim))
                true
            } catch (e: CharacterCodingException) {
                false
            }
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

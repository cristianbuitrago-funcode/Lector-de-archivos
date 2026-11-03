package com.funcodestudiocb.analyzer.engine.ingest

import com.funcodestudiocb.analyzer.engine.api.ArchiveFormat

/** Resultado de identificar un archivo por su contenido (firma), sin fiarse de la extensión. */
sealed interface DetectedFormat {
    data class Supported(val format: ArchiveFormat) : DetectedFormat

    /** Formato reconocido pero no admitido (7z, gzip, ejecutable…), con su nombre legible. */
    data class Unsupported(val description: String) : DetectedFormat

    data object Unknown : DetectedFormat
}

object FormatDetector {

    /** Bytes necesarios para identificar cualquiera de los formatos conocidos. */
    const val HEAD_BYTES = 8

    private val RAR4 = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR5 = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    private val ZIP_LOCAL = bytes(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY = bytes(0x50, 0x4B, 0x05, 0x06)
    private val ZIP_SPANNED = bytes(0x50, 0x4B, 0x07, 0x08)
    private val ZIP_SPANNED_ALT = bytes(0x50, 0x4B, 0x30, 0x30)

    private val UNSUPPORTED = listOf(
        bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) to "7-Zip",
        bytes(0x1F, 0x8B) to "gzip",
        bytes(0x42, 0x5A, 0x68) to "bzip2",
        bytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) to "xz",
        bytes(0x28, 0xB5, 0x2F, 0xFD) to "Zstandard",
        bytes(0x4D, 0x5A) to "ejecutable de Windows (posible autoextraíble)",
        bytes(0x7F, 0x45, 0x4C, 0x46) to "ejecutable ELF",
        bytes(0x25, 0x50, 0x44, 0x46) to "PDF",
    )

    fun detect(head: ByteArray): DetectedFormat = when {
        head.startsWith(RAR5) -> DetectedFormat.Supported(ArchiveFormat.RAR5)
        head.startsWith(RAR4) -> DetectedFormat.Supported(ArchiveFormat.RAR4)
        head.startsWith(ZIP_LOCAL) || head.startsWith(ZIP_EMPTY) -> DetectedFormat.Supported(ArchiveFormat.ZIP)
        head.startsWith(ZIP_SPANNED) || head.startsWith(ZIP_SPANNED_ALT) -> DetectedFormat.Unsupported("ZIP dividido en partes")
        else -> UNSUPPORTED.firstOrNull { (magic, _) -> head.startsWith(magic) }
            ?.let { DetectedFormat.Unsupported(it.second) }
            ?: DetectedFormat.Unknown
    }

    /** Firma de comprimidos conocidos dentro del proyecto (para registrarlos como anidados, sin expandirlos). */
    fun isArchiveSignature(head: ByteArray): Boolean =
        detect(head) is DetectedFormat.Supported ||
            UNSUPPORTED.take(5).any { (magic, _) -> head.startsWith(magic) } ||
            isTar(head)

    private fun isTar(head: ByteArray): Boolean =
        head.size >= 262 && String(head, 257, 5, Charsets.US_ASCII) == "ustar"

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

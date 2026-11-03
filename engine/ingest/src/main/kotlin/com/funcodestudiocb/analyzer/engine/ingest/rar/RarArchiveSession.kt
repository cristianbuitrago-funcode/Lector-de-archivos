package com.funcodestudiocb.analyzer.engine.ingest.rar

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

/** Declaraciones JNI de libfuncode_unrar (UnRAR de RARLAB, ver native/unrar). */
internal object UnrarNative {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("funcode_unrar")
                    loaded = true
                }
            }
        }
    }

    @JvmStatic
    external fun nativeOpen(name: String, listOnly: Boolean, password: CharArray?, maxRuntimeSeconds: Int, maxAllocateMb: Int, result: IntArray): Long

    @JvmStatic
    external fun nativeNextHeader(handle: Long, out: LongArray, redirOut: Array<String?>): String?

    @JvmStatic
    external fun nativeProcess(handle: Long, operation: Int, sink: DataReceiver?): Int

    @JvmStatic
    external fun nativeState(handle: Long, out: IntArray)

    @JvmStatic
    external fun nativeClose(handle: Long)

    @JvmStatic
    external fun nativeDllVersion(): Int

    // Códigos de unrar/dll.hpp.
    const val SUCCESS = 0
    const val END_ARCHIVE = 10
    const val NO_MEMORY = 11
    const val BAD_DATA = 12
    const val BAD_ARCHIVE = 13
    const val UNKNOWN_FORMAT = 14
    const val EOPEN = 15
    const val MISSING_PASSWORD = 22
    const val BAD_PASSWORD = 24
    const val LARGE_DICT = 25
    const val USER_LIMIT = 26

    const val SKIP = 0
    const val TEST = 1

    const val ARCHIVE_VOLUME = 0x0001
    const val ARCHIVE_SOLID = 0x0008
    const val ARCHIVE_ENCRYPTED_HEADERS = 0x0080

    const val FILE_SPLIT_BEFORE = 0x01
    const val FILE_SPLIT_AFTER = 0x02
    const val FILE_ENCRYPTED = 0x04
    const val FILE_DIRECTORY = 0x20

    const val REDIR_UNIX_SYMLINK = 1L
    const val REDIR_WIN_SYMLINK = 2L
    const val REDIR_JUNCTION = 3L
    const val REDIR_HARDLINK = 4L
    const val REDIR_FILECOPY = 5L
}

/**
 * Lector RAR (RAR4 y RAR5) sobre UnRAR. Nunca escribe en disco: los datos llegan por retrollamada en modo
 * prueba. Los multivolumen se rechazan. Los sólidos solo se pueden recorrer en orden.
 */
class RarArchiveSession private constructor(
    private val name: String,
    private val limits: IngestLimits,
    private val maxRuntimeSeconds: Int,
    override val listing: ArchiveListing,
) : ArchiveSession {

    override fun readEntries(password: CharArray?, visitor: EntryVisitor, onResult: (ArchiveEntry, ReadOutcome) -> Unit) {
        val handle = openHandle(name, listOnly = false, password, limits, maxRuntimeSeconds).handle
        try {
            val values = LongArray(13)
            val redir = arrayOfNulls<String>(1)
            val state = IntArray(4)
            var index = 0
            while (true) {
                val rawName = UnrarNative.nativeNextHeader(handle, values, redir)
                val code = values[0].toInt()
                if (code == UnrarNative.END_ARCHIVE) break
                if (code != UnrarNative.SUCCESS || rawName == null) {
                    // Cabecera ilegible: el resto del comprimido no se puede recorrer.
                    listing.entries.drop(index).filter { !it.isDirectory }.forEach {
                        onResult(it, ReadOutcome.Corrupt("No se pudo leer la cabecera (código $code)"))
                    }
                    break
                }
                val entry = listing.entries.getOrNull(index)
                if (entry == null || entry.rawName != rawName) {
                    throw ArchiveRejectedException(RejectionReason.CORRUPT, "El contenido del RAR cambió entre el listado y la lectura")
                }
                index++
                val receiver = if (entry.isDirectory || entry.symlink || entry.hardlink) null else visitor.receiverFor(entry)
                if (receiver == null) {
                    UnrarNative.nativeProcess(handle, UnrarNative.SKIP, null)
                    continue
                }
                val result = UnrarNative.nativeProcess(handle, UnrarNative.TEST, receiver)
                UnrarNative.nativeState(handle, state)
                onResult(entry, outcomeOf(result, aborted = state[0] == 1, entry))
            }
        } finally {
            UnrarNative.nativeClose(handle)
        }
    }

    private fun outcomeOf(code: Int, aborted: Boolean, entry: ArchiveEntry): ReadOutcome = when {
        code == UnrarNative.SUCCESS -> ReadOutcome.Completed
        aborted -> ReadOutcome.StoppedByReceiver
        code == UnrarNative.MISSING_PASSWORD -> ReadOutcome.MissingPassword
        code == UnrarNative.BAD_PASSWORD -> ReadOutcome.WrongPassword
        code == UnrarNative.BAD_DATA && entry.encrypted -> ReadOutcome.Corrupt("Datos dañados o contraseña incorrecta")
        code == UnrarNative.BAD_DATA -> ReadOutcome.Corrupt("El CRC no coincide")
        code == UnrarNative.LARGE_DICT || code == UnrarNative.USER_LIMIT || code == UnrarNative.NO_MEMORY ->
            ReadOutcome.Unsupported("Supera el límite de memoria o de tiempo para descomprimir")
        else -> ReadOutcome.Corrupt("Error de UnRAR (código $code)")
    }

    override fun close() = Unit

    companion object {
        private class OpenedArchive(val handle: Long, val flags: Int)

        fun open(name: String, format: ArchiveFormat, limits: IngestLimits, password: CharArray?, maxRuntimeSeconds: Int): RarArchiveSession {
            UnrarNative.ensureLoaded()
            val opened = openHandle(name, listOnly = true, password, limits, maxRuntimeSeconds)
            try {
                val entries = list(opened.handle, limits)
                val listing = ArchiveListing(
                    format = format,
                    entries = entries,
                    solid = opened.flags and UnrarNative.ARCHIVE_SOLID != 0,
                    headersEncrypted = opened.flags and UnrarNative.ARCHIVE_ENCRYPTED_HEADERS != 0,
                )
                return RarArchiveSession(name, limits, maxRuntimeSeconds, listing)
            } finally {
                UnrarNative.nativeClose(opened.handle)
            }
        }

        private fun openHandle(name: String, listOnly: Boolean, password: CharArray?, limits: IngestLimits, maxRuntimeSeconds: Int): OpenedArchive {
            val result = IntArray(4)
            val handle = UnrarNative.nativeOpen(name, listOnly, password, maxRuntimeSeconds, limits.maxRarMemoryMb, result)
            val (code, flags, passwordRequested, volumeRequested) = result.toList()
            if (handle == 0L) {
                throw when {
                    code == UnrarNative.BAD_PASSWORD -> ArchiveRejectedException(RejectionReason.WRONG_PASSWORD, "La contraseña no es correcta")
                    code == UnrarNative.MISSING_PASSWORD || passwordRequested == 1 ->
                        ArchiveRejectedException(RejectionReason.PASSWORD_REQUIRED, "Los nombres del RAR están cifrados: hace falta la contraseña")
                    volumeRequested == 1 -> ArchiveRejectedException(RejectionReason.MULTI_VOLUME, "RAR multivolumen no admitido")
                    else -> ArchiveRejectedException(RejectionReason.CORRUPT, "El RAR está dañado o no es válido (código $code)")
                }
            }
            if (flags and UnrarNative.ARCHIVE_VOLUME != 0) {
                UnrarNative.nativeClose(handle)
                throw ArchiveRejectedException(RejectionReason.MULTI_VOLUME, "RAR multivolumen no admitido: comprime el proyecto en un único archivo")
            }
            return OpenedArchive(handle, flags)
        }

        private fun list(handle: Long, limits: IngestLimits): List<ArchiveEntry> {
            val values = LongArray(13)
            val redir = arrayOfNulls<String>(1)
            val entries = mutableListOf<ArchiveEntry>()
            while (true) {
                redir[0] = null
                val rawName = UnrarNative.nativeNextHeader(handle, values, redir)
                val code = values[0].toInt()
                if (code == UnrarNative.END_ARCHIVE) break
                if (code != UnrarNative.SUCCESS || rawName == null) {
                    throw when (code) {
                        UnrarNative.MISSING_PASSWORD -> ArchiveRejectedException(RejectionReason.PASSWORD_REQUIRED, "Los nombres del RAR están cifrados: hace falta la contraseña")
                        UnrarNative.BAD_PASSWORD -> ArchiveRejectedException(RejectionReason.WRONG_PASSWORD, "La contraseña no es correcta")
                        else -> ArchiveRejectedException(RejectionReason.CORRUPT, "Cabecera dañada en el RAR (código $code)")
                    }
                }
                val flags = values[3].toInt()
                if (flags and (UnrarNative.FILE_SPLIT_BEFORE or UnrarNative.FILE_SPLIT_AFTER) != 0) {
                    throw ArchiveRejectedException(RejectionReason.MULTI_VOLUME, "El RAR contiene archivos repartidos en varios volúmenes")
                }
                if (entries.size >= limits.maxEntries) {
                    throw ArchiveRejectedException(RejectionReason.TOO_MANY_ENTRIES, "El RAR tiene más de ${limits.maxEntries} entradas")
                }
                val redirType = values[7]
                entries += ArchiveEntry(
                    index = entries.size,
                    rawName = rawName,
                    isDirectory = flags and UnrarNative.FILE_DIRECTORY != 0,
                    declaredSize = values[1],
                    compressedSize = values[2],
                    encrypted = flags and UnrarNative.FILE_ENCRYPTED != 0,
                    symlink = redirType == UnrarNative.REDIR_UNIX_SYMLINK || redirType == UnrarNative.REDIR_WIN_SYMLINK || redirType == UnrarNative.REDIR_JUNCTION,
                    hardlink = redirType == UnrarNative.REDIR_HARDLINK || redirType == UnrarNative.REDIR_FILECOPY,
                    linkTarget = redir[0],
                    modifiedEpochMs = values[9].takeIf { it > 0 },
                    dictionaryKb = values[6],
                )
                val skip = UnrarNative.nativeProcess(handle, UnrarNative.SKIP, null)
                if (skip != UnrarNative.SUCCESS) {
                    throw ArchiveRejectedException(RejectionReason.CORRUPT, "El RAR está dañado (código $skip)")
                }
            }
            return entries
        }
    }
}

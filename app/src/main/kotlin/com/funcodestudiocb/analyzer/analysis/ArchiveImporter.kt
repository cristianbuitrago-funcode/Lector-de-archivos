package com.funcodestudiocb.analyzer.analysis

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.funcodestudiocb.analyzer.engine.api.ArchiveFormat
import com.funcodestudiocb.analyzer.engine.ingest.DetectedFormat
import com.funcodestudiocb.analyzer.engine.ingest.EntryNames
import com.funcodestudiocb.analyzer.engine.ingest.FormatDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Comprimido copiado al almacenamiento privado de la app, listo para analizar. */
data class ImportedArchive(
    val file: File,
    /** Nombre original, con los caracteres invisibles escapados. */
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val format: ArchiveFormat,
    /** La extensión del nombre no corresponde al formato real (p. ej. un RAR llamado ".zip"). */
    val extensionMismatch: Boolean,
)

sealed interface ImportResult {
    data class Imported(val archive: ImportedArchive) : ImportResult
    data class TooLarge(val maxBytes: Long) : ImportResult
    data class Unsupported(val description: String) : ImportResult
    data class Unreadable(val reason: String) : ImportResult
}

/**
 * Copia el archivo elegido por el usuario a una carpeta privada sin copia de seguridad, de la que solo se lee
 * a través de un descriptor entregado al proceso aislado.
 *
 * - El límite de tamaño se aplica mientras se copia (no se confía en el tamaño que informa el proveedor).
 * - El formato se identifica por la firma del contenido, no por la extensión.
 * - La copia se borra al terminar el análisis o al descartarlo; al iniciar la app se borran las huérfanas.
 *
 * El almacenamiento de la app está protegido por el cifrado de archivos de Android (ver ADR 0004).
 */
class ArchiveImporter(
    private val resolver: ContentResolver,
    private val directory: File,
) {
    suspend fun import(uri: Uri, maxBytes: Long): ImportResult = withContext(Dispatchers.IO) {
        val (name, declaredSize) = describe(uri)
        if (declaredSize != null && declaredSize > maxBytes) return@withContext ImportResult.TooLarge(maxBytes)
        directory.mkdirs()
        val target = File(directory, "${UUID.randomUUID()}.bin")
        val digest = MessageDigest.getInstance("SHA-256")
        val head = ByteArray(FormatDetector.HEAD_BYTES)
        var headLength = 0
        var total = 0L
        try {
            val input = resolver.openInputStream(uri) ?: return@withContext ImportResult.Unreadable("No se pudo abrir el archivo")
            input.use { stream ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) {
                            target.delete()
                            return@withContext ImportResult.TooLarge(maxBytes)
                        }
                        if (headLength < head.size) {
                            val take = minOf(n, head.size - headLength)
                            System.arraycopy(buffer, 0, head, headLength, take)
                            headLength += take
                        }
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                    out.fd.sync()
                }
            }
        } catch (e: IOException) {
            target.delete()
            return@withContext ImportResult.Unreadable("Error de lectura (${e::class.java.simpleName})")
        } catch (e: SecurityException) {
            target.delete()
            return@withContext ImportResult.Unreadable("Sin permiso para leer el archivo: vuelve a elegirlo o a compartirlo")
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        when (val detected = FormatDetector.detect(head.copyOf(headLength))) {
            is DetectedFormat.Supported -> ImportResult.Imported(
                ImportedArchive(
                    file = target,
                    displayName = name,
                    sizeBytes = total,
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                    format = detected.format,
                    extensionMismatch = !extensionMatches(name, detected.format),
                ),
            )
            is DetectedFormat.Unsupported -> {
                target.delete()
                ImportResult.Unsupported(detected.description)
            }
            DetectedFormat.Unknown -> {
                target.delete()
                ImportResult.Unsupported("no es un ZIP ni un RAR")
            }
        }
    }

    /** Borra copias que hayan quedado de una ejecución interrumpida. */
    fun cleanupOrphans(keep: File? = null) {
        directory.listFiles()?.filter { it != keep }?.forEach { it.delete() }
    }

    private fun describe(uri: Uri): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex)
                    if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
                }
            }
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')
        val safe = EntryNames.escapeForDisplay(name ?: fallback ?: "proyecto").take(MAX_NAME).ifBlank { "proyecto" }
        return safe to size?.takeIf { it >= 0 }
    }

    private fun extensionMatches(name: String, format: ArchiveFormat): Boolean {
        val lower = name.lowercase()
        return when (format) {
            ArchiveFormat.ZIP -> lower.endsWith(".zip") || !lower.contains('.')
            ArchiveFormat.RAR4, ArchiveFormat.RAR5 -> lower.endsWith(".rar") || !lower.contains('.')
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_NAME = 255
    }
}

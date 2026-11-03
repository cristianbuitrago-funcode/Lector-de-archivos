package com.funcodestudiocb.analyzer.engine.ingest

import com.funcodestudiocb.analyzer.engine.api.ArchiveFormat
import com.funcodestudiocb.analyzer.engine.api.RejectionReason
import java.nio.channels.FileChannel

/**
 * El comprimido a analizar.
 *
 * @property channel acceso aleatorio de solo lectura (ZIP).
 * @property rarName nombre con el que UnRAR abre el archivo: `funcode-fd:<n>` en Android (proceso aislado,
 *   solo descriptores) o una ruta normal en las pruebas y la CLI.
 */
class ArchiveSource(
    val channel: FileChannel,
    val rarName: String,
) {
    val size: Long get() = channel.size()

    /** Lee los primeros bytes sin mover la posición del canal. */
    fun head(count: Int): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(count)
        var position = 0L
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position)
            if (n <= 0) break
            position += n
        }
        return buffer.array().copyOf(buffer.position())
    }
}

/** Una entrada del comprimido, tal como la declara (los tamaños pueden ser falsos). */
data class ArchiveEntry(
    /** Posición en el orden en que se recorrerá el comprimido. */
    val index: Int,
    val rawName: String,
    val isDirectory: Boolean,
    val declaredSize: Long,
    val compressedSize: Long,
    val encrypted: Boolean,
    val symlink: Boolean = false,
    val hardlink: Boolean = false,
    val linkTarget: String? = null,
    val modifiedEpochMs: Long? = null,
    /** RAR: memoria de diccionario necesaria, en KiB. */
    val dictionaryKb: Long = 0,
)

/** Contenido del comprimido obtenido sin descomprimir datos. */
data class ArchiveListing(
    val format: ArchiveFormat,
    val entries: List<ArchiveEntry>,
    val solid: Boolean,
    val headersEncrypted: Boolean,
    /** Avisos estructurales detectados al listar (p. ej. cabecera local distinta del directorio central). */
    val structuralWarnings: List<StructuralWarning> = emptyList(),
)

data class StructuralWarning(val path: String?, val detail: String)

/** El comprimido no puede procesarse. */
class ArchiveRejectedException(val reason: RejectionReason, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Resultado de leer los datos de una entrada. */
sealed interface ReadOutcome {
    data object Completed : ReadOutcome

    /** El receptor pidió detener la lectura (límite alcanzado). */
    data object StoppedByReceiver : ReadOutcome
    data object MissingPassword : ReadOutcome
    data object WrongPassword : ReadOutcome

    /** Datos dañados: CRC o autenticación incorrectos, flujo truncado o inválido. */
    data class Corrupt(val detail: String) : ReadOutcome
    data class Unsupported(val detail: String) : ReadOutcome
}

/** Recibe los datos descomprimidos de una entrada por bloques. Devuelve `false` para detener la lectura. */
fun interface DataReceiver {
    fun onData(buffer: ByteArray, length: Int): Boolean
}

/** Qué hacer con cada entrada al recorrer el comprimido. */
fun interface EntryVisitor {
    /** Devuelve un receptor para leer la entrada, o `null` para saltarla sin descomprimir. */
    fun receiverFor(entry: ArchiveEntry): DataReceiver?
}

/** Un comprimido abierto. */
interface ArchiveSession : AutoCloseable {
    val listing: ArchiveListing

    /**
     * Recorre las entradas en orden físico. Para cada una pregunta a [visitor] si se lee y, al terminar,
     * notifica el resultado a [onResult]. Los comprimidos sólidos (RAR) solo pueden recorrerse en orden.
     */
    fun readEntries(password: CharArray?, visitor: EntryVisitor, onResult: (ArchiveEntry, ReadOutcome) -> Unit)
}

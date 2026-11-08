package com.funcodestudiocb.analyzer.analysis

import com.funcodestudiocb.analyzer.engine.api.ContentChunk
import com.funcodestudiocb.analyzer.engine.api.ProtocolJson
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** La copia temporal no supera la verificación (alterada, truncada o ya borrada). */
class SpoolUnreadableException(cause: Throwable?) : Exception("La copia temporal del código no es legible", cause)

/**
 * Almacén temporal de la copia redactada del código mientras llega del motor y hasta que se guarda en la BD
 * cifrada (que puede estar cerrada si la sesión se bloqueó durante el análisis).
 *
 * No se guarda en memoria (puede ocupar decenas de MB). Se escribe en disco cifrado con AES-256-GCM y una clave
 * efímera que solo existe en memoria: al cerrar el almacén se borra el archivo y la clave, así que lo que pudiera
 * quedar en la memoria flash es ilegible (borrado criptográfico). Si la app muere, la clave se pierde y el archivo
 * huérfano se borra al arrancar.
 *
 * Cada fragmento se cifra con su número de orden en el vector de inicialización: al leer se detecta cualquier
 * cambio, reordenación o truncado.
 */
class ContentSpool private constructor(private val file: File) : Closeable {

    private val key = ByteArray(KEY_BYTES).also(random::nextBytes)
    private val noncePrefix = ByteArray(4).also(random::nextBytes)
    private var out: DataOutputStream? = DataOutputStream(BufferedOutputStream(FileOutputStream(file)))
    private var closed = false

    var chunks: Int = 0
        private set
    var chars: Long = 0
        private set

    @Synchronized
    fun append(chunk: ContentChunk) {
        val stream = out ?: throw IllegalStateException("El almacén ya no admite escrituras")
        val plain = ProtocolJson.encodeToString(ResultRecord.serializer(), chunk).toByteArray(Charsets.UTF_8)
        val sealed = cipher(Cipher.ENCRYPT_MODE, chunks.toLong()).doFinal(plain)
        plain.fill(0)
        stream.writeInt(sealed.size)
        stream.write(sealed)
        chunks++
        chars += chunk.text.length
    }

    /** Termina la escritura. Después solo se puede leer. */
    @Synchronized
    fun finishWriting() {
        out?.close()
        out = null
    }

    /**
     * Recorre los fragmentos en orden. Lanza [SpoolUnreadableException] si el archivo se modificó, se truncó,
     * tiene fragmentos de más o ya se cerró.
     */
    fun read(): Sequence<ContentChunk> = sequence {
        if (closed) throw SpoolUnreadableException(null)
        finishWriting()
        // Se recorre a mano (y no con yieldAll) para que cualquier fallo de cualquier fragmento, no solo del
        // primero, llegue como SpoolUnreadableException.
        val iterator = readVerified().iterator()
        while (true) {
            val next = try {
                if (iterator.hasNext()) iterator.next() else null
            } catch (e: Exception) {
                throw SpoolUnreadableException(e)
            } ?: break
            yield(next)
        }
    }

    private fun readVerified(): Sequence<ContentChunk> = sequence {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            for (i in 0 until chunks) {
                val size = input.readInt()
                require(size in 1..MAX_SEALED_BYTES) { "Fragmento con tamaño inválido" }
                val sealed = ByteArray(size)
                input.readFully(sealed)
                val plain = cipher(Cipher.DECRYPT_MODE, i.toLong()).doFinal(sealed)
                val record = ProtocolJson.decodeFromString(ResultRecord.serializer(), plain.toString(Charsets.UTF_8))
                plain.fill(0)
                yield(record as ContentChunk)
            }
            val extra = try {
                input.readByte()
                true
            } catch (e: EOFException) {
                false
            }
            require(!extra) { "El almacén tiene datos de más" }
        }
    }

    private fun cipher(mode: Int, counter: Long): Cipher {
        val nonce = ByteBuffer.allocate(12).put(noncePrefix).putLong(counter).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
    }

    /** Borra el archivo y la clave. Se puede llamar varias veces. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { out?.close() }
        out = null
        key.fill(0)
        file.delete()
    }

    companion object {
        private const val KEY_BYTES = 32
        private const val MAX_SEALED_BYTES = 1 shl 20
        private val random = SecureRandom()

        fun create(directory: File): ContentSpool {
            directory.mkdirs()
            return ContentSpool(File.createTempFile("copia-", ".bin", directory))
        }

        /** Borra los almacenes que dejó una ejecución anterior de la app (sus claves ya no existen). */
        fun cleanupOrphans(directory: File) {
            directory.listFiles()?.forEach { it.delete() }
        }
    }
}

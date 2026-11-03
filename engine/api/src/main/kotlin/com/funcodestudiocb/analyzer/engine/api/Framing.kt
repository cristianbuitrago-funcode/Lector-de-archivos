package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Formato de los mensajes entre procesos.
 *
 * Cada registro viaja como una trama: 4 bytes con la longitud (big-endian) seguidos de un JSON UTF-8.
 * El discriminador de tipo es el campo `t`. Las claves desconocidas se rechazan.
 */
val ProtocolJson: Json = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = false
    isLenient = false
    encodeDefaults = true
    explicitNulls = false
    allowSpecialFloatingPointValues = false
}

/** Límites que aplica el lector a un flujo de tramas no confiable. */
data class FrameLimits(
    val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    val maxFrames: Long = DEFAULT_MAX_FRAMES,
) {
    init {
        require(maxFrameBytes > 0 && maxTotalBytes > 0 && maxFrames > 0)
    }

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES: Int = 1 shl 20 // 1 MiB
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 512L shl 20 // 512 MiB
        const val DEFAULT_MAX_FRAMES: Long = 5_000_000
    }
}

/** El flujo recibido no respeta el protocolo. El flujo completo debe descartarse a partir de este punto. */
class ProtocolViolationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Escribe registros como tramas. Seguro para usar desde varios hilos. */
class FrameWriter(
    output: OutputStream,
    private val maxFrameBytes: Int = FrameLimits.DEFAULT_MAX_FRAME_BYTES,
) : AutoCloseable {

    private val out = DataOutputStream(output.buffered())
    private val lock = Any()

    /** Número de registros escritos hasta ahora. */
    var written: Long = 0
        private set

    fun write(record: ResultRecord) {
        val bytes = ProtocolJson.encodeToString(ResultRecord.serializer(), record).toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxFrameBytes) {
            "Registro de ${bytes.size} bytes supera el máximo de $maxFrameBytes bytes"
        }
        synchronized(lock) {
            out.writeInt(bytes.size)
            out.write(bytes)
            written++
        }
    }

    fun flush() {
        synchronized(lock) { out.flush() }
    }

    override fun close() {
        synchronized(lock) { out.close() }
    }
}

/**
 * Lee tramas de un flujo NO confiable aplicando [FrameLimits].
 *
 * Devuelve `null` solo si el flujo termina limpiamente entre dos tramas. Cualquier otra anomalía
 * (longitud inválida, trama truncada, JSON mal formado, tipo desconocido, límites superados)
 * lanza [ProtocolViolationException].
 */
class FrameReader(
    private val input: InputStream,
    private val limits: FrameLimits = FrameLimits(),
) {
    private var totalBytes = 0L
    private var frames = 0L

    fun next(): ResultRecord? {
        val length = readLengthOrNull() ?: return null
        if (length <= 0) throw ProtocolViolationException("Longitud de trama inválida: $length")
        if (length > limits.maxFrameBytes) {
            throw ProtocolViolationException("Trama de $length bytes supera el máximo de ${limits.maxFrameBytes}")
        }
        frames++
        if (frames > limits.maxFrames) throw ProtocolViolationException("Se superó el número máximo de tramas")
        totalBytes += 4L + length
        if (totalBytes > limits.maxTotalBytes) throw ProtocolViolationException("Se superó el tamaño total permitido")

        val payload = ByteArray(length)
        readFully(payload)
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(payload)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw ProtocolViolationException("La trama no es UTF-8 válido", e)
        }
        return try {
            ProtocolJson.decodeFromString(ResultRecord.serializer(), text)
        } catch (e: SerializationException) {
            throw ProtocolViolationException("Registro mal formado o de tipo desconocido", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolViolationException("Registro con valores inválidos", e)
        }
    }

    private fun readLengthOrNull(): Int? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if ((b1 or b2 or b3) < 0) throw ProtocolViolationException("Cabecera de trama truncada")
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = try {
                input.read(buffer, offset, buffer.size - offset)
            } catch (e: EOFException) {
                -1
            }
            if (n < 0) throw ProtocolViolationException("Trama truncada: faltan ${buffer.size - offset} bytes")
            offset += n
        }
    }
}

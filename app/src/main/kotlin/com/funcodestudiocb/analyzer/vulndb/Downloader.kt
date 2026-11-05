package com.funcodestudiocb.analyzer.vulndb

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Resultado de una descarga. */
data class Downloaded(val bytes: Long, val lastModified: Long?)

/** La descarga superó el tamaño máximo permitido y se interrumpió. */
class DownloadTooLargeException(message: String) : IOException(message)

/** El servidor respondió con un error. */
class HttpStatusException(val status: Int, message: String) : IOException(message)

/**
 * Descargas de la base de vulnerabilidades. Solo se usa en el proceso principal: el proceso de análisis aislado
 * no tiene red.
 */
interface Downloader {
    /**
     * Descarga [url] en [target] (se sobrescribe). Corta si se superan [maxBytes] o si [cancelled] devuelve `true`.
     * [onProgress] recibe los bytes recibidos y el total anunciado (si se conoce).
     */
    fun download(url: String, target: File, maxBytes: Long, onProgress: (Long, Long?) -> Unit, cancelled: () -> Boolean): Downloaded

    /** Descarga un texto pequeño (UTF-8) de como mucho [maxBytes]. */
    fun text(url: String, maxBytes: Int): String
}

/**
 * Descargas por HTTPS con `HttpsURLConnection`. Solo admite los servidores de [ALLOWED_HOSTS]; no sigue redirecciones
 * a otros servidores ni a HTTP; no envía cookies ni identificadores (solo un agente de usuario genérico). El tamaño
 * se mide al leer: no se confía en la cabecera `Content-Length`.
 */
class HttpsDownloader(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 60_000,
) : Downloader {

    override fun download(url: String, target: File, maxBytes: Long, onProgress: (Long, Long?) -> Unit, cancelled: () -> Boolean): Downloaded {
        val connection = open(url)
        try {
            val announced = connection.contentLengthLong.takeIf { it >= 0 }
            if (announced != null && announced > maxBytes) throw DownloadTooLargeException("El archivo anunciado supera el máximo permitido")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(BUFFER)
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw java.util.concurrent.CancellationException("Descarga cancelada")
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw DownloadTooLargeException("La descarga supera el máximo permitido")
                        out.write(buffer, 0, n)
                        if (total - lastReport >= REPORT_EVERY) {
                            lastReport = total
                            onProgress(total, announced)
                        }
                    }
                    out.fd.sync()
                }
            }
            if (announced != null && total != announced) throw IOException("La descarga se cortó antes de terminar")
            onProgress(total, announced)
            return Downloaded(total, connection.lastModified.takeIf { it > 0 })
        } finally {
            connection.disconnect()
        }
    }

    override fun text(url: String, maxBytes: Int): String {
        val connection = open(url)
        try {
            return connection.inputStream.use { readLimited(it, maxBytes) }.decodeToString()
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String): HttpsURLConnection {
        val url = URL(address)
        require(isAllowed(url)) { "Servidor no permitido" }
        val connection = url.openConnection() as? HttpsURLConnection ?: throw IOException("Solo se admite HTTPS")
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept-Encoding", "identity")
        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw HttpStatusException(status, "El servidor respondió $status")
        }
        // Defensa adicional: la conexión final sigue siendo con el servidor pedido.
        if (!isAllowed(connection.url)) {
            connection.disconnect()
            throw IOException("Redirección a un servidor no permitido")
        }
        return connection
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw DownloadTooLargeException("La respuesta supera el máximo permitido")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        /** Únicos servidores a los que se conecta la app. */
        val ALLOWED_HOSTS = setOf("osv-vulnerabilities.storage.googleapis.com", "endoflife.date")
        private const val USER_AGENT = "FuncodeCodeAnalyzer"
        private const val BUFFER = 64 * 1024
        private const val REPORT_EVERY = 512L * 1024

        fun isAllowed(url: URL): Boolean = url.protocol == "https" && url.host in ALLOWED_HOSTS && (url.port == -1 || url.port == 443) && url.userInfo == null
    }
}

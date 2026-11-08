package com.funcodestudiocb.analyzer.service

import android.annotation.SuppressLint
import android.os.Process
import com.funcodestudiocb.analyzer.engine.api.IsolationReport
import com.funcodestudiocb.analyzer.engine.api.ProbeOutcome
import com.funcodestudiocb.analyzer.engine.api.ProbeResult
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.core.StageContext
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Autodiagnóstico del aislamiento, ejecutado DENTRO del proceso aislado.
 *
 * Intenta tres cosas que el sistema debería impedir: abrir una conexión de red, leer un archivo
 * privado de la app y listar el almacenamiento compartido. Cada intento se clasifica en
 * bloqueado por el sistema (esperado), permitido (fallo grave) o no concluyente.
 */
class IsolationProbeStage : Stage {
    override val id: String = "diagnostic.isolation"
    override val title: String = "Comprobación del aislamiento"

    override suspend fun run(context: StageContext) {
        val options = requireNotNull(context.config.diagnostic) { "Faltan opciones de diagnóstico" }
        context.progress(0.1f, "Comprobando la red")
        val network = probeNetwork()
        context.progress(0.5f, "Comprobando los archivos de la app")
        val appFiles = probeAppFiles(options.canaryPath, options.appDataDir)
        context.progress(0.8f, "Comprobando el almacenamiento compartido")
        val shared = probeSharedStorage()
        context.emit(
            IsolationReport(
                uid = Process.myUid(),
                pid = Process.myPid(),
                processName = ProcessInfo.processName().take(MAX_NAME),
                isolatedBySystem = ProcessInfo.isIsolatedProcess(),
                network = network,
                appFiles = appFiles,
                sharedStorage = shared,
            ),
        )
    }

    private fun probeNetwork(): ProbeResult = try {
        Socket().use { socket ->
            // 1.1.1.1:443 por dirección IP, sin resolución DNS. No se envía ningún dato.
            socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), 443), NETWORK_TIMEOUT_MS)
        }
        ProbeResult(ProbeOutcome.ALLOWED, "Se pudo abrir una conexión TCP desde el proceso aislado")
    } catch (e: SecurityException) {
        ProbeResult(ProbeOutcome.BLOCKED, "El sistema denegó la conexión: ${describe(e)}")
    } catch (e: IOException) {
        classify(e, "conexión de red")
    }

    private fun probeAppFiles(canaryPath: String, appDataDir: String): ProbeResult {
        val canary = try {
            File(canaryPath).readBytes()
            ProbeResult(ProbeOutcome.ALLOWED, "Se pudo leer un archivo privado de la app")
        } catch (e: SecurityException) {
            ProbeResult(ProbeOutcome.BLOCKED, "El sistema denegó la lectura: ${describe(e)}")
        } catch (e: IOException) {
            // El proceso principal acaba de crear el archivo canario: si aquí "no existe", es que el sistema lo
            // oculta al proceso aislado (aislamiento de datos de apps, Android 11+). Eso también es un bloqueo.
            if (NOT_FOUND_MARKERS.any { describe(e).contains(it, ignoreCase = true) }) {
                ProbeResult(ProbeOutcome.BLOCKED, "El archivo privado no es visible desde el proceso aislado: ${describe(e)}")
            } else {
                classify(e, "lectura del archivo canario")
            }
        }
        if (canary.outcome == ProbeOutcome.ALLOWED) return canary
        val listing = runCatching { File(appDataDir).list() }.getOrNull()
        if (listing != null) {
            return ProbeResult(ProbeOutcome.ALLOWED, "Se pudo listar el directorio de datos de la app (${listing.size} entradas)")
        }
        return canary
    }

    // Rutas fijas a propósito: se comprueba si el proceso aislado puede llegar a ellas.
    @SuppressLint("SdCardPath")
    private fun probeSharedStorage(): ProbeResult {
        val candidates = listOf("/storage/emulated/0", "/sdcard")
        for (path in candidates) {
            val entries = runCatching { File(path).list() }.getOrNull()
            if (entries != null && entries.isNotEmpty()) {
                return ProbeResult(ProbeOutcome.ALLOWED, "Se pudo listar $path (${entries.size} entradas)")
            }
        }
        val readable = candidates.any { runCatching { File(it).canRead() }.getOrDefault(false) }
        return if (readable) {
            ProbeResult(ProbeOutcome.INCONCLUSIVE, "El almacenamiento compartido parece accesible pero no se pudo listar")
        } else {
            ProbeResult(ProbeOutcome.BLOCKED, "No se pudo acceder al almacenamiento compartido")
        }
    }

    private fun classify(e: IOException, what: String): ProbeResult {
        val text = describe(e)
        val denied = DENIAL_MARKERS.any { text.contains(it, ignoreCase = true) }
        return if (denied) {
            ProbeResult(ProbeOutcome.BLOCKED, "El sistema denegó la $what: $text")
        } else {
            ProbeResult(ProbeOutcome.INCONCLUSIVE, "La $what falló por otro motivo, no concluyente: $text")
        }
    }

    private fun describe(e: Throwable): String =
        "${e::class.java.simpleName}: ${e.message.orEmpty()}".take(MAX_DETAIL)

    private companion object {
        const val NETWORK_TIMEOUT_MS = 3_000
        const val MAX_DETAIL = 300
        const val MAX_NAME = 200
        val DENIAL_MARKERS = listOf("EACCES", "EPERM", "Permission denied", "Operation not permitted")
        val NOT_FOUND_MARKERS = listOf("ENOENT", "No such file")
    }
}

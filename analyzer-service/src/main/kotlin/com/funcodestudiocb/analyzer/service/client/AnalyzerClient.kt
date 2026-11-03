package com.funcodestudiocb.analyzer.service.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.FrameLimits
import com.funcodestudiocb.analyzer.engine.api.FrameReader
import com.funcodestudiocb.analyzer.engine.api.ProtocolJson
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionReadResult
import com.funcodestudiocb.analyzer.engine.api.SessionReader
import com.funcodestudiocb.analyzer.engine.core.ProgressUpdate
import com.funcodestudiocb.analyzer.service.AnalyzerService
import com.funcodestudiocb.analyzer.service.IAnalysisCallback
import com.funcodestudiocb.analyzer.service.IAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Cómo terminó una ejecución del motor, visto desde el proceso principal. */
enum class RunStatus {
    /** Sesión completa y válida. */
    COMPLETED,

    /** El flujo terminó sin su registro final: el proceso aislado murió o cerró el canal. Resultados parciales. */
    INCOMPLETE,

    /** El proceso aislado envió datos que violan el protocolo. Se descarta lo posterior a la violación. */
    PROTOCOL_VIOLATION,

    /** El vigilante detuvo el análisis por superar el tiempo máximo. */
    WATCHDOG_TIMEOUT,

    /** No se pudo conectar con el servicio de análisis. */
    BIND_FAILED,

    /** El servicio rechazó la petición (configuración inválida, análisis en curso…). */
    REJECTED,
}

data class AnalyzerRun(
    val status: RunStatus,
    val session: SessionReadResult?,
    /** El proceso aislado terminó de forma inesperada durante la ejecución. */
    val processDied: Boolean,
    val message: String?,
)

/**
 * Cliente del proceso de análisis aislado, usado desde el proceso principal.
 *
 * Para cada ejecución: se enlaza el servicio (el sistema crea un proceso aislado nuevo), se le pasa
 * una tubería, se leen y validan los resultados y, al terminar o si el vigilante lo decide, se
 * desenlaza, lo que destruye el proceso aislado.
 */
class AnalyzerClient(
    context: Context,
    private val readLimits: FrameLimits = FrameLimits(),
    private val bindTimeoutMillis: Long = 15_000,
    private val watchdogGraceMillis: Long = 10_000,
    private val shutdownWaitMillis: Long = 10_000,
) {
    private val appContext = context.applicationContext

    /**
     * @param archive comprimido de solo lectura (se cierra al terminar); `null` en el modo diagnóstico.
     * @param password contraseña del comprimido; se envía al proceso aislado y quien llama debe borrarla después.
     */
    suspend fun run(
        config: AnalysisConfig,
        archive: ParcelFileDescriptor? = null,
        password: CharArray? = null,
        onProgress: (ProgressUpdate) -> Unit = {},
        /** Si es `false`, los registros solo llegan por [onRecord] (evita duplicarlos en memoria). */
        bufferRecords: Boolean = true,
        onRecord: (ResultRecord) -> Unit = {},
    ): AnalyzerRun = withContext(Dispatchers.IO) {
        try {
            runConnected(config, archive, password, onProgress, if (bufferRecords) MAX_BUFFERED else 0, onRecord)
        } finally {
            runCatching { archive?.close() }
        }
    }

    private suspend fun runConnected(
        config: AnalysisConfig,
        archive: ParcelFileDescriptor?,
        password: CharArray?,
        onProgress: (ProgressUpdate) -> Unit,
        maxBuffered: Int,
        onRecord: (ResultRecord) -> Unit,
    ): AnalyzerRun = withContext(Dispatchers.IO) {
        val configBytes = ProtocolJson.encodeToString(AnalysisConfig.serializer(), config).toByteArray()
        val connection = AnalyzerConnection(appContext)
        val analyzer = try {
            withTimeoutOrNull(bindTimeoutMillis) { connection.connect() }
        } catch (e: IllegalStateException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: run {
                connection.disconnect()
                return@withContext AnalyzerRun(RunStatus.BIND_FAILED, null, false, "No se pudo iniciar el proceso de análisis")
            }

        try {
            if (analyzer.protocolVersion() != AnalyzerService.PROTOCOL_VERSION) {
                return@withContext AnalyzerRun(RunStatus.REJECTED, null, false, "Versión de protocolo incompatible")
            }
            val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
            try {
                analyzer.start(configBytes, archive, password, writeEnd, ProgressBridge(onProgress))
            } catch (e: RemoteException) {
                readEnd.close()
                return@withContext AnalyzerRun(RunStatus.REJECTED, null, connection.died.get(), "El servicio no respondió")
            } catch (e: RuntimeException) {
                // IllegalArgumentException, IllegalStateException o SecurityException propagadas por Binder.
                readEnd.close()
                return@withContext AnalyzerRun(RunStatus.REJECTED, null, false, e.message)
            } finally {
                // El proceso aislado tiene su propia copia; si murió, el lector verá el final del flujo.
                writeEnd.close()
            }

            // La lectura bloqueante va en un ámbito propio: si el proceso aislado no muere a tiempo,
            // el vigilante puede abandonarla en lugar de quedarse esperando indefinidamente.
            val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reading = readerScope.async {
                ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                    SessionReader(FrameReader(input, readLimits), maxBuffered, onRecord).readAll()
                }
            }
            val session = withTimeoutOrNull(config.limits.maxTotalMillis + watchdogGraceMillis) { reading.await() }
            if (session != null) {
                return@withContext AnalyzerRun(statusOf(session), session, connection.died.get(), session.violation)
            }
            // Vigilante: pedir cancelación y destruir el proceso aislado. Al morir, se cierra su extremo
            // de la tubería y el lector termina con lo que haya recibido.
            runCatching { analyzer.cancel() }
            connection.disconnect()
            val partial = withTimeoutOrNull(shutdownWaitMillis) { reading.await() }
            if (partial == null) readerScope.cancel()
            AnalyzerRun(
                RunStatus.WATCHDOG_TIMEOUT,
                partial,
                connection.died.get(),
                "El análisis superó el tiempo máximo y se detuvo el proceso aislado",
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { analyzer.cancel() } }
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_BUFFERED = 100_000
    }

    private fun statusOf(session: SessionReadResult): RunStatus = when {
        session.violation != null -> RunStatus.PROTOCOL_VIOLATION
        session.isComplete -> RunStatus.COMPLETED
        else -> RunStatus.INCOMPLETE
    }

    private class ProgressBridge(private val onProgress: (ProgressUpdate) -> Unit) : IAnalysisCallback.Stub() {
        override fun onProgress(
            stageId: String?,
            stageTitle: String?,
            stageIndex: Int,
            stageCount: Int,
            stageFraction: Float,
            message: String?,
        ) {
            // Datos del proceso aislado: se acotan antes de mostrarlos.
            if (stageCount !in 0..MAX_STAGES || stageIndex !in 0 until maxOf(stageCount, 1)) return
            if (stageFraction.isNaN()) return
            onProgress(
                ProgressUpdate(
                    stageId = stageId.orEmpty().take(64),
                    stageTitle = stageTitle.orEmpty().take(MAX_TEXT),
                    stageIndex = stageIndex,
                    stageCount = stageCount,
                    stageFraction = stageFraction.coerceIn(0f, 1f),
                    message = message?.take(MAX_TEXT),
                ),
            )
        }

        override fun onFinished(outcome: Int, message: String?) = Unit

        private companion object {
            const val MAX_STAGES = 128
            const val MAX_TEXT = 200
        }
    }
}

/** Conexión con el servicio aislado. */
private class AnalyzerConnection(private val context: Context) : ServiceConnection {

    private val connected = CompletableDeferred<IAnalyzer>()
    private val bound = AtomicBoolean(false)
    val died = AtomicBoolean(false)

    private val deathRecipient = IBinder.DeathRecipient { died.set(true) }

    suspend fun connect(): IAnalyzer {
        val intent = Intent(context, AnalyzerService::class.java)
        if (!context.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
            throw IllegalStateException("bindService devolvió false")
        }
        bound.set(true)
        return connected.await()
    }

    fun disconnect() {
        if (bound.compareAndSet(true, false)) {
            runCatching { context.unbindService(this) }
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder) {
        runCatching { service.linkToDeath(deathRecipient, 0) }.onFailure { died.set(true) }
        connected.complete(IAnalyzer.Stub.asInterface(service))
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        died.set(true)
    }

    override fun onBindingDied(name: ComponentName?) {
        died.set(true)
    }
}

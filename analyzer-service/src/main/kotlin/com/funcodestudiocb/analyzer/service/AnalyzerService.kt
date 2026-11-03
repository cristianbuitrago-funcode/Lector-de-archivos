package com.funcodestudiocb.analyzer.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.ConfigValidator
import com.funcodestudiocb.analyzer.engine.api.FrameWriter
import com.funcodestudiocb.analyzer.engine.api.ProtocolJson
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionEnd
import com.funcodestudiocb.analyzer.engine.api.SessionStart
import com.funcodestudiocb.analyzer.engine.api.StageEvent
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import com.funcodestudiocb.analyzer.engine.core.ProgressListener
import com.funcodestudiocb.analyzer.engine.core.ProgressUpdate
import com.funcodestudiocb.analyzer.engine.core.RecordSink
import com.funcodestudiocb.analyzer.engine.core.SimulatedHangStage
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.core.StageRegistry
import com.funcodestudiocb.analyzer.engine.core.SyntheticRecordsStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Servicio que ejecuta el motor de análisis en el proceso aislado (`:analyzer`).
 *
 * Solo acepta llamadas de la propia app, valida de nuevo la configuración recibida, ejecuta un análisis
 * a la vez y escribe los resultados como tramas en la tubería que le entrega el proceso principal.
 */
class AnalyzerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicReference<Job?>(null)

    private val binder = object : IAnalyzer.Stub() {

        override fun protocolVersion(): Int = PROTOCOL_VERSION

        override fun start(config: ByteArray, results: ParcelFileDescriptor, callback: IAnalysisCallback) {
            enforceCaller()
            val output = ParcelFileDescriptor.AutoCloseOutputStream(results)
            val parsed = try {
                parseConfig(config)
            } catch (e: IllegalArgumentException) {
                output.close()
                throw e
            }
            val job = scope.launch(start = CoroutineStart.LAZY) { runAnalysis(parsed, output, callback) }
            if (!running.compareAndSet(null, job)) {
                job.cancel()
                output.close()
                throw IllegalStateException("Ya hay un análisis en curso")
            }
            job.invokeOnCompletion { running.compareAndSet(job, null) }
            job.start()
        }

        override fun cancel() {
            enforceCaller()
            running.get()?.cancel()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        // Defensa adicional: una etapa bloqueada sin cooperar no se detiene con la cancelación.
        // Al destruirse el servicio, el proceso aislado termina inmediatamente.
        Process.killProcess(Process.myPid())
    }

    private fun enforceCaller() {
        if (Binder.getCallingUid() != applicationInfo.uid) {
            throw SecurityException("Llamada no autorizada al servicio de análisis")
        }
    }

    private fun parseConfig(bytes: ByteArray): AnalysisConfig {
        require(bytes.size <= MAX_CONFIG_BYTES) { "Configuración demasiado grande" }
        val config = try {
            ProtocolJson.decodeFromString(AnalysisConfig.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Configuración mal formada", e)
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Configuración mal codificada", e)
        }
        val errors = ConfigValidator.validate(config)
        require(errors.isEmpty()) { "Configuración inválida: ${errors.joinToString("; ")}" }
        return config
    }

    private suspend fun runAnalysis(
        config: AnalysisConfig,
        output: ParcelFileDescriptor.AutoCloseOutputStream,
        callback: IAnalysisCallback,
    ) {
        val writer = FrameWriter(output)
        val progress = ThrottledProgress(callback) { running.get()?.cancel() }
        try {
            val result = PipelineRunner(ENGINE_VERSION).run(config, stagesFor(config), FlushingSink(writer), progress)
            writer.flush()
            progress.finished(result.outcome.ordinal, null)
        } catch (e: IOException) {
            // El proceso principal cerró la tubería: ya no hay nadie a quien informar.
            progress.finished(-1, "Canal de resultados cerrado")
        } finally {
            runCatching { writer.close() }
        }
    }

    private fun stagesFor(config: AnalysisConfig): List<Stage> = when (config.mode) {
        AnalysisMode.DIAGNOSTIC -> {
            val registry = StageRegistry()
                .register(IsolationProbeStage())
                .register(SyntheticRecordsStage())
            if ((config.diagnostic?.simulateHangMillis ?: 0) > 0) registry.register(SimulatedHangStage())
            registry.plan()
        }
        // El ConfigValidator rechaza este modo hasta la Fase 2.
        AnalysisMode.FULL -> emptyList()
    }

    /**
     * Escribe los registros y vacía el búfer en cada límite de sesión o etapa y, como mucho, cada
     * [FLUSH_INTERVAL_MS]. Así, si el motor se bloquea o muere, el proceso principal conserva lo producido hasta
     * entonces (resultados parciales) en lugar de perderlo en el búfer.
     */
    private class FlushingSink(private val writer: FrameWriter) : RecordSink {
        private var lastFlush = SystemClock.elapsedRealtime()

        override fun accept(record: ResultRecord) {
            writer.write(record)
            val now = SystemClock.elapsedRealtime()
            val boundary = record is SessionStart || record is StageEvent || record is SessionEnd
            if (boundary || now - lastFlush >= FLUSH_INTERVAL_MS) {
                writer.flush()
                lastFlush = now
            }
        }

        private companion object {
            const val FLUSH_INTERVAL_MS = 250L
        }
    }

    /** Reenvía el progreso al proceso principal sin saturar Binder (como mucho cada [INTERVAL_MS]). */
    private class ThrottledProgress(
        private val callback: IAnalysisCallback,
        private val onClientGone: () -> Unit,
    ) : ProgressListener {
        private var lastSent = 0L
        private var lastStage = -1

        override fun onProgress(update: ProgressUpdate) {
            val now = SystemClock.elapsedRealtime()
            val isBoundary = update.stageIndex != lastStage || update.stageFraction >= 1f
            if (!isBoundary && now - lastSent < INTERVAL_MS) return
            lastSent = now
            lastStage = update.stageIndex
            send {
                it.onProgress(
                    update.stageId, update.stageTitle, update.stageIndex, update.stageCount,
                    update.stageFraction, update.message,
                )
            }
        }

        fun finished(outcome: Int, message: String?) = send { it.onFinished(outcome, message) }

        private inline fun send(block: (IAnalysisCallback) -> Unit) {
            try {
                block(callback)
            } catch (e: RemoteException) {
                onClientGone()
            }
        }

        private companion object {
            const val INTERVAL_MS = 100L
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val ENGINE_VERSION = "0.1.0"
        private const val MAX_CONFIG_BYTES = 64 * 1024
    }
}

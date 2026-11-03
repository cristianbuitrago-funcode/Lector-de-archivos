package com.funcodestudiocb.analyzer

import android.content.Context
import androidx.room.Room
import com.funcodestudiocb.analyzer.analysis.AnalysisEngine
import com.funcodestudiocb.analyzer.analysis.EngineRun
import com.funcodestudiocb.analyzer.analysis.ForegroundController
import com.funcodestudiocb.analyzer.data.db.FuncodeDatabase
import com.funcodestudiocb.analyzer.data.vault.Aead
import com.funcodestudiocb.analyzer.data.vault.DeviceKeyProtector
import com.funcodestudiocb.analyzer.data.vault.DeviceKeyUnavailableException
import com.funcodestudiocb.analyzer.data.vault.KdfProfile
import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.FrameReader
import com.funcodestudiocb.analyzer.engine.api.FrameWriter
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionReader
import com.funcodestudiocb.analyzer.engine.core.Attributes
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import com.funcodestudiocb.analyzer.engine.core.ProgressUpdate
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveSource
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.PasswordHolder
import com.funcodestudiocb.analyzer.engine.inventory.FullPipeline
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.SecureRandom

/** Sustituto del Android Keystore (Robolectric no lo implementa). */
class FakeDeviceProtector : DeviceKeyProtector {
    private var key: ByteArray? = ByteArray(32).also(SecureRandom()::nextBytes)
    override fun protect(plaintext: ByteArray) = Aead.seal(key!!, plaintext, byteArrayOf())
    override fun unprotect(blob: ByteArray): ByteArray =
        Aead.open(key ?: throw DeviceKeyUnavailableException("sin clave"), blob, byteArrayOf())
    override fun destroy() {
        key = null
    }
}

/**
 * Motor en el mismo proceso (solo pruebas en la JVM, sin proceso aislado). Sus registros pasan por el mismo
 * protocolo de tramas y el mismo lector validador que en la app real.
 */
class InProcessAnalysisEngine : AnalysisEngine {
    override suspend fun analyze(
        config: AnalysisConfig,
        archive: File,
        password: CharArray?,
        onProgress: (ProgressUpdate) -> Unit,
        onRecord: (ResultRecord) -> Unit,
    ): EngineRun {
        val bytes = ByteArrayOutputStream()
        val writer = FrameWriter(bytes)
        FileInputStream(archive).use { stream ->
            val attributes = Attributes()
            attributes[IngestKeys.SOURCE] = ArchiveSource(stream.channel, archive.absolutePath)
            attributes[IngestKeys.PASSWORD] = PasswordHolder(password?.copyOf())
            PipelineRunner("prueba").run(config, FullPipeline.stages(), writer::write, attributes) { onProgress(it) }
        }
        writer.close()
        val session = SessionReader(FrameReader(ByteArrayInputStream(bytes.toByteArray())), maxBufferedRecords = 0, onRecord = onRecord).readAll()
        return EngineRun(session.end?.outcome, session.violation)
    }
}

/** Contenedor de pruebas: Keystore simulado, Argon2 rápido, BD en memoria, motor en el mismo proceso. */
fun testContainer(context: Context, engine: AnalysisEngine = InProcessAnalysisEngine()): AppContainer = AppContainer(
    appContext = context,
    deviceProtector = FakeDeviceProtector(),
    biometricKeys = null,
    kdfProfile = KdfProfile(memoryKiB = 1024, iterations = 1, parallelism = 1),
    openDatabase = { ctx, key ->
        key.fill(0)
        Room.inMemoryDatabaseBuilder(ctx, FuncodeDatabase::class.java).allowMainThreadQueries().build()
    },
    diagnosticsFactory = null,
    engine = engine,
    foreground = ForegroundController.NONE,
)

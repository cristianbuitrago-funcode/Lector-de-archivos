package com.funcodestudiocb.analyzer

import android.content.Context
import com.funcodestudiocb.analyzer.analysis.AnalysisCoordinator
import com.funcodestudiocb.analyzer.analysis.AnalysisEngine
import com.funcodestudiocb.analyzer.analysis.AnalysisForegroundService
import com.funcodestudiocb.analyzer.analysis.AnalysisRepository
import com.funcodestudiocb.analyzer.analysis.ArchiveImporter
import com.funcodestudiocb.analyzer.analysis.ForegroundController
import com.funcodestudiocb.analyzer.analysis.IsolatedAnalysisEngine
import com.funcodestudiocb.analyzer.data.DataWiper
import com.funcodestudiocb.analyzer.data.db.FuncodeDatabase
import com.funcodestudiocb.analyzer.data.session.SessionManager
import com.funcodestudiocb.analyzer.data.vault.AndroidBiometricKeyProvider
import com.funcodestudiocb.analyzer.data.vault.AndroidKeystoreDeviceProtector
import com.funcodestudiocb.analyzer.data.vault.BiometricKeyProvider
import com.funcodestudiocb.analyzer.data.vault.DeviceKeyProtector
import com.funcodestudiocb.analyzer.data.vault.KdfProfile
import com.funcodestudiocb.analyzer.data.vault.Vault
import com.funcodestudiocb.analyzer.data.vault.VaultStorage
import com.funcodestudiocb.analyzer.data.vault.WipeReason
import com.funcodestudiocb.analyzer.service.client.IsolationDiagnostics
import com.funcodestudiocb.analyzer.vulndb.Downloader
import com.funcodestudiocb.analyzer.vulndb.HttpsDownloader
import com.funcodestudiocb.analyzer.vulndb.VulnDbStore
import com.funcodestudiocb.analyzer.vulndb.VulnDbUpdateService
import com.funcodestudiocb.analyzer.vulndb.VulnDbUpdater
import java.io.File

/**
 * Dependencias de la app (inyección manual). Las pruebas construyen su propio contenedor con
 * implementaciones falsas del Keystore, una base de datos en memoria y el motor en el mismo proceso.
 */
class AppContainer(
    val appContext: Context,
    deviceProtector: DeviceKeyProtector,
    biometricKeys: BiometricKeyProvider?,
    kdfProfile: KdfProfile = KdfProfile.DEFAULT,
    openDatabase: (Context, ByteArray) -> FuncodeDatabase = FuncodeDatabase::open,
    diagnosticsFactory: ((Context) -> IsolationDiagnostics)? = { IsolationDiagnostics(it) },
    engine: AnalysisEngine = IsolatedAnalysisEngine(appContext),
    foreground: ForegroundController = AnalysisForegroundService.controller(appContext),
    downloader: Downloader = HttpsDownloader(),
    updateForeground: ForegroundController = VulnDbUpdateService.controller(appContext),
    /** Espacio libre para el actualizador; `null` = el del sistema ([allocatableBytes]). */
    freeSpace: ((File) -> Long)? = null,
) {
    private val wiper = DataWiper(appContext)
    private val wipeNoticeFile = File(appContext.noBackupFilesDir, "aviso_borrado")

    val vault: Vault = Vault(
        storage = VaultStorage(File(appContext.noBackupFilesDir, "vault/vault.json")),
        device = deviceProtector,
        biometric = biometricKeys,
        kdfProfile = kdfProfile,
        onWipe = { reason ->
            coordinator.forgetEverything()
            vulnDbUpdater.cancel()
            session.lock()
            wiper.wipeAppData()
            if (reason == WipeReason.FAILED_ATTEMPTS) runCatching { wipeNoticeFile.writeText("1") }
            session.reset()
        },
    )

    val session: SessionManager = SessionManager(
        isConfigured = { vault.isConfigured() },
        openDatabase = { key -> openDatabase(appContext, key) },
    )

    /** `null` cuando no hay servicio aislado disponible (pruebas en la JVM). */
    val diagnostics: IsolationDiagnostics? = diagnosticsFactory?.invoke(appContext)

    val repository = AnalysisRepository(session)

    /** Base de vulnerabilidades (datos públicos) y su actualizador, que solo se ejecuta cuando el usuario lo pide. */
    val vulnDbStore = VulnDbStore(File(appContext.noBackupFilesDir, "vulnerabilidades"))
    val vulnDbUpdater = VulnDbUpdater(vulnDbStore, downloader, updateForeground, freeSpace = freeSpace ?: ::allocatableBytes)

    /** Espacio que se puede ocupar, contando la caché que el sistema puede liberar. */
    internal fun allocatableBytes(dir: File): Long {
        val storage = appContext.getSystemService(android.os.storage.StorageManager::class.java) ?: return dir.usableSpace
        return try {
            storage.getAllocatableBytes(storage.getUuidForPath(dir))
        } catch (e: java.io.IOException) {
            dir.usableSpace
        }
    }

    val coordinator: AnalysisCoordinator = AnalysisCoordinator(
        importer = ArchiveImporter(appContext.contentResolver, File(appContext.noBackupFilesDir, "import")),
        engine = engine,
        repository = repository,
        session = session,
        spoolDirectory = File(appContext.noBackupFilesDir, "copia-codigo"),
        foreground = foreground,
        vulnDb = { vulnDbStore.indexOrNull() },
    )

    /** Se borraron los datos por intentos fallidos: la configuración inicial lo explica una vez. */
    fun hasWipeNotice(): Boolean = wipeNoticeFile.exists()

    fun dismissWipeNotice() {
        wipeNoticeFile.delete()
    }

    companion object {
        fun production(context: Context): AppContainer {
            val app = context.applicationContext
            return AppContainer(
                appContext = app,
                deviceProtector = AndroidKeystoreDeviceProtector(app),
                biometricKeys = AndroidBiometricKeyProvider(app),
            )
        }
    }
}

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

    val coordinator: AnalysisCoordinator = AnalysisCoordinator(
        importer = ArchiveImporter(appContext.contentResolver, File(appContext.noBackupFilesDir, "import")),
        engine = engine,
        repository = repository,
        session = session,
        spoolDirectory = File(appContext.noBackupFilesDir, "copia-codigo"),
        foreground = foreground,
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

package com.funcodestudiocb.analyzer

import android.content.Context
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
import com.funcodestudiocb.analyzer.service.client.IsolationDiagnostics
import java.io.File

/**
 * Dependencias de la app (inyección manual). Las pruebas construyen su propio contenedor con
 * implementaciones falsas del Keystore y una base de datos en memoria.
 */
class AppContainer(
    val appContext: Context,
    deviceProtector: DeviceKeyProtector,
    biometricKeys: BiometricKeyProvider?,
    kdfProfile: KdfProfile = KdfProfile.DEFAULT,
    openDatabase: (Context, ByteArray) -> FuncodeDatabase = FuncodeDatabase::open,
    diagnosticsFactory: ((Context) -> IsolationDiagnostics)? = { IsolationDiagnostics(it) },
) {
    private val wiper = DataWiper(appContext)

    val vault: Vault = Vault(
        storage = VaultStorage(File(appContext.noBackupFilesDir, "vault/vault.json")),
        device = deviceProtector,
        biometric = biometricKeys,
        kdfProfile = kdfProfile,
        onWipe = {
            session.lock()
            wiper.wipeAppData()
            session.reset()
        },
    )

    val session: SessionManager = SessionManager(
        isConfigured = { vault.isConfigured() },
        openDatabase = { key -> openDatabase(appContext, key) },
    )

    /** `null` cuando no hay servicio aislado disponible (pruebas en la JVM). */
    val diagnostics: IsolationDiagnostics? = diagnosticsFactory?.invoke(appContext)

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

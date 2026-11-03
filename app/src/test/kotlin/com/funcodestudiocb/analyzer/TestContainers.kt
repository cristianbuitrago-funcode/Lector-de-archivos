package com.funcodestudiocb.analyzer

import android.content.Context
import androidx.room.Room
import com.funcodestudiocb.analyzer.data.db.FuncodeDatabase
import com.funcodestudiocb.analyzer.data.vault.Aead
import com.funcodestudiocb.analyzer.data.vault.DeviceKeyProtector
import com.funcodestudiocb.analyzer.data.vault.DeviceKeyUnavailableException
import com.funcodestudiocb.analyzer.data.vault.KdfProfile
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

/** Contenedor de pruebas: Keystore simulado, Argon2 rápido, BD en memoria y sin proceso aislado. */
fun testContainer(context: Context): AppContainer = AppContainer(
    appContext = context,
    deviceProtector = FakeDeviceProtector(),
    biometricKeys = null,
    kdfProfile = KdfProfile(memoryKiB = 1024, iterations = 1, parallelism = 1),
    openDatabase = { ctx, key ->
        key.fill(0)
        Room.inMemoryDatabaseBuilder(ctx, FuncodeDatabase::class.java).allowMainThreadQueries().build()
    },
    diagnosticsFactory = null,
)

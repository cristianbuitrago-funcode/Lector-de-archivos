package com.funcodestudiocb.analyzer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.funcodestudiocb.analyzer.data.db.FuncodeDatabase
import com.funcodestudiocb.analyzer.data.db.RunEntity
import com.funcodestudiocb.analyzer.data.vault.AndroidKeystoreDeviceProtector
import com.funcodestudiocb.analyzer.data.vault.DeviceKeyUnavailableException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class EncryptedStorageInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clean() {
        FuncodeDatabase.databaseFiles(context).forEach { it.delete() }
        AndroidKeystoreDeviceProtector(context, "funcode.test.device").destroy()
    }

    private fun newKey() = ByteArray(32).also(SecureRandom()::nextBytes)

    @Test
    fun laBaseDeDatosQuedaCifradaEnDisco() = runBlocking {
        val key = newKey()
        val db = FuncodeDatabase.open(context, key.copyOf())
        db.runs().insert(RunEntity("r1", "DIAGNOSTIC", "COMPLETED", 1, 2, "MARCADOR-EN-CLARO", true))
        db.close()

        val bytes = context.getDatabasePath(FuncodeDatabase.FILE_NAME).readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.startsWith("SQLite format 3"), "La cabecera de SQLite no debe aparecer en claro")
        assertFalse(text.contains("MARCADOR-EN-CLARO"), "El contenido no debe aparecer en claro")

        val reopened = FuncodeDatabase.open(context, key.copyOf())
        assertEquals(1, reopened.runs().countByMode("DIAGNOSTIC").first())
        reopened.close()
    }

    @Test
    fun conOtraClaveNoSePuedeAbrir() {
        val db = FuncodeDatabase.open(context, newKey())
        db.openHelper.writableDatabase
        db.close()

        val wrong = FuncodeDatabase.open(context, newKey())
        assertFailsWith<Exception> { wrong.openHelper.writableDatabase }
        wrong.close()
    }

    @Test
    fun laClaveDelDispositivoProtegeYSeDestruye() {
        val protector = AndroidKeystoreDeviceProtector(context, "funcode.test.device")
        val secret = newKey()
        val blob = protector.protect(secret)
        assertContentEquals(secret, protector.unprotect(blob))

        protector.destroy()
        assertFailsWith<DeviceKeyUnavailableException> { protector.unprotect(blob) }
    }
}

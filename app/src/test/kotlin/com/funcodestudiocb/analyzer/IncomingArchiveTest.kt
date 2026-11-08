package com.funcodestudiocb.analyzer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class IncomingArchiveTest {

    private val own = "com.funcodestudiocb.analyzer"

    @Test
    fun soloContenidoDeOtrasApps() {
        assertTrue(IncomingArchive.acceptable(Uri.parse("content://com.android.providers.downloads.documents/document/42"), own))
        assertTrue(IncomingArchive.acceptable(Uri.parse("content://com.funcodestudiocb.otraapp.files/x.zip"), own))
        assertFalse(IncomingArchive.acceptable(Uri.parse("file:///sdcard/Download/proyecto.zip"), own))
        assertFalse(IncomingArchive.acceptable(Uri.parse("file:///data/user/0/$own/databases/funcode.db"), own))
        assertFalse(IncomingArchive.acceptable(Uri.parse("content://$own.informes/informes-compartidos/informe.html"), own), "Proveedor propio")
        assertFalse(IncomingArchive.acceptable(Uri.parse("content://$own/x"), own))
        assertFalse(IncomingArchive.acceptable(Uri.parse("content://COM.FUNCODESTUDIOCB.ANALYZER.informes/x"), own), "Sin distinguir mayúsculas")
        assertFalse(IncomingArchive.acceptable(Uri.parse("content:///sin-autoridad"), own))
    }
}

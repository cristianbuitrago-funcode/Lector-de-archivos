package com.funcodestudiocb.analyzer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.funcodestudiocb.analyzer.ui.FuncodeRoot
import com.funcodestudiocb.analyzer.ui.theme.FuncodeTheme

/** Única actividad. Hereda de FragmentActivity porque BiometricPrompt lo requiere. */
class MainActivity : FragmentActivity() {

    private val app: FuncodeApp get() = application as FuncodeApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sin capturas de pantalla ni miniaturas en "recientes": el contenido es confidencial.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            FuncodeTheme { FuncodeRoot(app.container) }
        }
        // Tras un giro de pantalla la actividad se recrea con el mismo intent: solo se procesa la primera vez.
        if (savedInstanceState == null) handleIncomingArchive(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingArchive(intent)
    }

    /**
     * Comprimido abierto o compartido desde otra app. Se copia de inmediato al almacenamiento privado (el
     * permiso de lectura que concede la otra app es temporal); el análisis empieza cuando el usuario lo pide.
     */
    private fun handleIncomingArchive(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        app.container.coordinator.select(uri, fromOtherApp = true)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        app.autoLock.onUserInteraction()
    }
}

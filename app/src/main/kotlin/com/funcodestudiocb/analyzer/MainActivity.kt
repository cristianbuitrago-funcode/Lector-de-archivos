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
        // Contra el «tapjacking»: si otra app dibuja encima, los toques no llegan (Android 12+ ya lo hace por defecto).
        window.decorView.filterTouchesWhenObscured = true
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
        if (!IncomingArchive.acceptable(uri, packageName)) return
        app.container.coordinator.select(uri, fromOtherApp = true)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        app.autoLock.onUserInteraction()
    }
}

/** Qué comprimidos de otras apps se aceptan. */
internal object IncomingArchive {
    /**
     * Solo `content://` (nunca `file://`, que permitiría pedir a la app que lea sus propios archivos) y nunca de un
     * proveedor de esta misma app: otra app podría apuntar a uno para que Funcode importe datos internos.
     */
    fun acceptable(uri: Uri, ownPackage: String): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority?.lowercase().orEmpty()
        val own = ownPackage.lowercase()
        return authority.isNotEmpty() && authority != own && !authority.startsWith("$own.")
    }
}

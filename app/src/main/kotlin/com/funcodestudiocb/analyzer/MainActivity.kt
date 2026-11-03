package com.funcodestudiocb.analyzer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        app.autoLock.onUserInteraction()
    }
}

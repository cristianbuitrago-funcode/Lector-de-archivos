package com.funcodestudiocb.analyzer

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.funcodestudiocb.analyzer.security.AutoLock
import com.funcodestudiocb.analyzer.service.ProcessInfo

class FuncodeApp : Application() {

    /** Contenedor de dependencias. No existe en el proceso aislado. */
    lateinit var container: AppContainer
        private set

    lateinit var autoLock: AutoLock
        private set

    override fun onCreate() {
        super.onCreate()
        // Esta clase también se instancia en el proceso de análisis aislado, que no tiene acceso al
        // almacenamiento ni a la base de datos: allí no se inicializa nada.
        if (ProcessInfo.isIsolatedProcess()) return

        container = AppContainer.production(this)
        autoLock = AutoLock(container.vault, container.session)
        ProcessLifecycleOwner.get().lifecycle.addObserver(autoLock)
    }
}

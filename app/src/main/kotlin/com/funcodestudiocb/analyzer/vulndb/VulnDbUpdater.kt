package com.funcodestudiocb.analyzer.vulndb

import com.funcodestudiocb.analyzer.analysis.ForegroundController
import com.funcodestudiocb.analyzer.engine.vulndb.EolData
import com.funcodestudiocb.analyzer.engine.vulndb.EolProduct
import com.funcodestudiocb.analyzer.engine.vulndb.OsvEcosystems
import com.funcodestudiocb.analyzer.engine.vulndb.VulnIndexWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Estado de la actualización de la base de vulnerabilidades. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data class Running(
        /** Paso actual, en español («Descargando PyPI»…). */
        val step: String,
        /** Progreso total aproximado (0–1). */
        val overall: Float,
        val detail: String? = null,
    ) : UpdateState

    data class Done(val status: VulnDbStatus) : UpdateState

    /** Falló; la base anterior (si la había) sigue instalada y sin cambios. */
    data class Failed(val message: String) : UpdateState

    data object Cancelled : UpdateState
}

/**
 * Descarga los volcados públicos de OSV de los ecosistemas elegidos y los datos de endoflife.date, construye el índice
 * y lo instala de forma atómica. Nada del código analizado sale del teléfono: se piden los archivos completos de cada
 * ecosistema, no los paquetes de ningún proyecto.
 *
 * Si algo falla (red, espacio, servidor, volcado dañado), la base anterior queda intacta.
 */
class VulnDbUpdater(
    private val store: VulnDbStore,
    private val downloader: Downloader,
    private val foreground: ForegroundController = ForegroundController.NONE,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Bytes que se pueden ocupar en la carpeta indicada; 0 si no se sabe (no se comprueba). */
    private val freeSpace: (File) -> Long = { 0L },
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val cancelled = AtomicBoolean(false)
    private var job: Job? = null
    private val lock = Any()

    val running: Boolean get() = _state.value is UpdateState.Running

    /** Empieza a actualizar con [preferences]. Devuelve `false` si ya hay una actualización o no se eligió nada. */
    fun start(preferences: VulnDbPreferences): Boolean = synchronized(lock) {
        if (job?.isActive == true) return false
        val ecosystems = OsvEcosystems.SUPPORTED.filter { it in preferences.ecosystems }
        if (ecosystems.isEmpty() && !preferences.endOfLife) return false
        store.savePreferences(preferences)
        cancelled.set(false)
        _state.value = UpdateState.Running("Preparando", 0f)
        foreground.start()
        job = scope.launch {
            _state.value = try {
                UpdateState.Done(update(ecosystems, preferences.endOfLife))
            } catch (e: CancellationException) {
                UpdateState.Cancelled
            } catch (e: DownloadTooLargeException) {
                UpdateState.Failed("Un archivo descargado supera el tamaño máximo permitido. No se instaló nada.")
            } catch (e: HttpStatusException) {
                UpdateState.Failed("El servidor respondió con un error (${e.status}). Prueba más tarde. La base anterior se conserva.")
            } catch (e: NotEnoughSpaceException) {
                UpdateState.Failed(e.message.orEmpty())
            } catch (e: IOException) {
                UpdateState.Failed("No se pudo completar la actualización (${e.message ?: "error de red o de disco"}). La base anterior se conserva.")
            } catch (e: IllegalArgumentException) {
                UpdateState.Failed("No se pudo completar la actualización (${e.message ?: "datos no válidos"}). La base anterior se conserva.")
            } finally {
                runCatching { store.workDirectory.deleteRecursively() }
            }
        }
        true
    }

    /** Pide detener la actualización; los temporales se borran y la base anterior se conserva. */
    fun cancel() {
        cancelled.set(true)
    }

    /** Vuelve a [UpdateState.Idle] tras mostrar el resultado. */
    fun acknowledge() {
        synchronized(lock) {
            if (_state.value !is UpdateState.Running) _state.value = UpdateState.Idle
        }
    }

    /** Borra la base instalada (no se puede mientras se actualiza). */
    fun delete(): Boolean = synchronized(lock) {
        if (job?.isActive == true) return false
        store.delete()
        _state.value = UpdateState.Idle
        true
    }

    private fun update(ecosystems: List<String>, endOfLife: Boolean): VulnDbStatus {
        val work = store.workDirectory
        work.deleteRecursively()
        if (!work.mkdirs()) throw IOException("no se pudo crear la carpeta de trabajo")
        // Hace falta sitio para el comprimido más grande, los temporales del índice y el índice nuevo junto al viejo.
        val largest = ecosystems.maxOfOrNull { VulnDbStore.APPROX_DOWNLOAD_MB[it] ?: 50 } ?: 1
        val needed = (largest * 2L + ecosystems.sumOf { (VulnDbStore.APPROX_DOWNLOAD_MB[it] ?: 50).toLong() } / 2 + 64) * MB
        if (freeSpace(work) in 1 until needed) {
            throw NotEnoughSpaceException("No hay espacio suficiente: hacen falta unos ${needed / MB} MB libres. La base anterior se conserva.")
        }
        val weights = ecosystems.map { (VulnDbStore.APPROX_DOWNLOAD_MB[it] ?: 10).toFloat() }
        val eolWeight = if (endOfLife) 4f else 0f
        val total = weights.sum() + eolWeight + 2f
        var done = 0f
        fun report(step: String, part: Float, weight: Float, detail: String? = null) {
            _state.value = UpdateState.Running(step, ((done + part.coerceIn(0f, 1f) * weight) / total).coerceIn(0f, 1f), detail)
        }
        val now = clock()
        val missing = mutableListOf<String>()
        val indexDir = File(work, "indice").apply { mkdirs() }
        val info = VulnIndexWriter(indexDir).use { writer ->
            ecosystems.forEachIndexed { i, eco ->
                val weight = weights[i]
                val zip = File(work, "osv-${i}.zip")
                val got = downloader.download(OSV_URL.format(eco), zip, MAX_DUMP_BYTES, { bytes, of ->
                    report("Descargando $eco", if (of != null && of > 0) bytes.toFloat() / of * 0.7f else 0f, weight, "${bytes / MB} MB" + (of?.let { " de ${it / MB} MB" } ?: ""))
                }, cancelled::get)
                checkCancelled()
                writer.addOsvZip(eco, zip, got.lastModified ?: now, { n, of ->
                    report("Procesando $eco", 0.7f + if (of > 0) n.toFloat() / of * 0.3f else 0f, weight, "$n de $of avisos")
                }, cancelled::get)
                zip.delete()
                done += weight
            }
            if (endOfLife) {
                val products = mutableListOf<EolProduct>()
                EolData.PRODUCTS.entries.forEachIndexed { i, (product, label) ->
                    checkCancelled()
                    report("Descargando fechas de fin de soporte", i.toFloat() / EolData.PRODUCTS.size, eolWeight, label)
                    try {
                        products += EolData.parse(product, label, downloader.text(EOL_URL.format(product), MAX_EOL_BYTES))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Un producto que falla no impide el resto: se anota y se muestra en Ajustes.
                        missing += label
                    }
                }
                if (products.isEmpty()) throw IOException("no se pudo descargar ningún dato de fin de soporte")
                writer.setEol(products, now)
                done += eolWeight
            }
            checkCancelled()
            report("Construyendo el índice", 0f, 2f)
            val fresh = File(work, "nueva.fcv")
            writer.writeTo(fresh, now).also {
                checkCancelled()
                // Sustitución atómica: un análisis que ya abrió la base anterior sigue leyéndola sin problemas.
                store.directory.mkdirs()
                Files.move(fresh.toPath(), store.index.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }
        val status = VulnDbStatus.of(info, missing)
        store.saveStatus(status)
        return status
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Actualización cancelada")
    }

    private class NotEnoughSpaceException(message: String) : IOException(message)

    companion object {
        const val OSV_URL = "https://osv-vulnerabilities.storage.googleapis.com/%s/all.zip"
        const val EOL_URL = "https://endoflife.date/api/%s.json"
        private const val MB = 1024L * 1024
        /** Máximo de un volcado comprimido (npm ronda los 220 MB). */
        const val MAX_DUMP_BYTES = 1024L * MB
        const val MAX_EOL_BYTES = 2 * 1024 * 1024
    }
}

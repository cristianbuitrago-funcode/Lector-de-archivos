package com.funcodestudiocb.analyzer

import com.funcodestudiocb.analyzer.engine.vulndb.VulnDatabase
import com.funcodestudiocb.analyzer.vulndb.HttpsDownloader
import com.funcodestudiocb.analyzer.vulndb.UpdateState
import com.funcodestudiocb.analyzer.vulndb.VulnDbPreferences
import com.funcodestudiocb.analyzer.vulndb.VulnDbStore
import com.funcodestudiocb.analyzer.vulndb.VulnDbUpdater
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileInputStream
import java.net.URL
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class VulnDbUpdaterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun await(updater: VulnDbUpdater): UpdateState {
        val deadline = System.currentTimeMillis() + 30_000
        while (updater.state.value is UpdateState.Running) {
            if (System.currentTimeMillis() > deadline) fail("La actualización no terminó")
            Thread.sleep(5)
        }
        return updater.state.value
    }

    @Test
    fun `descarga los ecosistemas elegidos, construye el indice y lo instala`() {
        val store = VulnDbStore(tmp.newFolder("base"))
        assertNull(store.status())
        assertNull(store.indexOrNull())
        val downloader = FakeDownloader()
        val updater = VulnDbUpdater(store, downloader, clock = { 1_794_000_000_000 })
        assertTrue(updater.start(VulnDbPreferences(setOf("npm", "PyPI"), endOfLife = true)))
        val done = await(updater) as? UpdateState.Done ?: fail("Estado: ${updater.state.value}")

        // Solo se pidieron los volcados elegidos y los datos de fin de soporte: nada del proyecto.
        assertEquals(
            listOf("https://osv-vulnerabilities.storage.googleapis.com/npm/all.zip", "https://osv-vulnerabilities.storage.googleapis.com/PyPI/all.zip"),
            downloader.requested.filter { it.contains("osv") },
        )
        assertTrue(downloader.requested.all { it.startsWith("https://osv-vulnerabilities.storage.googleapis.com/") || it.startsWith("https://endoflife.date/api/") })

        val status = assertNotNull(store.status())
        assertEquals(done.status, status)
        assertEquals(listOf("npm", "PyPI"), status.ecosystems.map { it.name })
        assertEquals(3, status.ecosystems.first().advisories)
        assertEquals(1_793_000_000_000, status.ecosystems.first().sourceDate, "La fecha de los datos es la del volcado")
        assertEquals(2, status.eolProducts)
        // Los productos que no se pudieron descargar se anotan para decirlo en Ajustes.
        assertTrue("Django" in status.eolMissing && "Node.js" !in status.eolMissing)
        assertEquals(status.sizeBytes, store.index.length())

        // El índice instalado supera la verificación y responde.
        FileInputStream(store.index).channel.use { ch ->
            VulnDatabase.open(ch).use { db ->
                assertEquals(status.sha256, db.info.sha256)
                assertEquals(listOf("GHSA-p6mc-m468-83gw"), db.advisories("npm", "lodash").map { it.id })
            }
        }
        assertFalse(store.workDirectory.exists(), "Los temporales se borran")
        assertEquals(VulnDbPreferences(setOf("npm", "PyPI"), true), store.preferences(), "Se recuerda la elección")
        updater.acknowledge()
        assertEquals(UpdateState.Idle, updater.state.value)
    }

    @Test
    fun `si algo falla, la base anterior queda intacta`() {
        val store = VulnDbStore(tmp.newFolder("base"))
        val downloader = FakeDownloader()
        val updater = VulnDbUpdater(store, downloader)
        updater.start(VulnDbPreferences(setOf("npm"), endOfLife = false))
        assertTrue(await(updater) is UpdateState.Done)
        val before = store.index.readBytes()
        val status = store.status()

        // Error del servidor a mitad: falla y no cambia nada.
        downloader.failOn = "PyPI"
        updater.start(VulnDbPreferences(setOf("npm", "PyPI"), endOfLife = false))
        val failed = await(updater) as? UpdateState.Failed ?: fail("Estado: ${updater.state.value}")
        assertTrue("503" in failed.message && "se conserva" in failed.message, failed.message)
        assertTrue(before.contentEquals(store.index.readBytes()))
        assertEquals(status, store.status())
        assertFalse(store.workDirectory.exists())

        // Descarga mayor que el máximo.
        downloader.failOn = null
        downloader.hugeOn = "PyPI"
        updater.start(VulnDbPreferences(setOf("PyPI"), endOfLife = false))
        assertTrue(await(updater) is UpdateState.Failed)
        assertTrue(before.contentEquals(store.index.readBytes()))

        // Cancelación durante la descarga.
        downloader.hugeOn = null
        downloader.blockOn = "PyPI"
        updater.start(VulnDbPreferences(setOf("PyPI"), endOfLife = false))
        val deadline = System.currentTimeMillis() + 10_000
        while (!downloader.blocked) {
            check(System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
        assertFalse(updater.start(VulnDbPreferences(setOf("npm"), endOfLife = false)), "Solo una actualización a la vez")
        assertFalse(updater.delete(), "No se borra mientras se actualiza")
        updater.cancel()
        assertEquals(UpdateState.Cancelled, await(updater))
        assertTrue(before.contentEquals(store.index.readBytes()))
        assertFalse(store.workDirectory.exists())

        // Nada elegido: no se conecta.
        assertFalse(updater.start(VulnDbPreferences(emptySet(), endOfLife = false)))

        // Borrar la base.
        assertTrue(updater.delete())
        assertNull(store.status())
        assertNull(store.indexOrNull())
    }

    @Test
    fun `sin espacio suficiente no se descarga nada`() {
        val store = VulnDbStore(tmp.newFolder("base"))
        val downloader = FakeDownloader()
        val updater = VulnDbUpdater(store, downloader, freeSpace = { 10L * 1024 * 1024 })
        updater.start(VulnDbPreferences(setOf("npm"), endOfLife = false))
        val failed = await(updater) as? UpdateState.Failed ?: fail("Estado: ${updater.state.value}")
        assertTrue(failed.message.startsWith("No hay espacio suficiente"), failed.message)
        assertTrue(downloader.requested.isEmpty())
        assertNull(store.status())
    }

    @Test
    fun `un estado que no coincide con el indice no se da por bueno`() {
        val store = VulnDbStore(tmp.newFolder("base"))
        val updater = VulnDbUpdater(store, FakeDownloader())
        updater.start(VulnDbPreferences(setOf("npm"), endOfLife = false))
        assertTrue(await(updater) is UpdateState.Done)
        assertNotNull(store.indexOrNull())
        // El índice cambió (o se cortó) sin actualizar su descripción: se trata como si no hubiera base.
        store.index.appendBytes(ByteArray(10))
        assertNull(store.status())
        assertNull(store.indexOrNull())
    }

    @Test
    fun `el descargador solo admite HTTPS a los servidores de las bases`() {
        assertTrue(HttpsDownloader.isAllowed(URL("https://osv-vulnerabilities.storage.googleapis.com/npm/all.zip")))
        assertTrue(HttpsDownloader.isAllowed(URL("https://endoflife.date/api/nodejs.json")))
        assertFalse(HttpsDownloader.isAllowed(URL("http://endoflife.date/api/nodejs.json")), "Sin HTTP")
        assertFalse(HttpsDownloader.isAllowed(URL("https://example.com/all.zip")), "Otro servidor")
        assertFalse(HttpsDownloader.isAllowed(URL("https://endoflife.date.example.com/api/x.json")))
        assertFalse(HttpsDownloader.isAllowed(URL("https://endoflife.date:8443/api/x.json")), "Otro puerto")
        assertFalse(HttpsDownloader.isAllowed(URL("https://usuario@endoflife.date/api/x.json")), "Sin credenciales en la URL")
        assertEquals(VulnDbStore.APPROX_DOWNLOAD_MB.keys, com.funcodestudiocb.analyzer.engine.vulndb.OsvEcosystems.SUPPORTED.toSet())
    }
}

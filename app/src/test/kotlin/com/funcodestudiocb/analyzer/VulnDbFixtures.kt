package com.funcodestudiocb.analyzer

import com.funcodestudiocb.analyzer.vulndb.DownloadTooLargeException
import com.funcodestudiocb.analyzer.vulndb.Downloaded
import com.funcodestudiocb.analyzer.vulndb.Downloader
import com.funcodestudiocb.analyzer.vulndb.HttpStatusException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Avisos reales de OSV (resumidos) y datos de endoflife.date para las pruebas de la app. */
object VulnDbFixtures {
    val NPM = mapOf(
        "MAL.json" to """{"id":"MAL-2025-20690","summary":"Malicious code in flatmap-stream (npm)","affected":[{"package":{"ecosystem":"npm","name":"flatmap-stream"},"ranges":[{"type":"SEMVER","events":[{"introduced":"0"}]}]}]}""",
        "lodash.json" to """{"id":"GHSA-p6mc-m468-83gw","aliases":["CVE-2020-8203"],"summary":"Prototype Pollution in lodash","published":"2020-07-15T19:15:48Z","database_specific":{"severity":"HIGH"},"affected":[{"package":{"ecosystem":"npm","name":"lodash"},"ranges":[{"type":"SEMVER","events":[{"introduced":"3.7.0"},{"fixed":"4.17.19"}]}]}]}""",
        "qs.json" to """{"id":"GHSA-hrpp-h998-j3pp","aliases":["CVE-2022-24999"],"summary":"qs vulnerable to Prototype Pollution","severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"}],"affected":[{"package":{"ecosystem":"npm","name":"qs"},"ranges":[{"type":"SEMVER","events":[{"introduced":"6.5.0"},{"fixed":"6.5.3"}]}]}]}""",
    )
    val PYPI = mapOf(
        "ghsa.json" to """{"id":"GHSA-8q59-q68h-6hv4","aliases":["CVE-2020-14343"],"summary":"Improper Input Validation in PyYAML","database_specific":{"severity":"CRITICAL"},"affected":[{"package":{"ecosystem":"PyPI","name":"PyYAML"},"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"5.4"}]}]}]}""",
    )
    val EOL = mapOf(
        "nodejs" to """[{"cycle":"24","eol":"2028-04-30"},{"cycle":"20","eol":"2026-04-30","latest":"20.19.0"},{"cycle":"14","eol":"2023-04-30","latest":"14.21.3"}]""",
        "python" to """[{"cycle":"3.12","eol":"2028-10-31"},{"cycle":"3.6","eol":"2021-12-23","latest":"3.6.15"}]""",
    )

    fun zipBytes(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (n, text) ->
                out.putNextEntry(ZipEntry(n))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** Proyecto de prueba: npm con archivo de bloqueo, un paquete malicioso, uno vulnerable usado e indirectas. */
    val PROJECT = mapOf(
        "tienda/package.json" to """{ "name": "tienda", "dependencies": { "express": "4.16.0", "lodash": "^4.17.15", "flatmap-stream": "0.1.1", "react": "18.2.0" } }""",
        "tienda/package-lock.json" to """
            {
              "lockfileVersion": 3,
              "packages": {
                "": { "name": "tienda", "dependencies": { "express": "4.16.0", "lodash": "^4.17.15", "flatmap-stream": "0.1.1", "react": "18.2.0" } },
                "node_modules/express": { "version": "4.16.0", "dependencies": { "qs": "6.5.1" } },
                "node_modules/qs": { "version": "6.5.1" },
                "node_modules/lodash": { "version": "4.17.15" },
                "node_modules/flatmap-stream": { "version": "0.1.1" },
                "node_modules/react": { "version": "18.2.0" }
              }
            }
        """.trimIndent(),
        "tienda/src/server.js" to "const express = require('express');\nconst _ = require('lodash');\nconst app = express();\n",
        "tienda/.nvmrc" to "14\n",
    )
}

/**
 * Descargador de pruebas: sirve volcados de OSV y datos de endoflife.date en memoria, sin red. Puede fallar en una URL
 * concreta, servir un archivo enorme o esperar a que se cancele.
 */
class FakeDownloader(
    /** Volcados por ecosistema; los que no tienen avisos de prueba se sirven vacíos. */
    private val osv: Map<String, Map<String, String>> =
        com.funcodestudiocb.analyzer.engine.vulndb.OsvEcosystems.SUPPORTED.associateWith { emptyMap<String, String>() } +
            mapOf("npm" to VulnDbFixtures.NPM, "PyPI" to VulnDbFixtures.PYPI),
    private val eol: Map<String, String> = VulnDbFixtures.EOL,
) : Downloader {
    val requested = CopyOnWriteArrayList<String>()

    /** Ecosistema cuya descarga responde con un error del servidor. */
    @Volatile var failOn: String? = null

    /** Ecosistema cuya descarga se anuncia mayor que el máximo. */
    @Volatile var hugeOn: String? = null

    /** Ecosistema cuya descarga se queda esperando hasta que la cancelen. */
    @Volatile var blockOn: String? = null

    @Volatile var blocked = false

    override fun download(url: String, target: File, maxBytes: Long, onProgress: (Long, Long?) -> Unit, cancelled: () -> Boolean): Downloaded {
        requested += url
        val eco = url.removePrefix("https://osv-vulnerabilities.storage.googleapis.com/").removeSuffix("/all.zip")
        if (eco == failOn) throw HttpStatusException(503, "El servidor respondió 503")
        if (eco == hugeOn) throw DownloadTooLargeException("La descarga supera el máximo permitido")
        if (eco == blockOn) {
            blocked = true
            val deadline = System.currentTimeMillis() + 10_000
            while (!cancelled()) {
                check(System.currentTimeMillis() < deadline) { "Nadie canceló" }
                Thread.sleep(5)
            }
            throw java.util.concurrent.CancellationException("Descarga cancelada")
        }
        val entries = osv[eco] ?: throw HttpStatusException(404, "No existe")
        val bytes = VulnDbFixtures.zipBytes(entries)
        target.writeBytes(bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        return Downloaded(bytes.size.toLong(), 1_793_000_000_000)
    }

    override fun text(url: String, maxBytes: Int): String {
        requested += url
        val product = url.removePrefix("https://endoflife.date/api/").removeSuffix(".json")
        return eol[product] ?: throw IOException("Sin datos de $product")
    }
}

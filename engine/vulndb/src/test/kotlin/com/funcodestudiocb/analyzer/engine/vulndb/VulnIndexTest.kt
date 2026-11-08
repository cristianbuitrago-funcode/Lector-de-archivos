package com.funcodestudiocb.analyzer.engine.vulndb

import com.funcodestudiocb.analyzer.engine.api.Severity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Volcados de OSV de prueba con el formato real de los avisos. */
internal object OsvFixtures {
    val LOG4J = """
        {"id":"GHSA-jfh8-c2jp-5v3q","aliases":["CVE-2021-44228"],"summary":"Remote code injection in Log4j","published":"2021-12-10T00:40:56Z",
         "database_specific":{"severity":"CRITICAL","cwe_ids":["CWE-502","CWE-917"]},
         "severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"}],
         "affected":[{"package":{"ecosystem":"Maven","name":"org.apache.logging.log4j:log4j-core"},
           "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"2.13.0"},{"fixed":"2.15.0"}]},{"type":"ECOSYSTEM","events":[{"introduced":"2.0-beta9"},{"fixed":"2.3.1"}]}]}]}
    """.trimIndent()
    val PYYAML = """
        {"id":"PYSEC-2021-142","aliases":["CVE-2020-14343","GHSA-8q59-q68h-6hv4"],"details":"A vulnerability was discovered in the PyYAML library in versions before 5.4.",
         "affected":[{"package":{"ecosystem":"PyPI","name":"pyyaml"},"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"5.4"}]},{"type":"GIT","repo":"x","events":[{"introduced":"0"},{"fixed":"abc"}]}]}]}
    """.trimIndent()
    val PYYAML_GHSA = """
        {"id":"GHSA-8q59-q68h-6hv4","aliases":["CVE-2020-14343"],"summary":"Improper Input Validation in PyYAML","database_specific":{"severity":"CRITICAL"},
         "affected":[{"package":{"ecosystem":"PyPI","name":"PyYAML"},"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"5.4"}]}]}]}
    """.trimIndent()
    val MALWARE = """
        {"id":"MAL-2025-20690","summary":"Malicious code in flatmap-stream (npm)","affected":[{"package":{"ecosystem":"npm","name":"flatmap-stream"},"ranges":[{"type":"SEMVER","events":[{"introduced":"0"}]}]}]}
    """.trimIndent()
    val LODASH = """
        {"id":"GHSA-p6mc-m468-83gw","aliases":["CVE-2020-8203"],"summary":"Prototype Pollution in lodash","database_specific":{"severity":"HIGH"},
         "affected":[{"package":{"ecosystem":"npm","name":"lodash"},"ranges":[{"type":"SEMVER","events":[{"introduced":"3.7.0"},{"fixed":"4.17.19"}]}]}]}
    """.trimIndent()
    val QS = """
        {"id":"GHSA-hrpp-h998-j3pp","aliases":["CVE-2022-24999"],"summary":"qs vulnerable to Prototype Pollution","severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"}],
         "affected":[{"package":{"ecosystem":"npm","name":"qs"},"ranges":[{"type":"SEMVER","events":[{"introduced":"6.5.0"},{"fixed":"6.5.3"}]}]}]}
    """.trimIndent()
    val REQUESTS = """
        {"id":"GHSA-9wx4-h78v-vm56","aliases":["CVE-2024-35195"],"summary":"Requests Session object does not verify requests after making first request with verify=False","database_specific":{"severity":"MODERATE"},
         "affected":[{"package":{"ecosystem":"PyPI","name":"requests"},"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"2.32.0"}]}]}]}
    """.trimIndent()
    val WITHDRAWN = """
        {"id":"GHSA-xxxx-yyyy-zzzz","withdrawn":"2022-01-01T00:00:00Z","affected":[{"package":{"ecosystem":"npm","name":"lodash"},"ranges":[{"type":"SEMVER","events":[{"introduced":"0"}]}]}]}
    """.trimIndent()
    val EOL_NODE = """[{"cycle":"20","eol":"2026-04-30","latest":"20.19.0"},{"cycle":"18","eol":"2025-04-30","latest":"18.20.8"},{"cycle":"14","eol":"2023-04-30","latest":"14.21.3"},{"cycle":"24","eol":"2028-04-30","latest":"24.1.0"}]"""
    val EOL_PYTHON = """[{"cycle":"3.12","eol":"2028-10-31"},{"cycle":"3.9","eol":"2026-01-31","latest":"3.9.24"},{"cycle":"3.6","eol":"2021-12-23","latest":"3.6.15"}]"""
    val EOL_DJANGO = """[{"cycle":"5.2","eol":"2028-04-30"},{"cycle":"2.2","eol":"2022-04-11","latest":"2.2.28"}]"""

    fun zip(dir: File, name: String, entries: Map<String, String>): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { out ->
            entries.forEach { (n, text) ->
                out.putNextEntry(ZipEntry(n))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return f
    }

    /** Base completa de prueba: npm, PyPI y Maven, con fin de soporte de Node.js, Python y Django. */
    fun database(dir: File): File {
        val work = File(dir, "trabajo").apply { mkdirs() }
        val target = File(dir, "base.fcv")
        VulnIndexWriter(work).use { w ->
            w.addOsvZip("npm", zip(dir, "npm.zip", mapOf("MAL-2025-20690.json" to MALWARE, "GHSA-p6mc.json" to LODASH, "GHSA-hrpp.json" to QS, "retirado.json" to WITHDRAWN)), 1_700_000_000_000)
            w.addOsvZip("PyPI", zip(dir, "pypi.zip", mapOf("PYSEC.json" to PYYAML, "GHSA-8q59.json" to PYYAML_GHSA, "GHSA-9wx4.json" to REQUESTS)), 1_700_000_000_000)
            w.addOsvZip("Maven", zip(dir, "maven.zip", mapOf("GHSA-jfh8.json" to LOG4J)), 1_700_000_000_000)
            w.setEol(
                listOf(
                    EolData.parse("nodejs", "Node.js", EOL_NODE),
                    EolData.parse("python", "Python", EOL_PYTHON),
                    EolData.parse("django", "Django", EOL_DJANGO),
                ),
                1_700_000_000_000,
            )
            w.writeTo(target)
        }
        return target
    }
}

class VulnIndexTest {

    @TempDir
    lateinit var dir: File

    private fun open(f: File) = VulnDatabase.open(FileInputStream(f).channel)

    @Test
    fun `la base se escribe, se verifica y se consulta por paquete`() {
        val f = OsvFixtures.database(dir)
        open(f).use { db ->
            assertEquals(listOf("npm", "PyPI", "Maven"), db.info.ecosystems.map { it.name })
            assertEquals(3, db.info.ecosystems.first { it.name == "PyPI" }.advisories)
            val log4j = db.advisories("Maven", "org.apache.logging.log4j:log4j-core").single()
            assertEquals("GHSA-jfh8-c2jp-5v3q", log4j.id)
            assertEquals(Severity.CRITICAL, log4j.severity)
            assertEquals(10.0, log4j.score)
            assertEquals(listOf("CWE-502", "CWE-917"), log4j.cwes)
            assertEquals(listOf("2.15.0", "2.3.1"), log4j.fixed)
            // PEP 503: PyYAML, pyyaml y py_yaml son el mismo paquete; el rango GIT no se guarda.
            val yaml = db.advisories("PyPI", "PyYAML")
            assertEquals(setOf("PYSEC-2021-142", "GHSA-8q59-q68h-6hv4"), yaml.map { it.id }.toSet())
            assertTrue(yaml.all { a -> a.ranges.all { r -> r.events.none { it.version == "abc" } } })
            assertEquals("A vulnerability was discovered in the PyYAML library in versions before 5.4.", yaml.first { it.id.startsWith("PYSEC") }.summary)
            assertTrue(db.advisories("npm", "flatmap-stream").single().malicious)
            assertEquals(listOf("GHSA-p6mc-m468-83gw"), db.advisories("npm", "lodash").map { it.id }, "El aviso retirado no se guarda")
            assertTrue(db.advisories("npm", "no-existe").isEmpty())
            assertTrue(db.advisories("Go", "lodash").isEmpty())
            assertEquals(listOf("nodejs", "python", "django"), db.eol.map { it.name })
            assertEquals("2023-04-30", db.eol.first().cycles.first { it.cycle == "14" }.eol)
            assertTrue(db.info.sha256.length == 64)
        }
    }

    @Test
    fun `una base danada, truncada o ajena se rechaza`() {
        val f = OsvFixtures.database(dir)
        val bytes = f.readBytes()
        // Un byte cambiado en medio: la suma de comprobación no coincide.
        val damaged = File(dir, "danada.fcv").apply { writeBytes(bytes) }
        RandomAccessFile(damaged, "rw").use { raf ->
            raf.seek(bytes.size / 2L)
            val b = raf.read()
            raf.seek(bytes.size / 2L)
            raf.write(b xor 0xff)
        }
        assertFailsWith<InvalidIndexException> { open(damaged) }
        assertFailsWith<InvalidIndexException> { open(File(dir, "truncada.fcv").apply { writeBytes(bytes.copyOf(bytes.size - 100)) }) }
        assertFailsWith<InvalidIndexException> { open(File(dir, "vacia.fcv").apply { writeBytes(ByteArray(0)) }) }
        assertFailsWith<InvalidIndexException> { open(File(dir, "ajena.fcv").apply { writeBytes(ByteArray(4096) { it.toByte() }) }) }
    }

    @Test
    fun `un volcado con entradas enormes o invalidas no rompe la construccion`() {
        val work = File(dir, "t").apply { mkdirs() }
        val big = "{\"id\":\"X\",\"summary\":\"" + "a".repeat(VulnIndexWriter.MAX_ENTRY_BYTES + 10) + "\"}"
        val zip = OsvFixtures.zip(dir, "raro.zip", mapOf("enorme.json" to big, "roto.json" to "{no es json", "LODASH.json" to OsvFixtures.LODASH, "leeme.txt" to "x"))
        VulnIndexWriter(work).use { w ->
            val r = w.addOsvZip("npm", zip, 0)
            assertEquals(1, r.advisories)
            assertEquals(1, r.invalid)
            assertEquals(2, r.skipped)
            val info = w.writeTo(File(dir, "b.fcv"))
            assertEquals(1, info.ecosystems.single().packages)
        }
        assertTrue(work.listFiles().orEmpty().isEmpty(), "Los temporales se borran")
    }
}

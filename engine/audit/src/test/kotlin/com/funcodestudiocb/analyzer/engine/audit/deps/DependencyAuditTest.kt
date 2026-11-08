package com.funcodestudiocb.analyzer.engine.audit.deps

import com.funcodestudiocb.analyzer.engine.api.Certainty
import com.funcodestudiocb.analyzer.engine.api.Criticality
import com.funcodestudiocb.analyzer.engine.api.DependencySummary
import com.funcodestudiocb.analyzer.engine.api.EndOfLife
import com.funcodestudiocb.analyzer.engine.api.EolStatus
import com.funcodestudiocb.analyzer.engine.api.ResolvedDependency
import com.funcodestudiocb.analyzer.engine.api.SecurityCategory
import com.funcodestudiocb.analyzer.engine.api.Severity
import com.funcodestudiocb.analyzer.engine.api.UsageStatus
import com.funcodestudiocb.analyzer.engine.api.VersionSource
import com.funcodestudiocb.analyzer.engine.audit.Audit
import com.funcodestudiocb.analyzer.engine.audit.assertCompleteCards
import com.funcodestudiocb.analyzer.engine.audit.audit
import com.funcodestudiocb.analyzer.engine.vulndb.EolData
import com.funcodestudiocb.analyzer.engine.vulndb.VulnIndexWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class DependencyAuditTest {

    @TempDir
    lateinit var dir: File

    private fun zip(name: String, entries: Map<String, String>): File = File(dir, name).also { f ->
        ZipOutputStream(f.outputStream()).use { out ->
            entries.forEach { (n, text) ->
                out.putNextEntry(ZipEntry(n))
                out.write(text.trimIndent().toByteArray())
                out.closeEntry()
            }
        }
    }

    /** Base de prueba con avisos reales (resumidos) de OSV y datos de endoflife.date. */
    private fun database(): File {
        val work = File(dir, "trabajo").apply { mkdirs() }
        val target = File(dir, "base.fcv")
        VulnIndexWriter(work).use { w ->
            w.addOsvZip(
                "npm",
                zip(
                    "npm.zip",
                    mapOf(
                        "MAL.json" to """{"id":"MAL-2025-20690","summary":"Malicious code in flatmap-stream (npm)","affected":[{"package":{"ecosystem":"npm","name":"flatmap-stream"},"ranges":[{"type":"SEMVER","events":[{"introduced":"0"}]}]}]}""",
                        "lodash.json" to """{"id":"GHSA-p6mc-m468-83gw","aliases":["CVE-2020-8203"],"summary":"Prototype Pollution in lodash","database_specific":{"severity":"HIGH"},"affected":[{"package":{"ecosystem":"npm","name":"lodash"},"ranges":[{"type":"SEMVER","events":[{"introduced":"3.7.0"},{"fixed":"4.17.19"}]}]}]}""",
                        "qs.json" to """{"id":"GHSA-hrpp-h998-j3pp","aliases":["CVE-2022-24999"],"summary":"qs vulnerable to Prototype Pollution","severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"}],"affected":[{"package":{"ecosystem":"npm","name":"qs"},"ranges":[{"type":"SEMVER","events":[{"introduced":"6.5.0"},{"fixed":"6.5.3"}]}]}]}""",
                        "minimist.json" to """{"id":"GHSA-xvch-5gv4-984h","aliases":["CVE-2021-44906"],"summary":"Prototype Pollution in minimist","database_specific":{"severity":"CRITICAL"},"affected":[{"package":{"ecosystem":"npm","name":"minimist"},"ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"0.2.4"}]}]}]}""",
                    ),
                ),
                1_793_000_000_000,
            )
            w.addOsvZip(
                "PyPI",
                zip(
                    "pypi.zip",
                    mapOf(
                        "pysec.json" to """{"id":"PYSEC-2021-142","aliases":["CVE-2020-14343","GHSA-8q59-q68h-6hv4"],"details":"PyYAML before 5.4 allows arbitrary code execution.","affected":[{"package":{"ecosystem":"PyPI","name":"pyyaml"},"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"5.4"}]}]}]}""",
                        "ghsa.json" to """{"id":"GHSA-8q59-q68h-6hv4","aliases":["CVE-2020-14343"],"summary":"Improper Input Validation in PyYAML","database_specific":{"severity":"CRITICAL"},"affected":[{"package":{"ecosystem":"PyPI","name":"PyYAML"},"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"5.4"}]}]}]}""",
                        "requests.json" to """{"id":"GHSA-9wx4-h78v-vm56","aliases":["CVE-2024-35195"],"summary":"Requests Session does not verify after verify=False","database_specific":{"severity":"MODERATE"},"affected":[{"package":{"ecosystem":"PyPI","name":"requests"},"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"2.32.0"}]}]}]}""",
                    ),
                ),
                1_793_000_000_000,
            )
            w.addOsvZip(
                "Maven",
                zip(
                    "maven.zip",
                    mapOf(
                        "log4j.json" to """{"id":"GHSA-jfh8-c2jp-5v3q","aliases":["CVE-2021-44228"],"summary":"Remote code injection in Log4j","database_specific":{"severity":"CRITICAL"},"affected":[{"package":{"ecosystem":"Maven","name":"org.apache.logging.log4j:log4j-core"},"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"2.13.0"},{"fixed":"2.15.0"}]}]}]}""",
                    ),
                ),
                1_793_000_000_000,
            )
            w.setEol(
                listOf(
                    EolData.parse("nodejs", "Node.js", """[{"cycle":"24","eol":"2028-04-30"},{"cycle":"20","eol":"2026-04-30","latest":"20.19.0"},{"cycle":"14","eol":"2023-04-30","latest":"14.21.3"}]"""),
                    EolData.parse("python", "Python", """[{"cycle":"3.12","eol":"2028-10-31"},{"cycle":"3.10","eol":"2027-01-31"},{"cycle":"3.6","eol":"2021-12-23","latest":"3.6.15"}]"""),
                    EolData.parse("django", "Django", """[{"cycle":"5.2","eol":"2028-04-30"},{"cycle":"2.2","eol":"2022-04-11","latest":"2.2.28"}]"""),
                    EolData.parse("postgresql", "PostgreSQL", """[{"cycle":"17","eol":"2029-11-08"},{"cycle":"10","eol":"2022-11-10","latest":"10.23"}]"""),
                ),
                1_793_000_000_000,
            )
            w.writeTo(target)
        }
        return target
    }

    private val project = mapOf(
        "tienda/web/package.json" to """
            {
              "name": "tienda-web",
              "engines": { "node": ">=20" },
              "dependencies": { "express": "4.16.0", "lodash": "^4.17.15", "flatmap-stream": "0.1.1", "react": "18.2.0" },
              "devDependencies": { "minimist": "0.0.8" }
            }
        """,
        "tienda/web/package-lock.json" to """
            {
              "lockfileVersion": 3,
              "packages": {
                "": { "name": "tienda-web", "dependencies": { "express": "4.16.0", "lodash": "^4.17.15", "flatmap-stream": "0.1.1", "react": "18.2.0" }, "devDependencies": { "minimist": "0.0.8" } },
                "node_modules/express": { "version": "4.16.0", "dependencies": { "body-parser": "1.18.2", "qs": "6.5.1" } },
                "node_modules/body-parser": { "version": "1.18.2", "dependencies": { "qs": "6.5.1" } },
                "node_modules/qs": { "version": "6.5.1" },
                "node_modules/lodash": { "version": "4.17.15" },
                "node_modules/flatmap-stream": { "version": "0.1.1" },
                "node_modules/react": { "version": "18.2.0" },
                "node_modules/minimist": { "version": "0.0.8", "dev": true }
              }
            }
        """,
        "tienda/web/src/server.js" to """
            const express = require('express');
            const _ = require('lodash');
            const app = express();
            app.get('/api/x', (req, res) => res.json(_.merge({}, {})));
        """,
        "tienda/web/.nvmrc" to "14",
        "tienda/api/requirements.txt" to "Django==2.2.0\nPyYAML==5.3\nrequests>=2.20",
        "tienda/api/app.py" to "import yaml\nfrom django.http import HttpResponse\n",
        "tienda/java/pom.xml" to """
            <project>
              <groupId>com.tienda</groupId><artifactId>tienda</artifactId><version>1</version>
              <dependencies>
                <dependency><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-core</artifactId><version>2.14.1</version></dependency>
              </dependencies>
            </project>
        """,
        "tienda/java/src/main/java/com/tienda/App.java" to "package com.tienda;\nimport org.apache.logging.log4j.LogManager;\nclass App {}\n",
        "tienda/Dockerfile" to "FROM python:3.6-slim\n",
        "tienda/docker-compose.yml" to "services:\n  db:\n    image: postgres:10\n",
    )

    private fun Audit.dep(name: String): ResolvedDependency = records.filterIsInstance<ResolvedDependency>().firstOrNull { it.name == name } ?: fail("Sin la dependencia $name")
    private fun Audit.eol(label: String): EndOfLife = records.filterIsInstance<EndOfLife>().firstOrNull { it.label == label } ?: fail("Sin fin de soporte de $label")
    private val Audit.deps: DependencySummary get() = records.filterIsInstance<DependencySummary>().single()

    @Test
    fun `directas e indirectas con versiones, uso, criticidad, vulnerabilidades y fin de soporte`() {
        val a = audit(dir, project, database = database())
        assertCompleteCards(a)

        // Directas e indirectas, con la cadena por la que llegan.
        val qs = a.dep("qs")
        assertFalse(qs.direct)
        assertEquals(VersionSource.LOCKFILE, qs.versionSource)
        assertEquals("tienda/web/package-lock.json", qs.lockfile)
        assertTrue(qs.introducedBy.containsAll(listOf("express › qs", "express › body-parser › qs")), qs.introducedBy.toString())
        assertEquals(UsageStatus.INDIRECT, qs.usage)
        assertEquals(Certainty.CONFIRMED, qs.vulnerabilities.single().certainty)
        // Sin etiqueta de la base: la severidad sale del vector CVSS 3.1 (7.5, alta).
        assertEquals(Severity.HIGH, qs.vulnerabilities.single().severity)
        assertEquals(7.5, qs.vulnerabilities.single().score)

        // Uso real: lodash y express se importan; react no; minimist es de desarrollo.
        val lodash = a.dep("lodash")
        assertTrue(lodash.direct)
        assertEquals("^4.17.15", lodash.declared)
        assertEquals("4.17.15", lodash.version)
        assertEquals(UsageStatus.USED, lodash.usage)
        assertEquals(listOf("tienda/web/src/server.js:2"), lodash.usedIn)
        assertEquals(listOf("4.17.19"), lodash.vulnerabilities.single().fixedIn)
        assertEquals(UsageStatus.NOT_SEEN, a.dep("react").usage)
        assertTrue(a.dep("react").vulnerabilities.isEmpty())
        assertEquals(UsageStatus.DEVELOPMENT_TOOL, a.dep("minimist").usage)

        // Paquete malicioso: hallazgo propio, crítico.
        val mal = a.dep("flatmap-stream")
        assertTrue(mal.vulnerabilities.single().malicious)
        assertEquals(Criticality.HIGH, mal.criticality)
        assertEquals(Severity.CRITICAL, a.find("FC-DEP-02", "tienda/web/package.json").severity)

        // PyPI: el mismo fallo publicado como PYSEC y GHSA se une; requests sin bloqueo es "posible".
        val yaml = a.dep("PyYAML")
        assertEquals(1, yaml.vulnerabilities.size)
        assertEquals("GHSA-8q59-q68h-6hv4", yaml.vulnerabilities.single().id)
        assertTrue("CVE-2020-14343" in yaml.vulnerabilities.single().aliases)
        assertEquals(UsageStatus.USED, yaml.usage, "Se importa como «yaml»")
        val requests = a.dep("requests")
        assertEquals(VersionSource.RANGE_IN_MANIFEST, requests.versionSource)
        assertEquals("2.20", requests.version)
        assertEquals(Certainty.POSSIBLE, requests.vulnerabilities.single().certainty)
        assertEquals(Certainty.POSSIBLE, a.find("FC-DEP-01", "tienda/api/requirements.txt", 3).certainty)

        // Maven: log4j por el grupo de sus importaciones.
        val log4j = a.dep("org.apache.logging.log4j:log4j-core")
        assertEquals(UsageStatus.USED, log4j.usage)
        assertEquals(Criticality.HIGH, log4j.criticality)
        val log4jFinding = a.find("FC-DEP-01", "tienda/java/pom.xml")
        assertEquals(Severity.CRITICAL, log4jFinding.severity)
        assertTrue(log4jFinding.explanation.contains("CVE-2021-44228") && log4jFinding.explanation.contains("2.15.0"), log4jFinding.explanation)
        assertTrue(log4jFinding.fixedExample!!.contains("<version>2.15.0</version>"))
        assertTrue(log4jFinding.references.any { it == "https://osv.dev/vulnerability/GHSA-jfh8-c2jp-5v3q" })

        // Desarrollo: minimist crítico baja un nivel y se dice por qué.
        val minimist = a.find("FC-DEP-01", "tienda/web/package.json", 5)
        assertEquals(Severity.HIGH, minimist.severity)
        assertNotNull(minimist.downgraded)

        // Indirecta: la ficha propone forzar la versión y está en el archivo de bloqueo.
        val qsFinding = a.findings.first { it.ruleId == "FC-DEP-01" && it.explanation.contains("«qs»") }
        assertEquals("tienda/web/package-lock.json", qsFinding.path)
        assertTrue(qsFinding.fixedExample!!.contains("overrides"))

        // Fin de soporte: archivos del proyecto, imagen de Docker y dependencia.
        assertEquals(EolStatus.UNSUPPORTED, a.eol("Node.js").let { if (it.version == "14") it else a.records.filterIsInstance<EndOfLife>().first { e -> e.label == "Node.js" && e.version == "14" } }.status)
        val node20 = a.records.filterIsInstance<EndOfLife>().first { it.label == "Node.js" && it.version == "20" }
        assertTrue(node20.minimumOfRange)
        assertEquals(EolStatus.UNSUPPORTED, node20.status, "20 terminó el 2026-04-30")
        assertEquals(EolStatus.UNSUPPORTED, a.eol("Python").status)
        assertEquals("3.6.15", a.eol("Python").latestInCycle)
        assertEquals(EolStatus.UNSUPPORTED, a.eol("Django").status)
        assertEquals(EolStatus.UNSUPPORTED, a.eol("PostgreSQL").status)
        val eolFinding = a.find("FC-EOL-01", "tienda/web/.nvmrc", 1)
        assertEquals(Certainty.CONFIRMED, eolFinding.certainty)
        assertEquals(Certainty.POSSIBLE, a.find("FC-EOL-01", "tienda/web/package.json").certainty, "Mínimo de un rango")

        // Resumen y resumen de seguridad coherentes.
        val s = a.deps
        assertEquals(1, s.malicious)
        assertTrue(s.vulnerablePackages >= 6)
        assertEquals(s.vulnerabilities, s.bySeverity.values.sum())
        assertEquals(setOf("Maven", "PyPI", "npm"), s.databases.map { it.ecosystem }.toSet())
        assertTrue(s.databases.all { it.date != null })
        assertTrue(s.notEvaluated.any { it.contains("versión mínima") })
        // React se detecta, pero la base de prueba no tiene sus fechas: se dice en lugar de darlo por soportado.
        assertTrue(s.notEvaluated.any { it.startsWith("Fin de soporte de ") && "React" in it && "Log4j" in it && it.endsWith("no tiene sus datos.") }, s.notEvaluated.toString())
        assertTrue(a.records.filterIsInstance<EndOfLife>().none { it.product == "react" })
        assertTrue(a.summary.byCategory[SecurityCategory.VULNERABLE_DEPENDENCY]!! >= 6)
        assertTrue(a.summary.byCategory[SecurityCategory.OUTDATED_LIBRARY]!! >= 4)
    }

    @Test
    fun `sin base o con la base danada se dice y no se inventa nada`() {
        val none = audit(dir, project)
        assertTrue(none.records.filterIsInstance<ResolvedDependency>().all { it.vulnerabilities.isEmpty() })
        assertTrue(none.records.filterIsInstance<EndOfLife>().isEmpty())
        assertTrue(none.deps.notEvaluated.any { it.contains("no hay base de vulnerabilidades") })
        assertTrue(none.summary.notEvaluated.any { it.contains("no hay base de vulnerabilidades") })
        assertNull(none.findings.firstOrNull { it.ruleId.startsWith("FC-DEP") || it.ruleId.startsWith("FC-EOL") })
        assertEquals(UsageStatus.USED, none.dep("lodash").usage, "El uso real no depende de la base")

        val damaged = database().also { f ->
            RandomAccessFile(f, "rw").use { raf ->
                raf.seek(f.length() / 2)
                val b = raf.read()
                raf.seek(f.length() / 2)
                raf.write(b xor 0x55)
            }
        }
        val bad = audit(dir, project, database = damaged)
        assertTrue(bad.deps.notEvaluated.any { it.contains("no utilizable") && it.contains("suma de comprobación") }, bad.deps.notEvaluated.toString())
        assertTrue(bad.records.filterIsInstance<ResolvedDependency>().all { it.vulnerabilities.isEmpty() })
    }
}

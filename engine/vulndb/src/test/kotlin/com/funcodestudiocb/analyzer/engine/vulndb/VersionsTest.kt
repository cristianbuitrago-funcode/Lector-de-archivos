package com.funcodestudiocb.analyzer.engine.vulndb

import com.funcodestudiocb.analyzer.engine.api.Severity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionsTest {

    /** Cada lista está en orden creciente: se comprueban todos los pares. */
    private fun assertOrdered(scheme: VersionScheme, vararg versions: String) {
        for (i in versions.indices) for (j in versions.indices) {
            val c = Versions.compare(scheme, versions[i], versions[j])
            val expected = i.compareTo(j).coerceIn(-1, 1)
            assertEquals(expected, c?.coerceIn(-1, 1), "$scheme: ${versions[i]} vs ${versions[j]}")
        }
    }

    @Test
    fun `semver de npm, crates y Go`() {
        assertOrdered(VersionScheme.SEMVER, "0.0.8", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.2.10", "4.17.15", "4.17.21")
        assertEquals(0, Versions.compare(VersionScheme.SEMVER, "v1.2.3", "1.2.3"))
        assertEquals(0, Versions.compare(VersionScheme.SEMVER, "1.2.3+incompatible", "1.2.3"))
        assertEquals(0, Versions.compare(VersionScheme.SEMVER, "1.2", "1.2.0"))
        assertTrue(Versions.compare(VersionScheme.SEMVER, "v0.0.0-20210101000000-abc", "v0.0.0-20220101000000-def")!! < 0)
        assertNull(Versions.compare(VersionScheme.SEMVER, "latest", "1.0.0"))
    }

    @Test
    fun `pep 440 de PyPI`() {
        assertOrdered(VersionScheme.PEP440, "1.0.dev1", "1.0a1", "1.0a2.dev1", "1.0b1", "1.0rc1", "1.0", "1.0.post1", "1.1", "2.2.0", "2.2.28", "3.0")
        assertEquals(0, Versions.compare(VersionScheme.PEP440, "1.0", "1.0.0"))
        assertEquals(0, Versions.compare(VersionScheme.PEP440, "1.0-alpha1", "1.0a1"))
        assertTrue(Versions.compare(VersionScheme.PEP440, "1!0.1", "2.0")!! > 0, "La época manda")
        assertNull(Versions.pep440("no-es-versión"))
    }

    @Test
    fun `maven, composer, nuget y rubygems`() {
        assertOrdered(VersionScheme.MAVEN, "2.0-beta9", "2.0-rc1", "2.0", "2.0.1", "2.3", "2.13.0", "2.14.1", "2.15.0")
        assertTrue(Versions.compare(VersionScheme.MAVEN, "1.0-SNAPSHOT", "1.0")!! < 0)
        assertEquals(0, Versions.compare(VersionScheme.MAVEN, "1.0", "1.0.0"))
        assertEquals(0, Versions.compare(VersionScheme.MAVEN, "1.0.Final", "1.0"))
        assertOrdered(VersionScheme.COMPOSER, "v5.4.0-beta1", "5.4.0-RC1", "v5.4.0", "5.4.0-p1", "5.4.1", "8.0.0")
        assertNull(Versions.composer("dev-master"))
        assertOrdered(VersionScheme.NUGET, "4.0.0-beta", "4.0.0", "4.0.0.1", "13.0.1")
        assertOrdered(VersionScheme.RUBYGEMS, "5.2.0.beta1", "5.2.0", "5.2.4.3", "6.0.0")
    }

    @Test
    fun `rangos de OSV`() {
        val log4j = listOf(
            AffectedRange(listOf(RangeEvent(EventKind.INTRODUCED, "2.13.0"), RangeEvent(EventKind.FIXED, "2.15.0"))),
            AffectedRange(listOf(RangeEvent(EventKind.INTRODUCED, "2.0-beta9"), RangeEvent(EventKind.FIXED, "2.3.1"))),
        )
        assertEquals(true, Affected.check(VersionScheme.MAVEN, "2.14.1", log4j, emptyList()))
        assertEquals(true, Affected.check(VersionScheme.MAVEN, "2.0", log4j, emptyList()))
        assertEquals(false, Affected.check(VersionScheme.MAVEN, "2.15.0", log4j, emptyList()))
        assertEquals(false, Affected.check(VersionScheme.MAVEN, "2.5", log4j, emptyList()), "Entre los dos rangos")
        val all = listOf(AffectedRange(listOf(RangeEvent(EventKind.INTRODUCED, "0"))))
        assertEquals(true, Affected.check(VersionScheme.SEMVER, "0.1.1", all, emptyList()), "Paquete malicioso: todas las versiones")
        val last = listOf(AffectedRange(listOf(RangeEvent(EventKind.INTRODUCED, "1.0.0"), RangeEvent(EventKind.LAST_AFFECTED, "1.4.2"))))
        assertEquals(true, Affected.check(VersionScheme.SEMVER, "1.4.2", last, emptyList()))
        assertEquals(false, Affected.check(VersionScheme.SEMVER, "1.4.3", last, emptyList()))
        assertEquals(true, Affected.check(VersionScheme.SEMVER, "9.2.3", emptyList(), listOf("9.1.6", "9.2.3")), "Lista de versiones")
        assertNull(Affected.check(VersionScheme.SEMVER, "rama-rara", last, emptyList()), "Versión que no se entiende: no se decide")
        assertEquals(listOf("2.15.0", "2.3.1"), Affected.fixedVersions(log4j))
    }

    @Test
    fun `puntuacion cvss 3 y nombres normalizados`() {
        assertEquals(10.0, Cvss3.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"))
        assertEquals(9.8, Cvss3.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"))
        assertEquals(5.5, Cvss3.baseScore("CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N"))
        assertEquals(6.1, Cvss3.baseScore("CVSS:3.0/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N"))
        assertEquals(0.0, Cvss3.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N"))
        assertNull(Cvss3.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"))
        assertEquals(Severity.CRITICAL, Cvss3.rating(9.8))
        assertEquals(Severity.MEDIUM, Cvss3.rating(5.5))
        assertEquals("pyyaml", OsvEcosystems.normalize("PyPI", "PyYAML"))
        assertEquals("zope-interface", OsvEcosystems.normalize("PyPI", "zope.interface"))
        assertEquals("serde-json", OsvEcosystems.normalize("crates.io", "serde_json"))
        assertEquals("github.com/Foo/Bar", OsvEcosystems.normalize("Go", "github.com/Foo/Bar"))
        assertFalse(OsvEcosystems.SUPPORTED.contains("CocoaPods"))
    }
}

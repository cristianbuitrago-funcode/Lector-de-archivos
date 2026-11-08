package com.funcodestudiocb.analyzer.engine.dependencies

import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.inventory.PathInfo
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LockfilesTest {

    private fun parse(path: String, text: String): ParsedLockfile = assertNotNull(Lockfiles.parse(PathInfo(path), text.trimIndent())).also { assertTrue(it.readable, "No se pudo leer $path") }

    private fun ParsedLockfile.pkg(name: String, version: String? = null): LockedPackage =
        packages.firstOrNull { it.name == name && (version == null || it.version == version) } ?: error("Sin $name@$version en $path: ${packages.map { "${it.name}@${it.version}" }}")

    @Test
    fun `npm v3 con anidados, desarrollo y quien requiere a quien`() {
        val lock = parse(
            "web/package-lock.json",
            """
            {
              "lockfileVersion": 3,
              "packages": {
                "": { "name": "web", "dependencies": { "express": "4.16.0" }, "devDependencies": { "jest": "^29.0.0" } },
                "node_modules/express": { "version": "4.16.0", "dependencies": { "qs": "6.5.1", "debug": "2.6.9" } },
                "node_modules/qs": { "version": "6.5.1" },
                "node_modules/debug": { "version": "2.6.9", "dependencies": { "ms": "2.0.0" } },
                "node_modules/debug/node_modules/ms": { "version": "2.0.0" },
                "node_modules/ms": { "version": "2.1.3" },
                "node_modules/jest": { "version": "29.7.0", "dev": true },
                "packages/propio": { "version": "1.0.0" },
                "node_modules/propio": { "resolved": "packages/propio", "link": true }
              }
            }
            """,
        )
        assertEquals(Ecosystem.NPM, lock.ecosystem)
        assertEquals(true, lock.pkg("express").direct)
        assertEquals(false, lock.pkg("qs").direct)
        assertEquals(setOf("express"), lock.pkg("qs").parents)
        // Node busca primero en el node_modules anidado: debug usa su propio ms 2.0.0.
        assertEquals(setOf("debug"), lock.pkg("ms", "2.0.0").parents)
        assertTrue(lock.pkg("ms", "2.1.3").parents.isEmpty())
        assertTrue(lock.pkg("jest").dev && lock.pkg("jest").direct == true)
        assertTrue(lock.packages.none { it.name == "propio" }, "Los paquetes del propio repositorio no son dependencias")
        assertNotNull(lock.pkg("qs").line)
    }

    @Test
    fun `yarn clasico y berry, y pnpm`() {
        val classic = parse(
            "yarn.lock",
            """
            # yarn lockfile v1

            "@babel/code-frame@^7.0.0", "@babel/code-frame@^7.10.4":
              version "7.12.13"
              resolved "https://registry.yarnpkg.com/@babel/code-frame/-/code-frame-7.12.13.tgz"
              dependencies:
                "@babel/highlight" "^7.12.13"

            "@babel/highlight@^7.12.13":
              version "7.13.10"
            """,
        )
        assertEquals("7.12.13", classic.pkg("@babel/code-frame").version)
        assertEquals(setOf("@babel/code-frame"), classic.pkg("@babel/highlight").parents)
        assertNull(classic.pkg("@babel/highlight").direct, "yarn.lock no dice cuáles son directas")
        val berry = parse(
            "app/yarn.lock",
            """
            __metadata:
              version: 6

            "lodash@npm:^4.17.0":
              version: 4.17.21
              resolution: "lodash@npm:4.17.21"
              languageName: node
              linkType: hard

            "app@workspace:.":
              version: 0.0.0-use.local
              linkType: soft
              dependencies:
                lodash: ^4.17.0
            """,
        )
        assertEquals(listOf("lodash@4.17.21"), berry.packages.map { "${it.name}@${it.version}" })
        val pnpm = parse(
            "pnpm-lock.yaml",
            """
            lockfileVersion: '6.0'
            dependencies:
              express:
                specifier: ^4.18.0
                version: 4.18.2
            devDependencies:
              vitest:
                specifier: ^1.0.0
                version: 1.0.4
            packages:
              /express@4.18.2:
                resolution: {integrity: sha512-x}
                dependencies:
                  qs: 6.11.0
              /qs@6.11.0:
                resolution: {integrity: sha512-y}
              /vitest@1.0.4(@types/node@20.0.0):
                resolution: {integrity: sha512-z}
                dev: true
            """,
        )
        assertEquals(true, pnpm.pkg("express").direct)
        assertEquals(setOf("express"), pnpm.pkg("qs").parents)
        assertEquals(false, pnpm.pkg("qs").direct)
        assertEquals("1.0.4", pnpm.pkg("vitest").version)
        assertTrue(pnpm.pkg("vitest").dev)
    }

    @Test
    fun `composer, pipfile, poetry, uv y cargo`() {
        val composer = parse(
            "composer.lock",
            """
            { "packages": [ { "name": "laravel/framework", "version": "v8.0.0", "require": { "php": "^7.3", "symfony/console": "^5.1" } },
                            { "name": "symfony/console", "version": "v5.1.5" } ],
              "packages-dev": [ { "name": "phpunit/phpunit", "version": "9.3.0" } ] }
            """,
        )
        assertEquals(setOf("laravel/framework"), composer.pkg("symfony/console").parents)
        assertTrue(composer.pkg("phpunit/phpunit").dev)
        val pipfile = parse("Pipfile.lock", """{ "default": { "django": { "version": "==2.2.0" } }, "develop": { "pytest": { "version": "==7.0.0" } } }""")
        assertEquals("2.2.0", pipfile.pkg("django").version)
        assertTrue(pipfile.pkg("pytest").dev)
        val poetry = parse(
            "poetry.lock",
            """
            [[package]]
            name = "flask"
            version = "2.0.1"
            category = "main"

            [package.dependencies]
            Werkzeug = ">=2.0"

            [[package]]
            name = "werkzeug"
            version = "2.0.1"
            """,
        )
        assertEquals(setOf("flask"), poetry.pkg("werkzeug").parents)
        val uv = parse(
            "uv.lock",
            """
            [[package]]
            name = "proyecto"
            version = "0.1.0"
            source = { editable = "." }
            dependencies = [
                { name = "requests" },
            ]

            [[package]]
            name = "requests"
            version = "2.31.0"
            source = { registry = "https://pypi.org/simple" }
            dependencies = [{ name = "urllib3" }]

            [[package]]
            name = "urllib3"
            version = "2.0.7"
            source = { registry = "https://pypi.org/simple" }
            """,
        )
        assertTrue(uv.packages.none { it.name == "proyecto" })
        assertEquals(true, uv.pkg("requests").direct)
        assertEquals(false, uv.pkg("urllib3").direct)
        assertEquals(setOf("requests"), uv.pkg("urllib3").parents)
        val cargo = parse(
            "Cargo.lock",
            """
            version = 3

            [[package]]
            name = "mi-app"
            version = "0.1.0"
            dependencies = [
             "serde",
             "smallvec 0.6.9",
            ]

            [[package]]
            name = "serde"
            version = "1.0.130"
            source = "registry+https://github.com/rust-lang/crates.io-index"

            [[package]]
            name = "smallvec"
            version = "0.6.9"
            source = "registry+https://github.com/rust-lang/crates.io-index"
            """,
        )
        assertTrue(cargo.packages.none { it.name == "mi-app" })
        assertEquals(true, cargo.pkg("smallvec").direct)
    }

    @Test
    fun `gemfile, nuget, pubspec, go y gradle`() {
        val gem = parse(
            "Gemfile.lock",
            """
            GEM
              remote: https://rubygems.org/
              specs:
                actionpack (5.2.0)
                  rack (~> 2.0)
                nokogiri (1.13.10-x86_64-linux)
                rack (2.0.5)

            PLATFORMS
              ruby

            DEPENDENCIES
              actionpack (= 5.2.0)
              nokogiri
            """,
        )
        assertEquals("1.13.10", gem.pkg("nokogiri").version)
        assertEquals(setOf("actionpack"), gem.pkg("rack").parents)
        assertEquals(false, gem.pkg("rack").direct)
        val nuget = parse(
            "packages.lock.json",
            """{ "version": 1, "dependencies": { "net6.0": {
                 "Newtonsoft.Json": { "type": "Direct", "requested": "[12.0.1, )", "resolved": "12.0.1" },
                 "System.Text.Encodings.Web": { "type": "Transitive", "resolved": "4.5.0" },
                 "MiLib": { "type": "Project" } } } }""",
        )
        assertEquals(true, nuget.pkg("Newtonsoft.Json").direct)
        assertEquals(false, nuget.pkg("System.Text.Encodings.Web").direct)
        assertTrue(nuget.packages.none { it.name == "MiLib" })
        val pub = parse(
            "pubspec.lock",
            """
            packages:
              http:
                dependency: "direct main"
                source: hosted
                version: "0.13.3"
              flutter:
                dependency: "direct main"
                source: sdk
                version: "0.0.0"
              collection:
                dependency: transitive
                source: hosted
                version: "1.15.0"
            """,
        )
        assertEquals(true, pub.pkg("http").direct)
        assertEquals(false, pub.pkg("collection").direct)
        assertTrue(pub.packages.none { it.name == "flutter" })
        val go = parse(
            "go.mod",
            """
            module ejemplo.com/app

            go 1.19

            require (
                github.com/gin-gonic/gin v1.7.0
                golang.org/x/text v0.3.0 // indirect
            )
            """,
        )
        assertEquals(true, go.pkg("github.com/gin-gonic/gin").direct)
        assertEquals(false, go.pkg("golang.org/x/text").direct)
        val gradle = parse(
            "app/gradle.lockfile",
            """
            # This is a Gradle generated file for dependency locking.
            com.squareup.okhttp3:okhttp:3.12.0=compileClasspath,runtimeClasspath
            junit:junit:4.12=testCompileClasspath,testRuntimeClasspath
            empty=annotationProcessor
            """,
        )
        assertEquals("3.12.0", gradle.pkg("com.squareup.okhttp3:okhttp").version)
        assertTrue(gradle.pkg("junit:junit").dev)
    }

    @Test
    fun `archivos que no son de bloqueo o estan danados`() {
        assertNull(Lockfiles.parse(PathInfo("package.json"), "{}"))
        assertNull(Lockfiles.parse(PathInfo("node_modules/x/package-lock.json"), "{}"), "Dentro de node_modules no cuenta")
        val broken = assertNotNull(Lockfiles.parse(PathInfo("package-lock.json"), "{ esto no es json"))
        assertFalse(broken.readable)
        assertTrue(broken.packages.isEmpty())
    }
}

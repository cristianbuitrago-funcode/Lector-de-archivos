package com.funcodestudiocb.analyzer.engine.dependencies

import com.funcodestudiocb.analyzer.engine.api.DependencyScope
import com.funcodestudiocb.analyzer.engine.api.Ecosystem
import com.funcodestudiocb.analyzer.engine.inventory.PathInfo
import com.funcodestudiocb.analyzer.engine.inventory.parse.SafeYaml
import com.funcodestudiocb.analyzer.engine.inventory.parse.YamlMap
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class ManifestParsersTest {

    private fun parse(path: String, text: String): ParsedManifest = assertNotNull(ManifestParsers.parse(PathInfo(path), text.trimIndent()))

    private fun ParsedManifest.byName() = dependencies.associateBy { it.name }

    @Test
    fun `npm - secciones, alcance y linea`() {
        val m = parse(
            "web/package.json",
            """
            {
              "name": "web",
              "dependencies": { "express": "^4.19.0", "@prisma/client": "5.1.0" },
              "devDependencies": {
                "jest": "29"
              },
              "peerDependencies": { "react": ">=18" }
            }
            """,
        )
        val deps = m.byName()
        assertEquals(Ecosystem.NPM, m.ecosystem)
        assertEquals("^4.19.0", deps["express"]?.version)
        assertEquals(DependencyScope.RUNTIME, deps["@prisma/client"]?.scope)
        assertEquals(DependencyScope.DEVELOPMENT, deps["jest"]?.scope)
        assertEquals(5, deps["jest"]?.line)
        assertEquals(DependencyScope.PEER, deps["react"]?.scope)
        assertNull(ManifestParsers.parse(PathInfo("node_modules/x/package.json"), "{}"), "Las dependencias instaladas no son del proyecto")
        assertFalse(parse("package.json", "{ roto").readable)
    }

    @Test
    fun `Composer - plataforma separada`() {
        val deps = parse("composer.json", """{"require": {"php": "^8.2", "ext-pdo": "*", "laravel/framework": "^11.0"}, "require-dev": {"phpunit/phpunit": "^10"}}""").byName()
        assertEquals(DependencyScope.PLATFORM, deps["php"]?.scope)
        assertEquals(DependencyScope.PLATFORM, deps["ext-pdo"]?.scope)
        assertEquals(DependencyScope.RUNTIME, deps["laravel/framework"]?.scope)
        assertEquals(DependencyScope.DEVELOPMENT, deps["phpunit/phpunit"]?.scope)
    }

    @Test
    fun `Gradle - coordenadas, complementos y referencias al catalogo`() {
        val m = parse(
            "app/build.gradle.kts",
            """
            plugins {
                id("com.android.application")
                alias(libs.plugins.kotlin.android)
                kotlin("plugin.serialization") version "2.0.0"
            }
            dependencies {
                implementation(project(":core"))
                implementation("com.squareup.retrofit2:retrofit:2.11.0")
                implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
                implementation(libs.androidx.core.ktx)
                implementation(libs.bundles.compose)
                testImplementation("junit:junit:4.13.2")
                // implementation("com.comentado:no:1.0")
                ksp(libs.room.compiler)
            }
            """,
        )
        val deps = m.byName()
        assertEquals(DependencyScope.BUILD, deps["com.android.application"]?.scope)
        assertEquals("2.0.0", deps["org.jetbrains.kotlin.plugin.serialization"]?.version)
        assertEquals("2.11.0", deps["com.squareup.retrofit2:retrofit"]?.version)
        assertEquals(DependencyScope.PLATFORM, deps["com.google.firebase:firebase-bom"]?.scope)
        assertEquals(DependencyScope.TEST, deps["junit:junit"]?.scope)
        assertNull(deps["com.comentado:no"], "Las líneas comentadas no cuentan")
        assertTrue(deps.keys.none { it.contains("core") }, "Los módulos del propio proyecto no son dependencias externas")
        assertEquals(
            listOf("kotlin.android" to CatalogRefKind.PLUGIN, "androidx.core.ktx" to CatalogRefKind.LIBRARY, "compose" to CatalogRefKind.BUNDLE, "room.compiler" to CatalogRefKind.LIBRARY),
            m.catalogRefs.map { it.alias to it.kind },
        )
        assertEquals(DependencyScope.BUILD, m.catalogRefs.last().scope)
    }

    @Test
    fun `Gradle - catalogo de versiones`() {
        val catalog = assertNotNull(
            parse(
                "gradle/libs.versions.toml",
                """
                [versions]
                core = "1.13.1"
                room = { strictly = "2.6.1" }

                [libraries]
                androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "core" }
                room_compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
                okhttp = "com.squareup.okhttp3:okhttp:4.12.0"
                compose-ui = { module = "androidx.compose.ui:ui" }

                [bundles]
                compose = [
                    "compose-ui",
                ]

                [plugins]
                kotlin-android = { id = "org.jetbrains.kotlin.android", version = "2.0.0" }
                """,
            ).catalog,
        )
        assertEquals("libs", catalog.name)
        assertEquals("androidx.core:core-ktx" to "1.13.1", catalog.libraries["androidx.core.ktx"])
        assertEquals("androidx.room:room-compiler" to "2.6.1", catalog.libraries["room.compiler"])
        assertEquals("com.squareup.okhttp3:okhttp" to "4.12.0", catalog.libraries["okhttp"])
        assertEquals("androidx.compose.ui:ui" to null, catalog.libraries["compose.ui"])
        assertEquals(listOf("compose.ui"), catalog.bundles["compose"])
        assertEquals("org.jetbrains.kotlin.android" to "2.0.0", catalog.plugins["kotlin.android"])
    }

    @Test
    fun `Maven - dependencias, propiedades, alcance y complementos`() {
        val deps = parse(
            "pom.xml",
            """
            <project>
              <properties><spring.version>3.3.0</spring.version></properties>
              <dependencyManagement><dependencies><dependency><groupId>x</groupId><artifactId>gestionada</artifactId></dependency></dependencies></dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                  <version>${'$'}{spring.version}</version>
                </dependency>
                <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
                <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
              </dependencies>
              <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId></plugin></plugins></build>
            </project>
            """,
        ).byName()
        assertEquals("3.3.0", deps["org.springframework.boot:spring-boot-starter-web"]?.version)
        assertEquals(5, deps["org.springframework.boot:spring-boot-starter-web"]?.line)
        assertEquals(DependencyScope.RUNTIME, deps["org.postgresql:postgresql"]?.scope)
        assertEquals(DependencyScope.TEST, deps["org.junit.jupiter:junit-jupiter"]?.scope)
        assertEquals(DependencyScope.BUILD, deps["org.apache.maven.plugins:maven-compiler-plugin"]?.scope)
        assertNull(deps["x:gestionada"], "dependencyManagement no declara uso")
    }

    @Test
    fun `Python - requirements, pyproject y Pipfile`() {
        val req = parse(
            "requirements-dev.txt",
            """
            # comentario
            -r requirements.txt
            --index-url https://usuario:clave@pypi.ejemplo/simple
            Django>=4.2,<5 ; python_version >= "3.10"
            psycopg2-binary==2.9.9  # BD
            requests[socks]
            git+https://github.com/x/y.git
            """,
        ).byName()
        assertEquals(setOf("Django", "psycopg2-binary", "requests"), req.keys)
        assertEquals(">=4.2,<5", req["Django"]?.version)
        assertEquals(DependencyScope.DEVELOPMENT, req["Django"]?.scope)

        val py = parse(
            "pyproject.toml",
            """
            [build-system]
            requires = ["hatchling"]

            [project]
            name = "api"
            dependencies = [
                "fastapi>=0.110",
                "sqlalchemy[asyncio] (>=2.0)",
            ]

            [project.optional-dependencies]
            test = ["pytest>=8"]

            [tool.poetry.dependencies]
            python = "^3.11"
            redis = { version = "^5.0", optional = true }
            """,
        ).byName()
        assertEquals(DependencyScope.BUILD, py["hatchling"]?.scope)
        assertEquals(">=0.110", py["fastapi"]?.version)
        assertEquals(7, py["fastapi"]?.line)
        assertEquals(">=2.0", py["sqlalchemy"]?.version)
        assertEquals(DependencyScope.TEST, py["pytest"]?.scope)
        assertEquals(DependencyScope.PLATFORM, py["python"]?.scope)
        assertEquals("^5.0", py["redis"]?.version)

        val pipfile = parse("Pipfile", "[packages]\nflask = \"*\"\n[dev-packages]\nblack = {version = \"==24.1\"}\n").byName()
        assertNull(pipfile["flask"]?.version)
        assertEquals(DependencyScope.DEVELOPMENT, pipfile["black"]?.scope)
    }

    @Test
    fun `Dart - pubspec con SDK, version y git`() {
        val deps = parse(
            "pubspec.yaml",
            """
            name: app
            dependencies:
              flutter:
                sdk: flutter
              http: ^1.2.0
              firebase_core: 2.31.0
              mi_paquete:
                git:
                  url: https://github.com/x/y.git
            dev_dependencies:
              flutter_test:
                sdk: flutter
            """,
        ).byName()
        assertEquals(DependencyScope.PLATFORM, deps["flutter"]?.scope)
        assertEquals(3, deps["flutter"]?.line)
        assertEquals("^1.2.0", deps["http"]?.version)
        assertEquals(5, deps["http"]?.line)
        assertNull(deps["mi_paquete"]?.version)
        assertEquals(DependencyScope.PLATFORM, deps["flutter_test"]?.scope)
    }

    @Test
    fun `NET, Go, Cargo, Gemfile, Podfile, CMake y Unreal`() {
        val csproj = parse(
            "Api/Api.csproj",
            """
            <Project Sdk="Microsoft.NET.Sdk.Web">
              <ItemGroup>
                <PackageReference Include="Microsoft.EntityFrameworkCore.SqlServer" Version="8.0.4" />
                <PackageReference Include="Serilog"><Version>3.1.1</Version></PackageReference>
                <PackageReference Include="Central" />
              </ItemGroup>
            </Project>
            """,
        ).byName()
        assertEquals(DependencyScope.PLATFORM, csproj["Microsoft.NET.Sdk.Web"]?.scope)
        assertEquals("8.0.4", csproj["Microsoft.EntityFrameworkCore.SqlServer"]?.version)
        assertEquals("3.1.1", csproj["Serilog"]?.version)
        assertNull(csproj["Central"]?.version)

        val go = parse(
            "go.mod",
            "module x\n\ngo 1.22\n\nrequire github.com/gin-gonic/gin v1.10.0\nrequire (\n\tgorm.io/gorm v1.25.10\n\tgolang.org/x/net v0.25.0 // indirect\n)\n",
        ).byName()
        assertEquals(setOf("github.com/gin-gonic/gin", "gorm.io/gorm"), go.keys, "Las indirectas son transitivas")

        val cargo = parse(
            "Cargo.toml",
            """
            [dependencies]
            tokio = { version = "1.37", features = ["full"] }
            serde = "1.0"
            interno = { path = "../interno" }

            [dev-dependencies]
            criterion = "0.5"

            [dependencies.sqlx]
            version = "0.7"
            features = ["postgres"]
            """,
        ).byName()
        assertEquals("1.37", cargo["tokio"]?.version)
        assertNull(cargo["interno"], "Las dependencias locales son partes del proyecto")
        assertEquals(DependencyScope.DEVELOPMENT, cargo["criterion"]?.scope)
        assertEquals("0.7", cargo["sqlx"]?.version)

        val gems = parse(
            "Gemfile",
            "source 'https://rubygems.org'\ngem 'rails', '~> 7.1'\ngem 'pg'\ngroup :development, :test do\n  gem 'rspec-rails'\nend\ngem 'puma', group: :production\n",
        ).byName()
        assertEquals("~> 7.1", gems["rails"]?.version)
        assertEquals(DependencyScope.TEST, gems["rspec-rails"]?.scope)
        assertEquals(DependencyScope.RUNTIME, gems["puma"]?.scope)

        val pods = parse("ios/Podfile", "target 'App' do\n  pod 'Alamofire', '~> 5.9'\n  pod 'Firebase/Analytics'\n  pod 'Local', :path => '../local'\nend\n").byName()
        assertEquals("~> 5.9", pods["Alamofire"]?.version)
        assertTrue("Firebase/Analytics" in pods)

        val cmake = parse("CMakeLists.txt", "find_package(OpenSSL 3.0 REQUIRED)\nFetchContent_Declare(googletest URL x)\n# find_package(Comentado)\n").byName()
        assertEquals("3.0", cmake["OpenSSL"]?.version)
        assertTrue("googletest" in cmake && "Comentado" !in cmake)

        val unreal = parse("Juego.uproject", """{"Plugins": [{"Name": "OnlineSubsystemSteam", "Enabled": true}, {"Name": "Apagado", "Enabled": false}]}""").byName()
        assertEquals(setOf("OnlineSubsystemSteam"), unreal.keys)
    }

    @Test
    fun `nombres no validos y limites`() {
        val deps = parse("package.json", """{"dependencies": {"nombre con espacios": "1", "ok": "1"}}""").byName()
        assertEquals(setOf("ok"), deps.keys)
        val many = buildString {
            append("{\"dependencies\": {")
            append((1..5_000).joinToString(",") { "\"p$it\": \"1\"" })
            append("}}")
        }
        assertEquals(ManifestParsers.MAX_PER_MANIFEST, parse("package.json", many).dependencies.size)
    }

    @Test
    fun `YAML - alias sin expandir, profundidad limitada y lineas de las claves`() {
        val bomb = buildString {
            append("a: &a [x, x, x, x, x, x, x, x, x, x]\n")
            for (i in 1..9) append("${('a' + i)}: &${('a' + i)} [${List(10) { "*${'a' + i - 1}" }.joinToString()}]\n")
        }
        val time = measureTime { assertNotNull(SafeYaml.parse(bomb)) }
        assertTrue(time.inWholeMilliseconds < 2_000, "Los alias nunca se expanden ($time)")
        assertNull(SafeYaml.parse("[".repeat(100) + "]".repeat(100)), "Profundidad máxima")
        assertNull(SafeYaml.parse("a: [b\n  c: : :"), "YAML inválido")
        val map = assertNotNull(SafeYaml.parse("x: 1\n\ny:\n  z: 2\n") as? YamlMap)
        assertEquals(3, map.lineOf("y"))
    }
}

package com.funcodestudiocb.analyzer.engine.pipeline

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.ContentChunk
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.DeclaredDependency
import com.funcodestudiocb.analyzer.engine.api.DependencyScope
import com.funcodestudiocb.analyzer.engine.api.EvidenceKind
import com.funcodestudiocb.analyzer.engine.api.FileProfile
import com.funcodestudiocb.analyzer.engine.api.FrameReader
import com.funcodestudiocb.analyzer.engine.api.FrameWriter
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.LanguageStats
import com.funcodestudiocb.analyzer.engine.api.LineMethod
import com.funcodestudiocb.analyzer.engine.api.ProtocolJson
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionReader
import com.funcodestudiocb.analyzer.engine.api.StageEvent
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import com.funcodestudiocb.analyzer.engine.api.Technology
import com.funcodestudiocb.analyzer.engine.core.Attributes
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveSource
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.PasswordHolder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class FullPipelineTest {

    @TempDir
    lateinit var dir: File

    private class Run(val records: List<ResultRecord>) {
        inline fun <reified T : ResultRecord> all(): List<T> = records.filterIsInstance<T>()
        fun tech(id: String): Technology = all<Technology>().firstOrNull { it.id == id } ?: fail("No se detectó $id: ${all<Technology>().map { it.id }}")
        fun has(id: String) = all<Technology>().any { it.id == id }
        fun profile(path: String): FileProfile = all<FileProfile>().first { it.path == path }
    }

    private fun run(entries: Map<String, String>): Run {
        val file = File.createTempFile("proyecto", ".zip", dir)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.trimIndent().toByteArray())
                out.closeEntry()
            }
        }
        FileInputStream(file).use { stream ->
            val attributes = Attributes()
            attributes[IngestKeys.SOURCE] = ArchiveSource(stream.channel, file.absolutePath)
            attributes[IngestKeys.PASSWORD] = attributes.closeLater(PasswordHolder(null))
            val config = AnalysisConfig("prueba", AnalysisMode.FULL, ingest = IngestOptions(file.name))
            val bytes = ByteArrayOutputStream()
            val writer = FrameWriter(bytes)
            runBlocking { PipelineRunner("test").run(config, FullPipeline.stages(), writer::write, attributes) }
            writer.close()
            val session = SessionReader(FrameReader(ByteArrayInputStream(bytes.toByteArray())), maxBufferedRecords = Int.MAX_VALUE).readAll()
            if (session.violation != null) fail("Flujo inválido: ${session.violation}")
            session.records.forEach { assertTrue(RecordValidator.validate(it).isEmpty(), "Registro inválido: $it") }
            val failed = session.records.filterIsInstance<StageEvent>().filter { it.status != StageStatus.COMPLETED }
            assertTrue(failed.isEmpty(), "Etapas sin completar: $failed")
            return Run(session.records)
        }
    }

    private val multiProject = mapOf(
        "backend/composer.json" to """
            {
              "require": {
                "php": "^8.2",
                "laravel/framework": "^11.0"
              }
            }
        """,
        "backend/artisan" to "#!/usr/bin/env php\n<?php\n",
        "backend/.env" to "DB_CONNECTION=mysql\nDB_PASSWORD=supersecreta123\n",
        "backend/app/Http/Controllers/UserController.php" to """
            <?php
            namespace App\Http\Controllers;

            use Illuminate\Support\Facades\DB;

            class UserController {}
        """,
        "mobile/pubspec.yaml" to """
            name: app
            dependencies:
              flutter:
                sdk: flutter
              firebase_core: ^2.31.0
              cloud_firestore: ^4.17.0
        """,
        "mobile/lib/main.dart" to """
            import 'package:flutter/material.dart';
            import 'package:cloud_firestore/cloud_firestore.dart';

            void main() => runApp(const MaterialApp());
        """,
        "mobile/android/app/google-services.json" to "{}",
        "web/package.json" to """{"name": "web", "dependencies": {"express": "^4.19.0", "pg": "^8.11.0"}}""",
        "web/server.js" to """
            const express = require("express"); // más adelante usaremos Redis
            const db = "postgres://app:clave-muy-secreta-99@db:5432/app";
            const app = express();
            app.listen(3000);
        """,
        "README.md" to "# Proyecto\n\nEl pago con Stripe llegará en la versión 2.\n",
    )

    @Test
    fun `cada tecnologia muestra donde se encontro y su confianza`() {
        val run = run(multiProject)

        val laravel = run.tech("laravel")
        assertEquals(Confidence.CONFIRMED, laravel.confidence)
        assertEquals(listOf("^11.0"), laravel.versions)
        val dependency = laravel.evidence.first()
        assertEquals(EvidenceKind.DEPENDENCY, dependency.kind)
        assertEquals("backend/composer.json", dependency.path)
        assertEquals(4, dependency.line)
        assertTrue(laravel.evidence.any { it.kind == EvidenceKind.FILE_NAME && it.path == "backend/artisan" })
        assertTrue(laravel.evidence.any { it.kind == EvidenceKind.IMPORT && it.path == "backend/app/Http/Controllers/UserController.php" && it.line == 4 })
        assertEquals(listOf("backend"), laravel.projectRoots)

        val mysql = run.tech("mysql")
        assertEquals(Confidence.PROBABLE, mysql.confidence, "Solo la configuración lo indica")
        assertEquals("backend/.env", mysql.evidence.single().path)

        assertEquals(Confidence.CONFIRMED, run.tech("flutter").confidence)
        assertEquals(Confidence.CONFIRMED, run.tech("firestore").confidence)
        assertTrue(run.tech("firestore").evidence.any { it.kind == EvidenceKind.IMPORT && it.path == "mobile/lib/main.dart" && it.line == 2 })
        assertEquals(Confidence.CONFIRMED, run.tech("firebase").confidence)

        val express = run.tech("express")
        assertEquals(Confidence.CONFIRMED, express.confidence)
        assertTrue(express.evidence.any { it.kind == EvidenceKind.IMPORT && it.path == "web/server.js" && it.line == 1 })
        val postgres = run.tech("postgresql")
        assertEquals(Confidence.CONFIRMED, postgres.confidence)
        assertTrue(postgres.evidence.any { it.kind == EvidenceKind.CONTENT && it.path == "web/server.js" && it.line == 2 })

        // Menciones: nunca suman y se distinguen como tales.
        val redis = run.tech("redis")
        assertEquals(Confidence.POSSIBLE, redis.confidence)
        assertTrue(redis.evidence.all { it.kind == EvidenceKind.MENTION })
        val stripe = run.tech("stripe")
        assertEquals(Confidence.POSSIBLE, stripe.confidence)
        assertEquals("README.md", stripe.evidence.single().path)

        // Lo que no aparece no se inventa.
        listOf("django", "spring-boot", "mongodb", "react", "unity").forEach { assertFalse(run.has(it), "Detectó $it sin evidencia") }
        run.all<Technology>().forEach { t -> assertTrue(t.evidence.isNotEmpty() && t.evidence.all { it.path != null }) }
    }

    @Test
    fun `ningun secreto completo sale en los registros`() {
        val run = run(multiProject)
        val json = run.records.joinToString("\n") { ProtocolJson.encodeToString(ResultRecord.serializer(), it) }
        assertFalse(json.contains("supersecreta123"))
        assertFalse(json.contains("clave-muy-secreta-99"))
    }

    @Test
    fun `lenguajes, lineas, dependencias y resaltado`() {
        val run = run(multiProject)
        val stats = run.all<LanguageStats>().associateBy { it.id }
        assertEquals(2, stats["php"]?.files, "artisan es PHP (línea #!)")
        assertEquals(1, stats["dart"]?.files)
        assertEquals(3, stats["dart"]?.code)
        assertEquals(1, stats["dart"]?.blank)
        assertEquals(1, stats["javascript"]?.syntaxFiles)

        val server = assertNotNull(run.profile("web/server.js").language)
        assertEquals(LineMethod.SYNTAX, server.method)
        assertEquals(4, server.code)
        val chunk = run.all<ContentChunk>().single { it.path == "web/server.js" }
        assertTrue(chunk.highlight.isNotEmpty(), "Sin resaltado")

        val laravel = run.all<DeclaredDependency>().single { it.name == "laravel/framework" }
        assertEquals("backend", laravel.projectRoot)
        assertEquals(DependencyScope.PLATFORM, run.all<DeclaredDependency>().single { it.name == "php" }.scope)
    }

    @Test
    fun `Android con catalogo de versiones`() {
        val run = run(
            mapOf(
                "settings.gradle.kts" to "rootProject.name = \"app\"\ninclude(\":app\")\n",
                "gradle/libs.versions.toml" to """
                    [versions]
                    compose-bom = "2024.06.00"
                    room = "2.6.1"
                    [libraries]
                    compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
                    room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
                    [plugins]
                    android-application = { id = "com.android.application", version = "8.5.0" }
                """,
                "app/build.gradle.kts" to """
                    plugins {
                        alias(libs.plugins.android.application)
                    }
                    dependencies {
                        implementation(platform(libs.compose.bom))
                        implementation(libs.room.runtime)
                    }
                """,
                "app/src/main/AndroidManifest.xml" to "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"/>",
                "app/src/main/java/com/x/Db.kt" to "package com.x\n\nimport androidx.room.Database\n\n@Database(entities = [], version = 1)\nabstract class Db\n",
            ),
        )
        val android = run.tech("android")
        assertEquals(Confidence.CONFIRMED, android.confidence)
        assertTrue(android.evidence.any { it.kind == EvidenceKind.DEPENDENCY && it.path == "app/build.gradle.kts" && it.line == 2 })
        val room = run.tech("room")
        assertEquals(Confidence.CONFIRMED, room.confidence)
        assertEquals(listOf("2.6.1"), room.versions)
        assertTrue(room.evidence.any { it.kind == EvidenceKind.IMPORT && it.path == "app/src/main/java/com/x/Db.kt" && it.line == 3 })
        assertEquals(Confidence.CONFIRMED, run.tech("jetpack-compose").confidence)
        assertEquals(Confidence.CONFIRMED, run.tech("gradle").confidence)
        assertTrue(run.all<CoverageNote>().none { it.detail.contains("catálogo de versiones de Gradle no se pudieron resolver") })
    }

    @Test
    fun `sin codigo reconocible no hay tecnologias y se explica el alcance`() {
        val run = run(mapOf("notas/lista.csv" to "a,b\n1,2\n"))
        assertTrue(run.all<Technology>().isEmpty())
        assertTrue(run.all<CoverageNote>().any { it.detail.contains("catálogo v1") })
        assertTrue(run.all<CoverageNote>().any { it.detail.contains("Fase 7") })
    }
}

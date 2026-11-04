package com.funcodestudiocb.analyzer.engine.relations

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.CoverageNote
import com.funcodestudiocb.analyzer.engine.api.CoverageReason
import com.funcodestudiocb.analyzer.engine.api.DependencyCycle
import com.funcodestudiocb.analyzer.engine.api.FileGraph
import com.funcodestudiocb.analyzer.engine.api.FrameReader
import com.funcodestudiocb.analyzer.engine.api.FrameWriter
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.Relation
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.BROKEN
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.PROBABLE
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.UNVERIFIED
import com.funcodestudiocb.analyzer.engine.api.RelationConfidence.VERIFIED
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.api.RelationsSummary
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionReader
import com.funcodestudiocb.analyzer.engine.api.StageEvent
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import com.funcodestudiocb.analyzer.engine.core.Attributes
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyCollector
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyStage
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveSource
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.PasswordHolder
import com.funcodestudiocb.analyzer.engine.inventory.ContentCollector
import com.funcodestudiocb.analyzer.engine.inventory.InventoryPipeline
import com.funcodestudiocb.analyzer.engine.inventory.RedactedText
import com.funcodestudiocb.analyzer.engine.inventory.RedactedTextListener
import com.funcodestudiocb.analyzer.engine.languages.LanguageAnalyzer
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

class RelationsTest {

    @TempDir
    lateinit var dir: File

    private class Run(val records: List<ResultRecord>) {
        val relations = records.filterIsInstance<Relation>()
        fun rel(source: String, target: String, kind: RelationKind? = null): Relation =
            relations.firstOrNull { it.source == source && it.target == target && (kind == null || it.kind == kind) }
                ?: fail("No hay relación $source → $target ($kind):\n" + relations.filter { it.source == source }.joinToString("\n") { "  ${it.kind} ${it.target ?: it.external ?: "ROTA " + it.reference} ${it.confidence}" })
        fun external(source: String, name: String): Relation = relations.firstOrNull { it.source == source && it.external == name } ?: fail("Sin externa $name en $source: ${relations.filter { it.source == source }.map { it.external }}")
        fun broken(source: String, reference: String): Relation =
            relations.firstOrNull { it.source == source && it.confidence == BROKEN && it.reference == reference } ?: fail("Sin relación rota $reference en $source")
        fun graph(path: String): FileGraph = records.filterIsInstance<FileGraph>().firstOrNull { it.path == path } ?: fail("Sin ficha de grafo para $path")
    }

    /** @param lexical archivos que se tratan como si tree-sitter no los hubiera podido analizar (sin importaciones). */
    private fun run(entries: Map<String, String>, lexical: Set<String> = emptySet()): Run {
        val file = File.createTempFile("proyecto", ".zip", dir)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.trimIndent().toByteArray())
                out.closeEntry()
            }
        }
        val dependencies = DependencyCollector()
        val relations = RelationCollector()
        val listener = RedactedTextListener { context, f ->
            val text = if (f.path in lexical && f.language != null) {
                RedactedText(f.path, f.info, f.text, f.lineStarts, f.language, LanguageAnalyzer.analyze(f.language!!, f.text, highlight = false, syntax = false), f.state)
            } else {
                f
            }
            relations.onText(context, text)
        }
        val stages = InventoryPipeline.registry(ContentCollector(listOf(dependencies, listener)))
            .register(DependencyStage(dependencies))
            .register(RelationStage(relations))
            .plan()
        FileInputStream(file).use { stream ->
            val attributes = Attributes()
            attributes[IngestKeys.SOURCE] = ArchiveSource(stream.channel, file.absolutePath)
            attributes[IngestKeys.PASSWORD] = attributes.closeLater(PasswordHolder(null))
            val bytes = ByteArrayOutputStream()
            val writer = FrameWriter(bytes)
            runBlocking { PipelineRunner("test").run(AnalysisConfig("prueba", AnalysisMode.FULL, ingest = IngestOptions(file.name)), stages, writer::write, attributes) }
            writer.close()
            val session = SessionReader(FrameReader(ByteArrayInputStream(bytes.toByteArray())), maxBufferedRecords = Int.MAX_VALUE).readAll()
            if (session.violation != null) fail("Flujo inválido: ${session.violation}")
            session.records.forEach { assertTrue(RecordValidator.validate(it).isEmpty(), "Registro inválido: $it ${RecordValidator.validate(it)}") }
            val failed = session.records.filterIsInstance<StageEvent>().filter { it.status != StageStatus.COMPLETED }
            assertTrue(failed.isEmpty(), "Etapas sin completar: $failed")
            return Run(session.records)
        }
    }

    /** Criterio de aceptación: toda conexión tiene sus cinco atributos y evidencia. */
    private fun assertFiveAttributes(run: Run) {
        assertTrue(run.relations.isNotEmpty())
        for (r in run.relations) {
            assertTrue(r.source.isNotEmpty(), "Origen")
            assertTrue(r.target != null || r.external != null || r.confidence == BROKEN, "Destino: $r")
            assertNotNull(r.kind)
            assertNotNull(r.impact, "Impacto")
            assertTrue(r.evidence.isNotEmpty() && r.evidence.all { it.path == r.source && it.detail.isNotBlank() }, "Evidencia: $r")
        }
    }

    @Test
    fun `JavaScript y TypeScript - rutas, alias, paquetes, recursos, rotas, herencia, ciclos y archivos sin uso`() {
        val run = run(
            mapOf(
                "web/package.json" to """{"name": "web", "dependencies": {"react": "^18.0.0"}}""",
                "web/tsconfig.json" to """{"compilerOptions": {"baseUrl": ".", "paths": {"@app/*": ["src/*"]}}}""",
                "web/index.html" to """
                    <!doctype html>
                    <link rel="stylesheet" href="src/styles.css">
                    <img src="img/logo.png">
                    <script type="module" src="/src/index.tsx"></script>
                """,
                "web/src/index.tsx" to """
                    import React from 'react';
                    import { createRoot } from 'react-dom/client';
                    import { App } from './App';
                    import { api } from '@app/services/api';
                    import fs from 'fs';
                    import lodash from 'lodash';
                    import { nada } from './nope';
                    import './styles.css';
                    createRoot(document.getElementById('root')).render(<App />);
                """,
                "web/src/App.tsx" to """
                    import { Base } from './base';
                    import { Button } from './components';
                    export class App extends Base {}
                """,
                "web/src/base.ts" to "export class Base {}\n",
                "web/src/components/index.ts" to "export * from './Button';\n",
                "web/src/components/Button.tsx" to "export function Button() { return null; }\n",
                "web/src/services/api.ts" to "import { b } from './cicloB';\nexport const api = {};\n",
                "web/src/services/cicloB.ts" to "import { api } from './api';\nexport const b = 1;\n",
                "web/src/unused.ts" to "export const nadie = 1;\n",
                "web/src/styles.css" to "@import './theme.css';\nbody { background: url('../img/fondo.png'); }\n",
                "web/src/theme.css" to "body { color: red; }\n",
                "web/img/fondo.png" to "PNG",
            ),
        )
        assertFiveAttributes(run)
        val app = run.rel("web/src/index.tsx", "web/src/App.tsx")
        assertEquals(VERIFIED, app.confidence)
        assertEquals(listOf("App"), app.exchanged)
        assertEquals(listOf("App"), app.impact.undefinedSymbols, "Si App desaparece, index.tsx pierde App")
        assertEquals(3, app.line)
        val alias = run.rel("web/src/index.tsx", "web/src/services/api.ts")
        assertTrue(alias.evidence.single().detail.contains("alias «@app/*»"), alias.evidence.toString())
        assertEquals(VERIFIED, run.external("web/src/index.tsx", "react").confidence)
        assertEquals(UNVERIFIED, run.external("web/src/index.tsx", "lodash").confidence, "No está declarado")
        assertEquals(VERIFIED, run.external("web/src/index.tsx", "node:fs").confidence)
        assertTrue(run.broken("web/src/index.tsx", "./nope").evidence.single().detail.contains("no lleva a ningún archivo"))
        assertEquals(RelationKind.IMPORT, run.rel("web/src/App.tsx", "web/src/components/index.ts").kind)
        assertEquals(VERIFIED, run.rel("web/src/App.tsx", "web/src/base.ts", RelationKind.INHERITANCE).confidence)
        assertEquals(VERIFIED, run.rel("web/src/styles.css", "web/src/theme.css").confidence)
        assertEquals(RelationKind.RESOURCE, run.rel("web/src/styles.css", "web/img/fondo.png").kind)
        assertEquals(PROBABLE, run.rel("web/index.html", "web/src/index.tsx").confidence, "Ruta absoluta del sitio")
        assertEquals(RelationKind.RESOURCE, run.broken("web/index.html", "img/logo.png").kind)

        val cycle = run.records.filterIsInstance<DependencyCycle>().single()
        assertEquals(listOf("web/src/services/api.ts", "web/src/services/cicloB.ts"), cycle.paths)
        assertEquals(0, run.graph("web/src/services/api.ts").cycle)
        assertTrue(run.graph("web/src/unused.ts").unreferenced)
        assertFalse(run.graph("web/src/App.tsx").unreferenced)
        assertTrue(run.graph("web/src/App.tsx").transitiveDependents >= 2, "index.tsx e index.html dependen de App")
        assertEquals(1, run.graph("web/src/base.ts").incoming)
        val summary = run.records.filterIsInstance<RelationsSummary>().single()
        assertEquals(1, summary.cycles)
        assertEquals(2, summary.broken)
    }

    @Test
    fun `Python - relativas, absolutas, submodulos, biblioteca estandar, paquetes y herencia`() {
        val run = run(
            mapOf(
                "api/requirements.txt" to "requests==2.31.0\n",
                "api/main.py" to "from app.views import *\n\nif __name__ == \"__main__\":\n    pass\n",
                "api/app/__init__.py" to "# paquete\n",
                "api/app/db.py" to "class Base:\n    pass\n",
                "api/app/models.py" to "from .db import Base\n\nclass User(Base):\n    pass\n",
                "api/app/views.py" to """
                    import os
                    import requests
                    import numpy
                    from .models import User
                    from . import db
                    from app.services.mail import enviar
                    from app.fantasma import nada
                """,
                "api/app/services/__init__.py" to "# servicios\n",
                "api/app/services/mail.py" to "def enviar():\n    pass\n",
            ),
        )
        assertFiveAttributes(run)
        assertEquals(listOf("User"), run.rel("api/app/views.py", "api/app/models.py").exchanged)
        assertEquals(VERIFIED, run.rel("api/app/views.py", "api/app/db.py").confidence)
        assertEquals(listOf("enviar"), run.rel("api/app/views.py", "api/app/services/mail.py").exchanged)
        assertEquals("todo lo que exporta", run.rel("api/main.py", "api/app/views.py").exchangedNote)
        assertEquals(VERIFIED, run.external("api/app/views.py", "os").confidence)
        assertEquals(VERIFIED, run.external("api/app/views.py", "requests").confidence)
        assertEquals(UNVERIFIED, run.external("api/app/views.py", "numpy").confidence)
        assertTrue(run.broken("api/app/views.py", "app.fantasma").evidence.single().detail.contains("es del proyecto"))
        assertEquals(VERIFIED, run.rel("api/app/models.py", "api/app/db.py", RelationKind.INHERITANCE).confidence)
    }

    @Test
    fun `Java y Kotlin - paquetes, mismo paquete, herencia, implementacion, generadas y rotas`() {
        val base = "app/src/main/java/com/demo"
        val run = run(
            mapOf(
                "$base/MainActivity.kt" to """
                    package com.demo

                    import com.demo.data.Repo
                    import com.demo.util.Fechas.Companion.hoy
                    import com.demo.R
                    import androidx.appcompat.app.AppCompatActivity

                    class MainActivity : AppCompatActivity() {
                        val repo = Repo()
                        val ayuda = Helper()
                    }
                """,
                "$base/Helper.kt" to "package com.demo\n\nclass Helper\n",
                "$base/data/Repo.java" to """
                    package com.demo.data;

                    import com.demo.data.Nada;

                    public class Repo extends BaseRepo implements Contrato {}
                """,
                "$base/data/BaseRepo.java" to "package com.demo.data;\n\npublic abstract class BaseRepo {}\n",
                "$base/data/Contrato.kt" to "package com.demo.data\n\ninterface Contrato\n",
                "$base/data/Huerfano.kt" to "package com.demo.data\n\nclass Huerfano\n",
                "$base/util/Fechas.kt" to "package com.demo.util\n\nclass Fechas {\n    companion object {\n        fun hoy() = 1\n    }\n}\n",
                "build.gradle.kts" to "val Helper = 1\nabstract class Repo\n",
                "app/build.gradle.kts" to "val x = Helper + 1\n",
            ),
        )
        assertFiveAttributes(run)
        val repo = run.rel("$base/MainActivity.kt", "$base/data/Repo.java")
        assertEquals(VERIFIED, repo.confidence)
        assertEquals(listOf("Repo"), repo.impact.undefinedSymbols)
        val helper = run.rel("$base/MainActivity.kt", "$base/Helper.kt", RelationKind.REFERENCE)
        assertEquals(PROBABLE, helper.confidence)
        assertEquals(listOf("Helper"), helper.exchanged)
        assertEquals(UNVERIFIED, run.external("$base/MainActivity.kt", "com.demo.R").confidence, "R se genera al compilar")
        val appcompat = run.external("$base/MainActivity.kt", "androidx.appcompat")
        assertEquals(UNVERIFIED, appcompat.confidence, "Sin dependencias declaradas en este proyecto de prueba")
        assertEquals(listOf("AppCompatActivity"), appcompat.exchanged)
        assertEquals(BROKEN, run.broken("$base/data/Repo.java", "com.demo.data.Nada").confidence)
        assertEquals(RelationKind.INHERITANCE, run.rel("$base/data/Repo.java", "$base/data/BaseRepo.java", RelationKind.INHERITANCE).kind)
        assertEquals(RelationKind.IMPLEMENTATION, run.rel("$base/data/Repo.java", "$base/data/Contrato.kt", RelationKind.IMPLEMENTATION).kind)
        assertEquals(VERIFIED, run.rel("$base/MainActivity.kt", "$base/util/Fechas.kt").confidence, "Miembro del companion object")
        assertTrue(run.graph("$base/data/Huerfano.kt").unreferenced)
        assertTrue(run.records.filterIsInstance<CoverageNote>().none { it.reason == CoverageReason.NOT_ANALYZED && it.stageId == "relations" })
        assertTrue(run.relations.none { it.source.endsWith(".kts") || it.target?.endsWith(".kts") == true }, "Los scripts .kts no comparten paquete")
        assertFalse(run.graph("$base/MainActivity.kt").unreferenced, "Una actividad la registra el framework")
    }

    @Test
    fun `sin importaciones de un archivo no se afirma que otros archivos no se usen`() {
        val base = "src/main/kotlin/com/demo"
        val entries = mapOf(
            "$base/Inicio.kt" to "package com.demo\n\nimport com.demo.datos.Huerfano\n\nfun inicio() = Huerfano()\n",
            "$base/datos/Huerfano.kt" to "package com.demo.datos\n\nclass Huerfano\n",
            "web/app.js" to "import { b } from './b.js'\n",
            "web/b.js" to "export const b = 1\n",
            "web/suelto.js" to "export const c = 2\n",
        )
        // Con tree-sitter, Huerfano.kt tiene quien lo use y suelto.js no.
        val complete = run(entries)
        assertFalse(complete.graph("$base/datos/Huerfano.kt").unreferenced)
        assertTrue(complete.graph("web/suelto.js").unreferenced)

        // Si Inicio.kt solo se pudo contar léxicamente, su importación falta: Huerfano.kt no puede darse por no usado,
        // pero JavaScript, que es otro ecosistema y se analizó completo, sigue señalándose.
        val partial = run(entries, lexical = setOf("$base/Inicio.kt"))
        assertTrue(partial.relations.none { it.source == "$base/Inicio.kt" && it.kind == RelationKind.IMPORT })
        assertFalse(partial.graph("$base/datos/Huerfano.kt").unreferenced, "No se inventa que no se usa")
        assertTrue(partial.graph("web/suelto.js").unreferenced)
        val note = partial.records.filterIsInstance<CoverageNote>().single { it.stageId == "relations" && it.reason == CoverageReason.NOT_ANALYZED }
        assertTrue("1 archivos (1 de Kotlin)" in note.detail && "sin referencias" in note.detail, note.detail)
    }

    @Test
    fun `PHP con PSR-4, CSharp, Dart, C y GDScript`() {
        val run = run(
            mapOf(
                "back/composer.json" to """{"require": {"laravel/framework": "^11.0"}, "autoload": {"psr-4": {"App\\": "app/"}}}""",
                "back/app/Http/Controllers/Controller.php" to "<?php\nnamespace App\\Http\\Controllers;\nabstract class Controller {}\n",
                "back/app/Http/Controllers/UserController.php" to """
                    <?php
                    namespace App\Http\Controllers;
                    use App\Models\User;
                    use App\Models\Fantasma;
                    use Illuminate\Http\Request;
                    class UserController extends Controller {}
                """,
                "back/app/Models/User.php" to "<?php\nnamespace App\\Models;\nclass User {}\n",
                "net/Models/Pedido.cs" to "namespace Demo.Models { public class Pedido {} }\n",
                "net/Services/Pedidos.cs" to "using System;\nusing Demo.Models;\nnamespace Demo.Services { class Pedidos { Pedido p; } }\n",
                "movil/pubspec.yaml" to "name: tienda\ndependencies:\n  flutter:\n    sdk: flutter\n  http: ^1.2.0\n",
                "movil/lib/main.dart" to "import 'package:flutter/material.dart';\nimport 'package:http/http.dart' as http;\nimport 'package:tienda/pantallas/inicio.dart';\nvoid main() {}\n",
                "movil/lib/pantallas/inicio.dart" to "class Inicio {}\n",
                "c/src/main.c" to "#include \"util.h\"\n#include <stdio.h>\n#include \"lib/api.h\"\n#include \"falta.h\"\n#ifdef WINDOWS\n#include \"solo_windows.h\"\n#endif\nint main(void) { return 0; }\n",
                "c/src/util.h" to "int util(void);\n",
                "c/include/lib/api.h" to "int api(void);\n",
                "godot/project.godot" to "[application]\nconfig/name=\"Juego\"\n",
                "godot/base.gd" to "class_name Personaje\nfunc mover():\n\tpass\n",
                "godot/jugador.gd" to "extends \"res://base.gd\"\nvar escena = preload(\"res://escenas/falta.tscn\")\n",
                "godot/enemigo.gd" to "extends Personaje\n",
            ),
        )
        assertFiveAttributes(run)
        val ctrl = "back/app/Http/Controllers/UserController.php"
        assertEquals(VERIFIED, run.rel(ctrl, "back/app/Models/User.php").confidence)
        assertTrue(run.broken(ctrl, "App\\Models\\Fantasma").evidence.single().detail.contains("PSR-4"))
        assertEquals(VERIFIED, run.external(ctrl, "Illuminate\\Http").confidence)
        assertEquals(PROBABLE, run.rel(ctrl, "back/app/Http/Controllers/Controller.php", RelationKind.INHERITANCE).confidence)

        assertEquals(PROBABLE, run.rel("net/Services/Pedidos.cs", "net/Models/Pedido.cs").confidence)
        assertEquals(VERIFIED, run.external("net/Services/Pedidos.cs", "System").confidence)

        assertEquals(VERIFIED, run.rel("movil/lib/main.dart", "movil/lib/pantallas/inicio.dart").confidence)
        assertEquals(VERIFIED, run.external("movil/lib/main.dart", "flutter").confidence)
        assertEquals(VERIFIED, run.external("movil/lib/main.dart", "http").confidence)

        assertEquals(VERIFIED, run.rel("c/src/main.c", "c/src/util.h").confidence)
        assertEquals(PROBABLE, run.rel("c/src/main.c", "c/include/lib/api.h").confidence)
        assertTrue(run.relations.none { it.reference.contains("stdio") }, "Las cabeceras de sistema no son relaciones")
        assertEquals(BROKEN, run.broken("c/src/main.c", "falta.h").confidence)
        assertEquals(UNVERIFIED, run.external("c/src/main.c", "solo_windows.h").confidence, "Inclusión condicional")

        assertEquals(RelationKind.INHERITANCE, run.rel("godot/jugador.gd", "godot/base.gd").kind)
        assertEquals(VERIFIED, run.rel("godot/enemigo.gd", "godot/base.gd").confidence, "Por class_name")
        assertEquals(RelationKind.RESOURCE, run.broken("godot/jugador.gd", "res://escenas/falta.tscn").kind)
    }

    @Test
    fun `grafo - componentes, PageRank, dependientes y alcanzabilidad`() {
        val g = DependencyGraph(listOf("a", "b", "c", "d", "e"), listOf("a" to "b", "b" to "c", "c" to "b", "d" to "c", "a" to "e"))
        val comp = g.components()
        assertEquals(comp[1], comp[2], "b y c forman un ciclo")
        assertTrue(comp.toSet().size == 4)
        val rank = g.pageRank()
        assertTrue(rank[2] > rank[0] && rank[1] > rank[3], "Los más usados pesan más")
        val (dependents, capped) = g.transitiveDependents(comp)
        assertEquals(3, dependents[2], "a, b y d llegan a c")
        assertEquals(0, dependents[0])
        assertFalse(capped.any { it })
        val (bfs, bfsCapped) = g.transitiveDependents(comp, cap = 2, maxExact = 0)
        assertEquals(2, bfs[2])
        assertTrue(bfsCapped[2])
        val reached = g.reachedFrom(listOf("a"))
        assertTrue(reached[2].isNotEmpty() && reached[3].isEmpty())
        // Una cadena larga no desborda la pila (algoritmo iterativo).
        val n = 200_000
        val chain = DependencyGraph((0 until n).map { "n$it" }, (0 until n - 1).map { "n$it" to "n${it + 1}" })
        assertEquals(n, chain.components().toSet().size)
    }
}

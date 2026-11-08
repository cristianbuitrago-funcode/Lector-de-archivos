package com.funcodestudiocb.analyzer.engine.architecture

import com.funcodestudiocb.analyzer.engine.api.AnalysisConfig
import com.funcodestudiocb.analyzer.engine.api.AnalysisMode
import com.funcodestudiocb.analyzer.engine.api.ArchLayer
import com.funcodestudiocb.analyzer.engine.api.ArchitectureSummary
import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.Diagram
import com.funcodestudiocb.analyzer.engine.api.DiagramKind
import com.funcodestudiocb.analyzer.engine.api.DiagramNodeKind
import com.funcodestudiocb.analyzer.engine.api.FileLayer
import com.funcodestudiocb.analyzer.engine.api.FrameReader
import com.funcodestudiocb.analyzer.engine.api.FrameWriter
import com.funcodestudiocb.analyzer.engine.api.InformationFlow
import com.funcodestudiocb.analyzer.engine.api.IngestOptions
import com.funcodestudiocb.analyzer.engine.api.RecordValidator
import com.funcodestudiocb.analyzer.engine.api.RelationKind
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.SessionReader
import com.funcodestudiocb.analyzer.engine.api.StageEvent
import com.funcodestudiocb.analyzer.engine.api.StageStatus
import com.funcodestudiocb.analyzer.engine.audit.AuditCollector
import com.funcodestudiocb.analyzer.engine.audit.SecurityStage
import com.funcodestudiocb.analyzer.engine.audit.deps.DependencyAuditStage
import com.funcodestudiocb.analyzer.engine.audit.deps.RuntimeCollector
import com.funcodestudiocb.analyzer.engine.core.Attributes
import com.funcodestudiocb.analyzer.engine.core.PipelineRunner
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyCollector
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyStage
import com.funcodestudiocb.analyzer.engine.ingest.ArchiveSource
import com.funcodestudiocb.analyzer.engine.ingest.IngestKeys
import com.funcodestudiocb.analyzer.engine.ingest.PasswordHolder
import com.funcodestudiocb.analyzer.engine.inventory.ContentCollector
import com.funcodestudiocb.analyzer.engine.inventory.InventoryPipeline
import com.funcodestudiocb.analyzer.engine.relations.RelationCollector
import com.funcodestudiocb.analyzer.engine.relations.RelationStage
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ArchitectureTest {

    @TempDir
    lateinit var dir: File

    private class Arch(val records: List<ResultRecord>) {
        val layers = records.filterIsInstance<FileLayer>().associateBy { it.path }
        val flows = records.filterIsInstance<InformationFlow>()
        val summary = records.filterIsInstance<ArchitectureSummary>().single()
        fun diagram(kind: DiagramKind) = records.filterIsInstance<Diagram>().single { it.kind == kind }
        fun layer(path: String) = layers[path] ?: fail("Sin capa: $path. Hay: ${layers.keys}")
        fun flow(method: String, path: String) = flows.firstOrNull { it.method == method && it.path == path }
            ?: fail("Sin flujo $method $path. Hay: ${flows.map { "${it.method} ${it.path}" }}")
        fun chain(f: InformationFlow) = f.steps.joinToString(" → ") { "${it.layer}:${it.label}" }
    }

    /** Análisis con el pipeline real (ingesta, inventario, dependencias, relaciones, auditorías y arquitectura). */
    private fun analyze(vararg entries: Pair<String, String>): Arch {
        val file = File(dir, "proyecto-${entries.size}.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.trimIndent().replace("¤", "$").toByteArray())
                out.closeEntry()
            }
        }
        val dependencies = DependencyCollector()
        val relations = RelationCollector()
        val audit = AuditCollector()
        val runtimes = RuntimeCollector()
        val architecture = ArchitectureCollector()
        val stages = InventoryPipeline.registry(ContentCollector(listOf(dependencies, relations, audit, runtimes, architecture)))
            .register(DependencyStage(dependencies))
            .register(RelationStage(relations))
            .register(DependencyAuditStage(runtimes))
            .register(SecurityStage(audit))
            .register(ArchitectureStage(architecture))
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
            session.records.forEach { assertTrue(RecordValidator.validate(it).isEmpty(), "Registro inválido: ${RecordValidator.validate(it)} en $it") }
            val failed = session.records.filterIsInstance<StageEvent>().filter { it.status != StageStatus.COMPLETED }
            assertTrue(failed.isEmpty(), "Etapas sin completar: $failed")
            return Arch(session.records)
        }
    }

    /** Ningún nodo se sale del dibujo ni se solapa con otro. */
    private fun assertDrawable(d: Diagram) {
        d.nodes.forEach { n -> assertTrue(n.x >= 0 && n.y >= 0 && n.x + n.width <= d.width + 1 && n.y + n.height <= d.height + 1, "Fuera: ${n.id}") }
        d.nodes.forEachIndexed { i, a ->
            d.nodes.drop(i + 1).forEach { b ->
                val overlap = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
                assertTrue(!overlap, "Se solapan ${a.id} y ${b.id}")
            }
        }
        val ids = d.nodes.map { it.id }.toSet()
        d.edges.forEach { assertTrue(it.from in ids && it.to in ids) }
    }

    @Test
    fun `Express, React y Sequelize - capas, flujo completo hasta la tabla y dependencias inversas`() {
        val a = analyze(
            "server/package.json" to """{"name": "server", "dependencies": {"express": "^4.19.0", "sequelize": "^6.0.0"}}""",
            "server/app.js" to """
                const express = require('express');
                const users = require('./routes/users');
                const app = express();
                app.use('/api/users', users);
                app.listen(3000);
            """,
            "server/routes/users.js" to """
                const router = require('express').Router();
                const ctrl = require('../controllers/users');
                router.get('/', ctrl.list);
                router.post('/', ctrl.create);
                module.exports = router;
            """,
            "server/controllers/users.js" to """
                const service = require('../services/userService');
                exports.list = async (req, res) => res.json(await service.all());
                exports.create = async (req, res) => res.json(await service.add(req.body));
            """,
            "server/services/userService.js" to """
                const User = require('../models/user');
                exports.all = () => User.findAll();
                exports.add = (data) => User.create(data);
            """,
            "server/services/audit.js" to """
                exports.log = (what) => console.log(what);
            """,
            "server/models/user.js" to """
                const { DataTypes } = require('sequelize');
                const sequelize = require('../db');
                const audit = require('../services/audit');
                audit.log('modelo cargado');
                module.exports = sequelize.define('User', { name: DataTypes.STRING }, { tableName: 'users' });
            """,
            "server/db.js" to """
                const { Sequelize } = require('sequelize');
                module.exports = new Sequelize('sqlite::memory:');
            """,
            "client/package.json" to """{"name": "client", "dependencies": {"react": "^18.0.0", "axios": "^1.0.0"}}""",
            "client/src/api.js" to """
                import axios from 'axios';
                const api = axios.create({ baseURL: '/api' });
                export const listUsers = () => api.get('/users');
                export const github = () => fetch('https://api.github.com/users/octocat');
            """,
            "client/src/Users.jsx" to """
                import React, { useState } from 'react';
                import { listUsers } from './api';
                export default function Users() {
                  const [users, setUsers] = useState([]);
                  return <ul>{users.map(u => <li>{u.name}</li>)}</ul>;
                }
            """,
            "client/src/Users.test.jsx" to "import Users from './Users';\ntest('x', () => {});\n",
        )
        // Capas con su certeza y su motivo.
        assertEquals(ArchLayer.API, a.layer("server/routes/users.js").layer)
        assertEquals(Confidence.CONFIRMED, a.layer("server/routes/users.js").confidence)
        assertEquals(ArchLayer.CONTROLLER, a.layer("server/controllers/users.js").layer)
        assertTrue("atiende la ruta" in a.layer("server/controllers/users.js").reason)
        assertEquals(ArchLayer.SERVICE, a.layer("server/services/userService.js").layer)
        assertEquals(Confidence.PROBABLE, a.layer("server/services/userService.js").confidence)
        assertEquals(ArchLayer.MODEL, a.layer("server/models/user.js").layer)
        assertEquals(Confidence.CONFIRMED, a.layer("server/models/user.js").confidence, a.layer("server/models/user.js").reason)
        assertEquals(ArchLayer.FRONTEND, a.layer("client/src/Users.jsx").layer)
        assertEquals(Confidence.CONFIRMED, a.layer("client/src/Users.jsx").confidence, "Marca de React")
        assertEquals(ArchLayer.FRONTEND, a.layer("client/src/api.js").layer)
        assertEquals(ArchLayer.CONFIG, a.layer("server/package.json").layer)
        assertEquals(ArchLayer.TEST, a.layer("client/src/Users.test.jsx").layer)

        // Flujo legible: Frontend → API → Controlador → Servicio → Modelo → BD.
        val flow = a.flow("GET", "/api/users")
        assertEquals(
            listOf(ArchLayer.FRONTEND, ArchLayer.API, ArchLayer.CONTROLLER, ArchLayer.SERVICE, ArchLayer.MODEL, ArchLayer.DATABASE),
            flow.steps.map { it.layer }, a.chain(flow),
        )
        assertEquals("tabla users", flow.steps.last().label)
        assertEquals(listOf(null, RelationKind.HTTP, RelationKind.ROUTE_HANDLER, RelationKind.IMPORT, RelationKind.DB_ACCESS, RelationKind.DB_ACCESS), flow.steps.map { it.via })
        assertTrue(flow.reachesDatabase)
        assertEquals("client/src/api.js", flow.steps.first().path)
        assertEquals("server/routes/users.js", flow.steps[1].path)
        assertEquals(a.flows.first(), a.flows.first { it.reachesDatabase }, "Los flujos completos van primero")

        // Dependencia inversa: el modelo usa un servicio.
        assertTrue(a.summary.inverted >= 1)
        assertTrue(a.summary.invertedSamples.any { it.startsWith("server/models/user.js") && "Modelo → Servicio" in it }, "${a.summary.invertedSamples}")
        assertEquals("Capas: controlador, servicio y modelo", a.summary.pattern)
        assertEquals(a.layers.size, a.summary.files)

        // Diagrama de capas: capas en orden y la conexión inversa marcada.
        val layers = a.diagram(DiagramKind.LAYERS)
        assertDrawable(layers)
        assertTrue(layers.nodes.none { it.layer == ArchLayer.TEST }, "Las pruebas no se dibujan")
        fun y(l: ArchLayer) = layers.nodes.single { it.layer == l }.y
        assertTrue(y(ArchLayer.FRONTEND) < y(ArchLayer.API) && y(ArchLayer.API) < y(ArchLayer.CONTROLLER) && y(ArchLayer.CONTROLLER) < y(ArchLayer.SERVICE) && y(ArchLayer.SERVICE) < y(ArchLayer.MODEL))
        assertTrue(layers.edges.single { it.from == "MODEL" && it.to == "SERVICE" }.inverted)
        assertTrue(layers.edges.none { it.from == "CONTROLLER" && it.to == "SERVICE" && it.inverted })
        assertTrue(layers.edges.any { it.from == "FRONTEND" && RelationKind.HTTP in it.kinds }, "La llamada HTTP del cliente al servidor")

        // Módulos y proyectos.
        val modules = a.diagram(DiagramKind.MODULES)
        assertDrawable(modules)
        assertTrue(modules.nodes.any { it.path == "server/services" } && modules.nodes.any { it.path == "server/models" })
        assertTrue(modules.edges.any { it.from == "server/models" && it.to == "server/services" && it.cycle }, "models ↔ services forman un ciclo")
        val projects = a.diagram(DiagramKind.PROJECTS)
        assertDrawable(projects)
        assertEquals(setOf("client", "server"), projects.nodes.filter { it.kind == DiagramNodeKind.PROJECT }.map { it.label }.toSet())
        assertTrue(projects.edges.any { it.from == "prj:client" && it.to == "prj:server" && RelationKind.HTTP in it.kinds })
        assertTrue(projects.nodes.any { it.kind == DiagramNodeKind.EXTERNAL_SERVICE && it.label == "api.github.com" })
        assertTrue(projects.nodes.any { it.kind == DiagramNodeKind.PACKAGE && it.label == "express" })
    }

    @Test
    fun `Django - urls, vistas y modelos hasta la tabla`() {
        val a = analyze(
            "mysite/manage.py" to "import django\n",
            "mysite/mysite/settings.py" to "DEBUG = False\nROOT_URLCONF = 'mysite.urls'\n",
            "mysite/mysite/urls.py" to """
                from django.urls import path, include
                urlpatterns = [path('blog/', include('blog.urls'))]
            """,
            "mysite/blog/__init__.py" to "",
            "mysite/blog/urls.py" to """
                from django.urls import path
                from . import views
                urlpatterns = [path('', views.index)]
            """,
            "mysite/blog/views.py" to """
                from .models import Post

                def index(request):
                    return Post.objects.filter(published=True)
            """,
            "mysite/blog/models.py" to """
                from django.db import models

                class Post(models.Model):
                    title = models.CharField(max_length=200)
            """,
        )
        assertEquals(ArchLayer.API, a.layer("mysite/blog/urls.py").layer)
        assertEquals(ArchLayer.CONTROLLER, a.layer("mysite/blog/views.py").layer)
        assertEquals(ArchLayer.MODEL, a.layer("mysite/blog/models.py").layer)
        assertEquals(Confidence.CONFIRMED, a.layer("mysite/blog/models.py").confidence, "models.Model")
        val flow = a.flows.first { it.path.startsWith("/blog") }
        assertEquals(listOf(ArchLayer.API, ArchLayer.CONTROLLER, ArchLayer.MODEL, ArchLayer.DATABASE), flow.steps.map { it.layer }, a.chain(flow))
        assertTrue(flow.steps.last().label.startsWith("tabla "), a.chain(flow))
        assertEquals("MVC (controlador y modelo)", a.summary.pattern)
    }

    @Test
    fun `Spring - controlador, servicio, repositorio y entidad`() {
        val base = "backend/src/main/java/com/demo/api"
        val a = analyze(
            "backend/pom.xml" to "<project><groupId>com.demo</groupId><artifactId>api</artifactId><version>1</version></project>",
            "$base/UserController.java" to """
                package com.demo.api;

                import org.springframework.web.bind.annotation.*;
                import java.util.List;

                @RestController
                @RequestMapping("/users")
                public class UserController {
                    private final UserService service;
                    public UserController(UserService service) { this.service = service; }

                    @GetMapping
                    public List<User> all() { return service.all(); }
                }
            """,
            "$base/UserService.java" to """
                package com.demo.api;

                import org.springframework.stereotype.Service;

                @Service
                public class UserService {
                    private final UserRepository repo;
                    public UserService(UserRepository repo) { this.repo = repo; }
                    public java.util.List<User> all() { return repo.findAll(); }
                }
            """,
            "$base/UserRepository.java" to "package com.demo.api;\n\nimport org.springframework.data.jpa.repository.JpaRepository;\n\npublic interface UserRepository extends JpaRepository<User, Long> {}\n",
            "$base/User.java" to """
                package com.demo.api;

                import jakarta.persistence.*;

                @Entity
                @Table(name = "users")
                public class User {
                    @Id private Long id;
                }
            """,
        )
        assertEquals(ArchLayer.CONTROLLER, a.layer("$base/UserController.java").layer)
        assertEquals(Confidence.CONFIRMED, a.layer("$base/UserController.java").confidence)
        assertTrue("@RestController" in a.layer("$base/UserController.java").reason)
        assertEquals(ArchLayer.SERVICE, a.layer("$base/UserService.java").layer)
        assertEquals(ArchLayer.MODEL, a.layer("$base/UserRepository.java").layer)
        assertEquals(ArchLayer.MODEL, a.layer("$base/User.java").layer)
        val flow = a.flow("GET", "/users")
        assertEquals(ArchLayer.API, flow.steps.first().layer)
        assertTrue(flow.steps.any { it.layer == ArchLayer.SERVICE && it.path == "$base/UserService.java" }, a.chain(flow))
        assertTrue(flow.steps.any { it.layer == ArchLayer.MODEL }, a.chain(flow))
        assertNotNull(a.diagram(DiagramKind.LAYERS).nodes.singleOrNull { it.layer == ArchLayer.CONTROLLER })
    }

    @Test
    fun `las marcas dentro de cadenas no cuentan y sin rutas no se inventan flujos`() {
        val a = analyze(
            "tool/Marks.kt" to """
                package tool

                val NEEDLES = listOf("@Entity", "CREATE TABLE", "@RestController", "from 'react'")
                fun main() = println(NEEDLES)
            """,
            "tool/util/Text.kt" to "package tool.util\n\nfun clean(s: String) = s.trim()\n",
        )
        // Las marcas están dentro de una cadena: no deciden la capa (como mucho, la función que le dio el inventario).
        val marks = a.layer("tool/Marks.kt")
        assertTrue("marca" !in marks.reason && marks.confidence != Confidence.CONFIRMED, marks.reason)
        assertEquals(ArchLayer.SHARED, a.layer("tool/util/Text.kt").layer)
        assertTrue(a.flows.isEmpty())
        assertTrue(a.summary.notes.any { "No se encontraron rutas" in it }, "${a.summary.notes}")
        assertTrue(a.summary.notes.any { "Compartido" in it })
        assertEquals(null, a.summary.pattern)
        assertEquals(0, a.summary.inverted)
    }
}

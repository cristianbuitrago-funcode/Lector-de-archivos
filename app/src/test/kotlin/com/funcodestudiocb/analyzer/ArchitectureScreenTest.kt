package com.funcodestudiocb.analyzer

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.funcodestudiocb.analyzer.analysis.AnalysisState
import com.funcodestudiocb.analyzer.engine.api.ArchLayer
import com.funcodestudiocb.analyzer.engine.api.DiagramKind
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.ui.FuncodeRoot
import com.funcodestudiocb.analyzer.ui.theme.FuncodeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Proyecto web de prueba: React llama a Express, que pasa por controlador, servicio y modelo hasta la tabla. */
object ArchitectureFixtures {
    val PROJECT = mapOf(
        "tienda/server/package.json" to """{"name": "server", "dependencies": {"express": "^4.19.0", "sequelize": "^6.0.0"}}""",
        "tienda/server/app.js" to "const express = require('express');\nconst users = require('./routes/users');\nconst app = express();\napp.use('/api/users', users);\napp.listen(3000);\n",
        "tienda/server/routes/users.js" to "const router = require('express').Router();\nconst ctrl = require('../controllers/users');\nrouter.get('/', ctrl.list);\nmodule.exports = router;\n",
        "tienda/server/controllers/users.js" to "const service = require('../services/userService');\nexports.list = async (req, res) => res.json(await service.all());\n",
        "tienda/server/services/userService.js" to "const User = require('../models/user');\nexports.all = () => User.findAll();\n",
        "tienda/server/models/user.js" to "const { DataTypes } = require('sequelize');\nconst sequelize = require('../db');\nmodule.exports = sequelize.define('User', { name: DataTypes.STRING }, { tableName: 'users' });\n",
        "tienda/server/db.js" to "const { Sequelize } = require('sequelize');\nmodule.exports = new Sequelize('sqlite::memory:');\n",
        "tienda/client/package.json" to """{"name": "client", "dependencies": {"react": "^18.0.0", "axios": "^1.0.0"}}""",
        "tienda/client/src/api.js" to "import axios from 'axios';\nconst api = axios.create({ baseURL: '/api' });\nexport const listUsers = () => api.get('/users');\n",
        "tienda/client/src/Users.jsx" to "import React, { useState } from 'react';\nimport { listUsers } from './api';\nexport default function Users() {\n  const [users] = useState([]);\n  return <ul>{users.length}</ul>;\n}\n",
    )
}

@RunWith(AndroidJUnit4::class)
class ArchitectureScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        File(context.noBackupFilesDir, "vault").deleteRecursively()
        container = testContainer(context)
        container.session.unlock(container.vault.setup("Funcode-2026".toCharArray()))
        compose.setContent { FuncodeTheme { FuncodeRoot(container) } }
    }

    private fun awaitCondition(what: String = "", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condición no cumplida ($what); estado: ${container.coordinator.state.value}" }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    private fun open(route: String) {
        compose.onNodeWithTag("open_menu").performClick()
        compose.onNodeWithTag("nav_$route").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun analyze() {
        val file = File(context.cacheDir, "tienda.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            ArchitectureFixtures.PROJECT.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        container.coordinator.select(Uri.fromFile(file))
        awaitCondition("importado") { container.coordinator.state.value is AnalysisState.Ready }
        container.coordinator.start(ExclusionOptions())
        awaitCondition("guardado") { (container.coordinator.state.value as? AnalysisState.Finished)?.saved == true }
    }

    @Test
    fun recorridoDeLaArquitectura() {
        analyze()
        val (_, report) = runBlocking { container.repository.load(container.repository.recent(1).first().first().id)!! }
        assertNotNull(report.architecture)
        assertEquals(ArchLayer.SERVICE, report.fileLayers.single { it.path == "tienda/server/services/userService.js" }.layer)
        assertTrue(report.diagrams.map { it.kind }.containsAll(listOf(DiagramKind.LAYERS, DiagramKind.MODULES, DiagramKind.PROJECTS)))
        val flow = report.flows.single { it.method == "GET" && it.path == "/api/users" }
        assertEquals(listOf(ArchLayer.FRONTEND, ArchLayer.API, ArchLayer.CONTROLLER, ArchLayer.SERVICE, ArchLayer.MODEL, ArchLayer.DATABASE), flow.steps.map { it.layer })

        // Capas: lienzo, resumen con notas y leyenda, ficha de la capa de servicios con sus archivos.
        open("arquitectura")
        awaitCondition("arquitectura") { compose.onAllNodesWithTag("diagram_canvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag("screen_title") and hasText("Arquitectura")).assertIsDisplayed()
        compose.onNodeWithTag("diagram_canvas").assertIsDisplayed()
        compose.onNodeWithText("Patrón aparente: Capas: controlador, servicio y modelo", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("arch_notes_toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("arch_legend").assertExists()
        compose.onNodeWithTag("arch_notes_toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("arch_node_chips").performScrollToNode(hasTestTag("arch_node_SERVICE"))
        compose.onNodeWithTag("arch_node_SERVICE").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("arch_node_title").assertIsDisplayed()
        compose.onNodeWithText("Lógica de negocio", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("userService.js").performScrollTo().assertIsDisplayed()

        // Flujos: la cadena completa hasta la tabla.
        compose.onNodeWithTag("arch_tabs").performScrollToNode(hasTestTag("arch_tab_FLOWS"))
        compose.onNodeWithTag("arch_tab_FLOWS").performClick()
        awaitCondition("flujos") { compose.onAllNodesWithTag("arch_flows").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("arch_flows").performScrollToNode(hasText("GET /api/users"))
        compose.onAllNodesWithText("GET /api/users")[0].assertIsDisplayed()
        compose.onNodeWithTag("arch_flows").performScrollToNode(hasText("tabla users"))
        compose.onNodeWithText("tabla users").assertIsDisplayed()
        compose.onAllNodesWithText("Llega a la base de datos.").fetchSemanticsNodes().isNotEmpty().let { check(it) }
        compose.onNodeWithTag("arch_flows").performScrollToNode(hasTestTag("arch_only_complete"))
        compose.onNodeWithTag("arch_only_complete").performClick()
        compose.waitForIdle()

        // Módulos y proyectos se dibujan.
        compose.onNodeWithTag("arch_tabs").performScrollToNode(hasTestTag("arch_tab_MODULES"))
        compose.onNodeWithTag("arch_tab_MODULES").performClick()
        awaitCondition("módulos") { compose.onAllNodesWithTag("diagram_canvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("arch_tabs").performScrollToNode(hasTestTag("arch_tab_PROJECTS"))
        compose.onNodeWithTag("arch_tab_PROJECTS").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("arch_node_chips").performScrollToNode(hasTestTag("arch_node_prj:tienda/client"))
        compose.onNodeWithTag("arch_node_prj:tienda/client").performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("arch_node_title") and hasText("tienda/client")).assertExists()

        // Archivos: foco en el controlador, con sus vecinos a dos saltos.
        compose.onNodeWithTag("arch_tabs").performScrollToNode(hasTestTag("arch_tab_FILES"))
        compose.onNodeWithTag("arch_tab_FILES").performClick()
        awaitCondition("selector") { compose.onAllNodesWithTag("arch_file_picker").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("arch_file_search").performTextInput("controllers")
        compose.waitForIdle()
        compose.onNodeWithTag("arch_pick:tienda/server/controllers/users.js").performClick()
        awaitCondition("foco") { compose.onAllNodesWithTag("diagram_canvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("arch_focus_title").assertIsDisplayed()
        compose.onNodeWithText("Conexiones de users.js").assertIsDisplayed()
        compose.onNodeWithTag("arch_depth_2").performClick()
        compose.waitForIdle()
        awaitCondition("dos saltos") { compose.onAllNodesWithTag("arch_node_tienda/server/models/user.js").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("arch_node_chips").performScrollToNode(hasTestTag("arch_node_tienda/server/services/userService.js"))
        compose.onNodeWithTag("arch_node_tienda/server/services/userService.js").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("arch_refocus").performScrollTo().performClick()
        compose.waitForIdle()
        awaitCondition("nuevo foco") { compose.onAllNodesWithText("Conexiones de userService.js").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun desdeElExploradorAlGrafoDelArchivo() {
        analyze()
        open("explorador")
        awaitCondition("explorador") { compose.onAllNodesWithTag("explorer_tree").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explorer_search").performTextInput("userService")
        compose.waitForIdle()
        compose.onNodeWithTag("explorer_tree").performScrollToNode(hasTestTag("row:tienda/server/services/userService.js"))
        compose.onNodeWithTag("row:tienda/server/services/userService.js").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("file_architecture").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Servicio").assertIsDisplayed()
        compose.onNodeWithTag("file_open_architecture").performScrollTo().performClick()
        awaitCondition("grafo del archivo") { compose.onAllNodesWithText("Conexiones de userService.js").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag("screen_title") and hasText("Arquitectura")).assertIsDisplayed()
    }
}

package com.funcodestudiocb.analyzer.engine.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureRecordsTest {

    private val diagram = Diagram(
        DiagramKind.LAYERS, "Capas de la arquitectura", 400f, 300f,
        listOf(
            DiagramNode("FRONTEND", "Frontend", DiagramNodeKind.LAYER, ArchLayer.FRONTEND, 16f, 16f, 168f, 60f, files = 3),
            DiagramNode("API", "API", DiagramNodeKind.LAYER, ArchLayer.API, 16f, 132f, 168f, 60f, files = 1),
        ),
        listOf(DiagramEdge("FRONTEND", "API", 2, listOf(RelationKind.HTTP), RelationConfidence.PROBABLE, points = listOf(100f, 76f, 100f, 132f), samples = listOf("web/api.js:3 → server.js"))),
    )

    private val flow = InformationFlow(
        0, "GET", "/api/users", "Express",
        listOf(
            InformationStep(ArchLayer.FRONTEND, "api.js:3", "web/api.js", 3),
            InformationStep(ArchLayer.API, "GET /api/users", "server/routes/users.js", 4, RelationKind.HTTP, RelationConfidence.PROBABLE),
            InformationStep(ArchLayer.DATABASE, "tabla users", via = RelationKind.DB_ACCESS, confidence = RelationConfidence.UNVERIFIED),
        ),
        RelationConfidence.UNVERIFIED, reachesDatabase = true,
    )

    private val summary = ArchitectureSummary(
        mapOf(ArchLayer.FRONTEND to 3, ArchLayer.API to 1, ArchLayer.SHARED to 1), classified = 4, files = 5, inverted = 1,
        invertedSamples = listOf("models/a.js → services/b.js (Modelo → Servicio)"), flows = 1, completeFlows = 1, modules = 2,
    )

    @Test
    fun `los registros de arquitectura van y vuelven sin cambios y son validos`() {
        val records = listOf(FileLayer("web/api.js", ArchLayer.FRONTEND, Confidence.PROBABLE, "cliente de la API"), diagram, flow, summary)
        records.forEach {
            val json = ProtocolJson.encodeToString(ResultRecord.serializer(), it)
            assertEquals(it, ProtocolJson.decodeFromString(ResultRecord.serializer(), json))
            assertEquals(emptyList(), RecordValidator.validate(it), "$it")
        }
    }

    @Test
    fun `se rechazan diagramas fuera de limites y flujos o totales incoherentes`() {
        fun invalid(r: ResultRecord) = assertTrue(RecordValidator.validate(r).isNotEmpty(), "Debería rechazarse: $r")
        invalid(diagram.copy(width = Float.NaN))
        invalid(diagram.copy(nodes = diagram.nodes.map { it.copy(x = 390f) }))
        invalid(diagram.copy(nodes = diagram.nodes + diagram.nodes.first()))
        invalid(diagram.copy(edges = listOf(diagram.edges.first().copy(to = "NO-EXISTE"))))
        invalid(diagram.copy(edges = listOf(diagram.edges.first().copy(points = listOf(1f, 2f, 3f)))))
        invalid(diagram.copy(edges = listOf(diagram.edges.first().copy(points = List(RecordValidator.MAX_EDGE_POINTS + 2) { 1f }))))
        invalid(diagram.copy(nodes = List(RecordValidator.MAX_DIAGRAM_NODES + 1) { i -> diagram.nodes.first().copy(id = "n$i") }, edges = emptyList()))
        invalid(diagram.copy(title = "con\u0000control"))
        invalid(flow.copy(steps = flow.steps.take(1)))
        invalid(flow.copy(reachesDatabase = false))
        invalid(flow.copy(steps = flow.steps.mapIndexed { i, s -> if (i == 1) s.copy(via = null) else s }))
        invalid(summary.copy(files = 9))
        invalid(summary.copy(completeFlows = 2))
        invalid(FileLayer("", ArchLayer.SHARED, Confidence.POSSIBLE, "x"))
    }
}

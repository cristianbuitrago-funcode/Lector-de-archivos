package com.funcodestudiocb.analyzer.engine.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelationRecordsTest {

    private val evidence = listOf(Evidence(EvidenceKind.IMPORT, "Importa «./b»: ruta relativa", "a.ts", 1))

    private fun relation(
        target: String? = "b.ts",
        external: String? = null,
        confidence: RelationConfidence = RelationConfidence.VERIFIED,
        evidence: List<Evidence> = this.evidence,
        impact: RelationImpact = RelationImpact(listOf("b"), 1, 3, false, listOf("main.ts"), 1),
    ) = Relation("a.ts", 1, target, external, "./b", RelationKind.IMPORT, listOf("b"), null, confidence, evidence, impact)

    @Test
    fun `los registros de relaciones van y vuelven sin cambios y son validos`() {
        val records = listOf(
            relation(),
            relation(target = null, external = "react"),
            relation(target = null, confidence = RelationConfidence.BROKEN),
            FileGraph("b.ts", 0, 1, 2, 1.0, 1, 3, cycle = 0, reachableFromEntryPoint = true, symbols = listOf(SymbolInfo("b", SymbolCategory.FUNCTION, 1))),
            DependencyCycle(0, listOf("a.ts", "b.ts"), 2),
            RelationsSummary(2, 3, 1, 1, 1, 1, 0, mapOf(RelationKind.IMPORT to 3), mapOf(RelationConfidence.VERIFIED to 2)),
        )
        records.forEach {
            val json = ProtocolJson.encodeToString(ResultRecord.serializer(), it)
            assertEquals(it, ProtocolJson.decodeFromString(ResultRecord.serializer(), json))
            assertEquals(emptyList(), RecordValidator.validate(it), "$it")
        }
    }

    @Test
    fun `relaciones incoherentes se rechazan en el proceso principal`() {
        fun invalid(r: ResultRecord) = assertTrue(RecordValidator.validate(r).isNotEmpty(), "Debería rechazarse: $r")
        invalid(relation(evidence = emptyList()))
        invalid(relation(target = "b.ts", confidence = RelationConfidence.BROKEN))
        invalid(relation(target = null, external = null))
        invalid(relation(target = "b.ts", external = "react"))
        invalid(relation(impact = RelationImpact(directDependents = -1)))
        invalid(relation(impact = RelationImpact(entryPoints = listOf("a", "b", "c", "d"), entryPointCount = 4)))
        invalid(relation(impact = RelationImpact(entryPoints = listOf("a"), entryPointCount = 0)))
        invalid(relation(target = "b\u0001.ts"))
        invalid(FileGraph("b.ts", 0, 1, 0, 1.5, 1, 0))
        invalid(FileGraph("b.ts", 0, 1, 0, 0.5, 0, 0))
        invalid(DependencyCycle(0, listOf("a.ts"), 1))
    }
}

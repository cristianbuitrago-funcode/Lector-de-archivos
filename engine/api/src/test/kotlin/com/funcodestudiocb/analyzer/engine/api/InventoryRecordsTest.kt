package com.funcodestudiocb.analyzer.engine.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InventoryRecordsTest {

    private val mask = RecordValidator.MASK_CHAR

    private fun roundTrip(record: ResultRecord) {
        val json = ProtocolJson.encodeToString(ResultRecord.serializer(), record)
        assertEquals(record, ProtocolJson.decodeFromString(ResultRecord.serializer(), json))
    }

    private val evidence = Evidence(EvidenceKind.DECLARATION, "Declarada en el manifiesto", "app/src/main/AndroidManifest.xml", 12)

    @Test
    fun `los registros del inventario van y vuelven sin cambios`() {
        listOf(
            FileProfile("app/Main.kt", listOf(RoleAssignment(FileRole.SOURCE, Confidence.CONFIRMED, listOf(evidence))), 10, ContentState.STORED, 1, "app"),
            ProjectRoot("", Ecosystem.GRADLE, "settings.gradle.kts"),
            EntryPoint(EntryPointKind.ANDROID_LAUNCHER, null, "com.x.Main", Confidence.CONFIRMED, listOf(evidence)),
            Redaction("a.env", 3, SecretType.PASSWORD, 14, "supe" + "$mask".repeat(8) + "23"),
            ContentChunk("a.kt", 0, "fun main() {}\n\t‮", last = true),
            InventorySummary(3, mapOf(FileRole.SOURCE to 2, FileRole.UI to 1), 1, 1, 0, 2, 100, 1, true),
        ).forEach {
            roundTrip(it)
            assertEquals(emptyList(), RecordValidator.validate(it), "$it")
        }
    }

    @Test
    fun `un secreto que muestra demasiado se rechaza en el proceso principal`() {
        fun errors(masked: String) = RecordValidator.validate(Redaction("a.kt", 1, SecretType.SECRET, 30, masked))
        assertEquals(emptyList(), errors("AKIA" + "$mask".repeat(12) + "LE"))
        assertTrue(errors("AKIAIO" + "$mask".repeat(10) + "LE").isNotEmpty(), "8 caracteres visibles")
        assertTrue(errors("AKIAIOSFODNN7EXAMPLE").isNotEmpty(), "El secreto completo")
        assertTrue(errors("ñand" + "$mask".repeat(4)).isNotEmpty(), "Solo ASCII visible")
        assertTrue(errors("").isNotEmpty())
        assertTrue(errors("$mask".repeat(40)).isNotEmpty(), "Demasiado largo")
    }

    @Test
    fun `fichas y puntos de entrada mal formados se rechazan`() {
        val role = RoleAssignment(FileRole.SOURCE, Confidence.CONFIRMED, listOf(evidence))
        assertTrue(RecordValidator.validate(FileProfile("a.kt", listOf(role, role), null, ContentState.STORED)).isNotEmpty(), "Rol repetido")
        val manyEvidence = RoleAssignment(FileRole.UI, Confidence.PROBABLE, List(9) { evidence })
        assertTrue(RecordValidator.validate(FileProfile("a.kt", listOf(manyEvidence), null, ContentState.STORED)).isNotEmpty())
        val longDetail = RoleAssignment(FileRole.UI, Confidence.PROBABLE, listOf(evidence.copy(detail = "x".repeat(501))))
        assertTrue(RecordValidator.validate(FileProfile("a.kt", listOf(longDetail), null, ContentState.STORED)).isNotEmpty())
        val controlDetail = RoleAssignment(FileRole.UI, Confidence.PROBABLE, listOf(evidence.copy(detail = "a\u0007b")))
        assertTrue(RecordValidator.validate(FileProfile("a.kt", listOf(controlDetail), null, ContentState.STORED)).isNotEmpty())
        assertTrue(RecordValidator.validate(EntryPoint(EntryPointKind.MAIN_FUNCTION, "a.kt", null, Confidence.PROBABLE, emptyList())).isNotEmpty(), "Sin evidencia")
        assertTrue(RecordValidator.validate(EntryPoint(EntryPointKind.MAIN_FUNCTION, "a.kt", "x\ny", Confidence.PROBABLE, listOf(evidence))).isNotEmpty())
        assertTrue(RecordValidator.validate(ContentChunk("a.kt", 0, "x".repeat(RecordValidator.MAX_CHUNK_CHARS + 1), true)).isNotEmpty())
        assertTrue(RecordValidator.validate(ContentChunk("a.kt", 0, "nulo\u0000", true)).isNotEmpty())
        assertTrue(RecordValidator.validate(ContentChunk("a.kt", -1, "", true)).isNotEmpty())
    }

    @Test
    fun `opciones del inventario fuera de rango se rechazan`() {
        val base = AnalysisConfig("a1", AnalysisMode.FULL, ingest = IngestOptions("p.zip"))
        assertEquals(emptyList(), ConfigValidator.validate(base))
        assertTrue(ConfigValidator.validate(base.copy(inventory = InventoryOptions(maxContentBytes = 0))).isNotEmpty())
        assertTrue(ConfigValidator.validate(base.copy(inventory = InventoryOptions(maxStoredChars = -1))).isNotEmpty())
        assertTrue(ConfigValidator.validate(base.copy(inventory = InventoryOptions(maxContentBytes = 1L shl 40))).isNotEmpty())
    }
}

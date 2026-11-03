package com.funcodestudiocb.analyzer

import com.funcodestudiocb.analyzer.analysis.AnalysisReport
import com.funcodestudiocb.analyzer.engine.api.Confidence
import com.funcodestudiocb.analyzer.engine.api.ContentState
import com.funcodestudiocb.analyzer.engine.api.DuplicateGroup
import com.funcodestudiocb.analyzer.engine.api.EntryPoint
import com.funcodestudiocb.analyzer.engine.api.EntryPointKind
import com.funcodestudiocb.analyzer.engine.api.Evidence
import com.funcodestudiocb.analyzer.engine.api.EvidenceKind
import com.funcodestudiocb.analyzer.engine.api.ExcludedDirectory
import com.funcodestudiocb.analyzer.engine.api.ExclusionReason
import com.funcodestudiocb.analyzer.engine.api.FileEntry
import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.api.FileProfile
import com.funcodestudiocb.analyzer.engine.api.FileRole
import com.funcodestudiocb.analyzer.engine.api.FileStatus
import com.funcodestudiocb.analyzer.engine.api.ResultRecord
import com.funcodestudiocb.analyzer.engine.api.RoleAssignment
import com.funcodestudiocb.analyzer.engine.api.SessionOutcome
import com.funcodestudiocb.analyzer.ui.explorer.DirNode
import com.funcodestudiocb.analyzer.ui.explorer.ExplorerModel
import com.funcodestudiocb.analyzer.ui.explorer.RelationReason
import com.funcodestudiocb.analyzer.ui.explorer.displayCodeLine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplorerModelTest {

    private val sha = "a".repeat(64)

    private fun file(path: String, size: Long = 10) = FileEntry(path, size, size, sha.takeIf { path.endsWith(".png") }, FileKind.TEXT, "UTF-8", FileStatus.ANALYZED)

    private fun profile(path: String, vararg roles: FileRole) =
        FileProfile(path, roles.map { RoleAssignment(it, Confidence.PROBABLE, listOf(Evidence(EvidenceKind.LOCATION, "motivo"))) }, 1, ContentState.STORED)

    private val records: List<ResultRecord> = listOf(
        file("demo/app/src/Main.kt", 100), profile("demo/app/src/Main.kt", FileRole.SOURCE, FileRole.ENTRY_POINT),
        file("demo/app/src/ui/Screen.kt", 50), profile("demo/app/src/ui/Screen.kt", FileRole.SOURCE, FileRole.UI),
        file("demo/README.md", 5), profile("demo/README.md", FileRole.DOCUMENTATION),
        file("demo/assets/a.png"), file("demo/assets/b.png"),
        file("demo/Assets/Player.cs"), file("demo/Assets/Player.cs.meta"),
        ExcludedDirectory("demo/node_modules", "node_modules", ExclusionReason.DIRECTORY_RULE, 1200, 5_000_000),
        DuplicateGroup(sha, 10, listOf("demo/assets/a.png", "demo/assets/b.png"), 2),
        EntryPoint(EntryPointKind.MAIN_FUNCTION, "demo/app/src/Main.kt", null, Confidence.PROBABLE, listOf(Evidence(EvidenceKind.DECLARATION, "x", "demo/README.md", 1))),
    )

    private val model = ExplorerModel(AnalysisReport.from(records, SessionOutcome.COMPLETED, null))

    @Test
    fun arbolConCarpetasExcluidasYTotales() {
        assertEquals(setOf("demo"), model.initiallyExpanded())
        val top = model.rows(model.initiallyExpanded(), null, "").map { it.node.path }
        assertEquals(listOf("demo", "demo/app", "demo/Assets", "demo/assets", "demo/node_modules", "demo/README.md"), top)
        val demo = model.root.children.single() as DirNode
        assertEquals(7, demo.fileCount)
        val excluded = model.rows(setOf("demo", "demo/node_modules"), null, "").single { it.node.path == "demo/node_modules" }
        assertFalse(excluded.expanded, "Una carpeta excluida no se abre")
        assertEquals(1200, (excluded.node as DirNode).excluded?.fileCount)
    }

    @Test
    fun filtroPorFuncionYBusquedaMuestranSoloLoQueCoincideConSusCarpetas() {
        val ui = model.rows(emptySet(), FileRole.UI, "").map { it.node.path }
        assertEquals(listOf("demo", "demo/app", "demo/app/src", "demo/app/src/ui", "demo/app/src/ui/Screen.kt"), ui)
        val search = model.rows(emptySet(), null, "MAIN").map { it.node.path }
        assertEquals(listOf("demo", "demo/app", "demo/app/src", "demo/app/src/Main.kt"), search)
        assertTrue(model.rows(emptySet(), FileRole.TEST, "").isEmpty())
        assertEquals(1, model.roleCounts[FileRole.UI])
        assertNull(model.roleCounts[FileRole.TEST])
    }

    @Test
    fun relacionesConocidas() {
        assertEquals(listOf("demo/assets/b.png" to RelationReason.DUPLICATE), model.related("demo/assets/a.png").map { it.path to it.reason })
        assertEquals(listOf("demo/README.md" to RelationReason.DECLARED_BY), model.related("demo/app/src/Main.kt").map { it.path to it.reason })
        assertEquals(listOf("demo/Assets/Player.cs.meta" to RelationReason.UNITY_META), model.related("demo/Assets/Player.cs").map { it.path to it.reason })
        assertEquals(FileRole.ENTRY_POINT, model.files.getValue("demo/app/src/Main.kt").primaryRole)
    }

    @Test
    fun lineasDeCodigoSeguras() {
        assertEquals("    x\\u202Ey" to null, displayCodeLine("\tx‮y\r"))
        val (text, original) = displayCodeLine("a".repeat(30), maxChars = 10)
        assertEquals("a".repeat(10), text)
        assertEquals(30, original)
    }
}

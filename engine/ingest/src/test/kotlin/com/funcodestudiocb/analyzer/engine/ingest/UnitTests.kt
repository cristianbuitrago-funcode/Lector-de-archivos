package com.funcodestudiocb.analyzer.engine.ingest

import com.funcodestudiocb.analyzer.engine.api.ArchiveFormat
import com.funcodestudiocb.analyzer.engine.api.ExclusionOptions
import com.funcodestudiocb.analyzer.engine.api.ExclusionReason
import com.funcodestudiocb.analyzer.engine.api.FileKind
import com.funcodestudiocb.analyzer.engine.ingest.zip.WinZipAes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatDetectorTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `identifica por la firma y no por la extension`() {
        assertEquals(DetectedFormat.Supported(ArchiveFormat.ZIP), FormatDetector.detect(bytes(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)))
        assertEquals(DetectedFormat.Supported(ArchiveFormat.ZIP), FormatDetector.detect(bytes(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0)))
        assertEquals(DetectedFormat.Supported(ArchiveFormat.RAR4), FormatDetector.detect(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0xCF)))
        assertEquals(DetectedFormat.Supported(ArchiveFormat.RAR5), FormatDetector.detect(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)))
        assertIs<DetectedFormat.Unsupported>(FormatDetector.detect(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0, 4)))
        assertIs<DetectedFormat.Unsupported>(FormatDetector.detect(bytes(0x4D, 0x5A, 0x90, 0, 3, 0, 0, 0)))
        assertIs<DetectedFormat.Unsupported>(FormatDetector.detect(bytes(0x50, 0x4B, 0x07, 0x08, 0, 0, 0, 0)))
        assertEquals(DetectedFormat.Unknown, FormatDetector.detect("hola mundo".toByteArray()))
        assertEquals(DetectedFormat.Unknown, FormatDetector.detect(ByteArray(0)))
    }
}

class EntryNamesTest {
    private fun check(name: String) = EntryNames.check(name, maxLength = 1024, maxDepth = 64)

    @Test
    fun `nombres validos se normalizan`() {
        assertEquals(NameCheck.Valid("src/main/App.kt", false), check("src\\main\\App.kt"))
        assertEquals(NameCheck.Valid("src/App.kt", false), check("./src//App.kt"))
        assertEquals(NameCheck.Valid("docs", false), check("docs/"))
        // NFC: "é" compuesto y descompuesto dan la misma ruta.
        assertEquals(check("caf\u00e9.txt"), check("cafe\u0301.txt"))
    }

    @Test
    fun `nombres peligrosos se rechazan con su motivo`() {
        fun problem(name: String) = (check(name) as NameCheck.Dangerous).problem
        assertEquals(NameProblem.PARENT_TRAVERSAL, problem("../../etc/passwd"))
        assertEquals(NameProblem.PARENT_TRAVERSAL, problem("src/../../x"))
        assertEquals(NameProblem.PARENT_TRAVERSAL, problem("..\\windows\\system32"))
        assertEquals(NameProblem.ABSOLUTE, problem("/etc/passwd"))
        assertEquals(NameProblem.ABSOLUTE, problem("\\\\servidor\\recurso"))
        assertEquals(NameProblem.DRIVE_LETTER, problem("C:\\Windows\\win.ini"))
        assertEquals(NameProblem.DRIVE_LETTER, problem(".\\C:\\Windows\\win.ini"))
        assertEquals(NameProblem.DRIVE_LETTER, problem("./d:/x"))
        assertEquals(NameProblem.CONTROL_CHARACTERS, problem("a\u0000.txt"))
        assertEquals(NameProblem.CONTROL_CHARACTERS, problem("a\nb.txt"))
        assertEquals(NameProblem.RESERVED_NAME, problem("src/CON"))
        assertEquals(NameProblem.RESERVED_NAME, problem("nul.txt"))
        assertEquals(NameProblem.EMPTY, problem(""))
        assertEquals(NameProblem.EMPTY, problem("./"))
        assertEquals(NameProblem.TOO_DEEP, problem((1..70).joinToString("/") { "d" }))
        assertEquals(NameProblem.TOO_LONG, problem("a".repeat(2000)))
    }

    @Test
    fun `nombres enganosos se marcan y se escapan para mostrarse`() {
        val result = check("factura\u202Efdp.exe")
        assertIs<NameCheck.Valid>(result)
        assertTrue(result.deceptive)
        assertEquals("factura\\u202Efdp.exe", EntryNames.escapeForDisplay("factura\u202Efdp.exe"))
    }

    @Test
    fun `ninguna ruta aceptada puede salir del proyecto (prueba aleatoria)`() {
        val alphabet = listOf("a", "b", ".", "..", "/", "\\", ":", "C:", "\u0000", "\u202E", " ", "con", "~")
        val random = Random(20261103)
        repeat(20_000) {
            val name = (1..random.nextInt(1, 12)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
            val result = check(name)
            if (result is NameCheck.Valid) {
                val parts = result.path.split('/')
                assertFalse(result.path.startsWith("/"), name)
                assertFalse(parts.any { it == ".." || it.isEmpty() || it == "." }, name)
                assertFalse(result.path.any { it.isISOControl() }, name)
                assertFalse(result.path.length >= 2 && result.path[0].isLetter() && result.path[1] == ':', name)
            }
        }
    }
}

class ExclusionPlannerTest {
    private val planner = ExclusionPlanner(ExclusionOptions())

    @Test
    fun `carpetas por nombre en cualquier nivel y archivos temporales`() {
        val files = listOf(
            "src/App.kt", "node_modules/react/index.js", "web/node_modules/a/b.js", ".git/config",
            "app/build/outputs/a.apk", "src/.DS_Store", "notas.txt~", "__MACOSX/._App.kt", "src/vendor_list.txt",
        )
        val plan = planner.plan(files, emptyList())
        assertNull(plan["src/App.kt"])
        assertNull(plan["src/vendor_list.txt"])
        assertEquals(ExclusionDecision("node_modules", "node_modules", ExclusionReason.DIRECTORY_RULE), plan["node_modules/react/index.js"])
        assertEquals("web/node_modules", plan["web/node_modules/a/b.js"]?.root)
        assertEquals(".git", plan[".git/config"]?.root)
        assertEquals("app/build", plan["app/build/outputs/a.apk"]?.root)
        assertEquals(ExclusionReason.TEMPORARY_FILE, plan["src/.DS_Store"]?.reason)
        assertEquals("*~", plan["notas.txt~"]?.rule)
        assertEquals("__MACOSX", plan["__MACOSX/._App.kt"]?.root)
    }

    @Test
    fun `carpetas generadas solo si esta el marcador del motor`() {
        val unity = listOf("Juego/ProjectSettings/ProjectVersion.txt", "Juego/Library/cache.bin", "Juego/Assets/Player.cs", "Otro/Library/util.cs")
        val plan = planner.plan(unity, emptyList())
        assertEquals(ExclusionReason.CONTEXT_RULE, plan["Juego/Library/cache.bin"]?.reason)
        assertEquals("Library (Unity)", plan["Juego/Library/cache.bin"]?.rule)
        assertNull(plan["Juego/Assets/Player.cs"])
        assertNull(plan["Otro/Library/util.cs"], "Una carpeta Library sin proyecto Unity no se excluye")

        val unreal = planner.plan(listOf("Shooter.uproject", "Intermediate/x.obj", "Source/Shooter.cpp"), emptyList())
        assertEquals(ExclusionReason.CONTEXT_RULE, unreal["Intermediate/x.obj"]?.reason)
        assertNull(unreal["Source/Shooter.cpp"])

        val godot = planner.plan(listOf("g/project.godot", "g/.godot/imported/a.ctex"), emptyList())
        assertEquals("g/.godot", godot["g/.godot/imported/a.ctex"]?.root)
    }

    @Test
    fun `las opciones del usuario se respetan`() {
        val custom = ExclusionPlanner(ExclusionOptions(directoryNames = listOf("generado"), contextRules = false, temporaryFiles = false))
        val plan = custom.plan(listOf("generado/a.kt", "node_modules/x.js", ".DS_Store", "ProjectSettings/ProjectVersion.txt", "Library/a"), listOf("generado/vacio"))
        assertEquals("generado", plan["generado/a.kt"]?.root)
        assertEquals("generado", plan["generado/vacio"]?.root)
        assertNull(plan["node_modules/x.js"])
        assertNull(plan[".DS_Store"])
        assertNull(plan["Library/a"])
    }
}

class ContentClassifierTest {
    @Test
    fun `texto binario codificacion y comprimidos`() {
        assertEquals(FileKind.TEXT to "ASCII", ContentClassifier.classify("fun main() {}".toByteArray(), 13))
        assertEquals(FileKind.TEXT to "UTF-8", ContentClassifier.classify("¿Qué tal? ñandú".toByteArray(), 18))
        assertEquals(FileKind.TEXT to "UTF-8 (BOM)", ContentClassifier.classify(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x41), 4))
        assertEquals(FileKind.TEXT to "UTF-16LE", ContentClassifier.classify(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0), 4))
        assertEquals(FileKind.TEXT to "ISO-8859-1 (probable)", ContentClassifier.classify("caf\u00e9".toByteArray(Charsets.ISO_8859_1), 4))
        assertEquals(FileKind.BINARY to null, ContentClassifier.classify(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0), 6))
        assertEquals(FileKind.BINARY to null, ContentClassifier.classify(byteArrayOf(1, 2, 0, 4), 4))
        assertEquals(FileKind.ARCHIVE to null, ContentClassifier.classify(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0), 8))
        assertEquals(FileKind.EMPTY to null, ContentClassifier.classify(ByteArray(0), 0))
        // UTF-8 cortado a mitad de un carácter al final de la muestra sigue siendo texto.
        val cut = "añ".toByteArray().copyOf(2)
        assertEquals(FileKind.TEXT to "UTF-8", ContentClassifier.classify(cut, 10_000))
    }
}

class Pbkdf2Test {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `vectores de prueba RFC 6070`() {
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", hex(WinZipAes.pbkdf2HmacSha1("password".toByteArray(), "salt".toByteArray(), 1, 20)))
        assertEquals("ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957", hex(WinZipAes.pbkdf2HmacSha1("password".toByteArray(), "salt".toByteArray(), 2, 20)))
        assertEquals("4b007901b765489abead49d926f721d065a429c1", hex(WinZipAes.pbkdf2HmacSha1("password".toByteArray(), "salt".toByteArray(), 4096, 20)))
        assertEquals(
            "3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038",
            hex(WinZipAes.pbkdf2HmacSha1("passwordPASSWORDpassword".toByteArray(), "saltSALTsaltSALTsaltSALTsaltSALTsalt".toByteArray(), 4096, 25)),
        )
    }
}

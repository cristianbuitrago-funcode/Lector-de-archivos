package com.funcodestudiocb.analyzer.engine.architecture

import com.funcodestudiocb.analyzer.engine.api.ArchLayer
import com.funcodestudiocb.analyzer.engine.core.StageContext
import com.funcodestudiocb.analyzer.engine.inventory.RedactedText
import com.funcodestudiocb.analyzer.engine.inventory.RedactedTextListener
import com.funcodestudiocb.analyzer.engine.languages.LanguageKind

/**
 * Recoge, en el mismo recorrido del comprimido, los archivos de código y las marcas explícitas de capa que contienen:
 * anotaciones y clases base que dicen qué es un archivo (`@RestController`, `models.Model`, `@Entity`, `@Composable`…).
 * Solo se mira si el texto contiene la marca; el código no se ejecuta ni se interpreta.
 */
class ArchitectureCollector : RedactedTextListener {

    /** Archivos de código o marcado (los que pueden tener capa), con su lenguaje. */
    internal val codeFiles = HashMap<String, String>()

    /** Marcas encontradas por archivo: capa → marca. */
    internal val marks = HashMap<String, MutableMap<ArchLayer, String>>()

    /** La marca era de un ViewModel (presentación en MVVM). */
    internal val viewModels = HashSet<String>()

    @Volatile internal var limitReached = false

    override fun onText(context: StageContext, file: RedactedText) {
        val language = file.language ?: return
        if (language.kind != LanguageKind.PROGRAMMING && language.kind != LanguageKind.MARKUP) return
        // De los XML solo cuentan los diseños de pantalla de Android; los demás son recursos o configuración.
        if (file.info.ext == "xml" && !(file.info.lowerDirs.any { it.startsWith("layout") } && "res" in file.info.lowerDirs)) return
        if (codeFiles.size >= MAX_FILES) {
            limitReached = true
            return
        }
        codeFiles[file.path] = language.id
        val text = if (file.text.length > MAX_SCAN) file.text.substring(0, MAX_SCAN) else file.text
        var found: MutableMap<ArchLayer, String>? = null
        for ((layer, needles) in NEEDLES) {
            val hit = needles.firstOrNull { inCode(text, it) } ?: continue
            if (found == null) found = LinkedHashMap()
            found[layer] = hit
        }
        if (found != null) {
            marks[file.path] = found
            if (found[ArchLayer.CONTROLLER] in VIEWMODEL) viewModels += file.path
        }
    }

    /**
     * La marca aparece en código, no dentro de una cadena (con un número impar de comillas antes en su línea, está dentro
     * de un texto) ni de un comentario: así las listas de marcas de este mismo analizador o una documentación que las
     * menciona no se toman por código.
     */
    private fun inCode(text: String, needle: String): Boolean {
        var from = 0
        var checked = 0
        while (checked < MAX_OCCURRENCES) {
            val i = text.indexOf(needle, from)
            if (i < 0) return false
            val lineStart = text.lastIndexOf('\n', i - 1) + 1
            var doubles = 0
            var singles = 0
            for (k in lineStart until i) {
                when (text[k]) {
                    '"' -> doubles++
                    '\'' -> if (needle.firstOrNull() != '\'') singles++
                }
            }
            val before = text.substring(lineStart, i).trimStart()
            val comment = COMMENT_STARTS.any { before.startsWith(it) } || before.contains("//") || before.contains(" #")
            if (!comment && doubles % 2 == 0 && singles % 2 == 0) return true
            from = i + needle.length
            checked++
        }
        return false
    }

    internal companion object {
        const val MAX_FILES = 60_000
        const val MAX_SCAN = 256 * 1024
        private const val MAX_OCCURRENCES = 20
        private val COMMENT_STARTS = listOf("//", "*", "/*", "#", "--", "<!--", "'''", "\"\"\"")

        private val VIEWMODEL = listOf(": ViewModel()", "extends ViewModel", "AndroidViewModel(", "@HiltViewModel")

        /** Marcas por capa. El orden de las capas es la prioridad cuando un archivo tiene varias. */
        val NEEDLES: List<Pair<ArchLayer, List<String>>> = listOf(
            ArchLayer.DATABASE to listOf(
                "CREATE TABLE", "create table", "Schema::create(", "migrations.Migration", "op.create_table(", ".createTable(",
            ),
            ArchLayer.MODEL to listOf(
                "@Entity", "@Table(", "@Document(", "models.Model", "db.Model", "DeclarativeBase", "declarative_base(",
                "mongoose.Schema", "new Schema(", "mongoose.model(", "extends Model", "< ApplicationRecord", "@Dao", "JpaRepository",
                "CrudRepository", "@Repository", ": DbContext", "DbSet<", "sequelize.define(", "Model.init(",
            ),
            ArchLayer.CONTROLLER to listOf(
                "@RestController", "@Controller", "[ApiController]", ": ControllerBase", "extends Controller", "< ApplicationController",
                "(APIView)", "(viewsets.", "(generics.", ": ViewModel()", "extends ViewModel", "AndroidViewModel(", "@HiltViewModel",
            ),
            ArchLayer.SERVICE to listOf("@Service", "@Injectable(", "@Transactional"),
            ArchLayer.FRONTEND to listOf(
                "@Composable", "AppCompatActivity", "ComponentActivity", "FragmentActivity", ": Fragment()", "from 'react'",
                "from \"react\"", "import React", "ReactDOM", "useState(", "<template>", "StatelessWidget", "StatefulWidget",
                "document.getElementById", "document.querySelector", "templateUrl:", "import SwiftUI", "UIViewController", "createApp(",
            ),
        )
    }
}

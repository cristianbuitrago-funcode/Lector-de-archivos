package com.funcodestudiocb.analyzer.engine.ingest

import java.text.Normalizer

/** Motivo por el que el nombre de una entrada se considera peligroso. */
enum class NameProblem(val description: String) {
    EMPTY("nombre vacío"),
    ABSOLUTE("ruta absoluta"),
    DRIVE_LETTER("ruta con letra de unidad"),
    PARENT_TRAVERSAL("sale de la carpeta del proyecto con '..'"),
    CONTROL_CHARACTERS("contiene caracteres nulos o de control"),
    RESERVED_NAME("usa un nombre reservado de Windows"),
    TOO_LONG("ruta demasiado larga"),
    TOO_DEEP("demasiados niveles de carpetas"),
}

/** Resultado de validar el nombre de una entrada del comprimido. */
sealed interface NameCheck {
    /** Nombre seguro, normalizado (separador '/', sin './', Unicode NFC, sin '/' final). */
    data class Valid(val path: String, val deceptive: Boolean) : NameCheck

    /** Nombre peligroso: la entrada no se analiza y se informa. [display] es el nombre escapado. */
    data class Dangerous(val problem: NameProblem, val display: String) : NameCheck
}

/**
 * Validación y normalización de los nombres de las entradas.
 *
 * Aunque el motor nunca escribe archivos en disco (no hay dónde "escapar"), un nombre peligroso indica un
 * comprimido manipulado o defectuoso: se registra como hallazgo y su contenido no se incorpora al proyecto.
 */
object EntryNames {

    private val RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    fun check(raw: String, maxLength: Int, maxDepth: Int): NameCheck {
        val display = escapeForDisplay(raw)
        if (raw.isEmpty()) return NameCheck.Dangerous(NameProblem.EMPTY, "(vacío)")
        // Coherente con RecordValidator: ningún carácter de control (tampoco el tabulador) en una ruta válida.
        if (raw.any { it.isISOControl() }) {
            return NameCheck.Dangerous(NameProblem.CONTROL_CHARACTERS, display)
        }
        val unified = Normalizer.normalize(raw.replace('\\', '/'), Normalizer.Form.NFC)
        if (unified.startsWith("/")) return NameCheck.Dangerous(NameProblem.ABSOLUTE, display)
        val parts = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return NameCheck.Dangerous(NameProblem.EMPTY, display)
        // Se comprueba sobre el primer componente ya normalizado: "./C:/Windows" también es una letra de unidad.
        val first = parts.first()
        if (first.length >= 2 && first[1] == ':' && first[0].isLetter()) {
            return NameCheck.Dangerous(NameProblem.DRIVE_LETTER, display)
        }
        if (parts.any { it == ".." }) return NameCheck.Dangerous(NameProblem.PARENT_TRAVERSAL, display)
        if (parts.any { it.substringBefore('.').trimEnd(' ').lowercase() in RESERVED }) {
            return NameCheck.Dangerous(NameProblem.RESERVED_NAME, display)
        }
        if (parts.size > maxDepth) return NameCheck.Dangerous(NameProblem.TOO_DEEP, display)
        val path = parts.joinToString("/")
        if (path.length > maxLength) return NameCheck.Dangerous(NameProblem.TOO_LONG, display.take(200) + "…")
        return NameCheck.Valid(path, deceptive = path.any(::isInvisibleOrBidi))
    }

    /**
     * Escapa los caracteres de control, invisibles y de dirección bidireccional como `\uXXXX`, para que un
     * nombre nunca pueda disfrazarse ni alterar la presentación (p. ej. "factura‮fdp.exe").
     */
    fun escapeForDisplay(text: String): String = buildString {
        for (ch in text) {
            if (ch.isISOControl() || isInvisibleOrBidi(ch)) append("\\u%04X".format(ch.code)) else append(ch)
        }
    }

    private fun isInvisibleOrBidi(ch: Char): Boolean =
        Character.getType(ch) == Character.FORMAT.toInt() || ch in ' '..' '
}

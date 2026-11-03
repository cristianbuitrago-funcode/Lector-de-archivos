package com.funcodestudiocb.analyzer.data.vault

/** Problemas posibles de una contraseña nueva. La interfaz los traduce a mensajes. */
enum class PasswordIssue {
    TOO_SHORT,
    TOO_LONG,

    /** Menos de dos tipos de caracteres (letras, números, símbolos) y no es una frase larga. */
    TOO_SIMPLE,

    /** Un solo carácter repetido o una secuencia trivial (1234…, abcd…). */
    TRIVIAL,
}

/**
 * Política de la contraseña del propietario.
 *
 * Es la última barrera si alguien obtiene el teléfono desbloqueado, así que se exige un mínimo razonable
 * sin imponer reglas que empujen a contraseñas predecibles: una frase larga es válida sin símbolos.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128
    const val PASSPHRASE_LENGTH = 16

    fun check(password: CharArray): Set<PasswordIssue> {
        val issues = mutableSetOf<PasswordIssue>()
        if (password.size < MIN_LENGTH) issues += PasswordIssue.TOO_SHORT
        if (password.size > MAX_LENGTH) issues += PasswordIssue.TOO_LONG

        val classes = listOf(
            password.any { it.isLetter() },
            password.any { it.isDigit() },
            password.any { !it.isLetterOrDigit() },
        ).count { it }
        if (classes < 2 && password.size < PASSPHRASE_LENGTH) issues += PasswordIssue.TOO_SIMPLE

        if (password.isNotEmpty() && isTrivial(password)) issues += PasswordIssue.TRIVIAL
        return issues
    }

    private fun isTrivial(password: CharArray): Boolean {
        if (password.all { it == password[0] }) return true
        if (password.size < 2) return false
        val lower = CharArray(password.size) { password[it].lowercaseChar() }
        val steps = (1 until lower.size).map { lower[it] - lower[it - 1] }
        val trivial = steps.all { it == 1 } || steps.all { it == -1 }
        lower.fill('\u0000')
        return trivial
    }
}

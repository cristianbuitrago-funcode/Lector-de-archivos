package com.funcodestudiocb.analyzer

import com.funcodestudiocb.analyzer.ui.components.revealControlCharacters
import org.junit.Test
import kotlin.test.assertEquals

class RevealControlCharactersTest {
    @Test
    fun losCaracteresInvisiblesSeMuestranEscapados() {
        // "factura‮fdp.exe" se vería como "facturaexe.pdf"
        assertEquals("factura\\u202Efdp.exe", "factura‮fdp.exe".revealControlCharacters())
        assertEquals("a\\u0000b", "a\u0000b".revealControlCharacters())
        assertEquals("línea\nsiguiente", "línea\nsiguiente".revealControlCharacters())
        assertEquals("normal", "normal".revealControlCharacters())
    }
}

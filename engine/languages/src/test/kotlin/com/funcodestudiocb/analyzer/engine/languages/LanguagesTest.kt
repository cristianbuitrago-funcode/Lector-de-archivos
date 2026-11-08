package com.funcodestudiocb.analyzer.engine.languages

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguagesTest {

    private fun id(path: String, head: String = "", cpp: Boolean = false) = Languages.detect(path, head, cpp)?.id

    @Test
    fun `por extension y por nombre`() {
        assertEquals("kotlin", id("app/src/Main.kt"))
        assertEquals("kotlin", id("build.gradle.kts"))
        assertEquals("gradle", id("build.gradle"))
        assertEquals("tsx", id("src/App.tsx"))
        assertEquals("gdscript", id("player.gd"))
        assertEquals("dockerfile", id("docker/Dockerfile.prod"))
        assertEquals("cmake", id("CMakeLists.txt"))
        assertEquals("ruby", id("Podfile"))
        assertEquals("properties", id(".env.local"))
        assertNull(id("imagen.png"))
        assertNull(id(".gitignore"))
    }

    @Test
    fun `casos ambiguos - h, m, ts y linea shebang`() {
        assertEquals("c", id("lib.h"))
        assertEquals("cpp", id("lib.h", cpp = true))
        assertEquals("objc", id("View.m", "#import <UIKit/UIKit.h>"))
        assertEquals("matlab", id("calc.m", "function y = f(x)"))
        assertEquals("xml", id("i18n/es.ts", "<?xml version=\"1.0\"?>\n<TS version=\"2.1\">"))
        assertEquals("typescript", id("src/index.ts", "export {}"))
        assertEquals("python", id("bin/tool", "#!/usr/bin/env python3\nprint()"))
        assertEquals("shell", id("scripts/run", "#!/bin/sh\necho"))
        assertNull(id("bin/tool", "sin shebang"))
    }

    @Test
    fun `cada lenguaje se recupera por su identificador`() {
        for (language in Languages.withGrammar + listOf(Languages.GO, Languages.DOCKERFILE, Languages.MATLAB)) {
            assertEquals(language, Languages.byId(language.id))
        }
    }
}

package com.funcodestudiocb.analyzer.engine.languages

import com.funcodestudiocb.analyzer.engine.languages.treesitter.HighlightCategory
import com.funcodestudiocb.analyzer.engine.languages.treesitter.SyntaxStatus
import com.funcodestudiocb.analyzer.engine.languages.treesitter.TreeSitter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class LanguageAnalyzerTest {

    private fun analyze(language: Language, text: String, highlight: Boolean = false) =
        LanguageAnalyzer.analyze(language, text.trimIndent() + "\n", highlight)

    private fun LanguageAnalysis.lines() = Triple(codeLines, commentLines, blankLines)

    private fun LanguageAnalysis.modules() = imports.map { it.module }

    @Test
    fun `las 15 gramaticas se cargan`() {
        assertTrue(TreeSitter.available, "libfuncode_treesitter no se cargó")
        for (language in Languages.withGrammar) {
            assertTrue(TreeSitter.hasGrammar(language.grammar!!), "Falta la gramática ${language.grammar}")
        }
    }

    @Test
    fun `JavaScript - lineas, importaciones y require fuera de los comentarios`() {
        val result = analyze(
            Languages.JAVASCRIPT,
            """
            // Servidor
            import express from "express";
            import { join } from 'node:path';
            export { helper } from "./helper.js";
            const db = require("pg"); // conexión

            /* require("falso") en un comentario
               de varias líneas */
            const s = "// no es un comentario";
            const lazy = await import("./lazy.js");
            """,
        )
        assertEquals(CountMethod.SYNTAX, result.method)
        assertEquals(Triple(6, 3, 1), result.lines())
        assertEquals(listOf("express", "node:path", "./helper.js", "pg", "./lazy.js"), result.modules())
        assertEquals(2, result.imports.first().line)
        assertEquals(0, result.syntaxErrors)
    }

    @Test
    fun `TypeScript y TSX`() {
        val ts = analyze(
            Languages.TYPESCRIPT,
            """
            import type { User } from "./models";
            interface A { b: number } // tipo
            """,
        )
        assertEquals(listOf("./models"), ts.modules())
        assertEquals(Triple(2, 0, 0), ts.lines())
        val tsx = analyze(
            Languages.TSX,
            """
            import React from "react";
            export const App = () => <div className="app">{/* nada */}</div>;
            """,
        )
        assertEquals(listOf("react"), tsx.modules())
        assertEquals(0, tsx.syntaxErrors)
    }

    @Test
    fun `Python - import, from e importaciones multiples`() {
        val result = analyze(
            Languages.PYTHON,
            """
            #!/usr/bin/env python3
            # Configuración
            import os, sys as system
            from django.db import models
            from . import views

            def f():
                return "# no"  # sí
            """,
        )
        assertEquals(Triple(5, 2, 1), result.lines())
        assertEquals(listOf("os", "sys", "django.db", "."), result.modules())
    }

    @Test
    fun `Java, Kotlin, C# y Dart`() {
        val java = analyze(
            Languages.JAVA,
            """
            package a;
            import java.util.List;
            import static org.junit.Assert.*;
            /** Doc */
            class A {}
            """,
        )
        assertEquals(listOf("java.util.List", "org.junit.Assert"), java.modules())
        assertEquals(Triple(4, 1, 0), java.lines())

        val kotlin = analyze(
            Languages.KOTLIN,
            """
            package b
            import androidx.compose.runtime.Composable
            import kotlinx.coroutines.*
            // comentario
            fun main() = println("hola")
            """,
        )
        assertEquals(listOf("androidx.compose.runtime.Composable", "kotlinx.coroutines"), kotlin.modules())
        assertEquals(Triple(4, 1, 0), kotlin.lines())

        val csharp = analyze(
            Languages.CSHARP,
            """
            using System;
            using Microsoft.EntityFrameworkCore;
            using Json = Newtonsoft.Json;
            class C { }
            """,
        )
        assertEquals(listOf("System", "Microsoft.EntityFrameworkCore", "Newtonsoft.Json"), csharp.modules())

        val dart = analyze(
            Languages.DART,
            """
            import 'package:flutter/material.dart';
            import 'package:http/http.dart' as http;
            void main() {}
            """,
        )
        assertEquals(listOf("package:flutter/material.dart", "package:http/http.dart"), dart.modules())
    }

    @Test
    fun `PHP, C, C++ y CSS`() {
        val php = analyze(
            Languages.PHP,
            """
            <?php
            use Illuminate\Support\Facades\DB;
            require_once 'config.php';
            # comentario
            echo "hola";
            """,
        )
        assertEquals(listOf("Illuminate\\Support\\Facades\\DB", "config.php"), php.modules())
        assertEquals(1, php.commentLines)

        val c = analyze(
            Languages.C,
            """
            #include <stdio.h>
            #include "sqlite3.h"
            int main(void) { return 0; } /* fin */
            """,
        )
        assertEquals(listOf("stdio.h", "sqlite3.h"), c.modules())
        assertEquals(Triple(3, 0, 0), c.lines())

        val cpp = analyze(Languages.CPP, "#include <vector>\nint x = 1;")
        assertEquals(listOf("vector"), cpp.modules())

        val css = analyze(Languages.CSS, "@import url(\"theme.css\");\n/* a */\nbody { color: red; }")
        assertEquals(listOf("theme.css"), css.modules())
        assertEquals(Triple(2, 1, 0), css.lines())
    }

    @Test
    fun `HTML, SQL y GDScript se analizan con su gramatica`() {
        val html = analyze(Languages.HTML, "<!-- cabecera -->\n<html>\n<body></body>\n</html>")
        assertEquals(CountMethod.SYNTAX, html.method)
        assertEquals(Triple(3, 1, 0), html.lines())

        val sql = analyze(Languages.SQL, "-- usuarios\nCREATE TABLE users (id INT);\n")
        assertEquals(CountMethod.SYNTAX, sql.method)
        assertEquals(Triple(1, 1, 0), sql.lines())

        val gd = analyze(Languages.GDSCRIPT, "extends Node\n# vida\nvar hp = 10\n")
        assertEquals(CountMethod.SYNTAX, gd.method)
        assertEquals(Triple(2, 1, 0), gd.lines())
    }

    @Test
    fun `codigo roto - se cuentan los errores de sintaxis sin fallar`() {
        val result = analyze(Languages.JAVA, "class A { void f( { int x = ; }\n")
        assertEquals(CountMethod.SYNTAX, result.method)
        assertTrue(result.syntaxErrors > 0)
    }

    @Test
    fun `sin tiempo suficiente - recuento lexico y motivo honesto`() {
        val big = buildString { repeat(30_000) { append("function f$it(a, b) { return a + b; } // c\n") } }
        val result = LanguageAnalyzer.analyze(Languages.JAVASCRIPT, big, highlight = false, timeoutMs = 0)
        assertEquals(CountMethod.LEXICAL, result.method)
        assertNotNull(result.syntaxProblem)
        assertEquals(30_000, result.codeLines)
    }

    @Test
    fun `un archivo grande se analiza en un tiempo razonable`() {
        val big = buildString { repeat(20_000) { append("def f$it(a):\n    return a  # x\n") } }
        lateinit var result: LanguageAnalysis
        val time = measureTime { result = LanguageAnalyzer.analyze(Languages.PYTHON, big, highlight = true) }
        assertEquals(CountMethod.SYNTAX, result.method)
        assertEquals(40_000, result.codeLines)
        assertTrue(time.inWholeMilliseconds < 5_000, "Tardó $time")
    }

    @Test
    fun `resaltado - posiciones en caracteres con texto no ASCII`() {
        val text = "// ñandú 😀\nconst s = \"é\";\nlet n = 42;\n"
        val result = LanguageAnalyzer.analyze(Languages.JAVASCRIPT, text, highlight = true)
        val spans = assertNotNull(result.highlight)
        val found = (spans.indices step 3).map { text.substring(spans[it], spans[it] + spans[it + 1]) to spans[it + 2] }
        assertTrue("// ñandú 😀" to HighlightCategory.COMMENT in found, found.toString())
        assertTrue("\"é\"" to HighlightCategory.STRING in found, found.toString())
        assertTrue("const" to HighlightCategory.KEYWORD in found, found.toString())
        assertTrue("42" to HighlightCategory.NUMBER in found, found.toString())
    }

    @Test
    fun `rangos de comentarios en caracteres, sintacticos y lexicos`() {
        val js = "const á = 1; // ñ firebase\n/* bloque */ x()\n"
        val syntax = LanguageAnalyzer.analyze(Languages.JAVASCRIPT, js, highlight = false)
        assertTrue(syntax.inComment(js.indexOf("firebase")))
        assertTrue(syntax.inComment(js.indexOf("bloque")))
        assertTrue(!syntax.inComment(js.indexOf("x()")))
        val go = "x := 1 // ñ redis\n/* sin cerrar"
        val lexical = LanguageAnalyzer.analyze(Languages.GO, go, highlight = false)
        assertTrue(lexical.inComment(go.indexOf("redis")))
        assertTrue(lexical.inComment(go.indexOf("cerrar")))
        assertTrue(!lexical.inComment(0))
    }

    @Test
    fun `sin resaltado si no se pide`() {
        assertNull(LanguageAnalyzer.analyze(Languages.JAVA, "class A {}\n", highlight = false).highlight)
    }

    @Test
    fun `recuento lexico - comentarios de bloque, cadenas y ultima linea sin salto`() {
        val go = LanguageAnalyzer.analyze(
            Languages.GO,
            "package main\n\n/* bloque\n   sigue */\nvar s = \"/* no */\" // fin\n// solo\nfunc main() {}",
            highlight = false,
        )
        assertEquals(CountMethod.LEXICAL, go.method)
        assertEquals(Triple(3, 3, 1), go.lines())
        assertNull(go.syntaxProblem, "Sin gramática no es un problema: es el nivel de soporte")

        val ruby = LanguageAnalyzer.analyze(Languages.RUBY, "=begin\ndoc\n=end\nputs 'hola' # x\n", highlight = false)
        assertEquals(Triple(1, 3, 0), ruby.lines())

        val empty = LanguageAnalyzer.analyze(Languages.TEXT, "", highlight = false)
        assertEquals(Triple(0, 0, 0), empty.lines())
    }

    @Test
    fun `recuento sintactico - CRLF y archivo sin salto final`() {
        val result = LanguageAnalyzer.analyze(Languages.JAVA, "class A {\r\n  // c\r\n\r\n}", highlight = false)
        assertEquals(Triple(2, 1, 1), result.lines())
    }

    @Test
    fun `estado de tree-sitter con una gramatica desconocida`() {
        assertEquals(SyntaxStatus.UNAVAILABLE, TreeSitter.parse("cobol", ByteArray(1), 100, 10, false, emptyList()).status)
    }
}

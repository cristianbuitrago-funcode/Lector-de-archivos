package com.funcodestudiocb.analyzer.engine.technologies

import com.funcodestudiocb.analyzer.engine.api.TechnologyCategory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TechnologyCatalogTest {

    @Test
    fun `el catalogo empaquetado se carga y cubre BD y servicios externos`() {
        val catalog = TechnologyCatalog.default
        assertEquals(1, catalog.version)
        assertTrue(catalog.rules.size >= 150, "Tecnologías: ${catalog.rules.size}")
        assertEquals(catalog.rules.size, catalog.rules.map { it.id }.toSet().size)
        val byCategory = catalog.rules.groupingBy { it.category }.eachCount()
        assertTrue((byCategory[TechnologyCategory.DATABASE] ?: 0) >= 15, "Bases de datos: $byCategory")
        assertTrue((byCategory[TechnologyCategory.EXTERNAL_SERVICE] ?: 0) >= 20, "Servicios externos: $byCategory")
        // Toda tecnología se puede confirmar con alguna combinación de sus señales.
        catalog.rules.forEach { rule ->
            assertTrue(rule.signals.sumOf { it.weight } >= rule.probable, "${rule.id} nunca llegaría a probable")
        }
    }

    @Test
    fun `un catalogo con errores se rechaza`() {
        fun catalog(signal: String) = "version: 1\ntechnologies:\n  - id: x\n    name: X\n    category: database\n    signals:\n      - $signal\n"
        assertThrows<IllegalStateException> { TechnologyCatalog.parse(catalog("{ pattern: \"a{1,300}\", hint: a }")) }
        assertThrows<IllegalStateException> { TechnologyCatalog.parse(catalog("{ pattern: \"a+\" }")) }
        assertThrows<IllegalStateException> { TechnologyCatalog.parse(catalog("{ dependency: x, ecosystem: desconocido }")) }
        assertThrows<IllegalStateException> { TechnologyCatalog.parse(catalog("{ dependency: x, weight: 2 }")) }
        assertThrows<IllegalStateException> { TechnologyCatalog.parse(catalog("{ peso: 1 }")) }
        assertThrows<IllegalStateException> { TechnologyCatalog.parse(catalog("{ file: x }").replace("database", "inventada")) }
        assertEquals(1, TechnologyCatalog.parse(catalog("{ file: x }")).rules.size)
    }

    @Test
    fun `importaciones - modulo o submodulo, nunca un prefijo de otra palabra`() {
        val m = TechnologySignalCollector::importMatches
        assertTrue(m.invoke("firebase/firestore", "firebase"))
        assertTrue(m.invoke("django.db.models", "django"))
        assertTrue(m.invoke("node:fs", "node:"))
        assertTrue(m.invoke("Illuminate\\Support\\Facades\\DB", "Illuminate\\"))
        assertTrue(m.invoke("package:flutter/material.dart", "package:flutter/"))
        assertFalse(m.invoke("fsevents", "fs"))
        assertFalse(m.invoke("reactive", "react"))
        assertTrue(m.invoke("react", "react"))
    }

    @Test
    fun `comodines de archivo - nombre o final de ruta`() {
        assertTrue(Glob("next.config.*").matches("web/next.config.mjs"))
        assertFalse(Glob("next.config.*").matches("web/next.config"))
        assertTrue(Glob("*.uproject").matches("Juego/Juego.uproject"))
        assertTrue(Glob(".github/workflows/*.yml").matches(".github/workflows/ci.yml"))
        assertTrue(Glob(".github/workflows/*.yml").matches("sub/.github/workflows/ci.yml"))
        assertFalse(Glob(".github/workflows/*.yml").matches(".github/workflows/x/ci.yml"))
        assertTrue(Glob("config/routes.rb").matches("app/config/routes.rb"))
        assertFalse(Glob("config/routes.rb").matches("app/myconfig/routes.rb"))
        assertTrue(Glob("Dockerfile").matches("Dockerfile"))
        assertFalse(Glob("Dockerfile").matches("docs/Dockerfile.md"))
    }

    @Test
    fun `busqueda de muchos textos a la vez - todas las apariciones, solapadas y sin distinguir mayusculas`() {
        fun all(patterns: List<String>, text: String, ignoreCase: Boolean = false): List<Pair<String, Int>> {
            val out = mutableListOf<Pair<String, Int>>()
            AhoCorasick(patterns, ignoreCase).scan(text) { i, at -> out += patterns[i] to at; true }
            return out.sortedWith(compareBy({ it.second }, { it.first }))
        }
        assertEquals(listOf("she" to 0, "he" to 1, "hers" to 1), all(listOf("he", "she", "hers"), "shers"))
        assertEquals(listOf("postgres://" to 4), all(listOf("postgres://", "mysql://"), "url=postgres://x"))
        assertEquals(listOf("redis" to 0, "redis" to 8), all(listOf("redis"), "Redis y REDIS", ignoreCase = true))
        assertEquals(emptyList(), all(listOf("abc"), "ab ac bc"))
        var count = 0
        AhoCorasick(listOf("a")).scan("aaaa") { _, _ -> ++count < 2 }
        assertEquals(2, count, "Se detiene cuando se pide")
    }
}

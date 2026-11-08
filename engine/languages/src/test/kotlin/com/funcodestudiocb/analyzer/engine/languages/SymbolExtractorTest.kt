package com.funcodestudiocb.analyzer.engine.languages

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolExtractorTest {

    private fun analyze(language: Language, text: String) = LanguageAnalyzer.analyze(language, text.trimIndent() + "\n", highlight = false)

    private fun LanguageAnalysis.decls() = declarations.associateBy { it.name }

    private fun LanguageAnalysis.imp(module: String) = imports.first { it.module == module }

    @Test
    fun `JavaScript - simbolos importados y declaraciones de nivel superior`() {
        val a = analyze(
            Languages.JAVASCRIPT,
            """
            import React, { useState, useEffect as efecto } from 'react';
            import * as api from './api';
            export { ayuda } from './ayuda';
            import './estilos.css';
            export const VERSION = 1;
            export default class App extends Component {
              render() { const interno = 2; }
            }
            function helper() { const local = 3; }
            const { a, b } = obj;
            """,
        )
        assertEquals(listOf("useState", "useEffect", "default"), a.imp("react").symbols)
        assertTrue(a.imp("./api").wildcard)
        assertEquals(listOf("ayuda"), a.imp("./ayuda").symbols)
        assertTrue(a.imp("./estilos.css").symbols.isEmpty())
        val d = a.decls()
        assertEquals(setOf("VERSION", "App", "helper"), d.keys, "Ni miembros, ni locales, ni desestructuración")
        assertEquals(SymbolKind.CLASS, d["App"]?.kind)
        assertEquals(listOf(Supertype("Component", false)), d["App"]?.supertypes)
        assertEquals(6, d["App"]?.line)
    }

    @Test
    fun `TypeScript - interfaces, tipos, enumeraciones y herencia`() {
        val d = analyze(
            Languages.TYPESCRIPT,
            """
            import type { Usuario } from './modelos';
            export interface Repo<T> extends Base<T>, Otra {}
            export type Id = string;
            enum Color { Rojo }
            export abstract class Servicio<T> extends Padre<T> implements Uno, Dos<T> {}
            """,
        ).decls()
        assertEquals(SymbolKind.INTERFACE, d["Repo"]?.kind)
        assertEquals(listOf("Base", "Otra"), d["Repo"]?.supertypes?.map { it.name })
        assertEquals(SymbolKind.TYPE, d["Id"]?.kind)
        assertEquals(SymbolKind.ENUM, d["Color"]?.kind)
        assertEquals(listOf(Supertype("Padre", false), Supertype("Uno", true), Supertype("Dos", true)), d["Servicio"]?.supertypes)
    }

    @Test
    fun `Python - from, import, clases con bases y funciones`() {
        val a = analyze(
            Languages.PYTHON,
            """
            from .models import User, Order as O
            from utils import *
            import os, sys
            class Vista(Base, Mixin, metaclass=Meta):
                def metodo(self):
                    pass
            @decorador
            def tarea():
                pass
            """,
        )
        assertEquals(listOf("User", "Order"), a.imp(".models").symbols)
        assertTrue(a.imp("utils").wildcard)
        assertTrue(a.imports.any { it.module == "sys" })
        val d = a.decls()
        assertEquals(setOf("Vista", "tarea"), d.keys)
        assertEquals(listOf("Base", "Mixin"), d["Vista"]?.supertypes?.map { it.name })
    }

    @Test
    fun `Java y Kotlin - paquete, importaciones, herencia e implementacion`() {
        val java = analyze(
            Languages.JAVA,
            """
            package com.demo.web;
            import com.demo.core.Servicio;
            import static com.demo.core.Util.ayuda;
            import com.demo.modelo.*;
            public class Controlador extends Base implements Uno, Dos<String> {
                class Interna {}
            }
            interface Otra extends Tercera {}
            """,
        )
        assertEquals(listOf("com.demo.web"), java.namespaces)
        assertEquals(listOf("Servicio"), java.imp("com.demo.core.Servicio").symbols)
        assertEquals(listOf("ayuda"), java.imp("com.demo.core.Util").symbols)
        assertTrue(java.imp("com.demo.modelo").wildcard)
        val jd = java.decls()
        assertEquals(setOf("Controlador", "Otra"), jd.keys)
        assertEquals(listOf(Supertype("Base", false), Supertype("Uno", true), Supertype("Dos", true)), jd["Controlador"]?.supertypes)
        assertEquals(SymbolKind.INTERFACE, jd["Otra"]?.kind)

        val kt = analyze(
            Languages.KOTLIN,
            """
            package com.demo.app

            import com.demo.core.Repo
            import kotlinx.coroutines.*

            class Pantalla<T : Any>(val x: T) : Actividad(), Escucha {
                fun miembro() {}
            }
            interface Escucha : Base
            object Unico : Escucha
            enum class Estado { A }
            fun String.extension() = this
            val CONSTANTE = 1
            typealias Texto = String
            """,
        )
        assertEquals(listOf("com.demo.app"), kt.namespaces)
        assertEquals(listOf("Repo"), kt.imp("com.demo.core.Repo").symbols)
        assertTrue(kt.imp("kotlinx.coroutines").wildcard)
        val kd = kt.decls()
        assertEquals(setOf("Pantalla", "Escucha", "Unico", "Estado", "extension", "CONSTANTE", "Texto"), kd.keys)
        assertEquals(listOf(Supertype("Actividad", false), Supertype("Escucha", true)), kd["Pantalla"]?.supertypes)
        assertEquals(SymbolKind.INTERFACE, kd["Escucha"]?.kind)
        assertEquals(SymbolKind.OBJECT, kd["Unico"]?.kind)
        assertEquals(SymbolKind.ENUM, kd["Estado"]?.kind)
    }

    @Test
    fun `PHP, CSharp y Dart`() {
        val php = analyze(
            Languages.PHP,
            """
            <?php
            namespace App\Http\Controllers;
            use App\Models\User;
            use App\Services\{Pago, Envio as E};
            require_once __DIR__ . '/config.php';
            class UserController extends Controller implements Contrato {}
            function ayuda() {}
            """,
        )
        assertEquals(listOf("App\\Http\\Controllers"), php.namespaces)
        assertEquals(setOf("App\\Models\\User", "App\\Services\\Pago", "App\\Services\\Envio", "/config.php"), php.imports.map { it.module }.toSet())
        assertEquals(listOf(Supertype("Controller", false), Supertype("Contrato", true)), php.decls()["UserController"]?.supertypes)

        val cs = analyze(
            Languages.CSHARP,
            """
            using System;
            using Json = Newtonsoft.Json.JsonConvert;
            namespace Demo.Api
            {
                public class Pedidos : ControllerBase, IPedidos {}
                interface IPedidos {}
            }
            """,
        )
        assertEquals(listOf("Demo.Api"), cs.namespaces)
        assertTrue(cs.imp("System").wildcard)
        assertEquals(listOf("JsonConvert"), cs.imp("Newtonsoft.Json.JsonConvert").symbols)
        assertEquals(listOf(Supertype("ControllerBase", false), Supertype("IPedidos", true)), cs.decls()["Pedidos"]?.supertypes)

        val dart = analyze(
            Languages.DART,
            """
            import 'package:app/servicios/api.dart' show Api, Cliente;
            import 'modelo.dart';
            class Inicio extends StatelessWidget with Mezcla implements Contrato {}
            void main() {}
            """,
        )
        assertEquals(listOf("Api", "Cliente"), dart.imp("package:app/servicios/api.dart").symbols)
        assertTrue(dart.imp("modelo.dart").wildcard)
        assertEquals(listOf("StatelessWidget", "Mezcla", "Contrato"), dart.decls()["Inicio"]?.supertypes?.map { it.name })
        assertTrue("main" in dart.decls())
    }

    @Test
    fun `C++ y GDScript`() {
        val cpp = analyze(
            Languages.CPP,
            """
            #include "motor/fisica.h"
            #include <vector>
            namespace juego {
            class Jugador : public Entidad, private Contable {};
            }
            int Mundo::paso(int dt) { return dt; }
            struct Punto { int x; };
            struct Punto origen;
            """,
        )
        assertEquals(setOf("motor/fisica.h", "<vector>"), cpp.imports.map { it.module }.toSet())
        assertEquals(listOf("juego"), cpp.namespaces)
        val d = cpp.decls()
        assertEquals(listOf("Entidad", "Contable"), d["Jugador"]?.supertypes?.map { it.name })
        assertTrue("paso" in d && "Punto" in d)

        val gd = analyze(Languages.GDSCRIPT, "extends \"res://base/personaje.gd\"\nclass_name Jugador\nfunc _ready():\n\tpass\n")
        assertEquals(ImportRef("res://base/personaje.gd", 1, extends = true), gd.imports.single())
        assertEquals(setOf("Jugador", "_ready"), gd.decls().keys)
        val node = analyze(Languages.GDSCRIPT, "extends Node2D\n")
        assertEquals("Node2D", node.imports.single().module)
    }
}

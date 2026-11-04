package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Cómo se contaron las líneas de un archivo. */
@Serializable
enum class LineMethod {
    /** Con el árbol sintáctico de tree-sitter: los comentarios son exactos. */
    SYNTAX,

    /** Con un analizador léxico de comentarios y cadenas (sin gramática, o el análisis sintáctico no terminó). */
    LEXICAL,
}

/** Lenguaje de un archivo y su recuento de líneas. */
@Serializable
data class FileLanguage(
    val id: String,
    val name: String,
    /** Nivel de soporte (1 = máximo; 0 = solo líneas, inventario y secretos). */
    val level: Int,
    val method: LineMethod,
    val code: Int,
    val comment: Int,
    val blank: Int,
    /** Nodos de error o ausentes del árbol sintáctico (código incompleto o que la gramática no entiende). */
    val syntaxErrors: Int = 0,
)

/** Totales de un lenguaje en el proyecto. */
@Serializable
@SerialName("language_stats")
data class LanguageStats(
    val id: String,
    val name: String,
    val level: Int,
    val files: Int,
    val code: Long,
    val comment: Long,
    val blank: Long,
    /** Archivos contados con el árbol sintáctico. */
    val syntaxFiles: Int,
    /** Archivos contados con el analizador léxico. */
    val lexicalFiles: Int,
    /** Archivos con algún error de sintaxis. */
    val filesWithErrors: Int,
) : ResultRecord

/** Para qué se declara una dependencia. */
@Serializable
enum class DependencyScope { RUNTIME, DEVELOPMENT, TEST, BUILD, PEER, OPTIONAL, PLATFORM }

/** Dependencia directa declarada en un manifiesto. Nombre y versión salen de la copia ya redactada. */
@Serializable
@SerialName("dependency")
data class DeclaredDependency(
    val manifest: String,
    val ecosystem: Ecosystem,
    val name: String,
    /** Versión o rango tal como se declara; `null` si no se indica o viene de otro sitio (BOM, catálogo sin versión). */
    val version: String? = null,
    val scope: DependencyScope,
    val line: Int? = null,
    val projectRoot: String? = null,
) : ResultRecord

@Serializable
enum class TechnologyCategory {
    LANGUAGE_RUNTIME,
    FRAMEWORK,
    UI,
    MOBILE,
    GAME_ENGINE,
    DATABASE,
    DATA_ACCESS,
    EXTERNAL_SERVICE,
    CLOUD,
    AUTHENTICATION,
    NETWORKING,
    TESTING,
    BUILD_TOOL,
    DEVOPS,
    LIBRARY,
}

/**
 * Tecnología detectada por el motor de reglas, con dónde se encontró y su confianza:
 *
 * - [Confidence.CONFIRMED]: declarada como dependencia o con un archivo que solo existe si se usa.
 * - [Confidence.PROBABLE]: se importa o se usa en el código, sin declaración que lo confirme.
 * - [Confidence.POSSIBLE]: solo se menciona (texto, configuración ambigua): no concluyente.
 */
@Serializable
@SerialName("technology")
data class Technology(
    val id: String,
    val name: String,
    val category: TechnologyCategory,
    val confidence: Confidence,
    /** Versiones declaradas distintas (redactadas, como máximo 8). */
    val versions: List<String> = emptyList(),
    /** Las evidencias más fuertes (como máximo 8), cada una con su archivo y línea. */
    val evidence: List<Evidence>,
    /** Total de evidencias encontradas. */
    val evidenceCount: Int,
    /** Subproyectos donde aparece. */
    val projectRoots: List<String> = emptyList(),
) : ResultRecord

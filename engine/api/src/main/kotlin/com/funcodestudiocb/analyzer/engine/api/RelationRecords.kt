package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Tipo de conexión entre archivos (§6.2 de la arquitectura). Los de framework llegan en la Fase 5B. */
@Serializable
enum class RelationKind {
    IMPORT,
    INHERITANCE,
    IMPLEMENTATION,

    /** Uso por nombre sin importación (mismo paquete o espacio de nombres). */
    REFERENCE,
    CALL,
    ROUTE_HANDLER,
    HTTP,
    DB_ACCESS,
    CONFIG_READ,

    /** Recurso: hoja de estilos, imagen, escena, enlace… */
    RESOURCE,
    IPC,
    INJECTION,
}

/**
 * Confianza de una conexión (§6.1):
 * - [VERIFIED]: resolución sintáctica a un archivo existente (o a un paquete declarado);
 * - [PROBABLE]: heurística con evidencia fuerte (nombre usado en el mismo paquete, alias habitual…);
 * - [UNVERIFIED]: coincidencia parcial o no comprobable (paquete no declarado, posible código generado);
 * - [BROKEN]: el destino no existe en el proyecto (posible error).
 */
@Serializable
enum class RelationConfidence { VERIFIED, PROBABLE, UNVERIFIED, BROKEN }

/** Impacto de modificar o eliminar el destino de una conexión, calculado sobre el grafo. */
@Serializable
data class RelationImpact(
    /** Símbolos que el origen usa del destino y que dejarían de estar definidos. */
    val undefinedSymbols: List<String> = emptyList(),
    /** Archivos que dependen directamente del destino (incluido el origen). */
    val directDependents: Int = 0,
    /** Archivos que dependen del destino directa o indirectamente (radio de impacto). */
    val transitiveDependents: Int = 0,
    /** El radio de impacto es como mínimo [transitiveDependents] (el cálculo se acotó). */
    val transitiveCapped: Boolean = false,
    /** Puntos de entrada desde los que se llega al origen (como máximo 3) y cuántos son en total. */
    val entryPoints: List<String> = emptyList(),
    val entryPointCount: Int = 0,
)

/**
 * Conexión entre un archivo y otro archivo del proyecto ([target]) o un paquete externo ([external]). Con
 * [RelationConfidence.BROKEN] no hay destino: [reference] dice qué se buscó.
 */
@Serializable
@SerialName("relation")
data class Relation(
    val source: String,
    val line: Int? = null,
    val target: String? = null,
    val external: String? = null,
    /** Lo que está escrito en el código (módulo, ruta, nombre de clase…). */
    val reference: String,
    val kind: RelationKind,
    /** Información intercambiada: símbolos importados, recurso usado… Vacía si no es determinable. */
    val exchanged: List<String> = emptyList(),
    /** Aclaración de [exchanged] ("módulo completo", "todo el paquete"…). */
    val exchangedNote: String? = null,
    val confidence: RelationConfidence,
    val evidence: List<Evidence>,
    val impact: RelationImpact = RelationImpact(),
) : ResultRecord

@Serializable
enum class SymbolCategory { CLASS, INTERFACE, ENUM, OBJECT, FUNCTION, TYPE, CONSTANT }

@Serializable
data class SymbolInfo(val name: String, val category: SymbolCategory, val line: Int)

/** Lugar de un archivo en el grafo de relaciones. */
@Serializable
@SerialName("file_graph")
data class FileGraph(
    val path: String,
    val outgoing: Int,
    val incoming: Int,
    /** Paquetes externos distintos que usa. */
    val externals: Int,
    /** Importancia (PageRank) normalizada: 1 = el archivo más importante del proyecto. */
    val importance: Double,
    /** Puesto por importancia (1 = el más importante). */
    val rank: Int,
    val transitiveDependents: Int,
    val transitiveCapped: Boolean = false,
    /** Índice del ciclo de dependencias en el que está, si lo hay. */
    val cycle: Int? = null,
    /** Se llega a él desde algún punto de entrada; `null` si el proyecto no tiene puntos de entrada conocidos. */
    val reachableFromEntryPoint: Boolean? = null,
    /** Ningún archivo lo usa según las relaciones encontradas y no es un punto de entrada (posible, no seguro). */
    val unreferenced: Boolean = false,
    val symbols: List<SymbolInfo> = emptyList(),
) : ResultRecord

/** Grupo de archivos que dependen unos de otros en círculo. */
@Serializable
@SerialName("dependency_cycle")
data class DependencyCycle(
    val index: Int,
    /** Archivos del ciclo (como máximo 100). */
    val paths: List<String>,
    val size: Int,
) : ResultRecord

/** Totales de las relaciones. */
@Serializable
@SerialName("relations_summary")
data class RelationsSummary(
    val files: Int,
    val relations: Int,
    val internal: Int,
    val external: Int,
    val broken: Int,
    val cycles: Int,
    val unreferencedFiles: Int,
    val byKind: Map<RelationKind, Int>,
    val byConfidence: Map<RelationConfidence, Int>,
) : ResultRecord

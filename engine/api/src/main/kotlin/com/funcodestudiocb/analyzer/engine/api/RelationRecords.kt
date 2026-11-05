package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Tipo de conexión entre archivos (§6.2 de la arquitectura). */
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

    /** Un archivo de configuración o del framework registra al destino (manifiesto, montaje de rutas, CMake, autoload…). */
    REGISTRATION,
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
    /**
     * Otros lugares del proyecto que dependen del mismo destino concreto ("archivo:línea", como máximo 5) y cuántos
     * son: llamadas a la misma ruta, lecturas de la misma clave, accesos a la misma tabla, usos del mismo canal…
     */
    val affected: List<String> = emptyList(),
    val affectedCount: Int = 0,
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
    /** Rutas del backend y páginas del frontend encontradas. */
    val endpoints: Int = 0,
    val pages: Int = 0,
    /** Llamadas HTTP encontradas y cuántas coinciden con una ruta del proyecto. */
    val httpCalls: Int = 0,
    val httpMatched: Int = 0,
    val dataEntities: Int = 0,
    val configKeys: Int = 0,
) : ResultRecord

@Serializable
enum class EndpointKind {
    /** Ruta del backend (API o página servida por el servidor). */
    API,

    /** Ruta del enrutador del frontend (React Router, Vue Router, Angular, Flutter…) o página por archivo. */
    PAGE,
}

/** Ruta declarada en el proyecto, con lo que la atiende y quién la llama. */
@Serializable
@SerialName("endpoint")
data class Endpoint(
    val kind: EndpointKind,
    /** GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS o ANY. */
    val method: String,
    /** Ruta completa con los prefijos conocidos (`/api/users/:id`). */
    val path: String,
    val framework: String,
    /** Dónde se declara la ruta. */
    val file: String,
    val line: Int,
    /** Lo que la atiende, como está escrito (`UserController@index`, `views.detail`). */
    val handler: String? = null,
    val handlerFile: String? = null,
    /** Se conocen todos los prefijos de la ruta (si no, puede faltar el inicio). */
    val prefixKnown: Boolean = true,
    /** Llamadas del proyecto a esta ruta ("archivo:línea", como máximo 5) y cuántas son. */
    val callers: List<String> = emptyList(),
    val callerCount: Int = 0,
) : ResultRecord

@Serializable
enum class DataEntityKind { TABLE, COLLECTION }

/** Tabla o colección de datos: dónde se define (si está en el proyecto) y quién la usa. */
@Serializable
@SerialName("data_entity")
data class DataEntity(
    val name: String,
    val kind: DataEntityKind,
    val file: String? = null,
    val line: Int? = null,
    /** Cómo se define: `CREATE TABLE`, modelo de Django, `@Entity`… */
    val definedBy: String? = null,
    /** Archivos que la leen o escriben. */
    val accessedBy: Int = 0,
    /** Operaciones encontradas (SELECT, INSERT, UPDATE, DELETE, lectura, escritura). */
    val operations: List<String> = emptyList(),
) : ResultRecord

/** Clave de configuración que el código lee. Nunca lleva su valor. */
@Serializable
@SerialName("config_key")
data class ConfigKey(
    val name: String,
    /** Archivos que la definen (como máximo 5). Vacío: se configura fuera del comprimido. */
    val definedIn: List<String> = emptyList(),
    /** Lecturas ("archivo:línea", como máximo 5) y cuántas son. */
    val readers: List<String> = emptyList(),
    val readCount: Int = 0,
) : ResultRecord

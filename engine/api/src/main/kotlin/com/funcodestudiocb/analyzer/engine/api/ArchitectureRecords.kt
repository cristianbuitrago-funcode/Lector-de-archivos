package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Capa de arquitectura de un archivo (Fase 8). Las seis primeras forman el recorrido típico de una petición; las demás
 * son transversales.
 */
@Serializable
enum class ArchLayer {
    /** Interfaz: páginas, componentes, pantallas, vistas, estilos. */
    FRONTEND,

    /** Declaración de rutas o puntos de entrada de la API. */
    API,

    /** Controladores, manejadores de rutas, vistas de Django, ViewModels. */
    CONTROLLER,

    /** Lógica de negocio: servicios, casos de uso. */
    SERVICE,

    /** Modelos, entidades, repositorios y acceso a datos. */
    MODEL,

    /** Esquemas SQL, migraciones y tablas. */
    DATABASE,

    /** Código sin una capa clara (utilidades, compartido, scripts). */
    SHARED,

    /** Configuración, compilación y manifiestos. */
    CONFIG,

    /** Pruebas. */
    TEST,
    ;

    companion object {
        /** Capas del recorrido de una petición, en orden. */
        val FLOW = listOf(FRONTEND, API, CONTROLLER, SERVICE, MODEL, DATABASE)
    }
}

/** Capa asignada a un archivo, con su certeza y el motivo. */
@Serializable
@SerialName("file_layer")
data class FileLayer(
    val path: String,
    val layer: ArchLayer,
    /** CONFIRMED: marca explícita (anotación, declaración de rutas, definición de tablas); PROBABLE: convención de carpetas o nombres; POSSIBLE: indicio débil. */
    val confidence: Confidence,
    val reason: String,
) : ResultRecord

@Serializable
enum class DiagramKind {
    /** Capas de arquitectura y las dependencias entre ellas. */
    LAYERS,

    /** Carpetas o módulos del proyecto y sus dependencias. */
    MODULES,

    /** Subproyectos, servicios externos y dependencias principales. */
    PROJECTS,

    /** Conexiones de un archivo con foco (lo dibuja la app a partir de las relaciones guardadas). */
    FOCUS,
}

@Serializable
enum class DiagramNodeKind {
    LAYER, MODULE, PROJECT, PACKAGE, EXTERNAL_SERVICE,

    /** Archivo (grafo de un archivo con foco, que la app dibuja a partir de las relaciones). */
    FILE,
}

/** Nodo de un diagrama ya colocado (esquina superior izquierda y tamaño, en unidades del dibujo). */
@Serializable
data class DiagramNode(
    val id: String,
    val label: String,
    val kind: DiagramNodeKind,
    val layer: ArchLayer? = null,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** Archivos que agrupa (capa, módulo, proyecto). */
    val files: Int = 0,
    /** Ruta de la carpeta (módulo o proyecto) o nombre del paquete. */
    val path: String? = null,
    /** Línea secundaria: tecnologías, versión, recuento… */
    val detail: String? = null,
    /** Peor severidad de las vulnerabilidades (paquetes) o `null`. */
    val severity: Severity? = null,
)

/** Arista agregada entre dos nodos, trazada como polilínea (x0, y0, x1, y1…). */
@Serializable
data class DiagramEdge(
    val from: String,
    val to: String,
    /** Relaciones entre archivos que agrupa. */
    val count: Int,
    val kinds: List<RelationKind> = emptyList(),
    /** La certeza más alta y la más baja de las relaciones que agrupa. */
    val confidence: RelationConfidence,
    val weakest: RelationConfidence = confidence,
    val points: List<Float>,
    /** Va contra el orden de las capas (por ejemplo, un modelo que usa un controlador). */
    val inverted: Boolean = false,
    /** Forma parte de un ciclo entre módulos. */
    val cycle: Boolean = false,
    /** Ejemplos "origen:línea → destino". */
    val samples: List<String> = emptyList(),
)

/** Diagrama con su disposición calculada en el motor (la misma para la pantalla, el PDF y el SVG). */
@Serializable
@SerialName("diagram")
data class Diagram(
    val kind: DiagramKind,
    val title: String,
    val width: Float,
    val height: Float,
    val nodes: List<DiagramNode>,
    val edges: List<DiagramEdge>,
    /** Se agruparon u omitieron elementos para que el diagrama sea legible: la nota dice cuáles. */
    val truncated: Boolean = false,
    val note: String? = null,
) : ResultRecord

/** Paso de un flujo de información. */
@Serializable
data class InformationStep(
    val layer: ArchLayer,
    /** Texto del paso: archivo, `GET /api/users`, `tabla users`… */
    val label: String,
    val path: String? = null,
    val line: Int? = null,
    /** Cómo se llega desde el paso anterior (`null` en el primero). */
    val via: RelationKind? = null,
    val confidence: RelationConfidence? = null,
)

/** Recorrido de una petición: Frontend → API → Controlador → Servicio → Modelo → BD (los pasos que existan). */
@Serializable
@SerialName("information_flow")
data class InformationFlow(
    val index: Int,
    val method: String,
    val path: String,
    val framework: String,
    val steps: List<InformationStep>,
    /** La certeza más baja de las conexiones del recorrido. */
    val confidence: RelationConfidence,
    val reachesDatabase: Boolean,
    /** Otros recorridos de la misma ruta que no se muestran (por ejemplo, otros servicios que usa). */
    val alternatives: Int = 0,
) : ResultRecord

/** Resumen de la arquitectura. */
@Serializable
@SerialName("architecture_summary")
data class ArchitectureSummary(
    val byLayer: Map<ArchLayer, Int>,
    /** Archivos con capa por una marca explícita o una convención (no "compartido"). */
    val classified: Int,
    val files: Int,
    /** Dependencias entre archivos que van contra el orden de las capas, y ejemplos. */
    val inverted: Int,
    val invertedSamples: List<String> = emptyList(),
    val flows: Int,
    val completeFlows: Int,
    val modules: Int,
    /** Patrón reconocido (MVC, MVVM, capas…) o `null`. */
    val pattern: String? = null,
    /** Lo que no se pudo deducir y por qué. */
    val notes: List<String> = emptyList(),
) : ResultRecord

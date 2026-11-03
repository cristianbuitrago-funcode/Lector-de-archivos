package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Función de un archivo dentro del proyecto. Un archivo puede tener varias. */
@Serializable
enum class FileRole {
    ENTRY_POINT,
    CONFIGURATION,
    DEPENDENCIES,
    BUILD,
    DATABASE,
    AUTHENTICATION,
    SERVER,
    UI,
    SOURCE,
    TEST,
    DOCUMENTATION,
    RESOURCE,
    MEDIA,
    DATA,
    BINARY,
    POSSIBLY_UNNECESSARY,
}

/**
 * Seguridad de una afirmación del inventario.
 *
 * - [CONFIRMED]: se deduce de un marcador definitivo (nombre de archivo estándar, declaración en un manifiesto,
 *   tipo de archivo comprobado al leerlo).
 * - [PROBABLE]: se deduce de convenciones de nombre o ubicación, o de un patrón de texto en el código (aún sin
 *   análisis sintáctico).
 * - [POSSIBLE]: indicio débil que conviene revisar (p. ej. "posiblemente innecesario").
 */
@Serializable
enum class Confidence { CONFIRMED, PROBABLE, POSSIBLE }

@Serializable
enum class EvidenceKind {
    /** Nombre de archivo estándar (package.json, AndroidManifest.xml…). */
    FILE_NAME,
    EXTENSION,

    /** Carpeta en la que está. */
    LOCATION,

    /** Patrón encontrado en su contenido. */
    CONTENT,

    /** Declarado en otro archivo (manifiesto, configuración). */
    DECLARATION,

    /** Tipo comprobado al leerlo (binario, vacío, comprimido). */
    CONTENT_TYPE,

    /** Contenido idéntico a otro archivo. */
    DUPLICATE,
}

/**
 * Por qué se afirma algo. [detail] lo redacta el motor y solo incluye rutas o identificadores ya validados,
 * nunca fragmentos del código.
 */
@Serializable
data class Evidence(
    val kind: EvidenceKind,
    val detail: String,
    /** Archivo donde está la evidencia, si no es el propio archivo. */
    val path: String? = null,
    val line: Int? = null,
)

@Serializable
data class RoleAssignment(
    val role: FileRole,
    val confidence: Confidence,
    val evidence: List<Evidence>,
)

/** Qué se sabe del contenido de un archivo y si hay copia redactada para el visor. */
@Serializable
enum class ContentState {
    /** Hay copia del código con los secretos ocultados. */
    STORED,

    /** La copia del código está desactivada en Ajustes. */
    NOT_STORED_DISABLED,

    /** Se alcanzó el tamaño máximo de la copia del código. */
    NOT_STORED_LIMIT,

    /** Archivo generado (bloqueo de dependencias, código minificado): no se guarda copia. */
    NOT_STORED_GENERATED,

    /** Texto mayor que el máximo para examinar su contenido: solo se clasifica por nombre y ubicación. */
    TOO_LARGE,
    BINARY,
    EMPTY,

    /** No se pudo leer (cifrado, dañado, límite alcanzado…). */
    NOT_READ,
}

/** Ficha de un archivo: sus funciones con la evidencia de cada una. */
@Serializable
@SerialName("profile")
data class FileProfile(
    val path: String,
    val roles: List<RoleAssignment>,
    val lineCount: Int? = null,
    val content: ContentState,
    /** Posibles secretos ocultados en la copia del código. */
    val redactions: Int = 0,
    /** Carpeta del subproyecto al que pertenece ("" = raíz del comprimido). */
    val projectRoot: String? = null,
) : ResultRecord

@Serializable
enum class Ecosystem {
    ANDROID,
    GRADLE,
    MAVEN,
    NPM,
    PYTHON,
    PHP_COMPOSER,
    DART_PUB,
    DOTNET,
    GO,
    RUST_CARGO,
    RUBY,
    COCOAPODS,
    CMAKE,
    UNITY,
    UNREAL,
    GODOT,
}

/** Carpeta que contiene un subproyecto, identificada por su manifiesto. */
@Serializable
@SerialName("project_root")
data class ProjectRoot(
    /** "" = raíz del comprimido. */
    val directory: String,
    val ecosystem: Ecosystem,
    /** Archivo que lo identifica. */
    val marker: String,
) : ResultRecord

@Serializable
enum class EntryPointKind {
    /** Actividad que abre la app desde el lanzador de Android. */
    ANDROID_LAUNCHER,

    /** Clase Application de Android (se ejecuta antes que cualquier pantalla). */
    ANDROID_APPLICATION,

    /** Componente de Android que otras apps o el sistema pueden iniciar. */
    ANDROID_EXPORTED_COMPONENT,

    /** Función main o equivalente. */
    MAIN_FUNCTION,

    /** Arranque de un servidor o API. */
    SERVER_APP,

    /** Archivo principal de un paquete npm. */
    PACKAGE_MAIN,

    /** Comando ejecutable de un paquete npm. */
    PACKAGE_BIN,

    /** Archivo que arranca un script de npm (start, dev…). */
    NPM_SCRIPT,

    /** Página HTML inicial. */
    WEB_PAGE,

    /** Código que monta la aplicación web en la página. */
    WEB_BOOTSTRAP,

    /** Controlador frontal de PHP (public/index.php) o consola (artisan). */
    PHP_FRONT_CONTROLLER,

    /** Proceso principal de Electron. */
    ELECTRON_MAIN,

    /** Escena con la que arranca un juego. */
    GAME_MAIN_SCENE,

    /** Script o escena que el motor de juego carga siempre (autoload). */
    GAME_AUTOLOAD,
}

/** Lugar por el que empieza a ejecutarse el proyecto (o una parte de él). */
@Serializable
@SerialName("entry_point")
data class EntryPoint(
    val kind: EntryPointKind,
    /** Archivo del punto de entrada; `null` si está declarado pero no aparece en el comprimido. */
    val path: String?,
    /** Lo declarado (clase, escena, ruta), solo con caracteres seguros. */
    val target: String? = null,
    val confidence: Confidence,
    val evidence: List<Evidence>,
    val projectRoot: String? = null,
) : ResultRecord

/** Tipo de posible secreto ocultado. */
@Serializable
enum class SecretType {
    PRIVATE_KEY,
    AWS_ACCESS_KEY,
    AWS_SECRET_KEY,
    GITHUB_TOKEN,
    GITLAB_TOKEN,
    GOOGLE_API_KEY,
    GOOGLE_OAUTH_SECRET,
    SLACK_TOKEN,
    SLACK_WEBHOOK,
    STRIPE_KEY,
    SENDGRID_KEY,
    OPENAI_KEY,
    ANTHROPIC_KEY,
    JWT,
    FIREBASE_SERVER_KEY,
    CONNECTION_STRING_PASSWORD,
    PASSWORD,
    SECRET,
}

/**
 * Posible secreto ocultado en la copia del código. El valor completo nunca sale del proceso aislado: solo su
 * tipo, longitud y como máximo los 4 primeros y los 2 últimos caracteres (§8.4 de la arquitectura).
 */
@Serializable
@SerialName("redaction")
data class Redaction(
    val path: String,
    val line: Int,
    val type: SecretType,
    val length: Int,
    val masked: String,
) : ResultRecord

/** Fragmento de la copia redactada (secretos ya ocultados) de un archivo de texto, para el visor. */
@Serializable
@SerialName("content")
data class ContentChunk(
    val path: String,
    val index: Int,
    val text: String,
    val last: Boolean,
) : ResultRecord

/** Totales del inventario. */
@Serializable
@SerialName("inventory_summary")
data class InventorySummary(
    val files: Int,
    val roleCounts: Map<FileRole, Int>,
    val entryPoints: Int,
    val projectRoots: Int,
    /** Archivos sin ninguna función reconocida. */
    val unclassified: Int,
    val storedFiles: Int,
    val storedChars: Long,
    val redactions: Int,
    val copyEnabled: Boolean,
) : ResultRecord

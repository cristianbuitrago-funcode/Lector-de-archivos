package com.funcodestudiocb.analyzer.engine.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Gravedad de un hallazgo (escala común a toda la herramienta). */
@Serializable
enum class Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

@Serializable
enum class ArchiveFormat { ZIP, RAR4, RAR5 }

@Serializable
enum class EncryptionState {
    NONE,

    /** Algunas entradas están cifradas. */
    PARTIAL,

    /** Todas las entradas con datos están cifradas. */
    ALL_ENTRIES,

    /** También los nombres están cifrados (RAR con cabeceras cifradas): sin contraseña no se ve nada. */
    HEADERS,
}

/** Datos generales del comprimido, obtenidos sin descomprimir nada. */
@Serializable
@SerialName("archive")
data class ArchiveSummary(
    val format: ArchiveFormat,
    val sizeBytes: Long,
    val entryCount: Int,
    val directoryCount: Int,
    /** Suma de los tamaños DECLARADOS (pueden ser falsos; los reales se cuentan al leer). */
    val declaredUncompressedBytes: Long,
    val solid: Boolean,
    val encryption: EncryptionState,
) : ResultRecord

@Serializable
enum class RejectionReason {
    UNSUPPORTED_FORMAT,
    MULTI_VOLUME,
    CORRUPT,
    TOO_MANY_ENTRIES,
    TOO_LARGE,
    COMPRESSION_BOMB,
    OVERLAPPING_ENTRIES,
    PASSWORD_REQUIRED,
    WRONG_PASSWORD,
}

/** El comprimido no se puede analizar. Ninguna etapa posterior se ejecuta. */
@Serializable
@SerialName("rejected")
data class ArchiveRejected(
    val reason: RejectionReason,
    val detail: String,
) : ResultRecord

@Serializable
enum class FileKind { TEXT, BINARY, ARCHIVE, EMPTY, UNKNOWN }

@Serializable
enum class FileStatus {
    /** Leído completo: tamaño real, huella SHA-256 y tipo conocidos. */
    ANALYZED,

    /** Supera el tamaño máximo por archivo: se registra pero no se lee. */
    SKIPPED_TOO_LARGE,

    /** No se leyó porque se alcanzó un límite global del análisis. */
    SKIPPED_LIMIT,

    /** Detenido por una proporción de compresión anómala (posible bomba). */
    SKIPPED_BOMB,

    /** Cifrado y sin contraseña válida. */
    ENCRYPTED,

    /** Datos dañados (CRC o autenticación incorrectos, flujo truncado…). */
    CORRUPT,

    /** Método de compresión no admitido. */
    UNSUPPORTED_METHOD,
}

/** Un archivo del proyecto (no excluido). */
@Serializable
@SerialName("file")
data class FileEntry(
    val path: String,
    /** Tamaño real si se leyó; si no, el declarado. */
    val sizeBytes: Long,
    val compressedBytes: Long,
    val sha256: String? = null,
    val kind: FileKind,
    val encoding: String? = null,
    val status: FileStatus,
    val modifiedEpochMs: Long? = null,
) : ResultRecord

@Serializable
enum class ExclusionReason {
    /** Carpeta de la lista configurable (node_modules, .git, build…). */
    DIRECTORY_RULE,

    /** Carpeta generada propia de un motor detectado por sus marcadores (Unity, Unreal…). */
    CONTEXT_RULE,

    /** Archivo temporal o de sistema (.DS_Store, Thumbs.db, *.swp…). */
    TEMPORARY_FILE,
}

/** Carpeta excluida del análisis: se registra su existencia y su tamaño, pero no se lee su contenido. */
@Serializable
@SerialName("excluded_dir")
data class ExcludedDirectory(
    val path: String,
    val rule: String,
    val reason: ExclusionReason,
    val fileCount: Int,
    val totalBytes: Long,
) : ResultRecord

/** Archivo excluido individualmente (temporales y de sistema). */
@Serializable
@SerialName("excluded_file")
data class ExcludedFile(
    val path: String,
    val rule: String,
    val sizeBytes: Long,
) : ResultRecord

@Serializable
enum class IngestFindingKind {
    /** Ruta absoluta, con "..", letra de unidad, caracteres de control o nombre reservado. */
    DANGEROUS_PATH,

    /** Nombre con caracteres invisibles o de dirección bidireccional que pueden disfrazarlo. */
    DECEPTIVE_NAME,
    SYMLINK,
    HARDLINK,
    NESTED_ARCHIVE,
    ENCRYPTED_ENTRY,
    COMPRESSION_BOMB,

    /** El tamaño real no coincide con el declarado. */
    SIZE_MISMATCH,
    CRC_MISMATCH,
    DUPLICATE_PATH,
    CASE_COLLISION,

    /** El nombre de la cabecera local de un ZIP no coincide con el del directorio central. */
    LOCAL_HEADER_MISMATCH,
    LARGE_DICTIONARY,
    CORRUPT_ENTRY,
    UNSUPPORTED_METHOD,
    TOTAL_LIMIT_REACHED,
    ENTRY_TOO_LARGE,
}

/** Hallazgo sobre el propio comprimido (su estructura), no sobre el código del proyecto. */
@Serializable
@SerialName("ingest_finding")
data class IngestFinding(
    val kind: IngestFindingKind,
    val severity: Severity,
    val path: String? = null,
    val detail: String,
) : ResultRecord

/** Archivos con contenido idéntico (misma huella SHA-256). */
@Serializable
@SerialName("duplicates")
data class DuplicateGroup(
    val sha256: String,
    val sizeBytes: Long,
    val paths: List<String>,
    /** Número total de copias (puede ser mayor que [paths] si se recortó la lista). */
    val count: Int,
) : ResultRecord

/** Totales de la ingesta. */
@Serializable
@SerialName("ingest_summary")
data class IngestSummary(
    val files: Int,
    val analyzedFiles: Int,
    val notAnalyzedFiles: Int,
    val excludedFiles: Int,
    val excludedDirectories: Int,
    val analyzedBytes: Long,
    val excludedBytes: Long,
    val duplicateGroups: Int,
    val duplicateFiles: Int,
    val findings: Int,
) : ResultRecord

/**
 * El comprimido (o parte de él) está cifrado y hace falta una contraseña. La app la pide al usuario y
 * repite el análisis. [wrongPassword] indica que se usó una contraseña y no era correcta.
 */
@Serializable
@SerialName("password_required")
data class PasswordRequired(
    val wrongPassword: Boolean,
    val detail: String,
) : ResultRecord

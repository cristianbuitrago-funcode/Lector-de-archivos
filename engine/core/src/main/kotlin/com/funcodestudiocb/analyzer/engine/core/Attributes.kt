package com.funcodestudiocb.analyzer.engine.core

/** Clave tipada de un dato compartido entre etapas. */
class AttributeKey<T : Any>(val name: String) {
    override fun toString(): String = "AttributeKey($name)"
}

/**
 * Datos compartidos entre las etapas de una misma ejecución del pipeline (p. ej. el comprimido abierto,
 * el plan de exclusiones o las huellas de los archivos) y recursos que deben cerrarse al terminar.
 */
class Attributes {
    private val lock = Any()
    private val values = HashMap<AttributeKey<*>, Any>()
    private val resources = ArrayList<AutoCloseable>()

    operator fun <T : Any> set(key: AttributeKey<T>, value: T) {
        synchronized(lock) { values[key] = value }
    }

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: AttributeKey<T>): T? = synchronized(lock) { values[key] as T? }

    fun <T : Any> require(key: AttributeKey<T>): T =
        get(key) ?: throw IllegalStateException("Falta el dato compartido '${key.name}'")

    /** Registra un recurso que el pipeline cerrará al terminar, ocurra lo que ocurra. */
    fun <T : AutoCloseable> closeLater(resource: T): T {
        synchronized(lock) { resources += resource }
        return resource
    }

    /** Cierra los recursos registrados en orden inverso. Los errores al cerrar se ignoran. */
    fun closeAll() {
        val toClose = synchronized(lock) { resources.toList().also { resources.clear() } }
        toClose.asReversed().forEach { runCatching { it.close() } }
    }
}

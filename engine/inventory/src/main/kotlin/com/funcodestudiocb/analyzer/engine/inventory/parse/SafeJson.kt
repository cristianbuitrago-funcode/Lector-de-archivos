package com.funcodestudiocb.analyzer.engine.inventory.parse

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lectura de JSON no confiable (package.json, angular.json…): antes de analizarlo se comprueba, sin construir
 * nada, que la profundidad de anidamiento no supere el máximo. Admite comentarios y comas finales, habituales
 * en archivos de configuración.
 */
object SafeJson {
    const val MAX_DEPTH = 64

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        isLenient = true
        allowComments = true
        allowTrailingComma = true
    }

    fun parse(text: String, maxDepth: Int = MAX_DEPTH): JsonElement? {
        if (!depthWithin(text, maxDepth)) return null
        return try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Recorre el texto contando corchetes y llaves fuera de las cadenas. */
    internal fun depthWithin(text: String, maxDepth: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > maxDepth) return false
                '}', ']' -> depth--
            }
        }
        return true
    }
}

fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonElement?.keys(): Set<String> = (this as? JsonObject)?.keys ?: emptySet()

fun JsonElement?.array(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

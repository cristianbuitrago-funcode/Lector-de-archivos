package com.funcodestudiocb.analyzer.engine.inventory.parse

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.events.AliasEvent
import org.snakeyaml.engine.v2.events.DocumentEndEvent
import org.snakeyaml.engine.v2.events.MappingEndEvent
import org.snakeyaml.engine.v2.events.MappingStartEvent
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.events.SequenceEndEvent
import org.snakeyaml.engine.v2.events.SequenceStartEvent
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.parser.ParserImpl
import org.snakeyaml.engine.v2.scanner.StreamReader

/** Nodo YAML ya leído. [line] empieza en 1. */
sealed class YamlNode(val line: Int)

class YamlScalar(val value: String, line: Int) : YamlNode(line)

class YamlList(val items: List<YamlNode>, line: Int) : YamlNode(line)

class YamlMap(val entries: Map<String, YamlNode>, line: Int, private val keyLines: Map<String, Int> = emptyMap()) : YamlNode(line) {
    operator fun get(key: String): YamlNode? = entries[key]

    /** Línea en la que aparece la clave. */
    fun lineOf(key: String): Int = keyLines[key] ?: entries[key]?.line ?: line
}

/**
 * Lectura de YAML no confiable (pubspec.yaml) y del catálogo de tecnologías.
 *
 * Se usan solo los eventos del analizador de snakeyaml-engine (una máquina de estados sin recursión) y el árbol se
 * construye aquí, de forma iterativa y con límites de profundidad y de nodos. Los alias (`*ancla`) nunca se
 * expanden: quedan como un valor vacío, así que una "bomba YAML" no puede multiplicar el contenido. Las claves
 * que no son texto se ignoran. Solo se lee el primer documento.
 */
object SafeYaml {
    const val MAX_DEPTH = 64
    const val MAX_NODES = 200_000

    private class Frame(val map: LinkedHashMap<String, YamlNode>?, val list: MutableList<YamlNode>?, val line: Int) {
        var pendingKey: String? = null
        var keyIsComplex = false
        val keyLines = HashMap<String, Int>()
    }

    fun parse(text: String, maxDepth: Int = MAX_DEPTH, maxNodes: Int = MAX_NODES): YamlNode? {
        val settings = LoadSettings.builder()
            .setMaxAliasesForCollections(0)
            .setAllowRecursiveKeys(false)
            .setCodePointLimit(text.length + 1)
            .build()
        return try {
            build(ParserImpl(settings, StreamReader(settings, text)), maxDepth, maxNodes)
        } catch (e: YamlEngineException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    private fun build(parser: ParserImpl, maxDepth: Int, maxNodes: Int): YamlNode? {
        val stack = ArrayDeque<Frame>()
        var root: YamlNode? = null
        var nodes = 0

        fun add(node: YamlNode): Boolean {
            if (++nodes > maxNodes) return false
            val top = stack.lastOrNull()
            if (top == null) {
                root = node
                return true
            }
            if (top.list != null) {
                top.list.add(node)
            } else if (top.map != null) {
                val key = top.pendingKey
                when {
                    top.keyIsComplex -> {
                        top.keyIsComplex = false
                    }
                    key == null -> {
                        // Este nodo es la clave: solo se admiten claves de texto.
                        if (node is YamlScalar) {
                            top.pendingKey = node.value
                            top.keyLines.putIfAbsent(node.value, node.line)
                        } else {
                            top.keyIsComplex = true
                        }
                        return true
                    }
                    else -> {
                        top.map.putIfAbsent(key, node)
                        top.pendingKey = null
                    }
                }
            }
            return true
        }

        while (parser.hasNext()) {
            val event = parser.next()
            val line = event.startMark.map { it.line + 1 }.orElse(0)
            when (event) {
                is ScalarEvent -> if (!add(YamlScalar(event.value, line))) return null
                is AliasEvent -> if (!add(YamlScalar("", line))) return null
                is MappingStartEvent -> {
                    if (stack.size >= maxDepth) return null
                    stack.addLast(Frame(LinkedHashMap(), null, line))
                }
                is SequenceStartEvent -> {
                    if (stack.size >= maxDepth) return null
                    stack.addLast(Frame(null, mutableListOf(), line))
                }
                is MappingEndEvent, is SequenceEndEvent -> {
                    val frame = stack.removeLastOrNull() ?: return null
                    val node = if (frame.map != null) YamlMap(frame.map, frame.line, frame.keyLines) else YamlList(frame.list.orEmpty(), frame.line)
                    // Una colección usada como clave se descarta junto con su valor.
                    if (!add(node)) return null
                }
                is DocumentEndEvent -> return root
                else -> Unit
            }
        }
        return root
    }
}

fun YamlNode?.map(key: String): YamlMap? = (this as? YamlMap)?.get(key) as? YamlMap

fun YamlNode?.text(key: String): String? = ((this as? YamlMap)?.get(key) as? YamlScalar)?.value

fun YamlNode?.list(key: String): YamlList? = (this as? YamlMap)?.get(key) as? YamlList

/*
 * Puente JNI de Funcode con tree-sitter.
 *
 * Una sola llamada por archivo: se analiza el texto (UTF-8) con la gramática indicada, se recorre el árbol una vez
 * y se devuelve un único int[] con lo que necesita el motor. No se conserva ningún árbol entre llamadas.
 *
 * Formato del resultado:
 *   [0] estado (0 = correcto, 1 = tiempo agotado, 2 = lenguaje no disponible, 3 = sin memoria)
 *   [1] número de nodos de error o ausentes (errores de sintaxis)
 *   [2] indicadores de recorte (1 = comentarios, 2 = fragmentos de resaltado, 4 = nodos pedidos)
 *   [3] n comentarios, seguido de n pares (inicio, fin) en bytes
 *   luego m fragmentos de resaltado: m, seguido de m tríos (inicio, fin, categoría)
 *   luego k nodos pedidos: k, seguido de k quíntuplas (índice del tipo pedido, inicio, fin, inicio del nombre,
 *   fin del nombre); el nombre es el hijo con el campo `name` (-1 si no lo tiene)
 *
 * Se ejecuta dentro del proceso aislado sobre archivos hostiles: el tiempo está limitado (se cancela el análisis),
 * el tamaño de cada lista está acotado y el recorrido es iterativo (sin recursión).
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "tree_sitter/api.h"

const TSLanguage *tree_sitter_javascript(void);
const TSLanguage *tree_sitter_typescript(void);
const TSLanguage *tree_sitter_tsx(void);
const TSLanguage *tree_sitter_python(void);
const TSLanguage *tree_sitter_java(void);
const TSLanguage *tree_sitter_kotlin(void);
const TSLanguage *tree_sitter_php(void);
const TSLanguage *tree_sitter_c_sharp(void);
const TSLanguage *tree_sitter_dart(void);
const TSLanguage *tree_sitter_sql(void);
const TSLanguage *tree_sitter_gdscript(void);
const TSLanguage *tree_sitter_html(void);
const TSLanguage *tree_sitter_css(void);
const TSLanguage *tree_sitter_c(void);
const TSLanguage *tree_sitter_cpp(void);

typedef struct {
    const char *name;
    const TSLanguage *(*language)(void);
} Grammar;

static const Grammar GRAMMARS[] = {
    {"javascript", tree_sitter_javascript}, {"typescript", tree_sitter_typescript}, {"tsx", tree_sitter_tsx},
    {"python", tree_sitter_python}, {"java", tree_sitter_java}, {"kotlin", tree_sitter_kotlin},
    {"php", tree_sitter_php}, {"c_sharp", tree_sitter_c_sharp}, {"dart", tree_sitter_dart},
    {"sql", tree_sitter_sql}, {"gdscript", tree_sitter_gdscript}, {"html", tree_sitter_html},
    {"css", tree_sitter_css}, {"c", tree_sitter_c}, {"cpp", tree_sitter_cpp},
};
#define GRAMMAR_COUNT ((int)(sizeof(GRAMMARS) / sizeof(GRAMMARS[0])))

enum { CAT_KEYWORD = 1, CAT_STRING = 2, CAT_COMMENT = 3, CAT_NUMBER = 4, CAT_CONSTANT = 5, CAT_TYPE = 6, CAT_FUNCTION = 7 };

/* Lista de enteros que crece y se recorta al llegar al máximo. */
typedef struct {
    int32_t *data;
    size_t size;
    size_t capacity;
    size_t max_items;
    size_t item_width;
    int truncated;
    int failed;
} IntList;

static void list_init(IntList *l, size_t max_items, size_t width) {
    memset(l, 0, sizeof(*l));
    l->max_items = max_items;
    l->item_width = width;
}

static void list_add(IntList *l, int32_t a, int32_t b, int32_t c) {
    if (l->failed) return;
    if (l->size / l->item_width >= l->max_items) {
        l->truncated = 1;
        return;
    }
    if (l->size + l->item_width > l->capacity) {
        size_t cap = l->capacity ? l->capacity * 2 : 1024;
        int32_t *grown = realloc(l->data, cap * sizeof(int32_t));
        if (!grown) {
            l->failed = 1;
            return;
        }
        l->data = grown;
        l->capacity = cap;
    }
    l->data[l->size++] = a;
    l->data[l->size++] = b;
    if (l->item_width >= 3) l->data[l->size++] = c;
}

/* Elemento de cinco enteros (nodos pedidos con su nombre). */
static void list_add5(IntList *l, int32_t a, int32_t b, int32_t c, int32_t d, int32_t e) {
    if (l->failed) return;
    if (l->size / l->item_width >= l->max_items) {
        l->truncated = 1;
        return;
    }
    if (l->size + l->item_width > l->capacity) {
        size_t cap = l->capacity ? l->capacity * 2 : 1024;
        int32_t *grown = realloc(l->data, cap * sizeof(int32_t));
        if (!grown) {
            l->failed = 1;
            return;
        }
        l->data = grown;
        l->capacity = cap;
    }
    l->data[l->size++] = a;
    l->data[l->size++] = b;
    l->data[l->size++] = c;
    l->data[l->size++] = d;
    l->data[l->size++] = e;
}

typedef struct {
    const char *data;
    uint32_t length;
} Buffer;

static const char *read_input(void *payload, uint32_t byte, TSPoint position, uint32_t *bytes_read) {
    (void)position;
    Buffer *b = (Buffer *)payload;
    if (byte >= b->length) {
        *bytes_read = 0;
        return "";
    }
    *bytes_read = b->length - byte;
    return b->data + byte;
}

typedef struct {
    struct timespec deadline;
} Deadline;

static int now_after(const struct timespec *deadline) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return now.tv_sec > deadline->tv_sec || (now.tv_sec == deadline->tv_sec && now.tv_nsec >= deadline->tv_nsec);
}

static bool parse_progress(TSParseState *state) {
    return now_after(&((Deadline *)state->payload)->deadline) ? true : false;
}

static int contains(const char *text, const char *part) { return strstr(text, part) != NULL; }

static int is_word(const char *text) {
    size_t n = strlen(text);
    if (n < 2) return 0;
    for (size_t i = 0; i < n; i++) {
        char ch = text[i];
        int ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_' || (i == 0 && (ch == '#' || ch == '@'));
        if (!ok) return 0;
    }
    return 1;
}

static int is_constant(const char *type) {
    static const char *CONSTANTS[] = {"true", "false", "null", "nil", "none", "None", "True", "False", "null_literal",
                                      "boolean_literal", "undefined", "this", "self", "super", "boolean", NULL};
    for (int i = 0; CONSTANTS[i]; i++) {
        if (strcmp(type, CONSTANTS[i]) == 0) return 1;
    }
    return 0;
}

static int is_string_node(const char *type) {
    return contains(type, "string") || contains(type, "char_literal") || contains(type, "character_literal") ||
           strcmp(type, "heredoc") == 0 || strcmp(type, "attribute_value") == 0;
}

/* Categoría de resaltado de una hoja (0 = sin color). */
static int leaf_category(const char *type, int named, const char *field, const char *parent) {
    if (!named) {
        if (is_constant(type)) return CAT_CONSTANT;
        return is_word(type) ? CAT_KEYWORD : 0;
    }
    if (is_constant(type)) return CAT_CONSTANT;
    if (contains(type, "number") || contains(type, "integer") || contains(type, "float") || contains(type, "decimal") ||
        contains(type, "hex_literal") || contains(type, "real_literal")) {
        return CAT_NUMBER;
    }
    if (contains(type, "type_identifier") || contains(type, "primitive_type") || contains(type, "predefined_type") ||
        contains(type, "builtin_type") || strcmp(type, "void_type") == 0) {
        return CAT_TYPE;
    }
    if (strcmp(type, "tag_name") == 0) return CAT_KEYWORD;
    if (field && parent && (contains(type, "identifier") || strcmp(type, "name") == 0)) {
        if (strcmp(field, "function") == 0 || strcmp(field, "method") == 0) return CAT_FUNCTION;
        if (strcmp(field, "name") == 0 &&
            (contains(parent, "function") || contains(parent, "method") || contains(parent, "constructor"))) {
            return CAT_FUNCTION;
        }
    }
    return 0;
}

/* Pila de tipos de los antecesores (el recorrido es iterativo). */
typedef struct {
    const char **items;
    size_t size;
    size_t capacity;
    int failed;
} TypeStack;

static void stack_push(TypeStack *s, const char *type) {
    if (s->failed) return;
    if (s->size == s->capacity) {
        size_t cap = s->capacity ? s->capacity * 2 : 256;
        const char **grown = realloc((void *)s->items, cap * sizeof(char *));
        if (!grown) {
            s->failed = 1;
            return;
        }
        s->items = grown;
        s->capacity = cap;
    }
    s->items[s->size++] = type;
}

JNIEXPORT jobjectArray JNICALL
Java_com_funcodestudiocb_analyzer_engine_languages_treesitter_TreeSitterNative_nativeGrammars(JNIEnv *env, jclass clazz) {
    (void)clazz;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray names = (*env)->NewObjectArray(env, GRAMMAR_COUNT, string_class, NULL);
    for (int i = 0; i < GRAMMAR_COUNT; i++) {
        jstring name = (*env)->NewStringUTF(env, GRAMMARS[i].name);
        (*env)->SetObjectArrayElement(env, names, i, name);
        (*env)->DeleteLocalRef(env, name);
    }
    return names;
}

static jintArray single_status(JNIEnv *env, int status) {
    jint values[7] = {status, 0, 0, 0, 0, 0, 0};
    jintArray out = (*env)->NewIntArray(env, 7);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 7, values);
    return out;
}

JNIEXPORT jintArray JNICALL
Java_com_funcodestudiocb_analyzer_engine_languages_treesitter_TreeSitterNative_nativeParse(
    JNIEnv *env, jclass clazz, jint grammar, jbyteArray source, jint timeout_ms, jint max_items, jboolean highlight,
    jobjectArray wanted_types) {
    (void)clazz;
    if (grammar < 0 || grammar >= GRAMMAR_COUNT || max_items <= 0) return single_status(env, 2);
    const TSLanguage *language = GRAMMARS[grammar].language();

    jsize length = (*env)->GetArrayLength(env, source);
    char *text = malloc(length > 0 ? (size_t)length : 1);
    if (!text) return single_status(env, 3);
    (*env)->GetByteArrayRegion(env, source, 0, length, (jbyte *)text);

    /* Tipos de nodo pedidos (p. ej. importaciones), traducidos a símbolos de la gramática. */
    jsize wanted_count = wanted_types ? (*env)->GetArrayLength(env, wanted_types) : 0;
    TSSymbol *wanted = calloc(wanted_count > 0 ? (size_t)wanted_count : 1, sizeof(TSSymbol));
    if (!wanted) {
        free(text);
        return single_status(env, 3);
    }
    for (jsize i = 0; i < wanted_count; i++) {
        jstring name = (jstring)(*env)->GetObjectArrayElement(env, wanted_types, i);
        const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
        if (!chars) {
            wanted[i] = 0;
        } else if (chars[0] == '=' && chars[1] != '\0') {
            /* "=&&": símbolo anónimo de la gramática (operadores como "&&" u "or"), pedido expresamente. */
            wanted[i] = ts_language_symbol_for_name(language, chars + 1, (uint32_t)strlen(chars + 1), false);
        } else {
            wanted[i] = ts_language_symbol_for_name(language, chars, (uint32_t)strlen(chars), true);
        }
        if (chars) (*env)->ReleaseStringUTFChars(env, name, chars);
        (*env)->DeleteLocalRef(env, name);
    }

    TSParser *parser = ts_parser_new();
    if (!ts_parser_set_language(parser, language)) {
        ts_parser_delete(parser);
        free(wanted);
        free(text);
        return single_status(env, 2);
    }
    Buffer buffer = {text, (uint32_t)length};
    TSInput input = {&buffer, read_input, TSInputEncodingUTF8, NULL};
    Deadline deadline;
    clock_gettime(CLOCK_MONOTONIC, &deadline.deadline);
    deadline.deadline.tv_sec += timeout_ms / 1000;
    deadline.deadline.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
    if (deadline.deadline.tv_nsec >= 1000000000L) {
        deadline.deadline.tv_sec += 1;
        deadline.deadline.tv_nsec -= 1000000000L;
    }
    TSParseOptions options = {&deadline, parse_progress};
    TSTree *tree = ts_parser_parse_with_options(parser, NULL, input, options);
    if (!tree) {
        ts_parser_delete(parser);
        free(wanted);
        free(text);
        return single_status(env, 1);
    }

    IntList comments, tokens, nodes;
    list_init(&comments, (size_t)max_items, 2);
    list_init(&tokens, (size_t)max_items, 3);
    list_init(&nodes, (size_t)max_items, 5);
    TSFieldId name_field = ts_language_field_id_for_name(language, "name", 4);
    TypeStack parents = {0};
    int errors = 0;
    int timed_out = 0;
    size_t visited = 0;

    TSTreeCursor cursor = ts_tree_cursor_new(ts_tree_root_node(tree));
    for (;;) {
        TSNode node = ts_tree_cursor_current_node(&cursor);
        const char *type = ts_node_type(node);
        int named = ts_node_is_named(node);
        uint32_t start = ts_node_start_byte(node);
        uint32_t end = ts_node_end_byte(node);
        int descend = 1;

        if ((++visited & 0x3FFF) == 0 && now_after(&deadline.deadline)) {
            timed_out = 1;
            break;
        }
        if (ts_node_is_error(node) || ts_node_is_missing(node)) errors++;
        if (wanted_count > 0) {
            TSSymbol symbol = ts_node_symbol(node);
            for (jsize i = 0; i < wanted_count; i++) {
                if (wanted[i] != 0 && wanted[i] == symbol) {
                    int32_t name_start = -1, name_end = -1;
                    if (name_field != 0) {
                        TSNode name = ts_node_child_by_field_id(node, name_field);
                        if (!ts_node_is_null(name)) {
                            name_start = (int32_t)ts_node_start_byte(name);
                            name_end = (int32_t)ts_node_end_byte(name);
                        }
                    }
                    list_add5(&nodes, (int32_t)i, (int32_t)start, (int32_t)end, name_start, name_end);
                    break;
                }
            }
        }
        if (named && contains(type, "comment")) {
            list_add(&comments, (int32_t)start, (int32_t)end, 0);
            if (highlight) list_add(&tokens, (int32_t)start, (int32_t)end, CAT_COMMENT);
            descend = 0;
        } else if (named && is_string_node(type)) {
            if (highlight && end > start) list_add(&tokens, (int32_t)start, (int32_t)end, CAT_STRING);
            descend = 0;
        } else if (highlight && ts_node_child_count(node) == 0 && end > start) {
            const char *field = ts_tree_cursor_current_field_name(&cursor);
            const char *parent = parents.size > 0 ? parents.items[parents.size - 1] : NULL;
            int category = leaf_category(type, named, field, parent);
            if (category) list_add(&tokens, (int32_t)start, (int32_t)end, category);
        }

        if (descend && ts_tree_cursor_goto_first_child(&cursor)) {
            stack_push(&parents, type);
            continue;
        }
        int done = 0;
        while (!ts_tree_cursor_goto_next_sibling(&cursor)) {
            if (!ts_tree_cursor_goto_parent(&cursor)) {
                done = 1;
                break;
            }
            if (parents.size > 0) parents.size--;
        }
        if (done) break;
    }
    ts_tree_cursor_delete(&cursor);
    ts_tree_delete(tree);
    ts_parser_delete(parser);
    free((void *)parents.items);
    free(wanted);
    free(text);

    jintArray out = NULL;
    if (timed_out) {
        out = single_status(env, 1);
    } else if (comments.failed || tokens.failed || nodes.failed || parents.failed) {
        out = single_status(env, 3);
    } else {
        size_t total = 3 + 1 + comments.size + 1 + tokens.size + 1 + nodes.size;
        out = (*env)->NewIntArray(env, (jsize)total);
        if (out) {
            jint header[4] = {0, errors, (comments.truncated ? 1 : 0) | (tokens.truncated ? 2 : 0) | (nodes.truncated ? 4 : 0),
                              (jint)(comments.size / 2)};
            jsize pos = 0;
            (*env)->SetIntArrayRegion(env, out, pos, 4, header);
            pos += 4;
            if (comments.size) (*env)->SetIntArrayRegion(env, out, pos, (jsize)comments.size, comments.data);
            pos += (jsize)comments.size;
            jint count = (jint)(tokens.size / 3);
            (*env)->SetIntArrayRegion(env, out, pos++, 1, &count);
            if (tokens.size) (*env)->SetIntArrayRegion(env, out, pos, (jsize)tokens.size, tokens.data);
            pos += (jsize)tokens.size;
            count = (jint)(nodes.size / 5);
            (*env)->SetIntArrayRegion(env, out, pos++, 1, &count);
            if (nodes.size) (*env)->SetIntArrayRegion(env, out, pos, (jsize)nodes.size, nodes.data);
        }
    }
    free(comments.data);
    free(tokens.data);
    free(nodes.data);
    return out;
}

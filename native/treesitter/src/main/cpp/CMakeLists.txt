# Biblioteca nativa libfuncode_treesitter: núcleo de tree-sitter + gramáticas + puente JNI.
# Se compila con el NDK para Android (AGP) y con el compilador del sistema para las pruebas en el PC.
# TS_SRC es la carpeta con las fuentes descargadas y verificadas por la tarea fetchTreeSitterSources.
cmake_minimum_required(VERSION 3.22)
project(funcode_treesitter C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT TS_SRC OR NOT EXISTS ${TS_SRC}/tree-sitter/vendor/tree-sitter/lib/src/lib.c)
    message(FATAL_ERROR "Faltan las fuentes de tree-sitter (TS_SRC=${TS_SRC}); ejecuta :native:treesitter:fetchTreeSitterSources")
endif()

set(TS_RUNTIME ${TS_SRC}/tree-sitter/vendor/tree-sitter/lib)

# nombre de la función del lenguaje : carpeta con parser.c (y scanner.c si existe)
set(TS_GRAMMARS
    javascript:javascript/src
    typescript:typescript/typescript/src
    tsx:typescript/tsx/src
    python:python/src
    java:java/src
    kotlin:kotlin/src
    php:php/php/src
    c_sharp:c-sharp/src
    dart:dart/src
    sql:sql/src
    gdscript:gdscript/src
    html:html/src
    css:css/src
    c:c/src
    cpp:cpp/src)

add_library(funcode_treesitter SHARED ${TS_RUNTIME}/src/lib.c funcode_treesitter_jni.c)
target_include_directories(funcode_treesitter PRIVATE ${TS_RUNTIME}/include ${TS_RUNTIME}/src)
target_compile_definitions(funcode_treesitter PRIVATE _POSIX_C_SOURCE=200112L _DEFAULT_SOURCE)
target_compile_options(funcode_treesitter PRIVATE -O2 -fvisibility=hidden -fstack-protector-strong)

foreach(grammar ${TS_GRAMMARS})
    string(REPLACE ":" ";" parts ${grammar})
    list(GET parts 1 dir)
    set(grammar_dir ${TS_SRC}/${dir})
    set(grammar_files ${grammar_dir}/parser.c)
    if(EXISTS ${grammar_dir}/scanner.c)
        list(APPEND grammar_files ${grammar_dir}/scanner.c)
    endif()
    target_sources(funcode_treesitter PRIVATE ${grammar_files})
    # Las tablas generadas son enormes: -Os reduce el tamaño y el tiempo de compilación sin afectar al análisis.
    set_source_files_properties(${grammar_files} PROPERTIES
        INCLUDE_DIRECTORIES ${grammar_dir}
        COMPILE_OPTIONS "-Os;-w")
endforeach()

if(ANDROID)
    target_link_options(funcode_treesitter PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,relro -Wl,-z,now)
else()
    find_package(JNI REQUIRED)
    target_include_directories(funcode_treesitter PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_options(funcode_treesitter PRIVATE -Wl,-z,relro -Wl,-z,now)
endif()

# Biblioteca nativa libfuncode_unrar: UnRAR (interfaz DLL) + puente JNI.
# Se compila con el NDK para Android (AGP) y con el compilador del sistema para las pruebas en el PC.
cmake_minimum_required(VERSION 3.22)
project(funcode_unrar CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(UNRAR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/unrar)

# Mismos objetos que el objetivo "lib" del makefile original de UnRAR (OBJECTS + LIB_OBJ), sin RAR_SMP.
set(UNRAR_SOURCES
    rar.cpp strlist.cpp strfn.cpp pathfn.cpp smallfn.cpp global.cpp file.cpp filefn.cpp filcreat.cpp
    archive.cpp arcread.cpp unicode.cpp system.cpp crypt.cpp crc.cpp rawread.cpp encname.cpp
    resource.cpp match.cpp timefn.cpp rdwrfn.cpp consio.cpp options.cpp errhnd.cpp rarvm.cpp secpassword.cpp
    rijndael.cpp getbits.cpp sha1.cpp sha256.cpp blake2s.cpp hash.cpp extinfo.cpp extract.cpp volume.cpp
    list.cpp find.cpp unpack.cpp headers.cpp threadpool.cpp rs16.cpp cmddata.cpp ui.cpp largepage.cpp
    filestr.cpp scantree.cpp dll.cpp qopen.cpp)
list(TRANSFORM UNRAR_SOURCES PREPEND ${UNRAR_DIR}/)

add_library(funcode_unrar SHARED ${UNRAR_SOURCES} funcode_unrar_jni.cpp)

target_compile_definitions(funcode_unrar PRIVATE RARDLL SILENT _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE)
target_include_directories(funcode_unrar PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(funcode_unrar PRIVATE
    -O2 -fexceptions -frtti -fvisibility=hidden
    -Wno-logical-op-parentheses -Wno-switch -Wno-dangling-else -Wno-unknown-warning-option)
# Endurecimiento.
target_compile_options(funcode_unrar PRIVATE -fstack-protector-strong -D_FORTIFY_SOURCE=2)

if(ANDROID)
    # Páginas de 16 KB (obligatorias en dispositivos recientes) y enlazado endurecido.
    target_link_options(funcode_unrar PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,relro -Wl,-z,now)
else()
    find_package(JNI REQUIRED)
    target_include_directories(funcode_unrar PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_options(funcode_unrar PRIVATE -Wl,-z,relro -Wl,-z,now)
endif()

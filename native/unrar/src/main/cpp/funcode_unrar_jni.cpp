// Puente JNI entre el motor de Funcode Code Analyzer y la biblioteca UnRAR (interfaz "DLL").
//
// - Nunca se escribe nada en disco: los datos descomprimidos se entregan a Kotlin por la retrollamada
//   UCM_PROCESSDATA en modo de prueba (RAR_TEST).
// - El archivo se abre por descriptor ("funcode-fd:<n>", ver la modificacion en unrar/file.cpp).
// - Los multivolumen se rechazan (no se piden volumenes adicionales) y los diccionarios grandes se deniegan.
// - Contrasena: solo en memoria nativa durante la sesion; se borra al cerrar.
//
// Las cadenas de este archivo son ASCII a proposito.

#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cwchar>
#include <string>
#include <vector>

#ifndef _UNIX
#define _UNIX
#endif
#include "unrar/dll.hpp"

namespace {

constexpr jint kChunk = 64 * 1024;
constexpr size_t kNameCapacity = 32768;
constexpr size_t kRedirCapacity = 4096;

struct Session {
    HANDLE handle = nullptr;
    std::wstring password;
    bool hasPassword = false;

    // Estado de la llamada RARProcessFileW en curso.
    JNIEnv *env = nullptr;
    jobject sink = nullptr;
    jmethodID onData = nullptr;
    jbyteArray buffer = nullptr;

    bool aborted = false;
    bool passwordRequested = false;
    bool volumeRequested = false;
    bool largeDictionary = false;

    std::vector<wchar_t> nameBuffer = std::vector<wchar_t>(kNameCapacity, 0);
    std::vector<wchar_t> redirBuffer = std::vector<wchar_t>(kRedirCapacity, 0);

    void wipePassword() {
        if (!password.empty()) {
            volatile wchar_t *p = &password[0];
            for (size_t i = 0; i < password.size(); i++) p[i] = 0;
        }
        password.clear();
        hasPassword = false;
    }
};

int CALLBACK OnUnrarEvent(UINT msg, LPARAM userData, LPARAM p1, LPARAM p2) {
    auto *s = reinterpret_cast<Session *>(userData);
    if (s == nullptr) return -1;
    switch (msg) {
        case UCM_CHANGEVOLUME:
        case UCM_CHANGEVOLUMEW:
            // Multivolumen no admitido: nunca se abren otros archivos.
            s->volumeRequested = true;
            return -1;
        case UCM_NEEDPASSWORD:
            s->passwordRequested = true;
            return -1;
        case UCM_NEEDPASSWORDW: {
            s->passwordRequested = true;
            if (!s->hasPassword || p1 == 0 || p2 <= 0) return -1;
            auto *dest = reinterpret_cast<wchar_t *>(p1);
            const size_t capacity = static_cast<size_t>(p2);
            if (s->password.size() + 1 > capacity) return -1;
            wmemcpy(dest, s->password.c_str(), s->password.size() + 1);
            return 1;
        }
        case UCM_LARGEDICT:
            // Diccionario mayor que el permitido por UnRAR: se deniega siempre.
            s->largeDictionary = true;
            return 0;
        case UCM_PROCESSDATA: {
            if (s->env == nullptr || s->sink == nullptr) return 1;
            JNIEnv *env = s->env;
            const auto *data = reinterpret_cast<const jbyte *>(p1);
            jlong remaining = static_cast<jlong>(p2);
            while (remaining > 0) {
                const jint n = remaining > kChunk ? kChunk : static_cast<jint>(remaining);
                env->SetByteArrayRegion(s->buffer, 0, n, data);
                const jboolean keepGoing = env->CallBooleanMethod(s->sink, s->onData, s->buffer, n);
                if (env->ExceptionCheck() || !keepGoing) {
                    s->aborted = true;
                    return -1;
                }
                data += n;
                remaining -= n;
            }
            return 1;
        }
        default:
            return 0;
    }
}

Session *asSession(jlong ptr) { return reinterpret_cast<Session *>(static_cast<intptr_t>(ptr)); }

// UTF-32 (wchar_t en Linux/Android) -> UTF-16 para Java. Puntos de codigo invalidos -> U+FFFD.
jstring toJavaString(JNIEnv *env, const wchar_t *text, size_t maxLen) {
    std::vector<jchar> out;
    out.reserve(64);
    for (size_t i = 0; i < maxLen && text[i] != 0; i++) {
        uint32_t cp = static_cast<uint32_t>(text[i]);
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<jchar>(cp));
        }
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

std::wstring utf16ToWide(const jchar *raw, jsize len) {
    std::wstring result;
    result.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; i++) {
        uint32_t c = raw[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len && raw[i + 1] >= 0xDC00 && raw[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (raw[i + 1] - 0xDC00);
            i++;
        }
        result.push_back(static_cast<wchar_t>(c));
    }
    return result;
}

std::wstring fromJavaChars(JNIEnv *env, jcharArray chars) {
    std::wstring result;
    if (chars == nullptr) return result;
    const jsize len = env->GetArrayLength(chars);
    jchar *raw = env->GetCharArrayElements(chars, nullptr);
    if (raw == nullptr) return result;
    result = utf16ToWide(raw, len);
    // JNI_ABORT: no se copia nada de vuelta. El array de Kotlin lo borra su propietario cuando termina.
    env->ReleaseCharArrayElements(chars, raw, JNI_ABORT);
    return result;
}

std::wstring fromJavaString(JNIEnv *env, jstring text) {
    std::wstring result;
    if (text == nullptr) return result;
    const jsize len = env->GetStringLength(text);
    const jchar *raw = env->GetStringChars(text, nullptr);
    if (raw == nullptr) return result;
    result = utf16ToWide(raw, len);
    env->ReleaseStringChars(text, raw);
    return result;
}

}  // namespace

extern "C" {

// result: [openResult, archiveFlags, passwordRequested, volumeRequested]
JNIEXPORT jlong JNICALL
Java_com_funcodestudiocb_analyzer_engine_ingest_rar_UnrarNative_nativeOpen(
        JNIEnv *env, jclass, jstring name, jboolean listOnly, jcharArray password,
        jint maxRuntimeSeconds, jint maxAllocateMb, jintArray result) {
    auto *s = new Session();
    if (password != nullptr) {
        s->password = fromJavaChars(env, password);
        s->hasPassword = true;
    }

    // "funcode-fd:<n>" (descriptor heredado, Android) o una ruta (pruebas y CLI en el PC).
    std::wstring arcName = fromJavaString(env, name);

    RAROpenArchiveDataEx data;
    memset(&data, 0, sizeof(data));
    data.ArcNameW = &arcName[0];
    data.OpenMode = listOnly ? RAR_OM_LIST : RAR_OM_EXTRACT;
    data.Callback = OnUnrarEvent;
    data.UserData = reinterpret_cast<LPARAM>(s);
    data.MaxRuntime = static_cast<unsigned int>(maxRuntimeSeconds > 0 ? maxRuntimeSeconds : 0);
    data.MaxAllocate = static_cast<unsigned int>(maxAllocateMb > 0 ? maxAllocateMb : 0);

    HANDLE handle = RAROpenArchiveEx(&data);
    jint out[4] = {static_cast<jint>(data.OpenResult), static_cast<jint>(data.Flags),
                   s->passwordRequested ? 1 : 0, s->volumeRequested ? 1 : 0};
    env->SetIntArrayRegion(result, 0, 4, out);
    if (handle == nullptr) {
        s->wipePassword();
        delete s;
        return 0;
    }
    s->handle = handle;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(s));
}

// out: [code, unpSize, packSize, flags, fileAttr, hostOs, dictSizeKb, redirType, dirTarget, mtime(ms Unix),
//       method, unpVer, crc32]
JNIEXPORT jstring JNICALL
Java_com_funcodestudiocb_analyzer_engine_ingest_rar_UnrarNative_nativeNextHeader(
        JNIEnv *env, jclass, jlong ptr, jlongArray out, jobjectArray redirOut) {
    Session *s = asSession(ptr);
    RARHeaderDataEx header;
    memset(&header, 0, sizeof(header));
    std::fill(s->nameBuffer.begin(), s->nameBuffer.end(), 0);
    std::fill(s->redirBuffer.begin(), s->redirBuffer.end(), 0);
    header.FileNameEx = s->nameBuffer.data();
    header.FileNameExSize = static_cast<unsigned int>(s->nameBuffer.size());
    header.RedirName = s->redirBuffer.data();
    header.RedirNameSize = static_cast<unsigned int>(s->redirBuffer.size());

    const int code = RARReadHeaderEx(s->handle, &header);
    jlong values[13] = {0};
    values[0] = code;
    if (code == ERAR_SUCCESS) {
        values[1] = (static_cast<jlong>(header.UnpSizeHigh) << 32) | header.UnpSize;
        values[2] = (static_cast<jlong>(header.PackSizeHigh) << 32) | header.PackSize;
        values[3] = header.Flags;
        values[4] = header.FileAttr;
        values[5] = header.HostOS;
        values[6] = header.DictSize;
        values[7] = header.RedirType;
        values[8] = header.DirTarget;
        // MtimeHigh:MtimeLow es FILETIME (intervalos de 100 ns desde 1601).
        const uint64_t filetime = (static_cast<uint64_t>(header.MtimeHigh) << 32) | header.MtimeLow;
        values[9] = filetime == 0 ? 0 : static_cast<jlong>((filetime - 116444736000000000ULL) / 10000ULL);
        values[10] = header.Method;
        values[11] = header.UnpVer;
        values[12] = header.FileCRC;
    }
    env->SetLongArrayRegion(out, 0, 13, values);
    if (code != ERAR_SUCCESS) return nullptr;
    if (header.RedirType != 0 && redirOut != nullptr && env->GetArrayLength(redirOut) > 0) {
        jstring target = toJavaString(env, s->redirBuffer.data(), s->redirBuffer.size());
        env->SetObjectArrayElement(redirOut, 0, target);
    }
    return toJavaString(env, s->nameBuffer.data(), s->nameBuffer.size());
}

// operation: 0 = saltar, 1 = leer (prueba, sin escribir en disco). sink.onData(byte[], int): Boolean
JNIEXPORT jint JNICALL
Java_com_funcodestudiocb_analyzer_engine_ingest_rar_UnrarNative_nativeProcess(
        JNIEnv *env, jclass, jlong ptr, jint operation, jobject sink) {
    Session *s = asSession(ptr);
    s->aborted = false;
    if (operation == RAR_TEST && sink != nullptr) {
        jclass cls = env->GetObjectClass(sink);
        s->onData = env->GetMethodID(cls, "onData", "([BI)Z");
        if (s->onData == nullptr) return ERAR_UNKNOWN;
        s->buffer = env->NewByteArray(kChunk);
        if (s->buffer == nullptr) return ERAR_NO_MEMORY;
        s->env = env;
        s->sink = sink;
    }
    const int code = RARProcessFileW(s->handle, operation == RAR_TEST ? RAR_TEST : RAR_SKIP, nullptr, nullptr);
    if (s->buffer != nullptr) env->DeleteLocalRef(s->buffer);
    s->buffer = nullptr;
    s->env = nullptr;
    s->sink = nullptr;
    s->onData = nullptr;
    return code;
}

// out: [aborted, passwordRequested, volumeRequested, largeDictionary]
JNIEXPORT void JNICALL
Java_com_funcodestudiocb_analyzer_engine_ingest_rar_UnrarNative_nativeState(
        JNIEnv *env, jclass, jlong ptr, jintArray out) {
    Session *s = asSession(ptr);
    jint values[4] = {s->aborted ? 1 : 0, s->passwordRequested ? 1 : 0, s->volumeRequested ? 1 : 0,
                      s->largeDictionary ? 1 : 0};
    env->SetIntArrayRegion(out, 0, 4, values);
}

JNIEXPORT void JNICALL
Java_com_funcodestudiocb_analyzer_engine_ingest_rar_UnrarNative_nativeClose(JNIEnv *, jclass, jlong ptr) {
    Session *s = asSession(ptr);
    if (s == nullptr) return;
    if (s->handle != nullptr) RARCloseArchive(s->handle);
    s->wipePassword();
    delete s;
}

JNIEXPORT jint JNICALL
Java_com_funcodestudiocb_analyzer_engine_ingest_rar_UnrarNative_nativeDllVersion(JNIEnv *, jclass) {
    return RARGetDllVersion();
}

}  // extern "C"

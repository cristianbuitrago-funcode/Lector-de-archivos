// Biblioteca nativa libfuncode_unrar (UnRAR + JNI) para Android. Las declaraciones JNI viven en
// :engine:ingest (com.funcodestudiocb.analyzer.engine.ingest.rar.UnrarNative).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.funcodestudiocb.analyzer.nativeunrar"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

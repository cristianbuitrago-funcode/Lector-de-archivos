plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":engine:core"))
    implementation(libs.commons.compress)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // Solo para generar ZIP cifrados de referencia en las pruebas; no se incluye en la app.
    testImplementation(libs.zip4j)
    testRuntimeOnly(libs.junit5.launcher)
}

// UnRAR compilado para el PC: lo usan las pruebas del lector RAR y la CLI de pruebas.
val nativeSourceDir: String = rootProject.layout.projectDirectory.dir("native/unrar/src/main/cpp").asFile.absolutePath
val hostBuildDir: String = layout.buildDirectory.dir("host-native").get().asFile.absolutePath
val hostLibDir: String = "$hostBuildDir/out"

val configureHostUnrar = tasks.register<Exec>("configureHostUnrar") {
    group = "build"
    description = "Configura la compilación de UnRAR para el PC (pruebas)."
    inputs.file("$nativeSourceDir/CMakeLists.txt")
    outputs.file("$hostBuildDir/CMakeCache.txt")
    commandLine(
        "cmake", "-S", nativeSourceDir, "-B", hostBuildDir,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$hostLibDir",
    )
}

val buildHostUnrar = tasks.register<Exec>("buildHostUnrar") {
    group = "build"
    description = "Compila libfuncode_unrar para el PC (pruebas)."
    dependsOn(configureHostUnrar)
    inputs.dir(nativeSourceDir)
    outputs.dir(hostLibDir)
    commandLine("cmake", "--build", hostBuildDir, "--parallel")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(buildHostUnrar)
    systemProperty("java.library.path", hostLibDir)
    // Permite a una prueba obtener el número de descriptor de un FileDescriptor (prueba del parche de UnRAR).
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
    maxHeapSize = "1g"
}

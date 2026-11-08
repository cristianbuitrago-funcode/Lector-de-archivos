plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(project(":engine:api"))
    // Expresiones regulares de tiempo lineal sobre el contenido hostil.
    api(libs.re2j)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit5.launcher)
}

// tree-sitter compilado para el PC: lo usan las pruebas del motor y la CLI de pruebas.
val treeSitterProject = project(":native:treesitter")
val nativeSourceDir: String = treeSitterProject.layout.projectDirectory.dir("src/main/cpp").asFile.absolutePath
val treeSitterSources: String = treeSitterProject.layout.buildDirectory.dir("ts-src").get().asFile.absolutePath
val hostBuildDir: String = layout.buildDirectory.dir("host-native").get().asFile.absolutePath
val hostLibDir: String = "$hostBuildDir/out"

val configureHostTreeSitter = tasks.register<Exec>("configureHostTreeSitter") {
    group = "build"
    description = "Configura la compilación de tree-sitter para el PC (pruebas)."
    dependsOn(":native:treesitter:fetchTreeSitterSources")
    inputs.file("$nativeSourceDir/CMakeLists.txt")
    outputs.file("$hostBuildDir/CMakeCache.txt")
    commandLine(
        "cmake", "-S", nativeSourceDir, "-B", hostBuildDir,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DTS_SRC=$treeSitterSources",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$hostLibDir",
    )
}

val buildHostTreeSitter = tasks.register<Exec>("buildHostTreeSitter") {
    group = "build"
    description = "Compila libfuncode_treesitter para el PC (pruebas)."
    dependsOn(configureHostTreeSitter)
    inputs.dir(nativeSourceDir)
    inputs.dir(treeSitterSources)
    outputs.dir(hostLibDir)
    commandLine("cmake", "--build", hostBuildDir, "--parallel")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(buildHostTreeSitter)
    systemProperty("java.library.path", hostLibDir)
    maxHeapSize = "1g"
}

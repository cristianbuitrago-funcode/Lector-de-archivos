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

// Fase 9: métricas de mantenimiento (complejidad del árbol sintáctico, tamaño, duplicación, pruebas, documentación,
// deuda, herramientas de calidad y estructura) y el índice de mantenimiento.
dependencies {
    api(project(":engine:audit"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // tree-sitter compilado para el PC: la complejidad sale del árbol sintáctico, como en el teléfono.
    dependsOn(":engine:languages:buildHostTreeSitter")
    systemProperty("java.library.path", project(":engine:languages").layout.buildDirectory.dir("host-native/out").get().asFile.absolutePath)
}

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

// Fase 8: capas de arquitectura, diagramas y flujos de información, a partir de las relaciones (5A/5B) y de las
// dependencias (7). La disposición de los diagramas la calcula engine:layout.
dependencies {
    api(project(":engine:audit"))
    api(project(":engine:layout"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // tree-sitter compilado para el PC: las importaciones salen del árbol sintáctico, como en el teléfono.
    dependsOn(":engine:languages:buildHostTreeSitter")
    systemProperty("java.library.path", project(":engine:languages").layout.buildDirectory.dir("host-native/out").get().asFile.absolutePath)
}

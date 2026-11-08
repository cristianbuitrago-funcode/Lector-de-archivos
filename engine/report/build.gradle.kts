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

// Fase 9: el informe a partir de los registros validados (proceso principal y CLI): las 15 secciones y los anexos,
// el resumen ejecutivo y las conclusiones con plantillas deterministas, la exportación JSON y HTML (sin JavaScript) y
// la comparación entre análisis. No depende de Android: el PDF lo dibuja la app con este mismo modelo.
dependencies {
    api(project(":engine:api"))

    // Las pruebas generan informes de análisis reales con el pipeline completo.
    testImplementation(project(":engine:pipeline"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    dependsOn(":engine:languages:buildHostTreeSitter")
    systemProperty("java.library.path", project(":engine:languages").layout.buildDirectory.dir("host-native/out").get().asFile.absolutePath)
}

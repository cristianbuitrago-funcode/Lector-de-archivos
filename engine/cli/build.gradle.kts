// Ejecutable de consola SOLO para pruebas y CI: ejecuta el motor en el PC sobre un ZIP o RAR.
// No tiene el aislamiento de la app: no usar con archivos de origen desconocido fuera de un entorno controlado.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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
    implementation(project(":engine:ingest"))
}

application {
    mainClass.set("com.funcodestudiocb.analyzer.engine.cli.MainKt")
    applicationName = "funcode-analyzer"
}

val hostLibDir = project(":engine:ingest").layout.buildDirectory.dir("host-native/out").get().asFile.absolutePath
tasks.named<JavaExec>("run") {
    dependsOn(":engine:ingest:buildHostUnrar")
    systemProperty("java.library.path", hostLibDir)
}

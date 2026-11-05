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
    api(project(":engine:relations"))
    api(project(":engine:security"))
    api(project(":engine:vulndb"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // tree-sitter compilado para el PC (sin él, el recuento de líneas sería solo léxico).
    dependsOn(":engine:languages:buildHostTreeSitter")
    systemProperty("java.library.path", project(":engine:languages").layout.buildDirectory.dir("host-native/out").get().asFile.absolutePath)
}

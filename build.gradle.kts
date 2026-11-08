plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Las pruebas instrumentadas de cada módulo se ejecutan de una en una: comparten el único emulador y, en paralelo, ADB
// llegó a no responder en Android 8.0 (el ejecutor no arrancaba y el módulo quedaba con 0 pruebas).
abstract class EmuladorCompartido : BuildService<BuildServiceParameters.None>

val emulador = gradle.sharedServices.registerIfAbsent("emulador", EmuladorCompartido::class) {
    maxParallelUsages.set(1)
}

val pruebasEnEmulador = Regex("connected.+AndroidTest") // connectedDebugAndroidTest, no la tarea que las agrupa

subprojects {
    tasks.configureEach {
        if (pruebasEnEmulador.matches(name)) usesService(emulador)
    }
}

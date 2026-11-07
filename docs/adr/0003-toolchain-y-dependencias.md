# ADR 0003 — Toolchain, inyección de dependencias y verificación de dependencias

- **Estado:** aceptada (2026-11-03)
- **Decisión:**
  - AGP 9.5 (Kotlin integrado), Kotlin 2.4.21, Gradle 9.8.2, compileSdk/targetSdk 37, minSdk 26, JDK 21.
  - **Actualización (2026-11-07, inicio de la Fase 9):** AGP 9.5.1 y Gradle 9.9.0, versiones estables publicadas
    después de la Fase 7 que Lint señalaba. El wrapper fija la suma SHA-256 oficial de la distribución y su
    `gradle-wrapper.jar` coincide con el publicado por Gradle; la verificación de dependencias se regeneró (Gradle 9.9
    necesita además `kotlin-reflect` para compilar los scripts).
  - **Inyección de dependencias manual** (`AppContainer`) en lugar de Hilt, como preveía la arquitectura v0.2:
    el grafo es pequeño, evita un procesador de anotaciones más y permite a las pruebas construir el contenedor con
    implementaciones falsas del Keystore y una base de datos en memoria. Se revisará si el grafo crece.
  - Repositorios: Google Maven y el **espejo de Google de Maven Central**. Maven Central y el Gradle Plugin Portal
    devuelven HTTP 429 en el entorno de desarrollo.
  - **Verificación de dependencias** de Gradle (`gradle/verification-metadata.xml`, SHA-256) y checksum de la
    distribución de Gradle en el wrapper. Al añadir o actualizar una dependencia hay que regenerarla con
    `./gradlew --write-verification-metadata sha256 test lint assembleDebug assembleDebugAndroidTest :app:assembleRelease`
    y revisar el diff.
  - Acciones de GitHub fijadas por SHA de commit.
  - El motor (`engine:*`) es Kotlin/JVM puro y compila con avisos tratados como errores.

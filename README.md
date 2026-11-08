# Funcode Code Analyzer

**Funcode Studio CB** · Herramienta interna

Aplicación Android (APK) que analiza proyectos de software completos. Recibe un único archivo `.zip` o `.rar`, lo
examina dentro de un proceso aislado del propio teléfono, sin ejecutar nada y sin enviar el código a ningún servicio,
y genera un diagnóstico técnico: estructura, tecnologías, arquitectura, relaciones entre archivos, dependencias y
auditoría de seguridad, exportable en PDF, HTML y JSON.

## Estado

| Fase | Estado |
|------|--------|
| 1. Arquitectura y configuración inicial | ✅ Terminada y aprobada ([informe](docs/fases/FASE-1.md)) |
| 2. Carga y extracción ZIP/RAR | ✅ Terminada y aprobada ([informe](docs/fases/FASE-2.md)) |
| 3. Explorador y estructura | ✅ Terminada y aprobada ([informe](docs/fases/FASE-3.md)) |
| 4. Lenguajes y tecnologías | ✅ Terminada y aprobada ([informe](docs/fases/FASE-4.md)) |
| 5A. Relaciones entre archivos | ✅ Terminada y aprobada ([informe](docs/fases/FASE-5A.md)) |
| 5B. Relaciones de framework | ✅ Terminada y aprobada ([informe](docs/fases/FASE-5B.md)) |
| 6. Auditoría de seguridad | ✅ Terminada y aprobada ([informe](docs/fases/FASE-6.md)) |
| 7. Dependencias | ✅ Terminada y aprobada ([informe](docs/fases/FASE-7.md)) |
| 8. Diagramas | ✅ Terminada y aprobada ([informe](docs/fases/FASE-8.md)) |
| 9. Informes | ✅ Terminada, pendiente de aprobación ([informe](docs/fases/FASE-9.md)) |
| 10 | Pendiente |

- Arquitectura: [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md)
- Decisiones técnicas: [`docs/adr/`](docs/adr/)
- Licencias de terceros: [`docs/LICENCIAS-TERCEROS.md`](docs/LICENCIAS-TERCEROS.md)

## Estructura

```
app/                interfaz (Compose), acceso, bloqueo automático, diagnóstico
analyzer-service/   servicio de análisis en proceso aislado + cliente con vigilante (AIDL)
data/               bóveda de acceso (Argon2id, Keystore, biometría) y BD cifrada (SQLCipher + Room)
engine/api/         protocolo de resultados entre procesos y validación
engine/core/        orquestador del pipeline de análisis
engine/ingest/      identificación, comprobación y lectura de ZIP y RAR sin extraer a disco
engine/inventory/   funciones de los archivos, subproyectos, puntos de entrada, posibles innecesarios y copia redactada
engine/security/    ocultación de secretos con huella local y catálogo de reglas de seguridad (fichas completas)
engine/audit/       auditoría de seguridad estática: secretos, inyecciones con flujo de datos, configuración,
                    sesiones, plataformas, control de acceso de las rutas y supresiones; dependencias vulnerables,
                    uso real, criticidad y fin de soporte
engine/languages/   detección de lenguajes, líneas de código/comentario/en blanco, importaciones, símbolos y resaltado (tree-sitter)
engine/dependencies/ dependencias declaradas en los manifiestos y archivos de bloqueo (versiones reales e indirectas)
engine/vulndb/      índice local de vulnerabilidades (OSV) y fin de soporte (endoflife.date), comparadores de versión
engine/technologies/ motor de reglas declarativas (catálogo YAML) de frameworks, bases de datos y servicios externos
engine/relations/   relaciones entre archivos: resolución por ecosistema, analizadores de framework (rutas, HTTP, datos,
                    configuración, inyección, Android, Unity, Godot, canales), grafo, ciclos, importancia e impacto
engine/layout/      disposición de grafos por capas (tipo Sugiyama), determinista y acotada: pantalla, PDF y SVG
engine/architecture/ capa de cada archivo, diagramas de capas, módulos y proyectos, dependencias inversas y flujos
                    Frontend → API → Controlador → Servicio → Modelo → BD
engine/metrics/     métricas de mantenimiento: complejidad en el árbol sintáctico, tamaño, código repetido, pruebas,
                    documentación, deuda, herramientas de calidad y estructura, e índice de mantenimiento
engine/report/      el informe (15 secciones y anexos), resumen y conclusiones trazables, HTML sin JavaScript, JSON y
                    comparación entre análisis (el PDF lo dibuja la app con el mismo modelo)
engine/pipeline/    composición del análisis completo
engine/cli/         herramienta de línea de comandos para probar el motor en el PC (no forma parte de la app)
native/unrar/       UnRAR oficial (modificado, ver ADR 0004) compilado con el NDK, y su puente JNI
native/treesitter/  tree-sitter y 15 gramáticas (descargadas de npm y verificadas, ver ADR 0006) con su puente JNI
```

## Compilar y probar

Requisitos: JDK 21, Android SDK (plataforma 37, build-tools 37), NDK 29.0.14206865 y CMake 3.31.0 (los dos últimos
desde el SDK Manager), un compilador de C/C++ del sistema y CMake para las pruebas en el PC (UnRAR y tree-sitter),
y acceso a `registry.npmjs.org` la primera vez (fuentes de tree-sitter). Indica la ruta del SDK en
`local.properties` (`sdk.dir=…`) o con `ANDROID_HOME`.

```bash
./gradlew test lint                 # pruebas JVM (motor, bóveda, interfaz con Robolectric) y Lint
./gradlew assembleDebug             # APK de prueba por arquitectura: app/build/outputs/apk/debug/app-arm64-v8a-debug.apk (móviles actuales) y app-universal-debug.apk
./gradlew connectedDebugAndroidTest # pruebas en dispositivo o emulador (aislamiento, cifrado, análisis reales)
```

Para probar el motor en el PC (sin aislamiento: úsalo solo con archivos de confianza):

```bash
./gradlew :engine:cli:run --args="/ruta/proyecto.zip"                      # un registro por línea
./gradlew :engine:cli:run --args="/ruta/proyecto.rar --password X --json"  # los mismos registros en JSON
./gradlew :engine:cli:run --args="/ruta/proyecto.zip --informe informe.html" # además, el informe en HTML (o .json)
```

El APK de prueba de cada ejecución del CI se puede descargar en GitHub → *Actions* → ejecución → artefacto
`funcode-analyzer-debug-apk`.

### Firma de publicación

El APK de publicación se firma con una clave propia que **nunca** se sube al repositorio. Hay dos formas de
indicarla: con un archivo `keystore.properties` en la raíz (ignorado por git), con las claves `storeFile`,
`storePassword`, `keyAlias` y `keyPassword`, o con las variables de entorno `FUNCODE_KEYSTORE_FILE`,
`FUNCODE_KEYSTORE_PASSWORD`, `FUNCODE_KEY_ALIAS` y `FUNCODE_KEY_PASSWORD`. La clave de `keystore/debug.keystore`
es solo de depuración y es pública a propósito.

### Dependencias

Las dependencias se verifican por SHA-256 (`gradle/verification-metadata.xml`). Tras añadir o actualizar una:

```bash
./gradlew --write-verification-metadata sha256 test lint assembleDebug assembleDebugAndroidTest :app:assembleRelease :native:unrar:assembleRelease :native:treesitter:assembleRelease :engine:cli:installDist
```

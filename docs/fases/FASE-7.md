# Fase 7 — Dependencias

- **Estado:** terminada, pendiente de aprobación
- **Criterio de aceptación:** dependencias directas e indirectas con vulnerabilidades y uso.

## Objetivo

Saber qué dependencias instala de verdad el proyecto (directas e indirectas, con la versión real cuando hay archivo de
bloqueo), cuáles tienen **vulnerabilidades conocidas**, si el código **las usa**, cuánto depende de ellas y si el
entorno, el framework o la base de datos siguen **con soporte**. Sin ejecutar nada, sin enviar el código ni la lista de
dependencias a ningún servicio, y diciendo lo que no se puede saber. Decisiones en el ADR 0010.

## Qué se hizo

| Módulo | Contenido |
|--------|-----------|
| `engine:dependencies` | **Archivos de bloqueo** de 14 formatos: `package-lock.json` y `npm-shrinkwrap.json` (v1–v3, con la resolución de `node_modules` anidados), `yarn.lock` (clásico y Berry), `pnpm-lock.yaml` (v5, v6 y v9), `composer.lock`, `Pipfile.lock`, `poetry.lock`, `uv.lock`, `Cargo.lock`, `Gemfile.lock`, `packages.lock.json` (NuGet), `pubspec.lock`, `go.mod` (`// indirect`) y `gradle.lockfile`. De cada paquete: versión exacta, si es directo, de desarrollo y quién lo requiere. Los paquetes del propio repositorio (enlaces, *workspaces*, rutas locales) no cuentan. Un archivo dañado o truncado se dice en la cobertura. |
| `engine:vulndb` (nuevo) | **Índice `.fcv`** de los volcados de OSV y de endoflife.date: escritura en el teléfono, lectura por posiciones y **verificación SHA-256 al abrirlo**. **Comparadores de versión** de SemVer, PEP 440, Maven, NuGet, Composer y RubyGems; algoritmo de **rangos de OSV**; **CVSS 3.x** calculado del vector; nombres normalizados (PEP 503…). Los volcados se tratan como comprimidos no confiables. |
| `engine:audit` | Etapa **`dependency-audit`**: empareja bloqueo y manifiesto, fusiona módulos, calcula **cadenas** (`express › body-parser › qs`), **uso real** con las importaciones de la 5A, **criticidad** con sus motivos, **vulnerabilidades** con certeza y **fin de soporte** de entornos, frameworks, bases de datos y herramientas (Node.js, Python, PHP, Java, .NET, Django, Spring, PostgreSQL, Gradle, Unity, Godot…, desde `.nvmrc`, `engines`, Dockerfile, `docker-compose`, `TargetFramework`, `jvmToolchain`…). Hallazgos de seguridad nuevos: **FC-DEP-01** (dependencia vulnerable), **FC-DEP-02** (paquete malicioso), **FC-EOL-01/02** (sin soporte / lo pierde pronto), con el cambio recomendado por ecosistema. |
| `engine:api` | Esquema de resultados **v8**: `resolved_dependency`, `end_of_life` y `dependency_summary`, validados en el proceso principal. |
| `analyzer-service` | Contrato **v4**: el índice viaja al proceso aislado como **descriptor de solo lectura**; el aislado lo verifica antes de usarlo. |
| `app` | **Permiso `INTERNET` solo para el actualizador** (el proceso de análisis sigue sin red). **Actualizador**: HTTPS a dos servidores, con consentimiento, en servicio en primer plano, sustitución atómica. **Ajustes → Bases de vulnerabilidades**: qué hay descargado y de qué fecha, ecosistemas con su tamaño, fin de soporte, progreso, detener, eliminar y fuentes. **Pantalla Dependencias**: totales y estado de la base, filtros (directas, indirectas, con vulnerabilidades, uso, ecosistema, búsqueda), lista por riesgo y **ficha** de cada dependencia; fin de soporte; se abre desde Seguridad, el informe y la ficha de cada manifiesto o archivo de bloqueo en el Explorador. |

### La ficha de cada dependencia

| Campo | Contenido |
|-------|-----------|
| Versión | declarada (`^4.17.15`), evaluada (`4.17.15`) y **de dónde sale**: archivo de bloqueo, versión fija del manifiesto, mínima del rango o desconocida |
| Ubicación | manifiesto y línea, archivo de bloqueo y línea, y otros módulos con la misma versión (se abren en el Explorador) |
| Quién la introduce | si es indirecta, hasta 3 cadenas desde una directa y cuántas hay |
| Uso en el código | usada (con los archivos y líneas que la importan), sin uso visto, herramienta de desarrollo, indirecta o no analizado, con lo que significa cada uno |
| Criticidad | alta, media o baja, con sus motivos |
| Vulnerabilidades | identificador (GHSA, PYSEC, MAL…) y alias (CVE), severidad y puntuación CVSS, resumen, **certeza** (confirmada con versión exacta, posible con la mínima de un rango), versiones que la corrigen, fecha y referencia de OSV. Un paquete malicioso lo dice expresamente |

## Pruebas ejecutadas

### En este entorno (JVM), todas correctas

| Módulo | Pruebas | Qué cubren |
|--------|---------|------------|
| `engine:vulndb` (nuevo) | 8 | **Comparadores** con todos los pares ordenados: SemVer (prelanzamientos, `v`, `+incompatible`, pseudo-versiones de Go), PEP 440 (dev, a, b, rc, post, épocas, `1.0` = `1.0.0`), Maven (`beta9`, `rc1`, `SNAPSHOT`, `Final`), Composer, NuGet y RubyGems; una versión que no se entiende no se decide. **Rangos de OSV**: los dos rangos de Log4Shell y el hueco entre ellos, `last_affected`, lista de versiones, paquete malicioso (todas). **CVSS 3.x** (10,0, 9,8, 5,5, 6,1, 0; el 4.0 no se inventa). **Índice**: se escribe, se verifica y se consulta (PEP 503, avisos retirados y rangos `GIT` fuera, alias); uno dañado, truncado, vacío o ajeno se rechaza; un volcado con entradas enormes o mal formadas no rompe la construcción y los temporales se borran. |
| `engine:dependencies` | 15 | Las 10 anteriores y 5 de **archivos de bloqueo**: npm v3 con anidados (Node usa su `ms` propio), desarrollo, enlaces del propio repositorio fuera; yarn clásico y Berry; pnpm v6 con quién requiere a quién; composer, Pipfile, poetry, uv y Cargo (el propio paquete fuera); Gemfile (plataforma quitada), NuGet, pubspec (el SDK fuera), go.mod y Gradle; archivos dañados o dentro de `node_modules`. |
| `engine:audit` | 10 | Las 8 anteriores y 2 de **dependencias** con el pipeline real y una base con avisos reales de OSV: directas e indirectas con su cadena (`express › body-parser › qs`), versión del bloqueo, CVSS calculado, uso real (lodash y express usados, react no visto, minimist de desarrollo), Log4Shell confirmado, PyYAML con alias fusionados, requests "posible" por rango, paquete malicioso crítico, fin de soporte de Node.js 14, Python 3.6, PostgreSQL 10 y Django 2.2 (y del mínimo de `engines`), **la nota de los productos sin datos** (React, Express, Log4j), resumen coherente y fichas completas; **sin base**: nada inventado y la nota que lo dice; **base dañada**: rechazada por la suma de comprobación. |
| `engine:api` | 33 | Con el esquema v8: los tres registros nuevos van y vuelven sin cambios y se rechazan si superan sus límites o los totales no cuadran. |
| `app` (Robolectric) | 37 | Las 30 anteriores y 7 nuevas. **Actualizador** (descargador simulado, sin red): solo pide los volcados elegidos y endoflife.date, construye e instala el índice verificable, anota los productos que fallan y recuerda la elección; si el servidor falla a mitad, si la descarga es demasiado grande, si se cancela o si falta espacio, **la base anterior queda intacta** y no queda nada temporal; una sola actualización a la vez; un estado que no coincide con el índice no se da por bueno; el descargador **solo admite HTTPS a los dos servidores** (ni HTTP, ni otro servidor, puerto o credenciales en la URL). **Recorrido de la interfaz**: Ajustes sin base → elegir npm → el diálogo de consentimiento aparece **antes de cualquier descarga** → base instalada y mostrada; análisis con la base (lodash usada y confirmada, paquete malicioso, Node.js 14 sin soporte, FC-DEP-02 en Seguridad); Seguridad → Dependencias, filtro de indirectas, ficha de `qs` con su cadena y versión corregida, búsqueda de lodash con el archivo que la importa y apertura en el Explorador; **sin base**: el informe lo dice, no inventa vulnerabilidades ni fin de soporte, **analizar no se conecta** y la ficha del archivo de bloqueo lleva a sus dependencias. |
| Resto | 106 | `engine:relations` 11, `engine:languages` 25, `engine:pipeline` 6, `engine:technologies` 5, `engine:inventory` 14, `engine:ingest` 36, `engine:core` 10, `data` 18 (sin cambios), `analyzer-service` 1. |
| **Total** | **239** | **0 fallos** |

Además: **Android Lint sin ningún hallazgo** (avisó de que el espacio libre debía consultarse con
`StorageManager.getAllocatableBytes`, que cuenta la caché que el sistema puede liberar: corregido), APK de
publicación con R8 correcto y dependencias verificadas en modo estricto (la Fase 7 no añade ninguna dependencia
externa).

**Prueba con un proyecto real** (este repositorio, con la base real de OSV de los 9 ecosistemas, unos 58 MB, y los 48
productos de endoflife.date): 45 dependencias directas en 16 archivos de Gradle; **0 vulnerabilidades** (son las
versiones actuales); 37 usadas, 7 herramientas de compilación o pruebas y 1 **sin uso visto**, `androidx.sqlite`, que
es correcto: solo la usa el código que genera Room al compilar, que no está en el repositorio; 7 sin versión conocida
(vienen de los BOM de Compose y de JUnit) y el informe lo dice; Gradle 9.8.2, Java 17 y 21 y Kotlin 2.4 con soporte. El análisis
completo tarda 6,3 s en el PC y la etapa de dependencias 49 ms.

**Proyecto de demostración vulnerable** (npm con archivo de bloqueo, Python, Maven, Dockerfile y docker-compose; el de
las capturas): 11 directas y 3 indirectas, **10 paquetes con 75 avisos** (14 críticos), entre ellos Django 2.2.0 (43),
Log4j 2.14.1 (Log4Shell), PyYAML 5.3, lodash 4.17.15, `qs` y `body-parser` por `express`, el paquete malicioso
`flatmap-stream` y `requests` como "posible" por venir de un rango; fin de soporte de Django 2.2, Node.js 12 y 14,
Python 3.6 y PostgreSQL 10. Cada uno en su archivo y línea.

**Construcción de la base en el PC** (datos reales del 5 de noviembre de 2026): 8 ecosistemas sin npm, 19,6 MB en
unos 6,5 s; con npm (226 000 paquetes), 57,6 MB en unos 19 s. En el teléfono será más lento (estimación: varios
minutos con npm), por eso va en un servicio en primer plano con su progreso.

### En emulador (CI de GitHub Actions)

Android 8.0 (API 26), 15 (API 35) y 16 (API 36). Nuevo en esta fase:

- `baseDeVulnerabilidadesPorDescriptorAlProcesoAislado`: el actualizador construye la base **en el propio dispositivo**
  (con un descargador sin red que sirve avisos reales de OSV y fechas de Node.js) y consulta el espacio libre al
  sistema (`StorageManager`); el análisis en el **proceso aislado** recibe el índice por descriptor y devuelve lodash
  4.17.15 con su aviso confirmado y usada en el código, el paquete malicioso `flatmap-stream` (FC-DEP-02) y Node.js 14
  sin soporte, con todos los registros válidos. Después se cambia un byte del índice sin cambiar su tamaño: el proceso
  aislado lo **rechaza por la suma de comprobación**, el informe dice "no utilizable" y no inventa vulnerabilidades.
- `zipCompletoEnElProcesoAislado`: sin base descargada, la dependencia se lista, no hay vulnerabilidades y el resumen
  explica que no hay base.
- **El proceso aislado sigue sin red aunque la app ya tenga el permiso de Internet**: el diagnóstico de aislamiento
  intenta abrir una conexión desde el proceso aislado y exige que se deniegue (pruebas de aislamiento y recorrido de
  extremo a extremo, que lo ejecuta dentro de la app).

**Resultado: 14 de 14 correctas en las tres versiones** (ejecución n.º 30: 3 de aislamiento, 3 de cifrado, 3 de
migración, 4 de análisis completo y 1 recorrido de extremo a extremo), y el trabajo de compilación, pruebas JVM y Lint
también correcto. Antes:

- La n.º 27 (solo el motor) falló porque una prueba en emulador esperaba la nota "Fase 7" pendiente, que esta fase
  elimina; la n.º 28 se canceló sola al empezar la n.º 29.
- La n.º 29 falló en la prueba nueva: lanzaba el segundo análisis mientras el primero aún se guardaba y el coordinador
  respondía "ocupado". Todo lo anterior de esa prueba (base construida en el dispositivo, descriptor, vulnerabilidades,
  paquete malicioso, fin de soporte) ya pasaba en las tres versiones.
- En el primer intento de la n.º 30 falló en Android 15 la prueba de extremo a extremo **de la Fase 1** (la sesión no
  se abrió en 30 s tras pulsar "Crear acceso"); pasó al repetir solo ese trabajo y había pasado en las tres versiones
  en la n.º 29. No toca nada de esta fase: se anota como prueba intermitente a vigilar.

## Capturas

Ajustes → Bases de vulnerabilidades (estado, ecosistemas con su tamaño, diálogo de consentimiento); panel de
dependencias del informe; pantalla Dependencias (totales, estado de la base, filtros); ficha de Django con sus
vulnerabilidades; ficha de `qs` con las cadenas que la introducen; fin de soporte; ficha del archivo de bloqueo en el
Explorador.

## Incidentes y cómo se resolvieron

1. **Kotlin aparecía como "sin uso visto"** en este repositorio: los artefactos Maven no se llaman como sus paquetes
   Java. Se añadió la correspondencia de los artefactos más comunes y de `kotlinx-*`.
2. **La misma dependencia se repetía por cada módulo de Gradle.** Ahora se fusiona (misma familia, nombre, versión y
   tipo) y la ficha dice en qué otros módulos aparece.
3. **`engines` de `package.json` en una sola línea no se leía.** El lector de entornos usa ahora el lector JSON seguro.
4. **Un producto sin datos de fin de soporte se omitía en silencio.** Ahora el informe lo dice ("Fin de soporte de
   React: la base descargada no tiene sus datos").
5. **Botón de Ajustes inalcanzable en pantallas estrechas**: estaba en una fila con desplazamiento horizontal dentro
   de la pantalla desplazable; ahora los botones se reparten en varias líneas.
6. **El enlace a Dependencias aparecía en Seguridad aunque el proyecto no tuviera ninguna**; ahora solo aparece si hay
   dependencias o versiones con fin de soporte.
7. **Fechas en inglés y concordancias** («Nov 5, 2026», «1 paquetes maliciosos») en las capturas: las fechas y los
   números usan ahora siempre el formato español (la app solo está en español) y las frases no dependen del plural;
   las notas del motor concuerdan en singular.
8. **En las pruebas JVM la consulta de espacio libre se bloqueaba** (Robolectric no implementa `StorageManager`): se
   inyecta; las pruebas JVM usan un valor fijo y la prueba en emulador comprueba la consulta real del sistema.

## Limitaciones conocidas (honestidad del resultado)

- **Solo vulnerabilidades publicadas en OSV** y hasta la fecha de la base descargada (cada informe guarda la fecha de
  los datos y avisa si tenían más de 7 días). Que no aparezca ninguna no garantiza que no las haya.
- **Sin archivo de bloqueo**, la versión es la mínima del rango: las vulnerabilidades son "posibles" y no hay
  dependencias indirectas. POM padres, BOM y catálogos externos al ZIP no se resuelven (versión desconocida).
- **Ecosistemas sin base de OSV** (CocoaPods, CMake, Unity, Unreal, Godot) y bibliotecas C/C++ copiadas a mano: se
  listan, pero sus vulnerabilidades no se evalúan; el informe lo dice.
- **Uso real**: solo importaciones estáticas de JavaScript/TypeScript, Python, Java/Kotlin, PHP, C# y Dart. Go, Rust y
  Ruby quedan como "no analizado". Lo cargado por configuración, reflexión o complementos aparece como "sin uso visto",
  y la ficha pide comprobarlo antes de quitar nada.
- **No se comprueba si el código vulnerable es alcanzable** (qué función de la librería se llama): una dependencia
  usada con una vulnerabilidad puede no ser explotable en este proyecto.
- **La base no se actualiza sola**: solo cuando se pide en Ajustes (decisión de privacidad, ADR 0010).

## Errores pendientes

Ninguno conocido en esta fase. A vigilar: la prueba de extremo a extremo de la Fase 1 falló una vez en el emulador de
Android 15 (la sesión no se abrió en 30 s) y pasó al repetirla; si vuelve a ocurrir, se revisará su tiempo de espera.

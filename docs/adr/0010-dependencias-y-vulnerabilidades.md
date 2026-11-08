# ADR 0010 — Dependencias, vulnerabilidades conocidas y fin de soporte (Fase 7)

- **Estado:** aceptada (2026-11-05, Fase 7)

## Contexto

La Fase 4 listaba las dependencias **declaradas** en los manifiestos. La Fase 7 tiene que decir cuáles se instalan de
verdad (directas e indirectas), si tienen **vulnerabilidades conocidas**, si el proyecto **las usa**, cuánto depende
de ellas y si el entorno, el framework o la base de datos siguen **con soporte**. Todo sin ejecutar nada, sin enviar
el código a ningún servicio y diciendo lo que no se puede saber.

## Decisiones

1. **Archivos de bloqueo para las versiones reales.** `engine:dependencies` lee `package-lock.json`/`npm-shrinkwrap`
   (v1–v3, con la resolución de `node_modules` anidados de Node), `yarn.lock` (clásico y Berry), `pnpm-lock.yaml`
   (v5, v6 y v9), `composer.lock`, `Pipfile.lock`, `poetry.lock`, `uv.lock`, `Cargo.lock`, `Gemfile.lock`,
   `packages.lock.json` de NuGet, `pubspec.lock`, `go.mod` (`// indirect`) y `gradle.lockfile`. Cada archivo de
   bloqueo se empareja con el manifiesto de su carpeta y su familia: la versión del bloqueo manda (**exacta**), y sin
   bloqueo se usa la del manifiesto (**exacta** si es fija, **mínima del rango** si es un rango, **desconocida** si viene
   de un catálogo, un BOM o una variable). Las indirectas llevan **hasta 3 cadenas** (`express › body-parser › qs`)
   calculadas con una búsqueda en anchura sobre el grafo del bloqueo. La misma dependencia con la misma versión en
   varios módulos se fusiona en una (con "también en").
2. **Base de vulnerabilidades local: OSV.** Se usan los volcados públicos de OSV.dev
   (`osv-vulnerabilities.storage.googleapis.com/<ecosistema>/all.zip`) de npm, PyPI, Maven, Packagist, NuGet, Pub, Go,
   crates.io y RubyGems. Se descartó consultar la API de OSV por paquete: enviaría la lista de dependencias del
   proyecto a un tercero (incumpliría "no enviar el código del usuario a servicios externos sin autorización") y no
   funcionaría sin conexión. Con los volcados completos, lo que se pide es igual para todo el mundo y no revela nada
   del proyecto.
3. **Índice propio `.fcv` en `engine:vulndb`.** Los volcados (cientos de miles de JSON) se convierten en el teléfono a
   un índice binario: cabecera (ecosistemas con su fecha y totales, datos de fin de soporte), bloques por paquete con
   los avisos ya reducidos (identificador, alias, resumen, severidad, puntuación CVSS 3.x calculada del vector, CWE,
   rangos y versiones afectadas, versiones corregidas, si es malicioso) y un directorio ordenado por huella FNV-1a de
   64 bits del nombre normalizado (PEP 503 en PyPI, `_`→`-` en crates.io), con **suma SHA-256 al final**. Se lee por
   posiciones con un `FileChannel` (búsqueda binaria en el directorio, sin cargarlo entero) y se **verifica entero al
   abrirlo**: un índice dañado, truncado o ajeno se rechaza y el informe lo dice. Los volcados se tratan como
   comprimidos no confiables (entradas, tamaño por entrada medido al leer, tamaño total). Los avisos retirados y los
   rangos `GIT` no se guardan. 8 ecosistemas sin npm ocupan unos 20 MB; con npm, unos 60 MB.
4. **Comparación de versiones por ecosistema.** SemVer (npm, crates.io, Go con pseudo-versiones, Pub), PEP 440 (PyPI,
   con épocas, pre, post y dev), Maven (calificadores `alpha`, `beta`, `rc`, `SNAPSHOT`, `Final`), NuGet, Composer
   (`p`, `RC`, `v`) y RubyGems. El algoritmo de rangos de OSV (`introduced`, `fixed`, `last_affected`, lista de
   versiones) devuelve **sí, no o "no se puede decidir"** (una versión que no se entiende nunca se da por segura ni por
   vulnerable). Los avisos con los mismos alias (GHSA, PYSEC, CVE) se fusionan en uno. Certeza: **confirmada** con
   versión exacta, **posible** con la mínima de un rango. Severidad: la etiqueta de la base o la calculada del CVSS 3.x;
   sin ninguna, media; un paquete malicioso (`MAL-…`), crítica.
5. **Uso real con las relaciones de la 5A.** La etapa de relaciones deja, por paquete externo, los archivos y líneas
   que lo importan. Se empareja por ecosistema: nombre de npm y de Pub, alias de Python (`yaml` → PyYAML), paquetes
   Java de los artefactos Maven más comunes y `kotlinx-*`, proveedor del *namespace* en PHP y espacio de nombres en
   .NET. Go, Rust y Ruby quedan como **no analizado** (sus importaciones aún no se emparejan). Estados: **usada**,
   **sin uso visto** (directa de ejecución que nadie importa: puede cargarse por configuración, se dice), **herramienta
   de desarrollo**, **indirecta** y **no analizado** (lenguaje sin importaciones). La **criticidad** (alta, media, baja)
   suma motivos explicados: directa, usada en muchos archivos, framework principal, maneja datos sensibles
   (autenticación, criptografía, bases de datos, serialización), de ejecución o de desarrollo.
6. **Fin de soporte con endoflife.date.** Se descargan los ciclos de 48 productos (Node.js, Python, PHP, Ruby, Go,
   .NET, Java, Kotlin, frameworks web y móviles, bases de datos, servidores…). Las versiones salen de `.nvmrc`,
   `.python-version`, `runtime.txt`, `engines`, `requires-python`, `TargetFramework`, `FROM` de Dockerfile, imágenes de
   `docker-compose`, Gradle *wrapper*, Unity, Godot, `jvmToolchain`, `maven.compiler.release` y las dependencias
   directas de frameworks. Estados: **sin soporte**, **lo pierde en 6 meses**, **con soporte** y **ciclo desconocido**.
   Si un producto detectado no tiene datos en la base, se dice ("Fin de soporte de React: la base descargada no tiene
   sus datos") en lugar de darlo por soportado.
7. **Hallazgos de seguridad.** Las dependencias vulnerables (`FC-DEP-01`, CWE-1395, OWASP A06), los paquetes
   maliciosos (`FC-DEP-02`, CWE-506, A08, crítico) y el fin de soporte (`FC-EOL-01`/`02`, CWE-1104) entran en la
   auditoría de la Fase 6 con su ficha: archivo y línea (bloqueo o manifiesto), cadena por la que llega, versión que
   la corrige, cambio recomendado por ecosistema (incluido forzar una indirecta con `overrides`/`resolutions`) y
   enlaces de referencia como texto. Las de desarrollo se rebajan con el motivo.
8. **Red solo en el proceso principal y solo cuando el usuario lo pide.** La app declara por primera vez el permiso
   `INTERNET`, únicamente para el actualizador. El proceso de análisis aislado (`isolatedProcess`) sigue sin red aunque
   la app tenga el permiso (lo comprueba el diagnóstico de aislamiento en emulador). El actualizador:
   - se ejecuta **solo al pulsar Actualizar** en Ajustes, tras un **diálogo de consentimiento** que dice qué servidores
     se contactan, cuánto se descarga y que no se envía nada del proyecto; no hay actualizaciones automáticas ni en
     segundo plano (se avisa cuando la base tiene 7 días o más);
   - solo conecta con `osv-vulnerabilities.storage.googleapis.com` y `endoflife.date`, solo por HTTPS, con las
     autoridades del sistema (`network_security_config`), sin seguir redirecciones, sin cookies ni identificadores, con
     tiempos de espera y tamaño máximo medido al leer (1 GB por volcado, 2 MB por producto);
   - descarga, construye el índice en una carpeta de trabajo, lo verifica y lo **sustituye de forma atómica**; si algo
     falla o se cancela, la base anterior queda intacta y los temporales se borran;
   - corre en un servicio en primer plano (`dataSync`) con notificación de progreso para que Android no lo corte.
9. **El índice viaja como descriptor de solo lectura.** El contrato con el proceso aislado pasa a la **versión 4**:
   `start(config, archive, password, vulnDb, results, callback)`. El proceso principal abre el índice en modo lectura y
   el aislado lo verifica (SHA-256) antes de usarlo; si no hay base o no supera la verificación, el análisis sigue y el
   informe dice qué no se pudo evaluar. Analizar nunca usa la red.
10. **Almacenamiento.** El índice, su descripción (`estado.json`) y la elección de ecosistemas
    (`preferencias.json`) van en `noBackupFilesDir/vulnerabilidades`, fuera de las copias de seguridad y **sin cifrar**:
    solo contienen datos públicos y la lista de ecosistemas elegidos, no código ni resultados. Así una descarga larga
    puede terminar aunque la app se bloquee mientras tanto. El borrado total de datos también los elimina.
11. **Esquema de resultados v8.** Registros `resolved_dependency`, `end_of_life` y `dependency_summary`, validados en
    el proceso principal (listas acotadas: 3 cadenas, 5 archivos de uso, 50 vulnerabilidades, 8 alias, 10 versiones
    corregidas; totales coherentes con la severidad).

## Consecuencias

- Las vulnerabilidades dependen de la fecha de la base: el informe guarda la fecha de los datos de cada ecosistema y
  avisa si tenían más de 7 días al analizar.
- Sin archivo de bloqueo, las versiones son la mínima del rango: los resultados son "posibles" y el informe lo dice.
- Ecosistemas sin base de OSV (CocoaPods, CMake, Unity, Unreal y Godot) se listan, pero sus
  vulnerabilidades no se evalúan; el informe lo dice.
- La primera descarga con npm son unos 220 MB y tarda varios minutos en el teléfono (se elige expresamente; por
  defecto se descargan los otros 8 ecosistemas, unos 87 MB).

# Fase 3 — Explorador y estructura

**Estado: terminada, pendiente de tu aprobación.** Fecha: 2026-11-03.

## Objetivo

Que de cada proyecto analizado se pueda navegar el árbol completo y consultar la ficha de cualquier archivo: su
función (con la evidencia y la certeza de cada afirmación), los puntos de entrada del proyecto, sus subproyectos, los
archivos que posiblemente sobran y una copia del código con los posibles secretos ocultados.

## Qué se hizo

| Módulo | Contenido |
|--------|-----------|
| `engine:inventory` (nuevo) | **Funciones de cada archivo** (16: punto de entrada, configuración, dependencias, construcción, base de datos, autenticación, servidor/API, interfaz, código fuente, pruebas, documentación, recursos, multimedia, datos, binario, posiblemente innecesario), deducidas del nombre, la extensión, la carpeta y patrones de texto en el contenido de 11 lenguajes; cada una con su evidencia (archivo, línea, motivo) y su certeza. **Subproyectos** por sus manifiestos (Gradle, Android, Maven, npm, Python, Composer, Dart/Flutter, .NET, Go, Cargo, Ruby, CocoaPods, CMake, Unity, Unreal, Godot). **Puntos de entrada**: los declarados en manifiestos (AndroidManifest, package.json, angular.json, index.html, project.godot, configuración de compilación de Unity, DefaultEngine.ini de Unreal), los encontrados en el código (main en Java, Kotlin, Python, C/C++, C#, Go, Rust, Dart, Swift; arranque de servidores Express, NestJS, Flask, FastAPI, Django, Spring Boot, Ktor, ASP.NET; montaje de apps React, Vue y Angular) y los de convenciones (Next.js, Create React App, React Native, Nuxt, SvelteKit, `public/index.php`, `Program.cs`). **Posiblemente innecesarios**: copias por el nombre, duplicados exactos, artefactos compilados, logs, copias de seguridad, comprimidos olvidados, vacíos, configuración local o del editor. **Copia redactada** del código para el visor. Lector XML y lector JSON seguros. |
| `engine:security` (nuevo) | **Redactor de secretos**: claves privadas, claves de AWS, tokens de GitHub, GitLab y Slack, claves de Google, Stripe, SendGrid, OpenAI y Anthropic, JWT, claves de Firebase, contraseñas en cadenas de conexión y valores asignados a nombres sensibles en código, JSON, YAML, `.env`, `.properties` y XML. Muestra como máximo 4 + 2 caracteres. |
| `engine:ingest` | Punto de extensión para examinar el contenido en el mismo recorrido del comprimido; un fallo al examinar un archivo no detiene el resto. |
| `engine:api` | Esquema de resultados v3: ficha, subproyecto, punto de entrada, secreto ocultado, fragmento de la copia y resumen del inventario. El proceso principal valida además que ningún secreto muestre más de 6 caracteres. |
| `data` | BD versión 3 (tabla de la copia del código, migración automática desde la 2). |
| `app` | Pantalla **Explorador**: árbol con las carpetas excluidas, totales por carpeta, filtros por función, búsqueda, lista de puntos de entrada y de subproyectos, cambio de análisis, **ficha del archivo** (ubicación, extensión, tamaño, tipo y codificación, líneas, subproyecto, huella, funciones con certeza y evidencia, puntos de entrada, archivos relacionados conocidos, secretos ocultados) y **visor de código** con números de línea, líneas con secretos marcadas y caracteres invisibles o bidireccionales visibles. Dos paneles en tablet. El informe muestra la estructura y lleva al Explorador. **Ajustes → Análisis → Guardar copia del código** (activado por defecto). Copia temporal cifrada con clave efímera hasta guardarse en la BD. |

## Pruebas ejecutadas

### En este entorno (JVM), todas correctas

| Módulo | Pruebas | Qué cubren |
|--------|---------|------------|
| `engine:inventory` | 13 | Proyecto Android (subproyectos, actividad del lanzador, clase Application, componentes exportados y no exportados, componente de una biblioteca que no está en el comprimido, funciones con evidencia y línea, posibles innecesarios, secreto ocultado y copia de la misma longitud). Proyecto web/Node (main, bin declarado que no existe, script start, servidor que escucha ignorando la línea comentada, arranque cargado por index.html, archivos generados sin copia, `.env` redactado). Escenas de Godot, Unity y Unreal y autoloads. main en 6 lenguajes sin falsos positivos en comentarios. Copia desactivada, límite de la copia y archivos demasiado grandes. Troceado de un archivo grande sin perder nada. Nombres hostiles y evidencias largas. XML: sin expansión de entidades (XXE y *billion laughs*), espacios de nombres, CDATA, comentarios, documentos mal formados o demasiado profundos. JSON con anidamiento excesivo. Reglas de nombre sin falsos amigos. |
| `engine:security` | 8 | Formatos conocidos, clave privada con y sin marca de final, asignaciones en 13 tipos de archivo, ausencia de falsos positivos (referencias a variables, textos de interfaz, nombres parecidos), longitud conservada, solo ASCII visible y texto hostil de 2 MB en menos de 10 s. |
| `engine:api` | 28 | Las anteriores más los registros nuevos, el rechazo de un secreto que muestra demasiado y la validación de las opciones del inventario. |
| `engine:ingest` | 36 | Las de la Fase 2 (sin cambios de comportamiento). |
| `engine:core` | 10 | Las de la Fase 2. |
| `data` | 18 | Las de la Fase 1. |
| `analyzer-service` | 1 | Las de la Fase 1. |
| `app` (Robolectric) | 25 | Las anteriores más: la copia del código se guarda sin secretos y el temporal cifrado se borra; copia desactivada en Ajustes; un motor defectuoso que repite fragmentos no impide guardar; almacén cifrado (ida y vuelta, alteración y truncado detectados, huérfanos); modelo del árbol (excluidas, filtros, búsqueda, relaciones); **recorrido completo del Explorador** (filtro, ficha, secreto enmascarado y visor sin el secreto en ninguna parte de la pantalla). |
| **Total** | **139** | **0 fallos** |

Además: **Android Lint sin ningún hallazgo**, APK de publicación con R8 correcto, y el motor se probó con un proyecto
real (este mismo repositorio: 320 archivos, 14 subproyectos, 4 puntos de entrada correctos y 0 archivos sin clasificar).

### En emulador (CI de GitHub Actions)

Android 8.0 (API 26), 15 (API 35) y 16 (API 36). Además de las de fases anteriores:
- `FullAnalysisInstrumentedTest`: el ZIP se analiza en el proceso aislado y produce funciones, puntos de entrada y
  una copia redactada en la que el secreto no aparece; el almacén temporal cifrado se borra.
- `MigrationInstrumentedTest`: la BD de la Fase 2 migra a la versión 3 sin perder los análisis.

El resultado de la ejecución del CI se indica en el mensaje de entrega de la fase.

### Errores encontrados y corregidos durante la fase

1. Al probar con un proyecto real, el redactor ocultaba textos de la interfaz como "Contraseña actual" en
   `strings.xml`. Ahora un valor con espacios o una etiqueta típica no se consideran secretos (ver ADR 0005).
2. Las asignaciones con anotación de tipo (`val apiKey: String = "…"`, `const token: string = "…"`) no se ocultaban.
3. Los comprimidos de prueba, los `.jar` y las copias de manifiestos y licencias en varios módulos se señalaban como
   posiblemente innecesarios.
4. Las reglas de nombre confundían "Review" con una vista y "latest" con una prueba: ahora comparan palabras completas.
5. El almacén cifrado de la copia no informaba como "copia ilegible" un truncado posterior al primer fragmento.
6. El modelo del árbol inicializaba sus carpetas en el orden equivocado (habría fallado al abrir el Explorador).
7. Si el motor enviaba dos veces el mismo fragmento de la copia, la restricción de la BD impedía guardar el análisis
   completo en cada intento. Ahora los fragmentos repetidos se ignoran (lo detectó una prueba nueva).
8. El Explorador abierto desde el menú se quedaba en el primer análisis que mostró; ahora sigue al más reciente
   hasta que eliges otro.

## Cómo probarlo en tu teléfono

1. Instala el APK encima del anterior (tus análisis de la Fase 2 se conservan).
2. Analiza de nuevo un proyecto real en **Nuevo análisis**. Al terminar, el informe muestra el apartado
   **Estructura**; pulsa **Abrir en el Explorador**.
3. Revisa **Puntos de entrada**: en una app Android debe aparecer la pantalla de inicio con certeza "Confirmado".
4. Prueba los filtros (Interfaz, Base de datos, Posiblemente innecesario…) y la búsqueda.
5. Abre un archivo: comprueba su función, la evidencia (con línea) y, si es código, pulsa **Ver código**.
6. Si el proyecto tiene alguna clave o contraseña escrita en el código, comprueba que solo ves sus 4 primeros y 2
   últimos caracteres.
7. En **Ajustes → Análisis**, desactiva la copia del código, analiza otra vez y comprueba que el Explorador muestra
   la estructura pero no el contenido.
8. Abre un análisis de la Fase 2 en el Explorador: debe avisar de que no tiene inventario.

## Diferencias respecto a la arquitectura v0.2

- **Lector XML propio** en lugar de `XmlPullParser` (ADR 0005).
- El **redactor de secretos** se adelanta a esta fase porque la copia del código no puede guardarse sin él; la
  auditoría de seguridad completa sigue en la Fase 6.
- La copia del código mientras se guarda **sí** se cifra con clave efímera (borrado criptográfico), con
  `javax.crypto` en lugar de Google Tink.
- El visor aún no tiene **resaltado de sintaxis**: llega con tree-sitter (fases 4–5).

## Errores y limitaciones pendientes

1. Las funciones y los puntos de entrada que salen del contenido son **patrones de texto**: un patrón dentro de una
   cadena puede dar un falso positivo (por eso son "probables"). El análisis sintáctico de las fases 4–5 los precisará.
2. **Secretos**: se reconocen formatos conocidos y valores asignados a nombres sensibles. No se usa entropía; una
   contraseña con espacios asignada en el código no se detecta; la huella para comparar análisis llega en la Fase 9.
3. Los **archivos que nada usa** (huérfanos) solo se pueden detectar con el análisis de relaciones (Fase 5).
4. **Archivos relacionados**: por ahora solo duplicados, quien declara un punto de entrada y los `.meta` de Unity.
5. Con proyectos de decenas de miles de archivos, abrir el Explorador puede tardar unos segundos: el proceso principal
   reconstruye el inventario desde la BD. Se optimizará en la Fase 10.
6. El visor no busca dentro del archivo y recorta las líneas de más de 2 000 caracteres.
7. Algunos casos no se resuelven: escenas de Godot declaradas con `uid://`, configuración de compilación de Unity
   guardada en binario, rutas de puntos de entrada construidas en tiempo de ejecución. Se informan sin ruta.
8. Pendientes de fases anteriores: biometría probada solo con simulación, APK de publicación sin firma propia,
   contraseñas en `String` dentro de Compose, detekt/ktlint y R8 sin probar en un dispositivo.

## Siguiente fase

**Fase 4 — Lenguajes y tecnologías**: gramáticas tree-sitter, detección de lenguajes y conteo de líneas de código,
comentarios y en blanco, motor de reglas declarativas para frameworks, bases de datos y servicios externos, con la
evidencia y la confianza de cada tecnología.

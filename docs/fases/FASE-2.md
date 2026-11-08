# Fase 2 — Carga y extracción de ZIP y RAR

**Estado: terminada y aprobada.** Fecha: 2026-11-03.

## Objetivo

Que la app reciba un proyecto comprimido en ZIP o RAR, lo identifique, lo compruebe y lea todo su contenido dentro del
proceso aislado **sin extraer nada a disco y sin ejecutar nada**, con todas las protecciones contra archivos
maliciosos, y muestre y guarde (cifrado) qué se leyó, qué se excluyó y qué no se pudo analizar y por qué.

## Qué se hizo

| Módulo | Contenido |
|--------|-----------|
| `engine:ingest` (nuevo) | **Identificación por firma** (no por extensión): ZIP, RAR4, RAR5; se rechazan con su motivo 7z, tar, gzip, ejecutables, etc. **Validación de nombres**: rutas absolutas, `..`, letras de unidad, rutas UNC, caracteres de control, nombres reservados de Windows, longitud y profundidad máximas, normalización Unicode NFC, colisiones de mayúsculas/minúsculas y rutas repetidas; los nombres engañosos (caracteres invisibles, de dirección del texto, homógrafos) se marcan y se muestran escapados. **ZIP** con Commons Compress más comprobaciones propias previas (registro final, ZIP64, multidisco, entradas solapadas, cabeceras locales que no coinciden) y **descifrado propio** de ZipCrypto y WinZip AES. **RAR** con UnRAR oficial en modo prueba (sin escribir), con sus límites de memoria y tiempo; RAR multivolumen rechazado. **Lectura con límites aplicados mientras se lee**: tamaño por archivo, total de bytes reales, número de entradas, proporción de compresión por archivo y global (bombas de compresión), sin creer los tamaños declarados. **Exclusiones** configurables (`node_modules`, `build`, `.git`, `vendor`, `Pods`, etc.), las propias de cada motor solo si está su marcador (Unity, Unreal, Godot) y archivos temporales o de sistema (`__MACOSX`, `.DS_Store`, `Thumbs.db`…). **Clasificación** de cada archivo (texto con su codificación, binario, comprimido anidado, vacío), SHA-256 y **detección de duplicados**. Los enlaces simbólicos y duros se registran y **nunca se siguen**; los comprimidos anidados se registran sin expandirse. |
| `native:unrar` (nuevo) | UnRAR 7.3.2 compilado con el NDK para `arm64-v8a`, `armeabi-v7a` y `x86_64` (páginas de 16 KB, STL estática) y para el PC (pruebas). Puente JNI propio: la contraseña se copia y se borra, los datos llegan por bloques de 64 KB, se deniegan los volúmenes adicionales y los diccionarios grandes. Modificación mínima de UnRAR para abrir un descriptor heredado (el proceso aislado no puede abrir rutas). |
| `engine:core` | Atributos compartidos entre etapas con cierre garantizado de recursos y detención controlada de una etapa con un motivo redactado por el motor (nunca texto del proyecto). |
| `engine:api` | Esquema de resultados v2: comprimido, rechazo, archivo, carpeta y archivo excluidos, hallazgo del comprimido, duplicados, resumen y contraseña necesaria, todos validados en el proceso principal. Opciones de ingesta con sus límites validados. |
| `engine:cli` (nuevo) | Herramienta de línea de comandos **solo para pruebas en el PC** (sin aislamiento): `./gradlew :engine:cli:run --args="proyecto.zip [--password X] [--json]"`. |
| `analyzer-service` | El servicio aislado recibe el comprimido como descriptor de solo lectura y la contraseña como `char[]` por Binder (nunca en el JSON), y ejecuta la ingesta. |
| `data` | BD versión 2 (migración automática desde la 1 conservando los diagnósticos): análisis, sus registros y ajustes. Aviso de borrado automático. Contador de desbloqueos de la sesión. |
| `app` | Pantalla **Nuevo análisis**: elegir archivo con el selector del sistema, **"Abrir con…" y "Compartir"** desde otras apps, y **arrastrar y soltar** (tablets, ventanas múltiples, ChromeOS). Importación con límite de tamaño y comprobación de la firma, resumen del comprimido, **exclusiones editables y recordadas**, petición de contraseña si hace falta (con opción de continuar sin ella), **progreso por etapas**, servicio en primer plano con notificación privada para análisis largos, informe de la ingesta (totales, hallazgos agrupados por gravedad, archivos no analizados con su motivo, exclusiones, duplicados, cobertura y limitaciones) y **historial cifrado** con detalle y eliminación. Si la sesión se bloquea durante un análisis, el resultado se guarda al desbloquear. Tras un borrado automático por intentos fallidos, la configuración inicial lo explica (pendiente n.º 4 de la Fase 1). Avisos legales de terceros en Ajustes → Acerca de. |
| CI | Se añade el emulador con **Android 16 (API 36)** a los de Android 8.0 y 15. |

## Pruebas ejecutadas

### En este entorno (JVM), todas correctas

| Módulo | Pruebas | Qué cubren |
|--------|---------|------------|
| `engine:ingest` | 36 | Firma real frente a extensión; nombres válidos, peligrosos y engañosos, y prueba aleatoria de que ninguna ruta aceptada sale del proyecto; exclusiones por nombre, por motor con y sin marcador y opciones del usuario; clasificación y codificación; PBKDF2 con los vectores de la RFC 6070. **ZIP**: proyecto normal; nombres peligrosos, enlaces, rutas repetidas y colisiones; entradas solapadas (bomba no recursiva); bomba de compresión detenida durante la lectura; tamaños declarados falsos; límite total de bytes reales; proporción global anómala; registro final manipulado, multidisco y truncado; zstd y xz sin abortar; cabecera local distinta; ZIP anidado; ZipCrypto, AES-1 y AES-2 sin contraseña, con la correcta y con otra. **RAR** (corpus de libarchive): RAR4 y RAR5 normales, almacenados, sólidos y cifrados; nombres cifrados; enlaces simbólicos y duros; multivolumen rechazado; ZIP dentro de RAR; archivos dañados o diseñados para provocar fallos; apertura por descriptor heredado. Además, todos los registros pasan por el protocolo y el validador del proceso principal. |
| `engine:api` | 24 | Las de la Fase 1, más los registros y opciones nuevos. |
| `engine:core` | 10 | Las de la Fase 1, más la detención controlada de una etapa. |
| `data` | 18 | Las de la Fase 1. |
| `analyzer-service` | 1 | Las de la Fase 1. |
| `app` (Robolectric) | 14 | Las de la Fase 1, más: análisis completo guardado y copia temporal borrada; resultado guardado al desbloquear (también con bloqueo y desbloqueo inmediatos); formato no admitido sin copia temporal; descartar borra la copia; las exclusiones elegidas se recuerdan; recorrido de la pantalla Nuevo análisis. |
| **Total** | **103** | **0 fallos** |

Además: **Android Lint sin ningún hallazgo** en los cuatro módulos Android y el APK de publicación con R8 compila
conservando el puente JNI de UnRAR.

### En emulador (CI de GitHub Actions)

Se ejecutan en **Android 8.0 (API 26), 15 (API 35) y 16 (API 36)**. Las pruebas de la Fase 1 más:

| Prueba | Qué demuestra |
|--------|---------------|
| `FullAnalysisInstrumentedTest` (3) | Un ZIP y dos RAR5 (uno con nombres cifrados) se analizan de verdad en el proceso aislado, con UnRAR nativo leyendo por el descriptor heredado; el RAR con nombres cifrados pide la contraseña y con ella se lee completo. |
| `MigrationInstrumentedTest` (1) | La BD de la Fase 1 migra a la versión 2 sin perder los diagnósticos. |

**Resultado: 11 de 11 correctas en las tres versiones** (ejecución n.º 8 del CI: 3 de aislamiento, 3 de cifrado, 1 de
migración, 3 de análisis completo y 1 recorrido de extremo a extremo).

### Errores encontrados y corregidos durante la fase

1. La sal de WinZip AES-128 tenía una longitud incorrecta (el descifrado fallaba con la contraseña correcta).
2. El resumen contaba como "archivos" también los excluidos.
3. Una ruta como `./C:/…` esquivaba la comprobación de letra de unidad.
4. El puente JNI borraba la contraseña del lado de Java antes de tiempo.
5. Los indicadores del RAR abierto se guardaban en una variable global compartida.
6. Robolectric con Android 16 trae una copia antigua de Commons Compress que provocaba `NoSuchMethodError`
   (ver ADR 0004).
7. R8 fallaba por clases opcionales de Commons Compress (zstd, xz, brotli) que no se incluyen: ahora se ignoran y
   esos métodos se tratan como "compresión no admitida".
8. Si el motor lanzaba un error inesperado, la pantalla se quedaba en "Analizando".
9. Condición de carrera: si la sesión se bloqueaba y desbloqueaba muy deprisa, un resultado pendiente podía no
   guardarse, y dos guardados simultáneos podían chocar. Ahora se reacciona a cada desbloqueo y los guardados son
   de uno en uno.

## Cómo probarlo en tu teléfono

1. Instala el nuevo APK de prueba encima del anterior (se conservan tu acceso y tus diagnósticos).
2. **Menú → Nuevo análisis → Elegir archivo** y selecciona un ZIP o RAR de un proyecto real. Revisa el resumen
   (formato, tamaño, número de entradas) y las **exclusiones**; quita o añade alguna.
3. Pulsa **Analizar**: verás el progreso por etapas. Si sales de la app, la notificación indica que el análisis
   sigue; en la pantalla de bloqueo no muestra el nombre del archivo.
4. Revisa el informe: archivos analizados, datos leídos, exclusiones con su tamaño declarado, duplicados, hallazgos
   del comprimido y **Cobertura y limitaciones**, que dice lo que no se pudo analizar.
5. Prueba con un **ZIP o RAR con contraseña**: la app la pide; prueba una incorrecta (lo indica) y la correcta.
6. Desde el gestor de archivos o WhatsApp/Telegram usa **"Abrir con…" o "Compartir" → Funcode Analyzer**.
7. Renombra un archivo que no sea ZIP (por ejemplo una imagen) a `.zip`: debe rechazarlo por su contenido.
8. Vuelve a **Nuevo análisis**: el análisis aparece en **Análisis recientes**; ábrelo y prueba a eliminarlo.

## Diferencias respecto a la arquitectura v0.2

- **La copia temporal del comprimido no se cifra con una clave efímera**, porque UnRAR necesita leer un descriptor de
  archivo real. Queda protegida por el cifrado por archivo de Android, se borra al terminar y se limpian los restos al
  arrancar. Detalle y limitación en Android 8–9 en el ADR 0004.
- Memoria máxima de UnRAR: 512 MB (diccionarios mayores se rechazan con su motivo).
- Las pruebas de la app en la JVM usan Robolectric con Android 15 (antes 16) por el motivo del punto 6 anterior; se
  añadió el emulador con Android 16 al CI para cubrirlo con el sistema real.
- Se añadió una herramienta de línea de comandos para pruebas en el PC (no forma parte de la app).

## Errores y limitaciones pendientes

1. **RAR multivolumen** (`.part1.rar`…) y ZIP divididos no se admiten: se rechazan indicándolo. Para analizarlos,
   hay que unirlos en un único comprimido.
2. Métodos de compresión ZIP poco comunes (Zstandard, xz, LZMA, PPMd) no se leen: esos archivos aparecen como
   "compresión no admitida" y el resto del proyecto se analiza.
3. Los comprimidos anidados (un ZIP dentro del proyecto) se registran pero **no se abren**, por seguridad.
4. Un RAR solo se puede **cancelar** entre bloques de datos; con diccionarios grandes la cancelación puede tardar
   unos segundos (el vigilante del proceso aislado lo garantiza igualmente).
5. Las pruebas instrumentadas solo se ejecutan en los emuladores del CI; la selección con "Abrir con…", "Compartir"
   y arrastrar y soltar debe comprobarse a mano en tu teléfono (puntos 6 del apartado anterior).
6. Pendientes de la Fase 1 que siguen abiertos: biometría probada solo con simulación, APK de publicación sin firma
   propia (n.º 2), contraseñas en `String` dentro de Compose (n.º 3), detekt/ktlint y R8 sin probar en un
   dispositivo.

## Siguiente fase

**Fase 3 — Explorador y estructura**: inventario del proyecto, rol de cada archivo, puntos de entrada, archivos
posiblemente innecesarios, copia redactada del código (con los secretos ocultos parcialmente) y pantalla **Explorador**
para navegar el árbol y consultar la ficha de cualquier archivo.

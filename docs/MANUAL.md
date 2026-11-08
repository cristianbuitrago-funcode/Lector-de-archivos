# Manual de uso — Funcode Code Analyzer

Herramienta interna de **Funcode Studio CB** para diagnosticar proyectos de software desde un teléfono Android.
Analiza un proyecto comprimido (ZIP o RAR) **sin ejecutar su código** y **sin enviarlo a ningún servicio**: todo
ocurre dentro del teléfono, en un proceso aislado sin red ni acceso a tus archivos.

> **Qué esperar.** El análisis es estático: lee el código, no lo ejecuta. Lo que no puede comprobar (lo que depende de
> la ejecución, de la configuración del servidor o de valores que se calculan en tiempo real) lo dice como «no
> verificado», «no evaluado» o «no medido», en lugar de inventarlo. Cada dato de los informes indica de dónde sale.

---

## 1. Instalación y requisitos

- **Android 8.0 o posterior.** El APK `app-arm64-v8a` sirve para casi todos los móviles actuales; el
  `app-universal` funciona en cualquiera (ocupa más).
- Instala el APK desde el gestor de archivos (Android pedirá permitir la instalación desde esa app).
- La app **no pide permisos de almacenamiento**: abre los comprimidos con el selector de documentos de Android y
  guarda los informes donde tú elijas.
- Solo usa internet si tú lo pides, para descargar la base pública de vulnerabilidades (sección 9).

### Versión de prueba y versión de publicación

Los APK de cada fase son **de prueba** (`com.funcodestudiocb.analyzer.debug`, versión `-debug`): están firmados con
una clave de depuración pública y se pueden depurar con un PC conectado, lo que permitiría inspeccionar la app en
marcha. Para el uso diario conviene la **versión de publicación**, optimizada y firmada con **tu propia clave**:

1. Crea la clave una sola vez y guárdala en un lugar seguro (si la pierdes, no podrás actualizar la app instalada):
   `keytool -genkeypair -v -keystore funcode.jks -keyalg RSA -keysize 4096 -validity 10000 -alias funcode`
2. Crea `keystore.properties` en la raíz del proyecto (Git lo ignora) con `storeFile` (ruta absoluta al `.jks`),
   `storePassword`, `keyAlias` y `keyPassword`.
3. Compila con `./gradlew :app:assembleRelease`; el APK está en `app/build/outputs/apk/release/`.

La versión de publicación es una app distinta de la de prueba (otro identificador): no comparte sus datos.

## 2. Primer arranque: tu acceso

1. **Configura tu acceso** con una contraseña propia (mínimo 8 caracteres combinando letras, números o símbolos, o
   una frase de 16 o más). Protege todos los análisis con un cifrado ligado a este teléfono.
2. Opcional: activa **Entrar con huella o rostro** en Ajustes (necesita biometría fuerte configurada en el teléfono).

> **Importante:** si olvidas la contraseña y la biometría deja de estar disponible, los datos no se pueden recuperar.
> Es intencionado: nadie más puede descifrarlos.

La app se bloquea al salir de ella o tras un tiempo sin uso (configurable), y con el botón **Bloquear** de la barra
superior. Tras varios intentos fallidos espera cada vez más; si activas **Borrar todo tras 10 intentos fallidos**,
los datos se borran al décimo error seguido.

## 3. Panel

La pantalla de inicio resume tu historial:

- **Totales**: proyectos, análisis, hallazgos de seguridad y fecha del último análisis.
- **Hallazgos de seguridad por severidad** y **vulnerabilidades de dependencias por severidad**. Se cuentan con el
  **último análisis terminado de cada proyecto**, para no sumar dos veces el mismo problema. Si un proyecto se analizó
  sin la base de vulnerabilidades, sus dependencias aparecen como «no se pudieron comprobar».
- **Proyectos**: cada uno con su índice de mantenimiento y una pequeña gráfica si tiene varios análisis. Al tocarlo
  se abre el informe de su último análisis.
- **Tendencia del índice de mantenimiento** de los proyectos con dos o más análisis.
- **Tecnologías más utilizadas** (solo las confirmadas o probables) y **lenguajes por líneas de código**.
- **Últimos análisis** y **estado de la instalación** (acceso, cifrado, biometría, base de vulnerabilidades).

Los análisis se agrupan en proyectos por el **nombre del comprimido**, sin la extensión ni sufijos de versión, fecha
o copia: `tienda.zip`, `tienda-v2.zip`, `tienda (1).zip` y `tienda-main.zip` son el mismo proyecto. Dos proyectos
distintos con el mismo nombre se agruparían juntos; la comparación entre análisis (sección 10) sí lo detecta.

## 4. Nuevo análisis

1. **Seleccionar archivo** y elige el `.zip` o `.rar` con el proyecto completo. También puedes abrirlo desde otra app
   con «Abrir con…» o «Compartir» y elegir Funcode, o arrastrarlo en tablets y multiventana.
2. La app copia el archivo de forma segura a su almacenamiento privado y comprueba su formato real (si la extensión
   no coincide con el contenido, lo dice y lo analiza según el contenido).
3. Revisa las **exclusiones**: carpetas que no se leen (`node_modules`, `build`, `.git`… se registra su ruta, número
   de archivos y tamaño), las generadas por motores de juego y los archivos temporales. Puedes añadir carpetas por su
   nombre exacto; se recuerdan para los siguientes análisis.
4. **Analizar.** Verás el progreso por etapas en directo; puedes **cancelar** en cualquier momento. Mientras dura, una
   notificación muestra el avance (el análisis sigue aunque cambies de app).
5. Si el comprimido está **cifrado**, la app pide la contraseña: solo se usa en memoria durante el análisis y nunca
   se guarda. Puedes seguir sin ella (se analiza lo que no está cifrado).

**Protecciones automáticas.** Rutas peligrosas (`../`, rutas absolutas, enlaces), bombas de compresión, archivos
dañados, nombres con caracteres engañosos, límites de tamaño y número de archivos: lo peligroso se rechaza o se
detiene, y el informe dice qué y por qué. Límites por defecto: comprimido de hasta 300 MB, 1 GB descomprimido y
100 000 entradas.

**Resultado:** *Completo*, *Parcial* (algo no se pudo analizar: el Anexo A dice qué), *Rechazado* (el archivo no se
puede analizar con seguridad), *Falta la contraseña* o *Fallido*. El resultado se guarda cifrado en el historial.

## 5. Explorador

- **Árbol** de carpetas y archivos con filtros por función (código, configuración, pruebas, documentación…) y
  búsqueda por nombre o ruta.
- **Puntos de entrada** y **subproyectos** detectados.
- **Ficha de cada archivo**: función y por qué, lenguaje y líneas, tecnologías, relaciones con otros archivos,
  hallazgos de seguridad y métricas.
- **Visor de código** con resaltado y los hallazgos marcados. Es una **copia redactada**: los posibles secretos
  aparecen ocultos. Puedes desactivar la copia en Ajustes (entonces solo verás la estructura).

## 6. Arquitectura

Diagramas generados a partir de las conexiones encontradas en el código:

- **Capas** (interfaz, API, controladores, servicios, modelos, datos…), con las **dependencias inversas** marcadas.
- **Flujos**: cada ruta de la API desde quien la llama hasta la base de datos, paso a paso.
- **Módulos** (con ciclos), **Proyectos** (con servicios externos y dependencias principales) y **Archivos** (un
  archivo con sus conexiones a uno o dos saltos).

Cada conexión indica su certeza: **verificada**, **probable** o **no verificada**. Amplía con dos dedos; un doble
toque ajusta el dibujo a la pantalla. Todo lo que hay en el diagrama está también en la lista de debajo (accesible
con lector de pantalla).

## 7. Seguridad

Hallazgos de la auditoría estática, cada uno con **severidad** (crítica, alta, media, baja, informativa), **certeza**
(confirmada, probable, posible), ubicación, explicación, **corrección recomendada con un ejemplo** y la regla que lo
encontró. Puedes filtrar por severidad, certeza y categoría.

- Los **secretos** (claves, tokens, contraseñas) nunca se muestran completos: solo un fragmento enmascarado y una
  huella para reconocerlos entre análisis.
- Los valores de ejemplo o de prueba se **rebajan** en lugar de ocultarse.
- **Pruebas dinámicas recomendadas**: lo que el análisis estático no puede confirmar (control de acceso real, cabeceras
  del servidor desplegado, lógica de negocio…) y conviene probar con la aplicación en marcha.

## 8. Dependencias

Desde la ficha de un análisis o de un manifiesto: dependencias **directas e indirectas**, con su versión, si se usan
de verdad en el código, su criticidad, **vulnerabilidades conocidas**, paquetes **maliciosos** conocidos y **fin de
soporte**. Filtra por tipo, ecosistema, uso o vulnerabilidad.

Si no hay archivo de bloqueo, la versión se evalúa con la mínima que permite el rango declarado (la instalada puede
ser otra) y se dice.

## 9. Bases de vulnerabilidades (Ajustes)

Para saber si las dependencias tienen vulnerabilidades conocidas, la app usa una **copia local** de bases públicas:
avisos de **OSV.dev** y fechas de **endoflife.date**.

1. En **Ajustes → Bases de vulnerabilidades** elige los ecosistemas (npm, PyPI, Maven, Packagist…) y si quieres las
   fechas de fin de soporte. Verás el tamaño aproximado de la descarga.
2. **Descargar**: la app pide confirmación, se conecta solo a esos servidores (solo HTTPS) y **no envía nada de tus
   proyectos**. Puedes detenerla; la base anterior se conserva.
3. Si la base tiene muchos días, la app avisa de que conviene **actualizarla**.

Los análisis hechos sin base dicen «no evaluado» en vez de dar por buenas las dependencias.

## 10. Informes

- **Historial** de análisis con su índice de mantenimiento y el **resumen ejecutivo**: cada frase cita la sección donde
  está el dato (`§11`, `Anexo A`).
- **Informe completo en pantalla**: las 15 secciones (resumen, descripción, tecnologías, arquitectura, estructura,
  archivos importantes, relaciones, bases de datos, APIs, dependencias, seguridad, problemas, recomendaciones,
  mantenimiento y conclusiones) y dos anexos (cobertura y limitaciones; metadatos).
- **Exportar** en **PDF**, **HTML** (autocontenido, sin JavaScript) o **JSON** (todos los registros, para otras
  herramientas). **Guardar** abre el selector de Android para elegir dónde; **Compartir** envía una copia temporal a
  la app que elijas.
- **Comparar** dos análisis del mismo proyecto: hallazgos nuevos y resueltos, archivos añadidos, eliminados y
  modificados, dependencias añadidas, eliminadas y actualizadas, y la evolución de las cifras.

> **Ojo:** lo exportado **ya no está cifrado**: sale de la protección de la app cuando tú lo decides. Los secretos
> siguen enmascarados.

### El índice de mantenimiento

Un número de 0 a 100 **orientativo** (no un estándar) que combina nueve sub-índices con su regla a la vista:
complejidad (20 %), tamaño (10 %), duplicación (15 %), pruebas (15 %), documentación (10 %), salud de dependencias
(10 %), herramientas de calidad (5 %), deuda señalada (5 %) y estructura (10 %). Lo que no se puede evaluar no cuenta
y su peso se reparte. Niveles: 85 o más alto, 70 bueno, 50 medio, 30 bajo, menos muy bajo. Sirve sobre todo para
comparar análisis del mismo proyecto.

## 11. Ajustes

- **Acceso**: biometría, cambiar contraseña, bloqueo al salir y por inactividad, borrado tras 10 intentos, bloquear
  ahora.
- **Análisis**: guardar o no la copia redactada del código; exclusiones por defecto (desde Nuevo análisis).
- **Bases de vulnerabilidades** (sección 9).
- **Aislamiento del análisis → Diagnóstico**: ejecuta el motor en su proceso aislado e intenta usar la red y leer un
  archivo privado de la app; debe fallar. Úsalo para comprobarlo en tu teléfono.
- **Datos → Borrar todos los datos**: elimina análisis, ajustes y claves. No se puede deshacer.
- **Acerca de**: versión y componentes de terceros con sus licencias.

## 12. Privacidad y seguridad

- El código se analiza **dentro del teléfono**, en un proceso aislado de Android: sin permisos, sin red y sin acceso a
  los archivos de la app ni a los tuyos; solo lee el comprimido que la app le entrega.
- El historial (análisis, copia redactada del código y ajustes) se guarda en una base de datos **cifrada** con una
  clave protegida por el almacén de claves del teléfono y tu contraseña.
- La app no hace copias de seguridad en la nube ni se transfiere a otro teléfono; no permite capturas de pantalla ni
  muestra su contenido en «recientes»; ignora los toques cuando otra app dibuja encima.
- No escribe nada del análisis en el registro del sistema.
- Solo se conecta a internet para descargar las bases públicas de vulnerabilidades, cuando tú lo pides.

## 13. Limitaciones

- **Análisis estático**: no ejecuta el código. No puede confirmar lo que depende de la ejecución (valores calculados,
  configuración del servidor, reflexión, inyección en tiempo de ejecución); lo marca como no verificado.
- **Lenguajes**: análisis sintáctico completo en JavaScript, TypeScript, Python, Java, Kotlin, PHP, C#, Dart, GDScript,
  C y C++, entre otros; en el resto, análisis léxico más sencillo (el informe dice cuál se usó en cada archivo).
- **Binarios y archivos compilados** no se analizan por dentro.
- **Vulnerabilidades**: solo las publicadas en la base descargada y para las versiones que se pueden determinar.
- **Código de terceros copiado** en el repositorio cuenta como propio.
- **Rendimiento**: un proyecto de unos 5 000 archivos de código tarda alrededor de medio minuto en un PC; en un
  teléfono de gama media, unos pocos minutos (ver `docs/fases/FASE-10.md`).

## 14. Problemas frecuentes

| Mensaje | Qué hacer |
|---------|-----------|
| «El archivo supera el tamaño máximo permitido» | Excluye carpetas pesadas antes de comprimir (dependencias instaladas, compilados, vídeos) o divide el proyecto. |
| «Formato no admitido» | Solo se admiten ZIP y RAR (RAR4 y RAR5). Vuelve a comprimir en ZIP. |
| «El comprimido está cifrado» | Escribe la contraseña o sigue sin ella (se analizará solo lo que no está cifrado). |
| Resultado *Parcial* | Abre el **Anexo A** del informe: dice qué archivos o etapas no se analizaron y por qué. |
| «No se pudieron comprobar» las vulnerabilidades | Descarga la base en Ajustes y vuelve a analizar. |
| «La base tiene N días» | Actualízala en Ajustes para conocer las vulnerabilidades más recientes. |
| «La biometría del teléfono cambió» | Entra con tu contraseña y vuelve a activar la biometría en Ajustes. |
| Un análisis de una versión anterior no muestra algo | Vuelve a analizar el proyecto con la versión actual. |

## 15. Glosario

- **Certeza** (hallazgos y conexiones): *confirmada/verificada* (el código lo muestra sin duda), *probable* (lo indica
  con alta probabilidad), *posible/no verificada* (hay indicios, pero falta información).
- **Confianza** (tecnologías): *confirmada* (manifiesto o código inequívoco), *probable*, *posible* (solo indicios).
- **Severidad**: impacto si el problema es real (crítica, alta, media, baja, informativa).
- **Copia redactada**: copia del código con los posibles secretos ocultos, guardada cifrada.
- **Proceso aislado**: proceso de Android sin permisos ni red donde se ejecuta el análisis.

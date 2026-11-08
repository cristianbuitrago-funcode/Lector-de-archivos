# Fase 9 — Informes

- **Estado:** terminada y aprobada
- **Criterio de aceptación:** los tres formatos se exportan con las 15 secciones + anexos.

## Objetivo

Cerrar el diagnóstico con un informe completo y exportable: las 15 secciones y los dos anexos de la arquitectura
(§10.1) en **PDF, HTML y JSON** (§10.3), el **índice de mantenimiento** con sus sub-índices a la vista (§10.2), el
**historial** y la **comparación** entre análisis, y la pantalla **Informes**. Sin enviar nada fuera del teléfono salvo
cuando el usuario decide guardar o compartir, sin secretos completos y diciendo lo que no se pudo medir. Decisiones en
el ADR 0012.

## Qué se hizo

| Módulo | Contenido |
|--------|-----------|
| Herramientas | **Android Gradle Plugin 9.5.1 y Gradle 9.9.0** (la propuesta aprobada con la Fase 8): Lint vuelve a no tener ningún hallazgo. El wrapper fija la suma oficial de la distribución y su `gradle-wrapper.jar` es el publicado por Gradle; la verificación de dependencias se regeneró. |
| `engine:languages` | **Complejidad ciclomática de cada función medida en el árbol sintáctico** de tree-sitter en JavaScript, TypeScript, Python, Java, Kotlin, PHP, C#, Dart, GDScript, C y C++: condiciones, bucles, casos (sin el de por defecto), capturas, ternarios y operadores lógicos, con el nombre, la primera y la última línea de cada función. El puente nativo admite operadores (símbolos anónimos de la gramática) solo cuando se piden expresamente. |
| `engine:metrics` (nuevo) | Etapa **`metrics`** en el proceso aislado: tamaño, complejidad, **código repetido** (bloques de 6 o más líneas normalizadas), **pruebas**, **documentación**, **deuda señalada** (TODO, FIXME, HACK, XXX), **herramientas de calidad e integración continua**, **ciclos** y **acoplamiento extremo**. **Índice de mantenimiento** con nueve sub-índices, su peso, lo medido y la regla de cada uno; lo que no se puede evaluar no cuenta y se dice. |
| `engine:api` | Esquema de resultados **v10**: `file_metrics`, `metrics_summary` y `maintenance_index`, validados en el proceso principal. |
| `engine:report` (nuevo, sin Android) | El **informe**: 15 secciones y anexos A (cobertura y limitaciones) y B (metadatos) en un modelo común; **resumen ejecutivo, problemas, recomendaciones y conclusiones con plantillas deterministas** en las que cada frase cita la sección de su dato; **HTML** autocontenido sin JavaScript, con CSP por huella, diagramas SVG y secciones plegables; **JSON** con esquema versionado y todos los registros; **comparación** entre análisis. La CLI genera los mismos informes (`--informe salida.html\|json`). |
| `app` | **PDF nativo** (texto y diagramas vectoriales, portada Funcode, índice con páginas, «Página X de Y»). **Pantalla Informes** (deja de estar en construcción): historial, resumen e índice del análisis elegido, **informe completo en pantalla**, **guardar** en PDF, HTML o JSON con el selector de documentos (sin permisos de almacenamiento), **compartir** por acción explícita y **comparar** con otro análisis (exportable en PDF o HTML). Panel del **índice de mantenimiento** en el informe de cada análisis. Versión **0.9.0**. |

### Las 15 secciones y los anexos

1 Resumen ejecutivo · 2 Descripción general (archivo, resultado, subproyectos, puntos de entrada, avisos del
comprimido) · 3 Tecnologías (lenguajes con sus líneas, frameworks y servicios con su evidencia) · 4 Arquitectura
(patrón, capas, diagrama, flujos, dependencias inversas) · 5 Estructura de carpetas (carpetas con su función
principal, diagrama de módulos) · 6 Archivos más importantes (con el porqué: punto de entrada, cuántos lo usan, a
cuántos afecta un cambio, función, capa, hallazgos, complejidad) · 7 Relaciones entre archivos · 8 Bases de datos y
conexiones · 9 APIs y servicios externos (rutas, servicios, diagrama de proyectos) · 10 Dependencias (vulnerables, fin
de soporte) · 11 Auditoría de seguridad (por severidad, hallazgos, recomendaciones, pruebas dinámicas) · 12 Problemas
encontrados (por prioridad, con la sección del detalle) · 13 Recomendaciones (en orden: seguridad, dependencias,
mantenimiento) · 14 Nivel general de mantenimiento (índice, sub-índices, fórmula, funciones más complejas, código
repetido, deuda) · 15 Conclusiones · **Anexo A** Cobertura y limitaciones (etapas, archivos no analizados y por qué,
notas, carpetas excluidas) · **Anexo B** Metadatos (identificador, huella SHA-256 del comprimido, fechas, versiones de la
app, del motor y del esquema, reglas, fecha de las bases OSV y de fin de soporte).

### El índice de mantenimiento

| Sub-índice | Peso | Regla (se muestra en el informe) |
|------------|------|----------------------------------|
| Complejidad | 20 % | 100 − (2 × % de funciones con complejidad > 10 + 3 × % con > 20) |
| Tamaño | 10 % | 100 − (1,5 × % de archivos con más de 500 líneas + 2 × % de funciones con más de 60) |
| Duplicación | 15 % | 100 − 5 × % de líneas repetidas |
| Pruebas | 15 % | líneas de pruebas respecto a las de código: un 50 % o más da 100; sin pruebas, 0 |
| Documentación | 10 % | comentarios hasta 80 puntos (20 % de líneas de comentario) + README 20 puntos |
| Salud de dependencias | 10 % | 100 − 15 por vulnerabilidad crítica, 10 alta, 4 media, 1 baja, 40 por paquete malicioso, 15 por versión sin soporte, 5 si lo pierde pronto |
| Herramientas de calidad | 5 % | analizador o comprobación de tipos 40, integración continua 40, formateador 20 |
| Deuda señalada | 5 % | marcas por cada 1000 líneas (FIXME y HACK cuentan doble): 0 da 100, 10 o más dan 0 |
| Estructura | 10 % | 100 − (2 × % de archivos en ciclos + 3 × % de archivos que dependen de 20 o más) |

Índice = suma de (peso × valor) de los sub-índices evaluados ÷ suma de sus pesos. Niveles: 85 alto, 70 bueno, 50 medio,
30 bajo y menos, muy bajo. **Orientativo, no un estándar.**

## Pruebas ejecutadas

### En este entorno (JVM), todas correctas

| Módulo | Pruebas | Qué cubren |
|--------|---------|------------|
| `engine:languages` | 36 | Las 25 anteriores y **11 de complejidad** con valores calculados a mano en los 12 lenguajes: condiciones, bucles (`for…of`, `for` mejorado, `for` de rango), casos **sin el de por defecto**, capturas, ternarios, `&&`/`||`/`and`/`or`/`??`, `elif`, comprensiones de Python, `when` de Kotlin, lambdas medidas aparte, nombre desde la asignación (`const Usuarios = () =>`) o desde la firma (Dart); un `&&` dentro de una cadena no cuenta; el parámetro opcional de TypeScript no es una decisión; sin gramática no se mide; las importaciones y declaraciones no cambian. |
| `engine:metrics` (nuevo) | 9 | **Índice** con todos los sub-índices calculados a mano (69,4 → 69, medio), reparto de pesos cuando algo no se evalúa (y el aviso si pesa menos del 50 %), dependencias sin base = no evaluado, niveles y porcentajes en español. **Código repetido**: un bloque copiado en otro archivo marca las dos copias; comentarios, líneas triviales e importaciones no cuentan; dentro del mismo archivo solo si no se solapa. **Etapa completa** con el pipeline real: complejidad 6 de una función, deuda (la documentación que menciona las marcas, o las pone entre comillas, no cuenta), bloque repetido con sus dos ubicaciones, ESLint + GitHub Actions + Prettier, README, ciclo entre dos archivos; sin relaciones, la estructura no se evalúa. |
| `engine:report` (nuevo) | 4 | Con análisis reales: las **15 secciones y 2 anexos** en orden y cada frase citando una sección existente; **HTML** sin ninguna etiqueta `script` (ni siquiera la que trae el proyecto en un comentario, que aparece escapada), sin atributos `style` ni recursos externos, CSP con la huella exacta de su hoja de estilos y sin enlaces rotos; **JSON** con su esquema, la huella del comprimido, 17 secciones y los registros por tipo; **la contraseña del proyecto no aparece completa** en ninguno; **comparación** entre dos versiones (hallazgo resuelto, archivos añadidos, eliminados y modificados por su huella, dependencia añadida y actualizada, tendencia de las cifras). |
| `engine:api` | 37 | Las 35 anteriores y 2 de los registros nuevos: van y vuelven sin cambios y se rechazan si son incoherentes o superan sus límites. |
| `app` (Robolectric) | 44 | Las 39 anteriores, 3 del **reparto de columnas del PDF** (ninguna palabra corta se parte, el espacio sobrante va a quien lo necesita, una ruta muy larga no se queda la tabla) y 2 de **Informes**: historial con el índice y el resumen, informe completo en pantalla (de §1 al Anexo B), HTML y JSON generados en el teléfono, guardar en un destino, **compartir** con el `FileProvider` (una sola copia temporal, permiso de lectura y borrado) y **un análisis completo se presenta como completo**; **comparación** entre dos análisis del mismo proyecto. |
| Resto | 156 | `engine:layout` 8, `engine:architecture` 4, `engine:audit` 10, `engine:core` 10, `engine:dependencies` 15, `engine:ingest` 36, `engine:inventory` 14, `engine:pipeline` 6, `engine:relations` 11, `engine:security` 10, `engine:technologies` 5, `engine:vulndb` 8, `data` 18, `analyzer-service` 1. |
| **Total** | **286** | **0 fallos** |

Además: **Android Lint sin ningún hallazgo**, APK de publicación con R8 correcto y dependencias verificadas en modo
estricto (la Fase 9 no añade ninguna dependencia externa; solo cambian las versiones de AGP y Gradle).

**Prueba con un proyecto real** (este repositorio, 411 archivos de código y 72 524 líneas): **índice 72 sobre 100
(bueno)**. 7093 funciones medidas, complejidad media 2,9 (mediana 1, percentil 90 de 6); un 5,0 % por encima de 10 y
un 1,5 % por encima de 20 (las más complejas son del UnRAR de terceros copiado en el repositorio, con 240 y 227, y el
validador de registros, con 223); 2,7 % de líneas repetidas (los `build.gradle.kts` de los módulos se parecen
mucho); 63 archivos de pruebas (15 % de líneas respecto al código: el sub-índice más bajo, 30); Android Lint y
GitHub Actions detectados; README; 7 ciclos con 54 archivos; ninguna marca de deuda real (dos falsos positivos de la
propia documentación se corrigieron, ver incidentes). La salud de las dependencias queda «no evaluada» porque en el PC
no hay base de vulnerabilidades, y el informe lo dice. La etapa tarda 98 ms; el análisis completo, unos 6 s en el PC.
El informe HTML ocupa 172 KB y el JSON 6,6 MB (todos los registros).

**Proyecto de demostración** (la tienda de la Fase 8): índice 68 (medio), 18 funciones medidas, todas sencillas; lo
que más lo baja son la documentación y las herramientas de calidad; recomendaciones en orden (descargar la base de
vulnerabilidades, añadir pruebas, integración continua, un analizador y un README). La comparación con una segunda
versión (una ruta y una dependencia más) muestra 1 dependencia añadida, 1 archivo añadido y 1 modificado.

### En emulador (CI de GitHub Actions)

Android 8.0 (API 26), 15 (API 35) y 16 (API 36). Nuevo en esta fase, `informesPdfHtmlYJsonEnElDispositivo`: un
análisis en el **proceso aislado** con las métricas calculadas allí (la función de la ruta medida en el árbol
sintáctico, el índice calculado, los registros válidos) y los tres informes generados en el teléfono: el **PDF** se
abre con `PdfRenderer` de Android (al menos 5 páginas, tamaño A4 y la banda azul marino de la portada) y el HTML y el
JSON no contienen el secreto del proyecto. La CI adjunta el PDF y el HTML generados en cada emulador.

Desde esta fase la CI **comprueba que cada módulo ejecutó todas sus pruebas**: cuenta los `@Test` de cada módulo y
los compara con los resultados del emulador (ver el incidente 11).

- **Ejecución n.º 41**: todos los trabajos en verde. Android 15 y 16: 15 de 15 (app 6, data 6, proceso aislado 3).
  Android 8.0: app 6 y data 6, pero **el proceso aislado, 0 pruebas** sin que el trabajo fallara (incidente 11). El
  PDF del emulador (10 páginas A4) se revisó página a página: una columna estrecha partía «Probable» (incidente 12).
- **Ejecución n.º 42** (con la corrección): todos los trabajos en verde y la comprobación nueva lo confirma en los
  tres emuladores: **15 de 15** (proceso aislado 3, app 6, data 6), sin que hiciera falta repetir nada. En el PDF ya no
  se parte ninguna palabra; al revisarlo apareció un título de sección solo al pie de una página (incidente 13).
- **Ejecución n.º 43** (título con su contenido): todos los trabajos en verde; **15 de 15** en Android 8.0, 15 y 16.
  El PDF (10 páginas A4 en los tres) ya no deja ningún título solo al pie de una página: la sección 14 empieza
  arriba de la página 8 con su indicador. Comprobado con un detector sobre el texto de cada página y a la vista.

## Capturas

Pantalla Informes (historial, índice, resumen ejecutivo trazable y exportación); informe completo en pantalla
(arquitectura con su diagrama, recomendaciones, índice con sus sub-índices); panel del índice en el informe del
análisis; comparación entre dos análisis (resumen de cambios y evolución de las cifras). Además, los informes de
muestra en PDF (del emulador), HTML y JSON.

## Incidentes y cómo se resolvieron

1. **Gradle 9.9.0 necesitaba una dependencia más** (`kotlin-reflect`) y la verificación de dependencias la bloqueó, como
   debe. Se regeneró la verificación con el procedimiento del README y se comprobó que el `gradle-wrapper.jar` nuevo es
   el oficial.
2. **Faltaban funciones en la complejidad** (las declaraciones de nivel superior de JavaScript, Python, PHP, Kotlin, C):
   el puente nativo devuelve cada nodo con el primer tipo pedido que coincide, y llegaban como declaraciones. Ahora una
   declaración que también es función se cuenta como función. Las pruebas por lenguaje lo detectaron.
3. **Riesgo al admitir símbolos anónimos en el puente nativo**: un tipo con nombre que no existe en una gramática podía
   empezar a coincidir con una palabra clave y cambiar las importaciones. Los operadores se piden con un prefijo que
   ningún tipo tiene.
4. **Falsos positivos de deuda** en este mismo repositorio: la documentación del analizador menciona «TODO, FIXME,
   HACK o XXX» y pone ejemplos entre acentos graves. Solo cuentan las marcas usadas para señalar deuda.
5. **Un análisis completo aparecía en el informe como «no terminó»**: el resultado de la sesión se guarda en el estado
   del análisis y no como registro, y el informe reconstruido no lo encontraba. Se recupera del estado; la prueba de
   Informes lo comprueba. La frase del resumen («El análisis es no terminó») tampoco era correcta: ahora hay una frase
   para cada resultado.
6. **Detalles del HTML vistos en el navegador**: el índice se numeraba dos veces, las columnas estrechas partían
   palabras («Proba ble», «/api/product s»), el valor de las barras se cortaba y «20 %» se separaba en dos líneas.
   Ahora las palabras no se parten (las rutas pueden partirse tras cada `/`) y el `%` va con espacio duro.
7. **`PdfDocument` no existe en la JVM de pruebas** (Robolectric): el PDF se comprueba en el emulador con
   `PdfRenderer`, y la CI adjunta el PDF para revisarlo.
8. **Prueba de dependencias de la Fase 7 intermitente**: comprobaba la pantalla en cuanto terminaba el actualizador,
   antes de que se redibujara. Ahora espera a que muestre la base instalada.
9. **Lint** avisó de dos textos sin usar y de cuatro llamadas que tienen equivalente en core-ktx; corregido.
10. **Concordancias** («Se señalan 1 problema», «1 añadidas», «1 archivos de pruebas»): corregidas.
11. **En Android 8.0 no se ejecutaron las 3 pruebas de aislamiento y la CI salió en verde** (ejecución n.º 41; en las
    Fases 7 y 8 sí se ejecutaban). El registro lo explica: con las pruebas de tres módulos instalándose a la vez en el
    mismo emulador, el ejecutor del plugin de Android no pudo leer la versión del dispositivo por ADB («Failed to get
    device API level», código 255), y el plugin (9.5.1) dio la tarea por buena con 0 pruebas. Dos cambios: las tareas
    que usan el emulador se ejecutan **de una en una** (un servicio compartido de Gradle) y la CI usa
    `.github/scripts/pruebas-instrumentadas.sh`, que **cuenta los `@Test` de cada módulo y los compara con los
    resultados**: si falta alguno, o alguno falla o se omite, la CI falla. Un módulo sin ningún resultado (el ejecutor
    no llegó a arrancar) se repite una vez; **una prueba que falla no se repite nunca**.
12. **El PDF partía palabras en columnas estrechas** («Probabl-e» en la columna Certeza), visto al revisar el PDF del
    emulador. El ancho de las columnas se repartía por número de caracteres; ahora se mide el texto: cada columna tiene
    al menos el ancho de su palabra más larga (una ruta muy larga sí se parte, como mucho ocupa el 40 %) y el resto se
    reparte según lo que necesita cada una. Tres pruebas nuevas.
13. **Un título de sección se quedaba solo al pie de una página** («14. Nivel general de mantenimiento» con su
    indicador en la página siguiente), visto en el PDF de la ejecución n.º 42. El PDF reservaba siempre el mismo alto
    para empezar una sección; ahora un título (de sección o de apartado) solo se dibuja si debajo cabe el principio de
    su primer bloque: el indicador entero, el diagrama entero, la cabecera y la primera fila de una tabla o dos líneas
    de texto.

## Limitaciones conocidas (honestidad del resultado)

- **La complejidad solo se mide en los 12 lenguajes con gramática**; Go, Rust, Ruby, Swift y el resto aparecen como
  «no medido». Con errores de sintaxis, la medida de ese archivo puede quedar incompleta (el informe lo dice).
- **El código repetido se busca por texto normalizado**: dos copias con variables renombradas no se detectan. En
  proyectos muy grandes la tabla de huellas se llena y el resto solo se compara con lo ya visto (se dice).
- **El código de terceros copiado en el repositorio cuenta como propio** (aquí, UnRAR): no hay forma fiable de
  distinguirlo sin que el proyecto lo indique.
- **El índice es orientativo**: sirve para comparar análisis del mismo proyecto y priorizar; entre proyectos distintos
  es solo una referencia. Sus pesos son fijos.
- **La comparación identifica los hallazgos por regla, archivo y huella**: mover un archivo hace que su hallazgo
  aparezca como resuelto y como nuevo.
- **Lo exportado no está cifrado**: sale del almacenamiento de la app cuando el usuario lo decide (la pantalla lo
  advierte); los secretos van enmascarados.
- **En el PDF los diagramas muy grandes se reducen** para caber en la página (son vectoriales y se pueden ampliar en
  el visor); en pantalla se pueden ampliar con dos dedos.

## Errores pendientes

Ninguno conocido en esta fase. El **Panel** (pantalla de inicio) sigue marcado como en construcción: es de la Fase 10.

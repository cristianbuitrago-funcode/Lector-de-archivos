# ADR 0013 — Panel, rendimiento, accesibilidad y revisión final (Fase 10)

- **Estado:** aceptada (2026-11-08, Fase 10)

## Contexto

La Fase 10 cierra la aplicación: el **Panel** completo (§4.2), el pulido visual y la **accesibilidad**, el
**rendimiento en el dispositivo** (RNF-07: un proyecto de unas 5 000 fuentes en menos de 5 minutos en un teléfono de
gama media), las **pruebas de extremo a extremo** con un proyecto desconocido, la **revisión de seguridad** y el
**manual de uso**. El criterio de aceptación es «proyecto desconocido → diagnóstico completo en el teléfono».

## Decisiones

1. **El Panel lee solo los resúmenes.** Para cada análisis del historial se leen los registros de resumen (seguridad,
   dependencias, tecnologías, lenguajes e índice de mantenimiento) con una consulta por tipo sobre el índice
   `(analysis_id, type)` que ya existía; no hace falta cambiar el esquema de la base de datos ni cargar análisis
   enteros. Los análisis se agrupan en **proyectos por el nombre del comprimido** sin extensión ni sufijos de versión,
   fecha o copia (`tienda-v2.zip`, `tienda (1).zip`, `tienda-main.zip`). Es una heurística y se dice: dos proyectos
   distintos con el mismo nombre se agrupan juntos.
2. **Cifras globales sin contar dos veces.** Los hallazgos, las vulnerabilidades, las tecnologías y los lenguajes del
   Panel salen del **último análisis terminado de cada proyecto**. Las vulnerabilidades de un análisis hecho sin base
   descargada no se suman: se cuentan como «no comprobadas». Las tecnologías posibles no cuentan.
3. **Rendimiento medido como lo hace el proceso aislado.** La CLI tiene un modo `--medir` que serializa los registros
   y los descarta (en lugar de guardarlos todos, como hace para escribir el informe), con la duración de cada etapa y
   la memoria máxima. Con un proyecto real de **5 092 archivos** (bibliotecas de Python, herramientas de Node, una
   biblioteca C++ y este repositorio): **26 s en un PC** de 4 núcleos y el motor funciona con **96 MB** de memoria (no
   con 64 MB). En el dispositivo, una prueba instrumentada genera un proyecto de **5 000 archivos** (JavaScript,
   TypeScript, Python y Kotlin con importaciones y funciones con ramas), lo analiza en el proceso aislado, lo guarda
   cifrado y deja los tiempos en la salida de la CI. El objetivo de RNF-07 es para un teléfono real: el emulador da una
   referencia y la comprobación final es la del propietario en su teléfono.
4. **La app guarda los registros por tandas.** Un proyecto grande produce decenas de miles de registros (unos 24 MB en
   JSON sin la copia del código). Se convierten a JSON y se insertan de 500 en 500 dentro de la misma transacción, en
   lugar de crear toda la copia a la vez. Además la app pide **memoria ampliada** (`largeHeap`): es una herramienta que
   procesa proyectos grandes y el coste (memoria que Android puede reclamar al estar en segundo plano) es aceptable.
5. **Accesibilidad comprobada automáticamente.** Una prueba recorre las pantallas principales con un análisis real y
   exige que **todo lo pulsable tenga un nombre** que un lector de pantalla pueda leer y un **área táctil de al menos
   48 × 48 dp**. Encontró tres interruptores de Ajustes sin nombre (TalkBack decía «activado, interruptor» sin decir
   cuál); ahora llevan el texto de su fila. El texto al doble de tamaño se revisó con capturas. Los diagramas tienen su
   lista equivalente desde la Fase 8.
6. **Proyecto desconocido para la prueba de extremo a extremo.** Una tarea de Gradle comprime código real que no se
   escribió para las pruebas (UnRAR, de terceros, en C++, y un módulo Kotlin de este repositorio) y lo añade a la APK de
   pruebas. La prueba recorre la app (acceso, Nuevo análisis, Panel, Explorador, Arquitectura, Seguridad e Informes) y
   genera los tres informes en el teléfono. No comprueba cifras concretas: comprueba que el diagnóstico está completo.
7. **Revisión de seguridad de la app.** Se revisaron el manifiesto (solo la actividad principal se exporta; servicios
   y proveedores no), las copias de seguridad y la transferencia entre dispositivos (excluidas), la red (solo HTTPS,
   solo autoridades del sistema, servidores limitados en el código), las capturas (bloqueadas), el registro del
   sistema (no se escribe nada), los avisos pendientes (inmutables) y la entrada de comprimidos desde otras apps. Dos
   refuerzos: se **rechazan los `content://` de proveedores de la propia app** (otra app podría usarlos para que
   Funcode importara sus propios archivos) y se **ignoran los toques cuando otra app dibuja encima** (Android lo hace
   por defecto desde la versión 12; ahora también en 8 a 11).
8. **Sin detekt ni ktlint.** Estaban previstos desde la Fase 1 y nunca se configuraron. Se decide no adoptarlos al
   final: el compilador trata todos los avisos como errores y Android Lint no tiene ningún hallazgo; añadirlos ahora
   supondría dependencias nuevas sin verificar y reformatear cientos de archivos sin ganar corrección. La arquitectura
   se corrige para no decir que la CI los ejecuta.
9. **Lo que enseñó el informe del propietario en su teléfono.** El propietario analizó este mismo repositorio en su
   teléfono y el informe tenía cuatro defectos de interpretación (no de lectura), que se corrigen sin cambiar el
   esquema de resultados:
   - **Tecnologías inventadas desde un catálogo.** El catálogo de reglas (`catalog.yaml`) contiene las señales de más
     de cien tecnologías, y el informe decía que el proyecto usaba PostgreSQL, Stripe, Slack, Supabase… Ahora, si un
     archivo contiene como texto las señales de **12 o más tecnologías distintas**, se trata como un catálogo o una
     lista: sus coincidencias son **menciones** (no uso) y la cobertura lo dice con el número de archivos. El umbral
     es una heurística: un archivo de configuración real rara vez nombra tantas tecnologías a la vez.
   - **Tecnologías vistas solo en pruebas** (cadenas de conexión de ejemplo, importaciones en pruebas) quedan como
     **posibles**, salvo las de la categoría de pruebas (JUnit, Jest…), que se usan precisamente ahí.
   - **El informe separa lo posible**: el resumen cuenta las tecnologías reconocidas y dice cuántas «solo son
     posibles»; las tablas de tecnologías, bases de datos y servicios muestran solo las confirmadas y probables, y las
     posibles van en una línea aparte. Los **hallazgos graves en archivos de pruebas** (la auditoría ya los rebajaba
     y lo anotaba, Fase 6) se dicen como tales en el resumen, los problemas, las recomendaciones y las conclusiones,
     en lugar de presentarse como «lo más urgente».
   - **Función de cada carpeta y puntos de entrada.** La función de una carpeta es el rol de la mayoría de sus archivos
     y, si otro rol llega a una cuarta parte, también ese («Servidor; pruebas (29 %)»); antes ganaba el rol concreto
     más frecuente aunque fuera minoritario (una carpeta de C++ con tres documentos salía como «Documentación»). Los
     puntos de entrada ya no se toman de un patrón escrito dentro de una cadena de texto (`"@SpringBootApplication"`)
     ni de un `main` dentro de una carpeta de pruebas.
10. **Desglose del tiempo en el propio informe.** En el teléfono del propietario la lectura y el examen de los
    archivos se llevaron 40 de 45 s, y todas las etapas fueron entre 6 y 25 veces más lentas que en el PC (la APK era
    la de depuración, que Android no compila por adelantado). Como en un teléfono no se puede perfilar, el motor mide
    el **tiempo exclusivo** de cada parte de una etapa (lo anidado, como enviar un registro mientras se examina un
    archivo, cuenta en su parte y no en la que la contiene) y lo pone en el mensaje de la etapa cuando dura más de un
    segundo; el Anexo A lo muestra. Solo contiene nombres de partes escritos por el motor y milisegundos. No cambia el
    esquema: el campo `message` de las etapas ya existía. El motor pasa a la versión **1.0.1** (sus resultados
    cambian con estas correcciones).

## Consecuencias

- El Panel funciona con los análisis de cualquier versión anterior: si un análisis no tiene un resumen (por ejemplo,
  el índice de mantenimiento antes de la Fase 9), se muestra «sin índice».
- La memoria ampliada no garantiza nada en teléfonos con muy poca memoria. Si el proceso aislado se queda sin memoria,
  el vigilante lo detecta y el análisis termina con un error explicado; si fuera la app, Android la cerraría (no ha
  ocurrido con 5 000 archivos en los emuladores de la CI).
- El tiempo en el teléfono real queda por confirmar con el propietario; la prueba del emulador lo deja medido en cada
  ejecución de la CI.

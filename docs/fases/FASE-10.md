# Fase 10 — Interfaz final, pruebas y optimización

- **Estado:** terminada, pendiente de aprobación
- **Criterio de aceptación:** proyecto desconocido → diagnóstico completo en el teléfono.

## Objetivo

Cerrar la aplicación: el **Panel** completo, el pulido visual y la **accesibilidad**, el **rendimiento** con un
proyecto grande (RNF-07: unas 5 000 fuentes en menos de 5 minutos en un teléfono de gama media), las **pruebas de
extremo a extremo** con un proyecto desconocido, la **revisión de seguridad** y el **manual de uso**. Decisiones en el
ADR 0013.

## Qué se hizo

| Área | Contenido |
|------|-----------|
| **Panel** | Deja de estar en construcción. Totales (proyectos, análisis, hallazgos, último análisis); **hallazgos de seguridad y vulnerabilidades de dependencias por severidad** del último análisis terminado de cada proyecto (sin contar dos veces; lo no comprobado se dice); **proyectos** con su índice de mantenimiento y una gráfica de su evolución; **tendencia del índice**; **tecnologías más utilizadas** y **lenguajes por líneas**; **últimos análisis**; **estado de la instalación** (incluida la base de vulnerabilidades); bienvenida con los tres pasos cuando no hay análisis. Lee de la base de datos solo los registros de resumen. Se retiran las pantallas «En construcción» y un texto desfasado («Fase 3 de 10»). |
| **Rendimiento** | Modo `--medir` en la CLI (registros tratados como en el proceso aislado, tiempos por etapa y memoria máxima). La app guarda los registros **por tandas** dentro de la transacción y pide **memoria ampliada**. Prueba instrumentada con **5 000 archivos generados en el dispositivo**: análisis en el proceso aislado, guardado cifrado, Panel e informe; los tiempos quedan en la salida de la CI. |
| **Accesibilidad** | Auditoría automática de las pantallas principales con un análisis real: todo lo pulsable tiene **nombre** para el lector de pantalla y **48 × 48 dp** de área táctil. Los interruptores de Ajustes llevan el nombre de su fila. Texto al doble de tamaño revisado con capturas (se corrigió la numeración de la bienvenida). |
| **Pulido visual** | Tarjetas de totales en rejilla, fecha relativa («Hoy», «Ayer»), barras sin marcas engañosas a 0, líneas guía visibles en las tendencias, indicador de carga y filas animadas en el Panel. |
| **Extremo a extremo** | Proyecto **desconocido** generado al compilar las pruebas con código real que no se escribió para ellas (UnRAR en C++ y un módulo Kotlin de este repositorio): la prueba recorre la app (acceso, Nuevo análisis, Panel, Explorador, Arquitectura, Seguridad, Informes) y genera los tres informes en el teléfono. |
| **Resultado pendiente** | Si un análisis termina con la app bloqueada, el servicio en primer plano sigue con el aviso «desbloquea Funcode para guardarlo» hasta que se guarda, para que Android no cierre la app y se pierda el resultado. |
| **Seguridad** | Revisión del manifiesto, copias de seguridad, red, capturas, registro del sistema, avisos pendientes y entrada de archivos. Dos refuerzos: se rechazan los `content://` de proveedores de la propia app y se ignoran los toques con otra app encima (Android 8 a 11). |
| **Manual** | `docs/MANUAL.md`: instalación, versión de publicación con tu clave, acceso, cada pantalla, bases de vulnerabilidades, informes, índice, ajustes, privacidad, limitaciones, problemas frecuentes y glosario. |
| **Versiones** | App **1.0.0** (código 10) y motor **1.0.0** (seguía en 0.3.0 desde la Fase 3 y los informes lo mostraban). |

## Pruebas ejecutadas

### En este entorno (JVM)

| Módulo | Pruebas | Qué cubren |
|--------|---------|------------|
| `app` (Robolectric) | 52 | Las 44 anteriores y 8 nuevas: **modelo del Panel** (4: nombre del proyecto sin extensión ni sufijos, totales del último análisis terminado de cada proyecto sin contar dos veces, vulnerabilidades no comprobadas que no se suman, tecnologías sin las posibles, lenguajes por líneas, tendencia solo con dos índices, Panel vacío); **pantalla del Panel** con análisis reales (2: bienvenida que lleva a Nuevo análisis; dos versiones de la tienda agrupadas como un proyecto con tendencia, totales, vulnerabilidades no comprobadas, tecnologías, últimos análisis y acceso al informe); **auditoría de accesibilidad** de las pantallas principales (1); **comprimidos de otras apps** (1: solo `content://` y nunca de un proveedor propio). |
| Resto | 242 | Sin cambios respecto a la Fase 9. |
| **Total** | **294** | **0 fallos** |

Además: **Android Lint sin ningún hallazgo** y APK de publicación con R8 correcto.

### Rendimiento

| Dónde | Proyecto | Tiempo | Memoria |
|-------|----------|--------|---------|
| PC (4 núcleos), CLI `--medir` | Real: 5 092 archivos (Python, Node, C++ y este repositorio) | 26 s | el motor funciona con 96 MB (no con 64 MB) |
| PC, CLI `--medir` | Sintético de la prueba: 5 004 archivos | 11 s | — |
| Emulador Android 8.0 (2 núcleos) | Sintético: 5 000 archivos analizados en el proceso aislado y guardados cifrados | **69 s** (59 s de análisis + 10 s de guardado) | app: 512 MB disponibles |
| Emulador Android 15 (2 núcleos) | Ídem | **107 s** (93 s + 14 s) | app: 576 MB |
| Emulador Android 16 (2 núcleos) | Ídem | **110 s** (97 s + 12 s) | app: 576 MB |

El objetivo de RNF-07 (menos de 5 minutos en un teléfono de gama media) se cumple con holgura en los emuladores; falta
confirmarlo en tu teléfono. Casi todo el tiempo es la lectura y el análisis de cada archivo (el perfil en el PC señala
las expresiones regulares de nombres de funciones, secretos y señales de tecnologías como lo más costoso).

### En emulador (CI de GitHub Actions)

Android 8.0 (API 26), 15 (API 35) y 16 (API 36), con el recuento de pruebas de la Fase 9. Nuevas: la de **5 000
archivos** y la de **extremo a extremo con el proyecto desconocido** (app: 8 pruebas; total 17 por emulador).

- **Ejecución n.º 46**: Android 8.0 en verde (16 de 16). Android 15 y 16: la prueba de 5 000 archivos no se guardaba
  (incidente 5).
- **Ejecución n.º 47**: la prueba de 5 000 archivos pasa en los tres; la de extremo a extremo falla por la propia
  prueba (incidentes 6 y 7).
- **Ejecución n.º 48**: Android 8.0 en verde (**17 de 17**); en Android 15 y 16, el diálogo del permiso de
  notificaciones tapaba la app (incidente 8). El PDF del proyecto desconocido (11 páginas) no tiene títulos huérfanos.
- **Ejecución n.º 49**: **todos los trabajos en verde; 17 de 17 en Android 8.0, 15 y 16** (proceso aislado 3, app 8,
  data 6). La prueba de 5 000 archivos tardó **62 s, 84 s y 111 s** (análisis + guardado cifrado). El proyecto
  desconocido (161 archivos) salió **completo** en los tres, con su informe PDF de 11 páginas sin títulos huérfanos.

## Incidentes y cómo se resolvieron

1. **Pruebas de la app que fallaban según el orden** (Explorador, Arquitectura, Dependencias, coordinador): Robolectric
   crea un entorno con su propio cargador de clases por cada modo gráfico, y la biblioteca nativa de tree-sitter solo
   se puede cargar en uno por proceso. Las pruebas nuevas usaban el modo nativo y, al ejecutarse antes, dejaban a las
   demás sin gramáticas. Ahora todas usan el mismo modo (`robolectric.properties`); dos pruebas antiguas que daban
   por visible lo que estaba fuera de la pantalla se ajustaron (desplazarse hasta la pestaña «Archivos», bajar hasta el
   estado de la instalación).
2. **El generador de recursos de pruebas no se ejecutaba**: la tarea `Zip` de Gradle no declara su carpeta como
   salida y el plugin de Android no la enlazaba. Se sustituyó por una tarea propia con salida declarada.
3. **La CLI escribía «?» en lugar de «·» y «á»** en terminales sin UTF-8: ahora fuerza UTF-8 en su salida.
4. **Documentación desfasada**: la arquitectura decía que la CI ejecuta detekt y ktlint (nunca se configuraron) y que
   el motor usa Kotest (usa JUnit 6 con kotlin.test). Corregido; la decisión sobre detekt y ktlint está en el ADR 0013.
5. **La prueba de 5 000 archivos no se guardaba en Android 15 y 16** (ejecución n.º 46). Sin pantalla, la app está en
   segundo plano y se bloquea sola al minuto (el valor por defecto); el resultado esperaba, como está diseñado, a que se
   desbloqueara. La prueba ahora desbloquea como lo haría el usuario. Al investigarlo apareció un **riesgo real**: al
   terminar, el servicio en primer plano se detenía, y mientras el resultado esperaba Android podía cerrar la app y se
   perdía el análisis. Ahora el servicio sigue con el aviso «Análisis de … terminado — desbloquea Funcode para
   guardarlo» hasta que se guarda (y respeta el límite de tiempo de Android 15).
6. **La pulsación en «Analizar» se perdía** en la prueba de extremo a extremo (Android 15) mientras el teclado terminaba
   de ocultarse. Se cierra el teclado tras crear el acceso y la pulsación se repite si el análisis no empieza.
7. **La fila del proyecto en el Panel estaba fuera de la pantalla** y la lista no la dibuja hasta llegar a ella: la
   prueba se desplaza hasta ella.
8. **El diálogo del permiso de notificaciones** (Android 13+), que «Analizar» pide antes de empezar, quedaba fuera del
   alcance de la prueba y tapaba la app: se concede antes, como si ya se hubiera aceptado.

## Limitaciones conocidas (honestidad del resultado)

- **El rendimiento en un teléfono real está por confirmar**: el requisito habla de un teléfono de gama media; aquí se
  midió en el PC y en los emuladores de la CI.
- **La biometría solo se ha probado con simulación** (Robolectric y emulador sin sensor).
- **Las contraseñas pasan por `String` en la interfaz** (Compose no ofrece un campo de texto que se pueda borrar de
  memoria); la clave derivada sí se borra.
- **El APK de prueba es depurable**; para el uso diario conviene la versión de publicación con tu clave (manual, §1).
- **Los proyectos del Panel se agrupan por el nombre del comprimido**: dos proyectos distintos con el mismo nombre se
  juntan.

## Errores pendientes

Ninguno conocido en el código. Quedan dos comprobaciones que solo puedes hacer tú, en tu teléfono:

1. **Rendimiento real** (RNF-07): analizar un proyecto grande tuyo y ver el tiempo (el Anexo A del informe da la
   duración de cada etapa).
2. **Biometría real**: activar la huella o el rostro en Ajustes, bloquear y entrar con ella.

Y una decisión tuya: si quieres usar la **versión de publicación** firmada con tu clave (manual, §1).

## Fin del plan

Con esta fase se completan las 10 fases del plan de la arquitectura (§13). La extensión opcional posterior (un
complemento para PC con Semgrep y OWASP Dependency-Check cuyo JSON la app pueda importar) queda fuera del plan.

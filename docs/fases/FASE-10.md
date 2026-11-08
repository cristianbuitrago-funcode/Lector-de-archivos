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
| **Seguridad** | Revisión del manifiesto, copias de seguridad, red, capturas, registro del sistema, avisos pendientes y entrada de archivos. Dos refuerzos: se rechazan los `content://` de proveedores de la propia app y se ignoran los toques con otra app encima (Android 8 a 11). |
| **Manual** | `docs/MANUAL.md`: instalación, versión de publicación con tu clave, acceso, cada pantalla, bases de vulnerabilidades, informes, índice, ajustes, privacidad, limitaciones, problemas frecuentes y glosario. |
| **Versiones** | App **1.0.0** (código 10) y motor **1.0.0** (seguía en 0.3.0 desde la Fase 3 y los informes lo mostraban). |

## Pruebas ejecutadas

_Se completa al cerrar la fase._

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

_Se completa al cerrar la fase._

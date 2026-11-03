# Fase 1 — Arquitectura y configuración inicial

**Estado: terminada, pendiente de tu aprobación.** Fecha: 2026-11-03.

## Objetivo

Construir la base sobre la que se apoyan todas las fases: el proyecto Android multimódulo, el motor de análisis
ejecutándose en un proceso aislado, el acceso exclusivo del propietario con la base de datos cifrada, la interfaz con
la identidad de Funcode y la integración continua.

## Qué se hizo

| Módulo | Contenido |
|--------|-----------|
| `engine:api` | Registros de resultados versionados (`ResultRecord`), protocolo de tramas con límites (`FrameWriter`/`FrameReader`), validación semántica de cada registro (`RecordValidator`), lectura de sesiones completas con control de orden y recuento (`SessionReader`), configuración del análisis y su validación (`ConfigValidator`). |
| `engine:core` | Registro de etapas con orden por dependencias (`StageRegistry`) y orquestador (`PipelineRunner`): aislamiento de fallos, omisión de etapas dependientes, tiempo máximo por etapa y total, cancelación, cobertura, y nunca envía mensajes de excepción, que podrían contener fragmentos del proyecto. Etapas de diagnóstico. |
| `analyzer-service` | Servicio en **proceso aislado** (`isolatedProcess=true`) con interfaz AIDL. Solo acepta llamadas de la propia app, revalida la configuración y escribe los resultados por una tubería. Incluye un cliente con vigilante que, si se agota el tiempo, destruye el proceso aislado y conserva los resultados parciales, y un **autodiagnóstico del aislamiento**: red, archivos de la app, almacenamiento compartido e integridad del canal. |
| `data` | **Bóveda de acceso** (Argon2id + AES-256-GCM + Android Keystore, biometría fuerte opcional, esperas crecientes, borrado automático opcional), **BD cifrada** con SQLCipher + Room, gestión de sesión y borrado total. |
| `app` | Tema Funcode (negro, azul marino, azul eléctrico, dorado), configuración inicial del acceso, desbloqueo con contraseña o biometría, navegación adaptable (menú lateral en teléfono, raíl en tablet, cajón fijo en pantallas grandes), las 6 pantallas principales **marcadas "En construcción · Fase N"**, Panel con el estado real de la instalación, Ajustes completos de seguridad y pantalla de diagnóstico con historial guardado en la BD cifrada. Bloqueo automático al salir de la app y por inactividad, capturas de pantalla bloqueadas, copias de seguridad y transferencia entre dispositivos desactivadas. |
| CI | GitHub Actions: pruebas JVM, Lint, APK de prueba descargable, APK de publicación con R8 y pruebas instrumentadas en emulador (API 26 y 35). |

## Pruebas ejecutadas

### En este entorno (JVM), todas correctas

| Módulo | Pruebas | Qué cubren |
|--------|---------|------------|
| `engine:api` | 22 | Ida y vuelta de todos los registros; cabeceras y tramas truncadas; longitudes inválidas o gigantes (rechazadas sin reservar memoria); límites de tramas y bytes; JSON mal formado, tipos desconocidos y claves extra; UTF-8 inválido; reglas de sesión (inicio, final, recuento, datos fuera de orden); validación de campos; validación de configuración y rutas peligrosas. |
| `engine:core` | 9 | Orden por dependencias y errores de definición; sesión completa; aislamiento de fallos sin filtrar mensajes; cancelaciones internas; tiempo máximo por etapa y total; cancelación externa; progreso monótono; compatibilidad de extremo a extremo con el lector del proceso principal. |
| `data` | 18 | Configurar y desbloquear; la bóveda no contiene ni la clave ni la contraseña; política de contraseñas; contraseña incorrecta, esperas crecientes y reinicio del contador; borrado automático; pérdida de la clave del dispositivo; bóveda manipulada; cambio de contraseña; biometría (activar, desbloquear, invalidación); Argon2id contra la implementación de referencia; AES-GCM ante clave incorrecta, alteración y AAD distinto. |
| `analyzer-service` | 1 | Detección de procesos aislados por rango de UID (Android 8.x). |
| `app` (Robolectric) | 8 | Rechazo de contraseñas débiles; creación del acceso y navegación con pantallas marcadas por fase; bloqueo y desbloqueo con contraseña incorrecta y correcta; borrado total desde Ajustes; bloqueo automático en segundo plano, inmediato y por inactividad; escape de caracteres invisibles. |
| **Total** | **58** | **0 fallos** |

Además: **Android Lint sin ningún hallazgo** en los tres módulos Android, compilación del APK de publicación con R8
comprobando que se conservan el servicio aislado, la interfaz AIDL y SQLCipher, y el motor compila con los avisos
tratados como errores.

### En emulador (CI)

Este entorno no permite ejecutar un emulador de Android porque no tiene KVM. Estas 7 pruebas se ejecutan en GitHub
Actions (API 26 y 35):

- `IsolationInstrumentedTest`: el motor corre en un proceso aislado con otro UID; sin red ni acceso a archivos; canal íntegro; el vigilante detiene un análisis bloqueado; cada análisis usa un proceso nuevo.
- `EncryptedStorageInstrumentedTest`: la BD queda cifrada en disco (ni la cabecera de SQLite ni los datos en claro); no se abre con otra clave; la clave del dispositivo protege y se destruye.
- `EndToEndTest`: crear el acceso → entrar → Ajustes → diagnóstico de aislamiento correcto.

El resultado de estas pruebas se indica en la respuesta de entrega de la fase.

## Cómo probarlo en tu teléfono

1. Instala el APK de prueba (`Funcode Analyzer (prueba)`). Android pedirá permitir la instalación desde esa fuente.
2. **Configura tu acceso**: prueba primero una contraseña débil (debe rechazarse) y después una válida. Si tu teléfono
   tiene huella o rostro, acepta activarlo.
3. Navega por el menú: cada pantalla aún no implementada lo dice ("En construcción · Fase N") y describe qué mostrará.
4. **Panel**: muestra el estado real (acceso, cifrado, biometría, número de diagnósticos).
5. **Ajustes → Diagnóstico del aislamiento → Ejecutar**: deben salir todas las comprobaciones en verde. En la
   compilación de prueba la app tiene permiso de red, así que el bloqueo demuestra el aislamiento del motor.
6. Sal de la app más de 60 s (o pulsa el icono de salida arriba a la derecha) y comprueba que pide de nuevo el acceso.
7. Prueba una contraseña incorrecta varias veces: a partir del 5.º intento aparece una espera.
8. Comprueba que no se pueden hacer capturas de pantalla dentro de la app.

## Diferencias respecto a la arquitectura v0.2

- **Inyección de dependencias manual** en lugar de Hilt (ver ADR 0003).
- **targetSdk 37** (Android 17), la versión estable más reciente.
- Se añadió la **verificación de dependencias por checksum** y la fijación de las acciones de CI por SHA.
- El **servicio en primer plano** para análisis largos se implementa en la Fase 2, donde aparecen los primeros
  análisis reales; el diagnóstico es corto y no lo necesita.

## Errores y limitaciones pendientes

1. **Biometría**: se probó con una implementación simulada; el diálogo real del sistema solo puede verificarse en un
   teléfono con huella o rostro registrados (punto 2 de la lista anterior).
2. **APK de publicación sin firmar**: no existe aún una clave de firma de Funcode. Mientras tanto se usa el APK de
   prueba, que es *depurable*: con la depuración USB activada, `adb` podría copiar los archivos de la app, aunque
   siguen cifrados con claves que no salen del Keystore. Para el uso definitivo conviene generar una clave propia
   (Fase 10, o antes si lo pides).
3. **Contraseñas en memoria**: los campos de texto de Compose usan `String` inmutables, que no pueden borrarse de la
   memoria de forma garantizada.
4. Tras un **borrado automático** por intentos fallidos, la app vuelve a la configuración inicial sin mostrar un
   aviso explicando por qué. Se corregirá en la Fase 2.
5. **detekt/ktlint** aún no están configurados. Por ahora se usan Android Lint y avisos tratados como errores en el
   motor.
6. Robolectric se ejecuta con SDK 36 porque aún no admite el 37. Las pruebas en emulador cubren el comportamiento real.
7. El APK de publicación (con R8) aún no se ha probado en un dispositivo; se hará en la Fase 10.

## Siguiente fase

**Fase 2 — Carga y extracción ZIP/RAR**: selección del archivo (selector, "Abrir con…", arrastrar), importación cifrada
con clave efímera, firma real del archivo, ZIP con Commons Compress, RAR con UnRAR nativo (NDK), todas las
protecciones contra bombas y rutas maliciosas, exclusiones configurables, duplicados, progreso por etapas y servicio en
primer plano.

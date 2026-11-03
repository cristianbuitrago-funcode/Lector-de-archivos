# ADR 0004 — Ingesta de ZIP y RAR en el proceso aislado

- **Estado:** aceptada (2026-11-03, Fase 2)

## Decisiones

1. **Nada se extrae a disco.** El proceso aislado recibe el comprimido como descriptor de solo lectura y lee cada
   entrada en memoria, por bloques, aplicando los límites mientras lee (los tamaños declarados no se creen).
2. **ZIP: Apache Commons Compress con comprobaciones propias previas.** Antes de que la biblioteca procese el
   directorio central se lee el registro final (EOCD y ZIP64): se rechazan los ZIP multidisco y los que declaran más
   entradas de las permitidas, para no reservar memoria con un directorio central malicioso. Después se comprueban
   las cabeceras locales (entradas solapadas, datos fuera del archivo, nombre distinto del central).
   - Solo se aceptan métodos de compresión implementados sin dependencias (almacenado, deflate, deflate64, bzip2,
     implode, shrink). Zstandard, xz y otros quedan como "compresión no admitida": las bibliotecas opcionales no se
     incluyen y su ausencia no puede abortar el análisis.
   - **Descifrado propio de ZipCrypto y WinZip AES** sobre los datos en bruto de Commons Compress (que no descifra),
     usando solo primitivas de `javax.crypto` (PBKDF2-HMAC-SHA1 propio verificado con los vectores de la RFC 6070,
     AES-CTR little-endian y autenticación HMAC-SHA1).
3. **RAR: UnRAR 7.3.2 oficial de RARLAB**, compilado con el NDK y para el PC (pruebas). Se usa su interfaz "DLL"
   en modo prueba: los datos llegan por retrollamada y nunca se escriben. Se aplican sus límites propios de memoria y
   tiempo, se deniegan los diccionarios grandes y no se piden volúmenes adicionales. Se descartó `junrar` porque no
   lee RAR5.
   - **Modificación de UnRAR**: el proceso aislado no puede abrir rutas, así que `File::Open` acepta un nombre
     `funcode-fd:<n>` y usa una copia (`dup`) de ese descriptor. La modificación está marcada en
     `native/unrar/src/main/cpp/unrar/file.cpp` con el párrafo de licencia exigido por UnRAR, que también figura en
     la app (Ajustes → Acerca de) y en `docs/LICENCIAS-TERCEROS.md`.
4. **Exclusiones** por nombre exacto de carpeta (configurables), por motor detectado (solo si está su marcador:
   Unity, Unreal, Godot) y de archivos temporales o de sistema. Se registran ruta, número de archivos y tamaño
   declarado; nunca se lee su contenido.
5. **Contraseñas**: viajan al proceso aislado por Binder como `char[]` (nunca dentro del JSON de configuración), se
   copian a un contenedor que se borra al terminar y no se guardan nunca.

## Desviación respecto a la arquitectura v0.2

La arquitectura preveía cifrar la copia temporal del comprimido con una clave efímera (borrado criptográfico). **No
se implementa**: UnRAR solo puede leer un descriptor de archivo real, no un flujo descifrado, y descifrar el RAR
completo en memoria del proceso aislado no es viable con los tamaños admitidos (hasta 300 MB). En su lugar:

- la copia vive en el almacenamiento privado sin copia de seguridad (`noBackupFilesDir/import`), protegido por el
  **cifrado basado en archivos de Android** (obligatorio desde Android 10), que usa una clave por archivo;
- se borra al terminar o al descartar el análisis, y al arrancar la app se borran las copias huérfanas;
- en dispositivos Android 8–9 con cifrado de disco completo (sin cifrado por archivo) los restos borrados pueden
  persistir en la memoria flash hasta que se sobrescriban. Es una limitación documentada.

## Otras consecuencias

- La imagen de Android 16 de Robolectric incluye una copia antigua de Commons Compress (de los servicios del
  sistema). Las pruebas de la app en la JVM usan la imagen de Android 15, y el CI ejecuta los análisis reales en
  emuladores Android 8.0, 15 y 16. En publicación R8 renombra las clases de la biblioteca, así que no pueden chocar
  con ninguna copia del sistema.
- Corpus de pruebas: los RAR de prueba de libarchive (BSD) y ZIP maliciosos fabricados en las pruebas.

# ADR 0002 — Acceso exclusivo del propietario y jerarquía de claves

- **Estado:** aceptada (2026-11-03)
- **Contexto:** requisito "que solo yo pueda ingresar" y cifrado de la información almacenada.
- **Decisión:**
  1. La base de datos se cifra con SQLCipher usando una clave aleatoria de 256 bits (`claveBD`), pasada como clave
     en bruto (`x'…'`) para evitar la derivación PBKDF2 innecesaria.
  2. `claveBD` se guarda cifrada con AES-256-GCM bajo una clave derivada de la contraseña con **Argon2id**
     (64 MiB, 3 pasadas, paralelismo 1; parámetros guardados para poder endurecerlos).
  3. Ese bloque se cifra otra vez con una clave del **Android Keystore** (StrongBox si existe) sin autenticación por
     uso: liga los datos a este teléfono.
  4. Con la biometría activada, `claveBD` se guarda además cifrada con una clave del Keystore que exige **biometría
     fuerte en cada uso** y que el sistema invalida si cambia la biometría del dispositivo.
  5. La contraseña no se almacena: se verifica porque el cifrado autenticado solo se abre con la clave correcta.
  6. Intentos fallidos: 4 libres, después esperas de 30 s, 60 s, 2 min… hasta 15 min. Borrado automático opcional
     tras 10 fallos seguidos.
- **Consecuencias:**
  - Copiar los archivos a otro equipo, o conocer solo la contraseña, no basta para descifrar los datos.
  - Si se olvida la contraseña y la biometría deja de estar disponible, los datos son irrecuperables (aceptado y
    advertido en la interfaz).
  - Compose maneja los campos de texto como `String` inmutables: la contraseña tecleada no puede borrarse de la
    memoria de forma garantizada (las copias `CharArray` y las claves sí se borran).

# SQLCipher usa JNI: sus clases nativas deben conservarse.
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
# Argon2 de Bouncy Castle (solo se usa el generador; el resto puede eliminarse).
-dontwarn org.bouncycastle.**

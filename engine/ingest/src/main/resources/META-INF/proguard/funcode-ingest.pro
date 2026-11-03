# Commons Compress referencia compresores opcionales que no se incluyen (zstd, xz, brotli, Pack200/ASM…).
# El lector ZIP solo usa métodos sin dependencias (ver READABLE_METHODS en ZipArchiveSession).
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn io.airlift.compress.**

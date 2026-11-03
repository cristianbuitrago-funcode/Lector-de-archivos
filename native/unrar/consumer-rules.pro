# JNI de UnRAR: los métodos nativos y la retrollamada onData se buscan por nombre desde C++.
-keep class com.funcodestudiocb.analyzer.engine.ingest.rar.UnrarNative { native <methods>; }
-keep interface com.funcodestudiocb.analyzer.engine.ingest.DataReceiver { *; }
-keepclassmembers class * implements com.funcodestudiocb.analyzer.engine.ingest.DataReceiver {
    boolean onData(byte[], int);
}

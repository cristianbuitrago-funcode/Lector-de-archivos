# Interfaz AIDL y servicio aislado: los nombres deben conservarse.
-keep class com.funcodestudiocb.analyzer.service.IAnalyzer** { *; }
-keep class com.funcodestudiocb.analyzer.service.IAnalysisCallback** { *; }
-keep class com.funcodestudiocb.analyzer.service.AnalyzerService { *; }

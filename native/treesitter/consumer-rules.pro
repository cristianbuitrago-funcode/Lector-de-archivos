# El puente JNI busca estos métodos por nombre: R8 no debe renombrarlos ni eliminarlos.
-keep class com.funcodestudiocb.analyzer.engine.languages.treesitter.TreeSitterNative {
    native <methods>;
}

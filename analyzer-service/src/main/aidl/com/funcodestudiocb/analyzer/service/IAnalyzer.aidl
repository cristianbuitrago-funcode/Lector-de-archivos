package com.funcodestudiocb.analyzer.service;

import com.funcodestudiocb.analyzer.service.IAnalysisCallback;

/**
 * Contrato entre el proceso principal y el proceso de análisis aislado.
 */
interface IAnalyzer {
    /** Versión del contrato; ambos lados se compilan juntos, sirve para detectar errores de empaquetado. */
    int protocolVersion();

    /**
     * Inicia un análisis.
     * config: AnalysisConfig serializada en JSON (el servicio la vuelve a validar).
     * archive: el comprimido a analizar, de solo lectura (null en el modo diagnóstico).
     * password: contraseña del comprimido o null. Solo vive en memoria y se borra al terminar.
     * results: extremo de escritura de una tubería; el servicio escribe en ella las tramas de resultados.
     * callback: progreso y finalización.
     */
    void start(in byte[] config, in ParcelFileDescriptor archive, in char[] password,
               in ParcelFileDescriptor results, IAnalysisCallback callback);

    /** Solicita la cancelación cooperativa del análisis en curso. */
    void cancel();
}

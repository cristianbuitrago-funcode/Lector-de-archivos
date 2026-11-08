package com.funcodestudiocb.analyzer.service;

/** Notificaciones del proceso aislado al proceso principal. Asíncronas: nunca bloquean al motor. */
oneway interface IAnalysisCallback {
    void onProgress(String stageId, String stageTitle, int stageIndex, int stageCount, float stageFraction, String message);
    void onFinished(int outcome, String message);
}

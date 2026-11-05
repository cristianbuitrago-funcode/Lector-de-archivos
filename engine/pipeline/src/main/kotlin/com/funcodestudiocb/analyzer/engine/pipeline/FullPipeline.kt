package com.funcodestudiocb.analyzer.engine.pipeline

import com.funcodestudiocb.analyzer.engine.audit.AuditCollector
import com.funcodestudiocb.analyzer.engine.audit.SecurityStage
import com.funcodestudiocb.analyzer.engine.core.Stage
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyCollector
import com.funcodestudiocb.analyzer.engine.dependencies.DependencyStage
import com.funcodestudiocb.analyzer.engine.inventory.ContentCollector
import com.funcodestudiocb.analyzer.engine.inventory.InventoryPipeline
import com.funcodestudiocb.analyzer.engine.relations.RelationCollector
import com.funcodestudiocb.analyzer.engine.relations.RelationStage
import com.funcodestudiocb.analyzer.engine.technologies.TechnologyCatalog
import com.funcodestudiocb.analyzer.engine.technologies.TechnologySignalCollector
import com.funcodestudiocb.analyzer.engine.technologies.TechnologyStage

/**
 * Pipeline completo del análisis hasta la fase actual: ingesta, inventario, lenguajes, dependencias declaradas,
 * tecnologías, relaciones entre archivos y auditoría de seguridad. Todo el contenido se examina en un único recorrido del comprimido.
 */
object FullPipeline {
    fun stages(): List<Stage> {
        val dependencies = DependencyCollector()
        // Si el catálogo no se puede cargar, el análisis sigue sin tecnologías (la etapa lo informa).
        val technologies = TechnologyCatalog.loadDefault()?.let(::TechnologySignalCollector)
        val relations = RelationCollector()
        val audit = AuditCollector()
        val collector = ContentCollector(listOfNotNull(dependencies, technologies, relations, audit))
        return InventoryPipeline.registry(collector)
            .register(DependencyStage(dependencies))
            .register(TechnologyStage(technologies))
            .register(RelationStage(relations))
            .register(SecurityStage(audit))
            .plan()
    }
}

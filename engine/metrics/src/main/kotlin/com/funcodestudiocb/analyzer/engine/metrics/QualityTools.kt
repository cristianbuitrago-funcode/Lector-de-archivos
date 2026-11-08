package com.funcodestudiocb.analyzer.engine.metrics

import com.funcodestudiocb.analyzer.engine.api.QualityTool
import com.funcodestudiocb.analyzer.engine.api.QualityToolKind

/**
 * Herramientas de calidad configuradas en el proyecto: analizadores de código, formateadores, comprobación de tipos e
 * integración continua. Se reconocen por el nombre de su archivo de configuración y, en los archivos de proyecto más
 * comunes (`package.json`, `pyproject.toml`, `build.gradle`…), por la sección o el complemento que las activa.
 */
internal object QualityTools {

    private class ByName(val kind: QualityToolKind, val name: String, val matches: (fileName: String, path: String) -> Boolean)

    private fun exact(kind: QualityToolKind, name: String, vararg files: String) = ByName(kind, name) { f, _ -> f in files }
    private fun prefix(kind: QualityToolKind, name: String, prefix: String) = ByName(kind, name) { f, _ -> f.startsWith(prefix) }

    private val BY_NAME = listOf(
        prefix(QualityToolKind.LINTER, "ESLint", ".eslintrc"),
        ByName(QualityToolKind.LINTER, "ESLint") { f, _ -> f.startsWith("eslint.config.") },
        exact(QualityToolKind.LINTER, "Pylint", ".pylintrc", "pylintrc"),
        exact(QualityToolKind.LINTER, "Flake8", ".flake8"),
        exact(QualityToolKind.LINTER, "Ruff", "ruff.toml", ".ruff.toml"),
        ByName(QualityToolKind.LINTER, "detekt") { f, _ -> f.startsWith("detekt") && (f.endsWith(".yml") || f.endsWith(".yaml")) },
        exact(QualityToolKind.LINTER, "Android Lint", "lint.xml"),
        exact(QualityToolKind.LINTER, "Checkstyle", "checkstyle.xml"),
        exact(QualityToolKind.LINTER, "PMD", "pmd.xml", "ruleset.xml"),
        exact(QualityToolKind.LINTER, "RuboCop", ".rubocop.yml"),
        exact(QualityToolKind.LINTER, "golangci-lint", ".golangci.yml", ".golangci.yaml", ".golangci.toml"),
        exact(QualityToolKind.LINTER, "Clippy", "clippy.toml", ".clippy.toml"),
        exact(QualityToolKind.LINTER, "clang-tidy", ".clang-tidy"),
        exact(QualityToolKind.LINTER, "PHP_CodeSniffer", "phpcs.xml", "phpcs.xml.dist", ".phpcs.xml"),
        exact(QualityToolKind.LINTER, "SwiftLint", ".swiftlint.yml"),
        prefix(QualityToolKind.LINTER, "Stylelint", ".stylelintrc"),
        exact(QualityToolKind.LINTER, "Dart analyzer", "analysis_options.yaml"),
        exact(QualityToolKind.LINTER, "Roslyn analyzers", ".globalconfig"),
        prefix(QualityToolKind.FORMATTER, "Prettier", ".prettierrc"),
        ByName(QualityToolKind.FORMATTER, "Prettier") { f, _ -> f.startsWith("prettier.config.") },
        exact(QualityToolKind.FORMATTER, "EditorConfig", ".editorconfig"),
        exact(QualityToolKind.FORMATTER, "clang-format", ".clang-format"),
        exact(QualityToolKind.FORMATTER, "rustfmt", "rustfmt.toml", ".rustfmt.toml"),
        exact(QualityToolKind.FORMATTER, "PHP CS Fixer", ".php-cs-fixer.php", ".php-cs-fixer.dist.php"),
        exact(QualityToolKind.FORMATTER, "ktlint", ".ktlint"),
        exact(QualityToolKind.TYPE_CHECKER, "mypy", "mypy.ini", ".mypy.ini"),
        ByName(QualityToolKind.TYPE_CHECKER, "PHPStan") { f, _ -> f.startsWith("phpstan.neon") },
        exact(QualityToolKind.TYPE_CHECKER, "Psalm", "psalm.xml", "psalm.xml.dist"),
        ByName(QualityToolKind.CI, "GitHub Actions") { f, p -> "/.github/workflows/" in "/$p" && (f.endsWith(".yml") || f.endsWith(".yaml")) },
        exact(QualityToolKind.CI, "GitLab CI", ".gitlab-ci.yml"),
        exact(QualityToolKind.CI, "Jenkins", "Jenkinsfile"),
        exact(QualityToolKind.CI, "Azure Pipelines", "azure-pipelines.yml", "azure-pipelines.yaml"),
        ByName(QualityToolKind.CI, "CircleCI") { f, p -> "/.circleci/" in "/$p" && f == "config.yml" },
        exact(QualityToolKind.CI, "Bitbucket Pipelines", "bitbucket-pipelines.yml"),
        exact(QualityToolKind.CI, "Travis CI", ".travis.yml"),
        exact(QualityToolKind.CI, "Codemagic", "codemagic.yaml"),
        exact(QualityToolKind.CI, "AppVeyor", "appveyor.yml", ".appveyor.yml"),
        exact(QualityToolKind.CI, "Drone", ".drone.yml"),
        exact(QualityToolKind.CI, "Google Cloud Build", "cloudbuild.yaml", "cloudbuild.yml"),
    )

    /** Archivos de proyecto cuyo contenido puede activar herramientas (se miran en el recorrido del comprimido). */
    val PROJECT_FILES = setOf("package.json", "pyproject.toml", "setup.cfg", "tox.ini", "build.gradle", "build.gradle.kts", "pom.xml", "composer.json")

    private class Marker(val kind: QualityToolKind, val name: String, val needle: String)

    private val MARKERS = listOf(
        Marker(QualityToolKind.LINTER, "ESLint", "\"eslint\""),
        Marker(QualityToolKind.FORMATTER, "Prettier", "\"prettier\""),
        Marker(QualityToolKind.TYPE_CHECKER, "TypeScript (tsc)", "\"typescript\""),
        Marker(QualityToolKind.LINTER, "Ruff", "[tool.ruff"),
        Marker(QualityToolKind.LINTER, "Pylint", "[tool.pylint"),
        Marker(QualityToolKind.LINTER, "Flake8", "[flake8]"),
        Marker(QualityToolKind.FORMATTER, "Black", "[tool.black"),
        Marker(QualityToolKind.FORMATTER, "isort", "[tool.isort"),
        Marker(QualityToolKind.TYPE_CHECKER, "mypy", "[tool.mypy"),
        Marker(QualityToolKind.TYPE_CHECKER, "mypy", "[mypy]"),
        Marker(QualityToolKind.LINTER, "detekt", "detekt"),
        Marker(QualityToolKind.FORMATTER, "ktlint", "ktlint"),
        Marker(QualityToolKind.FORMATTER, "Spotless", "spotless"),
        Marker(QualityToolKind.LINTER, "Checkstyle", "checkstyle"),
        Marker(QualityToolKind.LINTER, "PMD", "maven-pmd-plugin"),
        Marker(QualityToolKind.LINTER, "SpotBugs", "spotbugs"),
        Marker(QualityToolKind.LINTER, "PHP_CodeSniffer", "squizlabs/php_codesniffer"),
        Marker(QualityToolKind.TYPE_CHECKER, "PHPStan", "phpstan/phpstan"),
        Marker(QualityToolKind.FORMATTER, "PHP CS Fixer", "friendsofphp/php-cs-fixer"),
    )

    /** Herramientas reconocidas por el nombre del archivo. */
    fun byPath(path: String): QualityTool? {
        val fileName = path.substringAfterLast('/')
        val hit = BY_NAME.firstOrNull { it.matches(fileName, path) } ?: return null
        return QualityTool(hit.kind, hit.name, path)
    }

    /** Herramientas activadas en un archivo de proyecto ([PROJECT_FILES]). */
    fun inProjectFile(path: String, text: String): List<QualityTool> {
        val sample = if (text.length > MAX_SCAN) text.substring(0, MAX_SCAN) else text
        return MARKERS.filter { sample.contains(it.needle) }.distinctBy { it.name }.map { QualityTool(it.kind, it.name, path) }
    }

    private const val MAX_SCAN = 200_000
}

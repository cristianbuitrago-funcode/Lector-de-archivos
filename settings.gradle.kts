// Repositorios: Google Maven (AndroidX, AGP) y el espejo oficial de Google de Maven Central.
// No se usan repo1.maven.org ni el Gradle Plugin Portal directamente porque limitan las peticiones (HTTP 429)
// en algunos entornos; todos los plugins usados publican sus marcadores en Maven Central o Google Maven.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven-central.storage-download.googleapis.com/maven2") { name = "MavenCentralMirror" }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven-central.storage-download.googleapis.com/maven2") { name = "MavenCentralMirror" }
    }
}

rootProject.name = "FuncodeCodeAnalyzer"

include(":app")
include(":analyzer-service")
include(":data")
include(":engine:api")
include(":engine:core")
include(":engine:ingest")
include(":engine:security")
include(":engine:inventory")
include(":engine:languages")
include(":engine:dependencies")
include(":engine:technologies")
include(":engine:pipeline")
include(":engine:cli")
include(":native:unrar")
include(":native:treesitter")

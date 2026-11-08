plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.funcodestudiocb.analyzer.service"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":engine:core"))
    api(project(":engine:pipeline"))
    // Biblioteca nativa de UnRAR (libfuncode_unrar.so) que carga el lector RAR en el proceso aislado.
    implementation(project(":native:unrar"))
    // tree-sitter y sus gramáticas (libfuncode_treesitter.so) para el análisis sintáctico en el proceso aislado.
    implementation(project(":native:treesitter"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(kotlin("test"))
}

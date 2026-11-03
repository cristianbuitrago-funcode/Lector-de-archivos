import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Firma de publicación: se lee de variables de entorno (CI) o de keystore.properties (local, nunca en git).
val releaseSigning: Map<String, String>? = run {
    val props = Properties()
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(props::load)
    fun value(key: String, env: String) = props.getProperty(key) ?: System.getenv(env)
    val store = value("storeFile", "FUNCODE_KEYSTORE_FILE") ?: return@run null
    mapOf(
        "storeFile" to store,
        "storePassword" to (value("storePassword", "FUNCODE_KEYSTORE_PASSWORD") ?: return@run null),
        "keyAlias" to (value("keyAlias", "FUNCODE_KEY_ALIAS") ?: return@run null),
        "keyPassword" to (value("keyPassword", "FUNCODE_KEY_PASSWORD") ?: return@run null),
    )
}

android {
    namespace = "com.funcodestudiocb.analyzer"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.funcodestudiocb.analyzer"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-fase1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Clave de depuración PÚBLICA y compartida (no es un secreto): permite instalar los APK de
            // prueba de cada fase encima del anterior sin perder los datos.
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseSigning != null) signingConfigs.getByName("release") else null
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric descarga sus SDK desde el espejo de Google de Maven Central.
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://maven-central.storage-download.googleapis.com/maven2",
                )
                // Robolectric (SDK 36) necesita acceder a internos del JDK 21.
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(project(":data"))
    implementation(project(":analyzer-service"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.lifecycle.runtime.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(kotlin("test"))
}

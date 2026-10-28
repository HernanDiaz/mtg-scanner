import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Clave de subida a Google Play: keystore.properties (no va al repositorio) apunta al .jks.
val uploadKey = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.hernan.mtgscanner"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hernan.mtgscanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (uploadKey.isNotEmpty()) create("upload") {
            storeFile = file(uploadKey.getProperty("storeFile"))
            storePassword = uploadKey.getProperty("storePassword")
            keyAlias = uploadKey.getProperty("keyAlias")
            keyPassword = uploadKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        // La librería nativa del OCR ocupa ~11 MB por arquitectura: solo las necesarias.
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") } // x86_64 para el emulador
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") { kotlin.srcDirs("src/main/kotlin") }
        getByName("test") { kotlin.srcDirs("src/test/kotlin") }
        // Las fotos de prueba no van en el APK de tests: tools/run_eval.sh las copia al dispositivo.
        getByName("androidTest") { kotlin.srcDirs("src/androidTest/kotlin") }
    }
}

dependencies {
    // Reconocimiento, búsqueda de nombres, modelos y exportar/importar: comunes con iOS
    implementation(project(":shared"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    // Cámara
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // OCR en el dispositivo (modelo incluido en el APK, funciona sin conexión)
    implementation(libs.mlkit.text.recognition)

    // Imágenes de cartas (bajo demanda, con caché)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.kotlinx.coroutines.android)

    // Copia automática diaria en segundo plano
    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

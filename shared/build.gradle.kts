plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Código común de Android e iPhone: el reconocimiento de cartas, la búsqueda de
 * nombres, el catálogo, la colección, exportar/importar y las pantallas (Compose
 * Multiplatform, con sus textos en composeResources). Lo propio de cada plataforma va
 * en androidMain / iosMain (expect/actual) o en la interfaz Platform.
 *
 * Las pruebas (commonTest) se ejecutan en la JVM (testAndroidHostTest) y, en un Mac,
 * en el simulador de iPhone (iosSimulatorArm64Test; ver .github/workflows).
 */
kotlin {
    android {
        namespace = "com.hernan.mtgscanner.shared"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
        androidResources { enable = true }
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        // ConcurrentCache y DbLock (Platform.kt) son clases expect/actual, aún en beta en Kotlin.
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        optIn.add("kotlinx.cinterop.BetaInteropApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.sqlite)
            implementation(libs.sqlite.framework)
            api(libs.kotlinx.coroutines.core)
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api(compose.components.resources)
            api(libs.jb.icons.core)
            api(libs.jb.lifecycle.viewmodel.compose)
            api(libs.jb.lifecycle.runtime.compose)
            api(libs.coil.compose)
            api(libs.coil.network.ktor)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            // El escáner de Android: cámara (CameraX) y OCR en el dispositivo (ML Kit).
            implementation(libs.camera.core)
            implementation(libs.camera.camera2)
            implementation(libs.camera.lifecycle)
            implementation(libs.camera.view)
            implementation(libs.mlkit.text.recognition)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    // En el paquete de las pantallas: así no hay que importar cada texto (Res.string.cancel).
    publicResClass = true
    packageOfResClass = "com.hernan.mtgscanner.ui"
    generateResClass = always
}

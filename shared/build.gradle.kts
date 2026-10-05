plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

/**
 * Código común de Android e iPhone: el reconocimiento de cartas, la búsqueda de
 * nombres, el catálogo, los modelos y exportar/importar la colección. Lo propio de
 * cada plataforma va en androidMain / iosMain (expect/actual).
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
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        // ConcurrentCache y CatalogLock (Platform.kt) son clases expect/actual, aún en beta en Kotlin.
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        optIn.add("kotlinx.cinterop.BetaInteropApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.sqlite)
            implementation(libs.sqlite.framework)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

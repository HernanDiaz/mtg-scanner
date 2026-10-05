import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

/**
 * Código común de Android e iPhone: el reconocimiento de cartas, la búsqueda de
 * nombres, los modelos y exportar/importar la colección. Sin nada de ninguna
 * plataforma salvo lo de Platform.kt (expect/actual).
 *
 * Las pruebas (commonTest) se ejecutan en la JVM (testDebugUnitTest) y, en un Mac,
 * en el simulador de iPhone (iosSimulatorArm64Test; ver .github/workflows).
 */
kotlin {
    // ConcurrentCache (Platform.kt) es una clase expect/actual, aún en beta en Kotlin.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.hernan.mtgscanner.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

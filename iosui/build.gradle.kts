plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/**
 * La app de iPhone: pantallas en Compose Multiplatform sobre el código común (shared).
 * Se compila como el framework "Shared" que usa el proyecto de Xcode (iosApp/).
 *
 * Va aparte de shared para no mezclar Compose Multiplatform con las versiones de
 * Compose de la app Android mientras sus pantallas no sean comunes.
 */
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            // El catálogo se lee con el SQLite del sistema (NativeSQLiteDriver).
            linkerOpts("-lsqlite3")
        }
    }

    compilerOptions {
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        optIn.add("kotlinx.cinterop.BetaInteropApi")
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

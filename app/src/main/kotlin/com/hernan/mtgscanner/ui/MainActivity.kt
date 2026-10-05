package com.hernan.mtgscanner.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hernan.mtgscanner.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Pantallas comunes con Android (ver shared); el escáner es de Android (CameraX + ML Kit).
            MtgScannerApp(application as App) { ScannerScreen() }
        }
    }
}

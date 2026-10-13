package com.hernan.mtgscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hernan.mtgscanner.scan.FrameRecognizer
import com.hernan.mtgscanner.scan.TextAnalyzer
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

@Composable
actual fun CameraView(vm: ScannerViewModel, recognizer: FrameRecognizer, torch: Boolean) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    // El permiso se pide solo al tocar el botón, no al abrir la app.
    if (!granted) {
        CameraPermissionNeeded { launcher.launch(Manifest.permission.CAMERA) }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // Un solo hilo: el OCR y la sesión de escaneo de un fotograma tras otro.
    val executor = remember { ClosableExecutor() }
    val analyzer = remember(recognizer) { TextAnalyzer(recognizer, executor, { vm.viewSize }, vm::onFrame, vm::reportTiming) }

    DisposableEffect(analyzer, lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // 1080p: el número de coleccionista es muy pequeño y necesita resolución.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            if (future.isDone) future.get().unbindAll()
            camera = null
            analyzer.close()
        }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }
    LaunchedEffect(camera) {
        val cam = camera ?: return@LaunchedEffect
        // El rango de zoom llega cuando la cámara termina de abrirse.
        var range = cam.cameraInfo.zoomState.value
        repeat(50) { if (range == null) { delay(100); range = cam.cameraInfo.zoomState.value } }
        val r = range ?: return@LaunchedEffect
        cam.cameraControl.setZoomRatio(SCAN_ZOOM.coerceIn(r.minZoomRatio, r.maxZoomRatio))
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/**
 * Un hilo para el análisis que, una vez cerrado, descarta lo que le llegue: al salir del
 * escáner, ML Kit aún puede entregar el resultado de un fotograma (y lanzaría
 * RejectedExecutionException en el hilo principal).
 */
private class ClosableExecutor : java.util.concurrent.Executor {
    private val delegate = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    override fun execute(command: Runnable) {
        if (closed) return
        try {
            delegate.execute(command)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    fun shutdown() {
        closed = true
        delegate.shutdown()
    }
}

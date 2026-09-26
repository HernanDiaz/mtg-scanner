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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hernan.mtgscanner.scan.GuideRegion
import com.hernan.mtgscanner.scan.TextAnalyzer
import java.util.concurrent.Executor

@Composable
fun ScannerScreen(vm: ScannerViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("La app necesita la cámara para escanear cartas.")
            Spacer(Modifier.size(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Dar permiso") }
        }
        return
    }

    val ui by vm.ui.collectAsStateWithLifecycle()
    val analyzer by vm.analyzer.collectAsStateWithLifecycle()
    val currency by vm.settings.currency.collectAsStateWithLifecycle()

    // El OCR solo mira dentro del marco guía; necesita saber cómo se ve la cámara.
    var viewSize by remember { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(analyzer, viewSize) {
        analyzer?.viewSize = viewSize?.let { it.width to it.height }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
        analyzer?.let { CameraPreview(it, vm.analysisExecutor, ui.torch) }
        CardGuide()

        // Arriba: total de la colección y lo que está leyendo el OCR.
        Column(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ui.summary.cards} cartas", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(formatPrice(ui.summary.total, currency), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
            }
            Text(
                if (ui.loading) "Cargando catálogo…" else ui.reading ?: "Apunta a una carta",
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        if (ui.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))

        // Abajo: última carta añadida, deshacer y opciones.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            ui.lastAdded?.let { added -> LastAddedCard(added, currency, onUndo = vm::undo) }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = ui.foil, onClick = vm::toggleFoil, label = { Text("Foil") })
                FilterChip(selected = ui.torch, onClick = vm::toggleTorch, label = { Text("Linterna") })
            }
        }
    }
}

@Composable
private fun LastAddedCard(added: AddedCard, currency: com.hernan.mtgscanner.data.Currency, onUndo: () -> Unit) {
    val p = added.printing
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CardThumb(p.smallImageUrl, 52.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(p.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.setName} · #${p.collectorNumber} · ${added.lang.uppercase()}${if (added.foil) " · Foil" else ""}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (added.needsReview) {
                    Text("Edición dudosa: revísala en Colección", style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFB74D))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatPrice(p.prices.of(currency, added.foil), currency), fontWeight = FontWeight.Bold)
                TextButton(onClick = onUndo) { Text("Deshacer") }
            }
        }
    }
}

/** Marco con proporción de carta (63×88 mm): solo se lee el texto de dentro. */
@Composable
private fun CardGuide() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width * GuideRegion.WIDTH_FRACTION
        val h = w * GuideRegion.CARD_ASPECT
        val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.6f), topLeft = topLeft, size = GeoSize(w, h),
            cornerRadius = CornerRadius(w * 0.05f), style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun CameraPreview(analyzer: TextAnalyzer, executor: Executor, torch: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }

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
        }
    }

    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

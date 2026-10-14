package com.hernan.mtgscanner.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.hernan.mtgscanner.scan.FrameRecognizer
import com.hernan.mtgscanner.scan.GuideRegion
import com.hernan.mtgscanner.scan.OcrLine
import com.hernan.mtgscanner.scan.PreparedImage
import com.hernan.mtgscanner.scan.recognizeText
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.focusMode
import platform.AVFoundation.hasTorch
import platform.AVFoundation.isFocusModeSupported
import platform.AVFoundation.requestAccessForMediaType
import platform.AVFoundation.torchMode
import platform.AVFoundation.videoZoomFactor
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.CIContext
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.UIKit.UIView
import kotlinx.cinterop.autoreleasepool
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.time.TimeSource

@Composable
actual fun CameraView(vm: ScannerViewModel, recognizer: FrameRecognizer, torch: Boolean) {
    // Norma 5.1.1 de Apple: el permiso se pide solo cuando el usuario toca el botón (nunca al
    // abrir la app); si lo deniega no se insiste ni se abre Ajustes: solo un texto informativo.
    var status by remember { mutableStateOf(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) }
    when (status) {
        AVAuthorizationStatusAuthorized -> Unit
        AVAuthorizationStatusNotDetermined -> {
            CameraPermissionNeeded {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ ->
                    dispatch_async(dispatch_get_main_queue()) {
                        status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
                    }
                }
            }
            return
        }
        else -> { // denegado o restringido
            CameraPermissionNeeded(onRequest = null)
            return
        }
    }

    val camera = remember(recognizer) { IosCamera(vm, recognizer) }
    DisposableEffect(camera) {
        camera.start()
        onDispose { camera.stop() }
    }
    LaunchedEffect(camera, torch) { camera.setTorch(torch) }
    UIKitView(factory = { camera.preview }, modifier = Modifier.fillMaxSize())
}

/** Vista que mantiene la capa de la cámara del tamaño de la vista. */
private class PreviewView(val layer0: AVCaptureVideoPreviewLayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        layer.addSublayer(layer0)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        layer0.frame = bounds
    }
}

/**
 * La cámara trasera a 1080p con zoom de escaneo ([SCAN_ZOOM]); cada fotograma, girado a
 * vertical, pasa por Vision y el reconocimiento, uno tras otro en su propia cola (los que
 * llegan mientras tanto se descartan).
 */
private class IosCamera(private val vm: ScannerViewModel, private val recognizer: FrameRecognizer) {
    private val session = AVCaptureSession().apply { sessionPreset = AVCaptureSessionPreset1920x1080 }
    private val queue = dispatch_queue_create("mtgscanner.scan", null)
    // La cámara de vídeo por defecto es la trasera gran angular
    private val device: AVCaptureDevice? = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
    private val ciContext = CIContext.contextWithOptions(null)
    private val delegate = FrameDelegate()
    val preview: UIView = PreviewView(AVCaptureVideoPreviewLayer(session = session).apply {
        // Como en Android (FILL_CENTER): llena la vista; el marco guía se calcula igual.
        videoGravity = AVLayerVideoGravityResizeAspectFill
    })

    init {
        device?.let { d ->
            (AVCaptureDeviceInput.deviceInputWithDevice(d, error = null) as? AVCaptureDeviceInput)?.let { input ->
                if (session.canAddInput(input)) session.addInput(input)
            }
            if (d.lockForConfiguration(null)) {
                d.videoZoomFactor = SCAN_ZOOM.toDouble() // todas las cámaras traseras llegan a mucho más
                if (d.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) d.focusMode = AVCaptureFocusModeContinuousAutoFocus
                d.unlockForConfiguration()
            }
        }
        val output = AVCaptureVideoDataOutput().apply {
            alwaysDiscardsLateVideoFrames = true
            // BGRA para copiar los píxeles directamente (PreparedImage.fromCamera).
            // "PixelFormatType" es el valor de kCVPixelBufferPixelFormatTypeKey.
            videoSettings = mapOf<Any?, Any?>("PixelFormatType" to NSNumber(unsignedInt = kCVPixelFormatType_32BGRA))
            setSampleBufferDelegate(delegate, queue)
        }
        if (session.canAddOutput(output)) session.addOutput(output)
    }

    fun start() = dispatch_async(queue) { session.startRunning() }

    fun stop() = dispatch_async(queue) { session.stopRunning() }

    fun setTorch(on: Boolean) {
        val d = device ?: return
        if (!d.hasTorch || !d.lockForConfiguration(null)) return
        d.torchMode = if (on) AVCaptureTorchModeOn else AVCaptureTorchModeOff
        d.unlockForConfiguration()
    }

    /**
     * El OCR solo de la zona del marco guía (Vision tarda según los píxeles que lee; el texto de
     * fuera se descarta igualmente), con las líneas devueltas en coordenadas de la imagen entera.
     */
    private fun recognizeTextIn(cg: CGImageRef, width: Int, height: Int, guide: GuideRegion.Rect?): List<OcrLine> {
        if (guide == null) return recognizeText(cg, width, height)
        val left = guide.left.toInt().coerceIn(0, width - 1)
        val top = guide.top.toInt().coerceIn(0, height - 1)
        val right = guide.right.toInt().coerceIn(left + 1, width)
        val bottom = guide.bottom.toInt().coerceIn(top + 1, height)
        val crop = CGImageCreateWithImageInRect(
            cg, CGRectMake(left.toDouble(), top.toDouble(), (right - left).toDouble(), (bottom - top).toDouble()),
        ) ?: return recognizeText(cg, width, height)
        try {
            return recognizeText(crop, right - left, bottom - top).map {
                it.copy(left = it.left + left, top = it.top + top, right = it.right + left, bottom = it.bottom + top)
            }
        } finally {
            CGImageRelease(crop)
        }
    }

    private inner class FrameDelegate : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
        override fun captureOutput(output: AVCaptureOutput, didOutputSampleBuffer: CMSampleBufferRef?, fromConnection: AVCaptureConnection) {
            autoreleasepool { process(didOutputSampleBuffer) }
        }

        private fun process(sampleBuffer: CMSampleBufferRef?) {
            val started = TimeSource.Monotonic.markNow()
            val pixels = CMSampleBufferGetImageBuffer(sampleBuffer) ?: return
            val image = PreparedImage.fromCamera(pixels) ?: run {
                // Por si la cámara no diera BGRA: girado con Core Image (más lento de soltar).
                // El sensor da la imagen apaisada: se gira a vertical (kCGImagePropertyOrientationRight).
                val ci = CIImage.imageWithCVPixelBuffer(pixels).imageByApplyingOrientation(ORIENTATION_RIGHT)
                PreparedImage.from(ciContext.createCGImage(ci, fromRect = ci.extent) ?: return)
            }
            val cg = image.cgImage
            try {
                val guide = vm.viewSize?.let { (w, h) -> GuideRegion.inImage(w, h, image.width, image.height) }
                var lines = recognizeTextIn(cg, image.width, image.height, guide)
                val ocrDone = TimeSource.Monotonic.markNow()
                guide?.let { lines = GuideRegion.filter(lines, it) }
                val result = recognizer.recognize(lines, image)
                vm.reportTiming((ocrDone - started).inWholeMilliseconds, ocrDone.elapsedNow().inWholeMilliseconds)
                vm.onFrame(result, lines.minByOrNull { it.top }?.text) { image.frame() }
            } finally {
                CGImageRelease(cg)
            }
        }
    }

    private companion object {
        const val ORIENTATION_RIGHT = 6
    }
}

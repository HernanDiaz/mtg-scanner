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
import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.CIContext
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

@Composable
actual fun CameraView(vm: ScannerViewModel, recognizer: FrameRecognizer, torch: Boolean) {
    var status by remember { mutableStateOf(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) }
    fun request() {
        if (status == AVAuthorizationStatusNotDetermined) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ ->
                dispatch_async(dispatch_get_main_queue()) { status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) }
            }
        } else {
            // Denegado antes: iOS ya no vuelve a preguntar, se cambia en Ajustes.
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any?>(), completionHandler = null)
            }
        }
    }
    LaunchedEffect(Unit) { if (status == AVAuthorizationStatusNotDetermined) request() }
    if (status != AVAuthorizationStatusAuthorized) {
        CameraPermissionNeeded(::request)
        return
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

    private inner class FrameDelegate : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
        override fun captureOutput(output: AVCaptureOutput, didOutputSampleBuffer: CMSampleBufferRef?, fromConnection: AVCaptureConnection) {
            val pixels = CMSampleBufferGetImageBuffer(didOutputSampleBuffer) ?: return
            // El sensor da la imagen apaisada: se gira a vertical (kCGImagePropertyOrientationRight).
            val ci = CIImage.imageWithCVPixelBuffer(pixels).imageByApplyingOrientation(ORIENTATION_RIGHT)
            val cg = ciContext.createCGImage(ci, fromRect = ci.extent) ?: return
            try {
                val image = PreparedImage.from(cg)
                var lines = recognizeText(cg, image.width, image.height)
                vm.viewSize?.let { (w, h) -> lines = GuideRegion.filter(lines, GuideRegion.inImage(w, h, image.width, image.height)) }
                val result = recognizer.recognize(lines, image)
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

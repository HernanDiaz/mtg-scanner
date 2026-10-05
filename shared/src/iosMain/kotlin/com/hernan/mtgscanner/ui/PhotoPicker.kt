package com.hernan.mtgscanner.ui

import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** UIKit no retiene el delegado del selector: se guarda aquí mientras está abierto. */
private var pickerDelegate: PickerDelegate? = null

/**
 * Elegir una foto de la fototeca (sin pedir permiso: el selector de iOS solo da a la
 * app la foto elegida). [onImage] recibe null si se cancela; siempre en el hilo principal.
 */
fun pickPhoto(onImage: (UIImage?) -> Unit) {
    val config = PHPickerConfiguration().apply {
        filter = PHPickerFilter.imagesFilter
        selectionLimit = 1
    }
    val picker = PHPickerViewController(configuration = config)
    val delegate = PickerDelegate { image ->
        pickerDelegate = null
        onImage(image)
    }
    pickerDelegate = delegate
    picker.delegate = delegate
    topViewController()?.presentViewController(picker, animated = true, completion = null)
}

private class PickerDelegate(private val onImage: (UIImage?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) {
            onImage(null)
            return
        }
        result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data: NSData?, _: NSError? ->
            val image = data?.let { UIImage(data = it) }
            dispatch_async(dispatch_get_main_queue()) { onImage(image) }
        }
    }
}

/** El controlador que está en pantalla, para presentar otro encima. */
@Suppress("DEPRECATION")
fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

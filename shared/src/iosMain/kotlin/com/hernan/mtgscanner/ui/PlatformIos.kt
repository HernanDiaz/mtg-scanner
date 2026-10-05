package com.hernan.mtgscanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

/** UIKit no retiene el delegado del selector: se guarda aquí mientras está abierto. */
private var documentDelegate: DocumentDelegate? = null

private class DocumentDelegate(private val onPicked: (NSURL) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        documentDelegate = null
        (didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.let(onPicked)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        documentDelegate = null
    }
}

private fun present(picker: UIDocumentPickerViewController, onPicked: (NSURL) -> Unit) {
    val delegate = DocumentDelegate(onPicked)
    documentDelegate = delegate
    picker.delegate = delegate
    topViewController()?.presentViewController(picker, animated = true, completion = null)
}

/**
 * Elegir un fichero (Archivos, iCloud Drive...). Se abre una copia dentro de la app
 * (asCopy): así se puede leer sin permisos especiales.
 */
@Composable
actual fun rememberOpenDocument(onPicked: (String) -> Unit): () -> Unit = remember(onPicked) {
    {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
        present(picker) { url -> onPicked(url.absoluteString ?: return@present) }
    }
}

/**
 * Crear un fichero donde elija el usuario: se prepara uno vacío con el nombre propuesto y
 * el selector lo mueve allí (Archivos, iCloud Drive...). Devuelve su dirección, que la app
 * puede seguir escribiendo (ver IosFiles.write y IosBackupTarget).
 */
@Composable
actual fun rememberCreateDocument(mime: String, onCreated: (String) -> Unit): (suggestedName: String) -> Unit = remember(onCreated) {
    { name ->
        val dir = "${NSTemporaryDirectory()}create-${kotlin.random.Random.nextInt(1_000_000)}"
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val path = "$dir/$name"
        fm.createFileAtPath(path, contents = null, attributes = null)
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(path)), asCopy = false)
        present(picker) { url -> onCreated(url.absoluteString ?: return@present) }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}

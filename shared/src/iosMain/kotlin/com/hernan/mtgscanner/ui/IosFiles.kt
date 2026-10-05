package com.hernan.mtgscanner.ui

import com.hernan.mtgscanner.scan.toByteArray
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController

/**
 * Ficheros en iPhone: los elegidos con el selector de documentos (ver PlatformIos) y el
 * menú de compartir del sistema (UIActivityViewController).
 */
class IosFiles : PlatformFiles {

    override suspend fun read(uri: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val url = NSURL.URLWithString(uri) ?: error("dirección no válida")
        withAccess(url) {
            val data = NSData.dataWithContentsOfURL(url) ?: error("no se puede leer")
            require(data.length.toLong() <= maxBytes) { "> ${maxBytes / 1_048_576} MB" }
            data.toByteArray()
        }
    }

    override fun displayName(uri: String): String? = NSURL.URLWithString(uri)?.lastPathComponent

    override suspend fun write(uri: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val url = NSURL.URLWithString(uri) ?: error("dirección no válida")
        withAccess(url) {
            // Sin escritura atómica: en iCloud Drive y otros proveedores no se puede crear un temporal al lado.
            if (!bytes.toNSData().writeToURL(url, atomically = false)) error("no se puede escribir")
        }
    }

    override fun exportPath(name: String): String {
        val dir = "${NSTemporaryDirectory()}exports"
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val ext = name.substringAfterLast('.', "")
        fm.contentsOfDirectoryAtPath(dir, error = null)?.filterIsInstance<String>()
            ?.filter { it.endsWith(".$ext") }?.forEach { fm.removeItemAtPath("$dir/$it", error = null) }
        return "$dir/$name"
    }

    override fun sharePath(path: String, mime: String, subject: String?, title: String) = present(listOf(NSURL.fileURLWithPath(path)))

    override fun shareText(text: String, subject: String, title: String) = present(listOf(text))

    private fun present(items: List<Any>) {
        val top = topViewController() ?: return
        val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
        // En iPad el menú sale como globo y necesita un ancla.
        sheet.popoverPresentationController?.sourceView = top.view
        top.presentViewController(sheet, animated = true, completion = null)
    }
}

/** Ficheros fuera de la app (iCloud Drive, otros proveedores): acceso de seguridad mientras se usan. */
inline fun <T> withAccess(url: NSURL, block: () -> T): T {
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        return block()
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}

fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

package com.hernan.mtgscanner.scan

/**
 * Un fotograma de la cámara ya derecho (Bitmap en Android, CGImage en iPhone), para
 * guardarlo como foto de la carta o foto de prueba.
 */
interface ScanFrame {
    val width: Int
    val height: Int

    /** JPEG con el lado mayor de como mucho [maxSide] píxeles. */
    fun jpeg(maxSide: Int = Int.MAX_VALUE, quality: Int = 90): ByteArray
}

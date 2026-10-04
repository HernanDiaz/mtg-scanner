package com.hernan.mtgscanner.data

import android.graphics.Bitmap
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fotos de las cartas: con la opción activada, al escanear una carta se guarda el
 * fotograma del que se reconoció, una foto por copia (por entrada de la colección).
 * Se ve en su ficha y puede ir en la exportación y en la copia automática (un zip con
 * el CSV y las fotos).
 *
 * Van en noBackupFilesDir: la copia de Android tiene un límite de 25 MB.
 */
class CardPhotos(private val dir: File) {

    private fun file(entryId: Long) = File(dir, "$entryId.jpg")

    @Synchronized
    fun save(entryId: Long, bitmap: Bitmap) {
        dir.mkdirs()
        val scaled = scaleDown(bitmap)
        val tmp = File(dir, "$entryId.tmp")
        tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        tmp.renameTo(file(entryId))
        if (scaled !== bitmap) scaled.recycle()
    }

    /** Las fotos de estas copias, de la más reciente a la más antigua. */
    fun of(entryIds: Collection<Long>): List<File> =
        entryIds.sortedDescending().map(::file).filter { it.exists() }

    fun count(): Int = photoFiles().size

    /** Espacio que ocupan, en bytes. */
    fun size(): Long = photoFiles().sumOf { it.length() }

    /** Borra las fotos de copias que ya no están en la colección. */
    @Synchronized
    fun removeOrphans(existing: Set<Long>) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.substringBefore('.').toLongOrNull()
            if (id == null || id !in existing || f.name.endsWith(".tmp")) f.delete()
        }
    }

    @Synchronized
    fun clear() {
        dir.deleteRecursively()
    }

    /** Cambia si cambian las fotos (para no repetir una copia igual). */
    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        photoFiles().sortedBy { it.name }.forEach { digest.update("${it.name}:${it.length()};".toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Zip con [csvName] ([csv], que nombra las fotos de cada línea en su columna de fotos)
     * y las fotos en "fotos/".
     */
    fun zip(out: OutputStream, csvName: String, csv: String, photoNames: Map<Long, String>) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(csvName))
            zip.write(csv.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((id, name) in photoNames) {
                val f = file(id).takeIf { it.exists() } ?: continue
                // Las JPEG ya están comprimidas: guardarlas sin volver a comprimir es más rápido.
                zip.putNextEntry(ZipEntry("$FOLDER/$name"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun photoFiles(): List<File> = dir.listFiles { f -> f.name.endsWith(".jpg") }?.toList().orEmpty()

    companion object {
        const val FOLDER = "fotos"
        /** Lado mayor de la foto guardada: se lee bien la carta y ocupa ~150 KB. */
        private const val MAX_SIDE = 1280
        private const val JPEG_QUALITY = 85

        private fun scaleDown(b: Bitmap): Bitmap {
            val side = maxOf(b.width, b.height)
            if (side <= MAX_SIDE) return b
            val k = MAX_SIDE.toFloat() / side
            return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
        }

        /** "Lightning Bolt (M11 149) 1234.jpg": se entiende qué es al abrir el zip. */
        fun photoName(entryId: Long, line: CollectionExport.Line): String {
            val safe = line.name.replace(Regex("[\\\\/:*?\"<>|]"), "").take(60)
            return "$safe (${line.setCode.uppercase()} ${line.collectorNumber}) $entryId.jpg"
        }
    }
}

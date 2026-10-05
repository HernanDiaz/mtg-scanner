package com.hernan.mtgscanner.data

import com.hernan.mtgscanner.scan.ScanFrame
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Fotos de las cartas: con la opción activada, al escanear una carta se guarda el
 * fotograma del que se reconoció, una foto por copia (por entrada de la colección).
 * Se ve en su ficha y puede ir en la exportación y en la copia automática (un zip con
 * el CSV y las fotos).
 *
 * [dirPath]: en Android, noBackupFilesDir (la copia de Android tiene un límite de 25 MB).
 */
class CardPhotos(dirPath: String) {
    private val fs = FileSystem.SYSTEM
    private val dir = dirPath.toPath()
    private val lock = DbLock()

    private fun file(entryId: Long) = dir / "$entryId.jpg"

    fun save(entryId: Long, frame: ScanFrame) = lock.withLock {
        fs.createDirectories(dir)
        val tmp = dir / "$entryId.tmp"
        fs.write(tmp) { write(frame.jpeg(MAX_SIDE, JPEG_QUALITY)) }
        fs.atomicMove(tmp, file(entryId))
    }

    /** Las fotos de estas copias, de la más reciente a la más antigua. */
    fun files(entryIds: Collection<Long>): List<Path> = entryIds.sortedDescending().map(::file).filter(fs::exists)

    /** Rutas de las fotos de estas copias, de la más reciente a la más antigua. */
    fun of(entryIds: Collection<Long>): List<String> = files(entryIds).map { it.toString() }

    /** Copias con foto. */
    fun ids(): Set<Long> = photoFiles().mapNotNull { it.name.substringBefore('.').toLongOrNull() }.toSet()

    fun count(): Int = photoFiles().size

    /** Espacio que ocupan, en bytes. */
    fun size(): Long = photoFiles().sumOf { fs.metadataOrNull(it)?.size ?: 0 }

    /** Borra las fotos de copias que ya no están en la colección. */
    fun removeOrphans(existing: Set<Long>) = lock.withLock {
        fs.listOrNull(dir)?.forEach { f ->
            val id = f.name.substringBefore('.').toLongOrNull()
            if (id == null || id !in existing || f.name.endsWith(".tmp")) fs.delete(f)
        }
    }

    fun clear() = lock.withLock { fs.deleteRecursively(dir) }

    /** Cambia si cambian las fotos (para no repetir una copia igual). */
    fun fingerprint(): String =
        fnv64(photoFiles().sortedBy { it.name }.map { "${it.name}:${fs.metadataOrNull(it)?.size};" })

    /**
     * Zip con [csvName] ([csv], que nombra las fotos de cada línea en su columna de fotos)
     * y las fotos en "fotos/".
     */
    fun zip(sink: BufferedSink, csvName: String, csv: String, photoNames: Map<Long, String>) {
        ZipWriter(sink).use { zip ->
            zip.add(csvName, csv.encodeToByteArray())
            for ((id, name) in photoNames) {
                val f = file(id).takeIf(fs::exists) ?: continue
                zip.add("${CollectionExport.PHOTOS_FOLDER}/$name", fs.read(f) { readByteArray() })
            }
        }
    }

    private fun photoFiles(): List<Path> = fs.listOrNull(dir)?.filter { it.name.endsWith(".jpg") }.orEmpty()

    private companion object {
        /** Lado mayor de la foto guardada: se lee bien la carta y ocupa ~150 KB. */
        const val MAX_SIDE = 1280
        const val JPEG_QUALITY = 85
    }
}

/** Resumen de 64 bits (FNV-1a) para notar cambios; no es criptográfico. */
fun fnv64(parts: List<String>): String {
    var hash = -0x340d631b7bdddcdbL
    for (part in parts) {
        for (b in part.encodeToByteArray()) hash = (hash xor (b.toLong() and 0xFF)) * 0x100000001b3L
        hash = (hash xor 0x0A) * 0x100000001b3L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

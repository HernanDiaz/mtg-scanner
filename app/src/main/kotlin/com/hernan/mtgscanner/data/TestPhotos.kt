package com.hernan.mtgscanner.data

import android.graphics.Bitmap
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fotos de prueba: con el modo activado, al añadir una carta se guarda el fotograma
 * de la cámara del que se reconoció y lo que la app creyó que era. Al exportar, la
 * respuesta correcta de cada foto es su carta tal como está entonces en la colección
 * (después de que el usuario corrija edición o idioma), así que revisar la colección
 * es etiquetar las fotos. El zip se evalúa en el PC como los demás conjuntos
 * (testset/<nombre>/ con labels.csv).
 *
 * Van en noBackupFilesDir: ni ocupan la copia de Android ni se restauran.
 */
class TestPhotos(private val dir: File) {

    /**
     * [entryId]: la entrada añadida; null si se guardó a mano (la app no la reconoció).
     * [viewW]/[viewH]: tamaño de la vista de la cámara, para situar el marco guía.
     */
    data class Photo(
        val file: String,
        val entryId: Long?,
        val gotName: String?,
        val gotSet: String?,
        val gotNumber: String?,
        val gotExact: Boolean,
        val gotLang: String?,
        val viewW: Int,
        val viewH: Int,
        val time: Long,
    )

    private val index get() = File(dir, INDEX)

    @Synchronized
    fun save(bitmap: Bitmap, photo: Photo) {
        dir.mkdirs()
        File(dir, photo.file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        index.appendText(listOf(
            photo.file, photo.entryId?.toString().orEmpty(), photo.gotName.orEmpty(), photo.gotSet.orEmpty(),
            photo.gotNumber.orEmpty(), if (photo.gotExact) "1" else "0", photo.gotLang.orEmpty(),
            photo.viewW.toString(), photo.viewH.toString(), photo.time.toString(),
        ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } + "\n")
    }

    @Synchronized
    fun photos(): List<Photo> {
        if (!index.exists()) return emptyList()
        return index.readLines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 10 || !File(dir, f[0]).exists()) return@mapNotNull null
            Photo(f[0], f[1].toLongOrNull(), f[2].ifEmpty { null }, f[3].ifEmpty { null }, f[4].ifEmpty { null },
                f[5] == "1", f[6].ifEmpty { null }, f[7].toInt(), f[8].toInt(), f[9].toLong())
        }
    }

    fun count() = photos().size

    /** Espacio que ocupan, en bytes. */
    fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    /** Zip con las fotos y labels.csv (ver [labels]). */
    @Synchronized
    fun export(out: File, entriesNow: (Collection<Long>) -> Map<Long, CollectionStore.EntryNow>): Int {
        val photos = photos()
        val now = entriesNow(photos.mapNotNull { it.entryId })
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("labels.csv"))
            zip.write(labels(photos, now).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (p in photos) {
                zip.putNextEntry(ZipEntry(p.file))
                File(dir, p.file).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return photos.size
    }

    @Synchronized
    fun clear() {
        dir.deleteRecursively()
    }

    companion object {
        private const val INDEX = "index.tsv"
        private const val JPEG_QUALITY = 92

        fun fileName(time: Long, seq: Int) = "movil_${time}_$seq.jpg"

        /**
         * labels.csv con las columnas de los conjuntos de prueba (file, card_id, name,
         * set_code, collector_number, lang, group, difficulty) más lo que dijo la app.
         * Grupos: "movil" (carta en la colección), "movil_sin_revisar" (sigue marcada
         * para revisar: la edición no está confirmada), "movil_borrada" (se borró: lectura
         * errónea o repetida) y "movil_manual" (guardada a mano: no la reconoció). En las
         * dos últimas la respuesta hay que ponerla mirando la foto.
         */
        fun labels(photos: List<Photo>, now: Map<Long, CollectionStore.EntryNow>): String = buildString {
            row("file", "card_id", "name", "set_code", "collector_number", "lang", "group", "difficulty",
                "view_w", "view_h", "app_name", "app_set", "app_number", "app_exact", "app_lang")
            for (p in photos) {
                val e = p.entryId?.let(now::get)
                val group = when {
                    p.entryId == null -> "movil_manual"
                    e == null -> "movil_borrada"
                    e.needsReview -> "movil_sin_revisar"
                    else -> "movil"
                }
                row(p.file, e?.cardId.orEmpty(), e?.name.orEmpty(), e?.setCode.orEmpty(), e?.collectorNumber.orEmpty(),
                    e?.lang.orEmpty(), group, "real", p.viewW.toString(), p.viewH.toString(),
                    p.gotName.orEmpty(), p.gotSet.orEmpty(), p.gotNumber.orEmpty(), if (p.gotExact) "1" else "0",
                    p.gotLang.orEmpty())
            }
        }

        private fun StringBuilder.row(vararg fields: String) {
            append(fields.joinToString(",") { s ->
                if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
            }).append('\n')
        }
    }
}

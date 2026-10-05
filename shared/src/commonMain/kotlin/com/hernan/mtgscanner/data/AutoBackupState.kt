package com.hernan.mtgscanner.data

/**
 * Estado de la copia automática, para mostrarlo: el fichero elegido (su dirección en el
 * sistema, como texto), la última copia y el último error. Con fotos, un segundo fichero.
 */
data class AutoBackupState(
    val file: String? = null,
    val fileName: String? = null,
    /** Última copia escrita (ms), o 0. */
    val lastSaved: Long = 0,
    val lastCards: Int = 0,
    /** Último intento sin cambios que copiar (ms), o 0. */
    val lastChecked: Long = 0,
    val lastError: String? = null,
    /** Con fotos: un segundo fichero, un zip con el CSV y las fotos. */
    val photosFile: String? = null,
    val photosFileName: String? = null,
    val lastPhotosSaved: Long = 0,
    val lastPhotos: Int = 0,
) {
    companion object {
        const val FILE_NAME = "mtg-scanner-auto.csv"
        const val PHOTOS_FILE_NAME = "mtg-scanner-auto-fotos.zip"
    }
}

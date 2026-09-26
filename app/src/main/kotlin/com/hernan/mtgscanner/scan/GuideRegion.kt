package com.hernan.mtgscanner.scan

/**
 * El marco guía que se dibuja sobre la cámara, traducido a coordenadas de la
 * imagen que analiza el OCR. Solo se tiene en cuenta el texto de dentro: así
 * las cartas de alrededor (un mazo, otra carta en la mesa) no interfieren.
 */
object GuideRegion {
    /** Ancho del marco respecto a la vista; alto según la proporción de carta 63×88. */
    const val WIDTH_FRACTION = 0.78f
    const val CARD_ASPECT = 88f / 63f

    /**
     * Margen extra alrededor del marco: nadie alinea la carta a la perfección y
     * es fácil acercarla de más. Una carta hasta un 30 % mayor que el marco se lee entera.
     */
    private const val MARGIN = 0.30f

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    }

    /**
     * La vista de la cámara (PreviewView en FILL_CENTER) escala la imagen para
     * llenar [viewW]×[viewH] recortando lo que sobra, centrada. El marco está
     * centrado en la vista, así que también en la imagen.
     */
    fun inImage(viewW: Int, viewH: Int, imageW: Int, imageH: Int): Rect {
        val scale = maxOf(viewW.toFloat() / imageW, viewH.toFloat() / imageH)
        val gw = WIDTH_FRACTION * viewW / scale * (1 + MARGIN)
        val gh = WIDTH_FRACTION * viewW * CARD_ASPECT / scale * (1 + MARGIN)
        val cx = imageW / 2f
        val cy = imageH / 2f
        return Rect(cx - gw / 2, cy - gh / 2, cx + gw / 2, cy + gh / 2)
    }

    fun filter(lines: List<OcrLine>, region: Rect): List<OcrLine> =
        lines.filter { region.contains((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) }
}

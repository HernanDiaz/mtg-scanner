package com.hernan.mtgscanner.scan

/**
 * Decide cuándo un fotograma reconocido se convierte en una carta añadida,
 * sin que el usuario toque nada:
 *  - Una carta se acepta cuando se lee igual en 2 fotogramas seguidos
 *    (filtra lecturas erróneas sueltas).
 *  - Si la impresión es ambigua, se espera un poco a que el OCR lea la línea
 *    de coleccionista; si no llega, se añade la más probable marcada para revisar.
 *  - La carta añadida queda "bloqueada" para no contarla en cada fotograma.
 *    Se considera que hay una carta nueva cuando aparece otro nombre, otra
 *    impresión exacta del mismo nombre (otro pantano de otra edición), o
 *    cuando la carta sale del marco un momento (dos copias idénticas seguidas).
 */
class ScanSession(
    private val confirmFrames: Int = 2,
    private val ambiguousWaitMs: Long = 900,
    // Medido con secuencias simuladas: una carta quieta no deja de leerse ni un
    // fotograma, y al cambiar de carta la mano tapa el marco ~400 ms o más.
    private val goneAfterMs: Long = 350,
) {
    private var lockedName: String? = null
    /** Impresión bloqueada, solo si se identificó con exactitud. */
    private var lockedPrintingId: String? = null
    private var lockedLastSeen = 0L

    private var candidate: FrameResult? = null
    private var candidateFrames = 0
    private var candidateSince = 0L
    private var candidateLastSeen = 0L

    /** Devuelve la carta a añadir, o null. */
    fun onFrame(frame: FrameResult?, now: Long): FrameResult? {
        if (frame == null) {
            if (lockedName != null && now - lockedLastSeen > goneAfterMs) unlockLocked()
            if (candidate != null && now - candidateLastSeen > goneAfterMs) {
                // Se fue mientras se esperaba la edición: se añade igualmente (para revisar).
                return flushCandidate(candidateLastSeen)
            }
            return null
        }

        if (frame.name == lockedName && !isOtherExactPrinting(frame)) {
            lockedLastSeen = now
            candidate = null
            return null
        }

        val current = candidate
        if (current == null || current.name != frame.name) {
            // Llega otra carta mientras la anterior esperaba la edición: esa no se pierde.
            val flushed = flushCandidate(now)
            candidate = frame
            candidateFrames = 1
            candidateSince = now
            candidateLastSeen = now
            return flushed
        }

        candidateFrames++
        candidateLastSeen = now
        // Una lectura exacta sustituye a una ambigua; entre exactas, gana la más reciente.
        if (frame.exact || !current.exact) candidate = frame
        val best = candidate!!

        if (candidateFrames >= confirmFrames && (best.exact || now - candidateSince >= ambiguousWaitMs)) {
            lockedName = best.name
            lockedPrintingId = if (best.exact) best.printing.id else null
            lockedLastSeen = now
            candidate = null
            return best
        }
        return null
    }

    /**
     * Acepta el candidato pendiente si se llegó a confirmar (visto en varios
     * fotogramas) y lo bloquea, por si vuelve a leerse tras una lectura errónea suelta.
     */
    private fun flushCandidate(lastSeen: Long): FrameResult? {
        val c = candidate ?: return null
        candidate = null
        if (candidateFrames < confirmFrames) return null
        lockedName = c.name
        lockedPrintingId = if (c.exact) c.printing.id else null
        lockedLastSeen = lastSeen
        return c
    }

    private fun isOtherExactPrinting(frame: FrameResult): Boolean {
        val locked = lockedPrintingId ?: return false
        return frame.exact && frame.printing.id != locked
    }

    private fun unlockLocked() {
        lockedName = null
        lockedPrintingId = null
    }

    /** Tras deshacer, permite volver a escanear la misma carta de inmediato. */
    fun unlock() {
        unlockLocked()
        candidate = null
    }
}

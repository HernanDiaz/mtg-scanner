package com.hernan.mtgscanner.scan

/**
 * Decide cuándo un fotograma reconocido se convierte en una carta añadida,
 * sin que el usuario toque nada:
 *  - Una lectura exacta (edición y número confirman el nombre) se acepta al
 *    momento; las demás, cuando se leen igual en 2 fotogramas seguidos
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
            if (flushed != null) return flushed
            // Una lectura exacta (nombre confirmado por edición y número) de otra carta no es un
            // error suelto: se acepta ya, sin esperar al segundo fotograma. En un móvil lento
            // (iPhone: ~1 s por fotograma) es la mitad de espera entre carta y carta. Otra
            // impresión del mismo nombre que la que está en cámara sí espera: un número mal leído
            // un instante ("371" por "372") contaría otro pantano.
            return if (frame.exact && frame.name != lockedName) accept(frame, now) else null
        }

        candidateFrames++
        candidateLastSeen = now
        // Una lectura exacta sustituye a una ambigua; entre exactas, gana la más reciente.
        if (frame.exact || !current.exact) candidate = frame
        val best = candidate!!

        if (best.exact || (candidateFrames >= confirmFrames && now - candidateSince >= ambiguousWaitMs)) {
            return accept(best, now)
        }
        return null
    }

    /** Añade [card] y la bloquea para no volver a contarla mientras siga en cámara. */
    private fun accept(card: FrameResult, now: Long): FrameResult {
        lockedName = card.name
        lockedPrintingId = if (card.exact) card.printing.id else null
        lockedLastSeen = now
        candidate = null
        return card
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

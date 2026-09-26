package com.hernan.mtgscanner

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.hernan.mtgscanner.data.Catalog
import com.hernan.mtgscanner.scan.ArtSampler
import com.hernan.mtgscanner.scan.ArtSignature
import com.hernan.mtgscanner.scan.FrameRecognizer
import com.hernan.mtgscanner.scan.GuideRegion
import com.hernan.mtgscanner.scan.OcrLine
import com.hernan.mtgscanner.scan.ScanSession
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Simula escanear cartas seguidas: cada secuencia de testset/secuencias pasa
 * fotograma a fotograma por OCR -> marco guía -> reconocedor -> ScanSession,
 * con los instantes del fichero (5 fps, como un móvil). Compara lo añadido
 * con lo esperado: cartas perdidas, duplicadas o equivocadas.
 */
@RunWith(AndroidJUnit4::class)
class SequenceEval {

    @Test fun sequences() {
        val instr = InstrumentationRegistry.getInstrumentation()
        // Fotogramas copiados al dispositivo por tools/run_sequences.sh.
        val root = File(instr.targetContext.getExternalFilesDir(null)!!, "testset/secuencias")
        val catalog = Catalog(instr.targetContext)
        val recognizer = FrameRecognizer(catalog.names, catalog.setCodes, catalog::printingsOf, catalog::bySetAndNumber, catalog::art)
        val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val summary = StringBuilder("secuencia\tesperadas\tañadidas\tbien\tperdidas\tsobrantes\tdetalle\n")
        val frameLog = StringBuilder("secuencia\tfotograma\tt_ms\tleido\texacta\tañadida\n")

        for (seq in root.list().orEmpty().sorted()) {
            val frames = csv(File(root, "$seq/frames.csv").readText())
            val expected = csv(File(root, "$seq/expected.csv").readText()).map { it[0] }
            val session = ScanSession()
            val added = mutableListOf<String>()

            for ((file, t) in frames.map { it[0] to it[1].toLong() }) {
                val bitmap = BitmapFactory.decodeFile(File(root, "$seq/$file").path)
                val text = Tasks.await(ocr.process(InputImage.fromBitmap(bitmap, 0)))
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    l.boundingBox?.let { OcrLine(l.text, it.left, it.top, it.right, it.bottom) }
                }
                val viewH = bitmap.width * 2200 / 1080
                val inGuide = GuideRegion.filter(lines, GuideRegion.inImage(bitmap.width, viewH, bitmap.width, bitmap.height))
                val art = ArtSampler { l, t, r, b ->
                    ArtSignature.compute(l, t, r, b) { x, y ->
                        bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1)) and 0xFFFFFF
                    }
                }
                val result = recognizer.recognize(inGuide, art)
                val accepted = session.onFrame(result, t)
                accepted?.let { added.add(it.name) }
                frameLog.append("$seq\t$file\t$t\t${result?.name ?: ""}\t${result?.exact ?: ""}\t${accepted?.name ?: ""}\n")
            }

            val (ok, missed, extra) = compare(expected, added)
            summary.append("$seq\t${expected.size}\t${added.size}\t$ok\t${missed.size}\t${extra.size}\t")
                .append("perdidas=${missed.joinToString("|")} sobrantes=${extra.joinToString("|")}\n")
        }

        val dir = instr.targetContext.getExternalFilesDir(null)!!
        File(dir, "eval_secuencias_summary.tsv").writeText(summary.toString())
        File(dir, "eval_secuencias.tsv").writeText(frameLog.toString())
        Log.i("SequenceEval", summary.toString())
        ocr.close()
    }

    /** Alinea esperado y añadido (subsecuencia común más larga): aciertos, perdidas y sobrantes. */
    private fun compare(expected: List<String>, added: List<String>): Triple<Int, List<String>, List<String>> {
        val n = expected.size
        val m = added.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (expected[i] == added[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val missed = mutableListOf<String>()
        val extra = mutableListOf<String>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                expected[i] == added[j] -> { i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> missed.add(expected[i++])
                else -> extra.add(added[j++])
            }
        }
        while (i < n) missed.add(expected[i++])
        while (j < m) extra.add(added[j++])
        return Triple(dp[0][0], missed, extra)
    }

    /** CSV sencillo con comillas; sin cabecera. */
    private fun csv(text: String): List<List<String>> =
        text.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val fields = mutableListOf<String>()
            val sb = StringBuilder()
            var quoted = false
            var k = 0
            while (k < line.length) {
                val c = line[k]
                when {
                    quoted && c == '"' && k + 1 < line.length && line[k + 1] == '"' -> { sb.append('"'); k++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { fields.add(sb.toString()); sb.clear() }
                    c != '\r' -> sb.append(c)
                }
                k++
            }
            fields.add(sb.toString())
            fields
        }.toList()
}

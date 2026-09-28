package com.hernan.mtgscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pasa las fotos de un subconjunto de testset/ por el mismo OCR y reconocedor
 * que la app y escribe un informe por foto en files/eval_<subconjunto>.tsv.
 *
 * Las fotos se copian antes al dispositivo (tools/run_eval.sh), en
 * files/testset/<subconjunto>/: son demasiadas para ir dentro del APK de pruebas.
 * Subconjunto y marco guía se eligen con argumentos de instrumentación:
 *   -e subset espanol_todo -e guide true
 * No falla por aciertos bajos: sirve para medir, no para bloquear el build.
 */
@RunWith(AndroidJUnit4::class)
class RecognitionEval {

    @Test fun evaluate() {
        val args = InstrumentationRegistry.getArguments()
        val subset = args.getString("subset")
        assumeTrue("Falta -e subset <nombre>", subset != null)
        // Las fotos sintéticas simulan un móvil (1080x1920): se aplica el marco guía como
        // en la app, con una pantalla típica de 1080x2200. Las reales no van encuadradas.
        evaluate(
            subset!!,
            guide = args.getString("guide", "true").toBoolean(),
            useArt = args.getString("art", "true").toBoolean(),
            useSymbols = args.getString("symbols", "true").toBoolean(),
        )
    }

    private fun evaluate(subset: String, guide: Boolean, useArt: Boolean, useSymbols: Boolean) {
        val instr = InstrumentationRegistry.getInstrumentation()
        val filesDir = instr.targetContext.getExternalFilesDir(null)!!
        val dir = File(filesDir, "testset/$subset")
        val catalog = appCatalog()
        val recognizer = FrameRecognizer(catalog.names, catalog.setCodes, catalog::printingsOf, catalog::bySetAndNumber, catalog::art,
            if (useSymbols) catalog.symbols else null, catalog::nameLanguages, catalog::setLanguages)
        val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val rows = parseCsv(File(dir, "labels.csv").readText())
        val header = rows.first()
        val out = StringBuilder(
            "file\tgroup\tdifficulty\toutcome\texpected\tgot\tgot_id\tset_read\tnumber_read\tms\tlang_expected\tlang_read\tlines\tart_true\tart_debug\tsymbol_debug\n"
        )
        val counts = sortedMapOf<String, Int>()
        var dumped = 0
        File(filesDir, "symwin_$subset").deleteRecursively()

        for (values in rows.drop(1)) {
            val r = header.zip(values).toMap()
            val bitmap = BitmapFactory.decodeFile(File(dir, r.getValue("file")).path)?.fitTo(1920) ?: continue

            val t0 = SystemClock.elapsedRealtime()
            val text = Tasks.await(ocr.process(InputImage.fromBitmap(bitmap, 0)))
            var lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                l.boundingBox?.let { OcrLine(l.text, it.left, it.top, it.right, it.bottom) }
            }
            if (guide) {
                val viewH = bitmap.width * 2200 / 1080
                lines = GuideRegion.filter(lines, GuideRegion.inImage(bitmap.width, viewH, bitmap.width, bitmap.height))
            }
            val art = if (!useArt) null else ArtSampler { x, y ->
                bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1)) and 0xFFFFFF
            }
            val result = recognizer.recognize(lines, art)
            val ms = SystemClock.elapsedRealtime() - t0
            // Las zonas donde se buscó el símbolo, para revisarlas a ojo.
            recognizer.lastSymbolWindow?.let { win ->
                if (dumped++ < 150) {
                    val bmp = Bitmap.createBitmap(win.w, win.h, Bitmap.Config.ARGB_8888)
                    for (y in 0 until win.h) for (x in 0 until win.w) {
                        val i = (y * win.w + x) * 3
                        fun c(k: Int) = (win.rgb[i + k] * 255).toInt().coerceIn(0, 255)
                        bmp.setPixel(x, y, (0xFF shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2))
                    }
                    val dir = File(filesDir, "symwin_$subset").apply { mkdirs() }
                    File(dir, r.getValue("file").substringBeforeLast('.') + ".png").outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }

            val expectedId = r["card_id"].orEmpty()
            val samePrinting = result != null && (result.printing.id == expectedId ||
                printingKey(result.printing.setCode, result.printing.collectorNumber) ==
                printingKey(r["set_code"].orEmpty(), r["collector_number"].orEmpty()))
            val outcome = when {
                result == null -> "no_leida"
                samePrinting && result.exact -> "exacta"
                samePrinting -> "dudosa_acierta"
                result.name != r["name"] -> "nombre_mal"
                expectedId.isEmpty() -> "nombre_ok"
                !result.exact -> "dudosa_revisar"
                else -> "CONFIADA_MAL" // lo peor: edición errónea sin avisar
            }
            val langExpected = r["lang"].orEmpty().ifEmpty { "en" }
            counts.merge(outcome, 1, Int::plus)
            counts.merge("${r["group"]}:$outcome", 1, Int::plus)
            if (result != null) counts.merge("idioma_${if (result.lang == langExpected) "ok" else "mal"}", 1, Int::plus)
            if (result?.byArt == true) counts.merge("por_imagen_${if (samePrinting) "bien" else "mal"}", 1, Int::plus)
            if (result?.bySymbol == true) {
                counts.merge("por_simbolo_${if (samePrinting) "bien" else "mal"}${if (result.exact) "_exacta" else ""}", 1, Int::plus)
            }
            out.append(listOf(
                r["file"], r["group"], r["difficulty"], outcome,
                "${r["name"]} [${r["set_code"]} ${r["collector_number"]}]",
                result?.let { "${it.name} [${it.printing.setCode} ${it.printing.collectorNumber}]" } ?: "",
                result?.printing?.id ?: "",
                result?.collector?.setCode ?: "", result?.collector?.number ?: "",
                ms.toString(), langExpected, result?.lang ?: "",
                lines.sortedBy { it.top }.joinToString(" | ") { it.text.replace('\t', ' ') },
                catalog.byId(expectedId)?.artId?.toString() ?: "",
                recognizer.artDebug ?: "",
                recognizer.symbolDebug ?: "",
            ).joinToString("\t")).append('\n')
        }

        val summary = counts.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        File(filesDir, "eval_$subset.tsv").writeText(out.toString())
        File(filesDir, "eval_${subset}_summary.tsv").writeText(summary + "\n")
        Log.i("RecognitionEval", "$subset:\n$summary")
        ocr.close()
    }

    /**
     * Impresiones físicamente indistinguibles cuentan como la misma: las de
     * The List ("plst ORI-123" = "ori 123") y las variantes ★ ("143★" = "143").
     */
    private fun printingKey(set: String, number: String): String {
        if (set == "plst" && '-' in number) {
            val (s, n) = number.split('-', limit = 2)
            return "${s.lowercase()}:${n.trimStart('0')}"
        }
        return "$set:${number.trimEnd('★').trimStart('0')}"
    }

    private fun Bitmap.fitTo(maxSide: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return this
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * s).toInt(), (height * s).toInt(), true)
    }

    /** CSV mínimo con comillas (los nombres llevan comas: "M'Baku, Jabari Chieftain"). */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (line in text.lineSequence().filter { it.isNotBlank() }) {
            val fields = mutableListOf<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { fields.add(sb.toString()); sb.clear() }
                    c != '\r' -> sb.append(c)
                }
                i++
            }
            fields.add(sb.toString())
            rows.add(fields)
        }
        return rows
    }
}

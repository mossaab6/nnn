package com.smartcash.analyzer.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device OCR (Arabic + English) using Tesseract. Works offline and without any API key.
 * Language files ship in assets/tessdata and are copied once to private storage.
 */
class TesseractOcr(private val context: Context) {

    data class Line(val text: String, val box: Rect, val confidence: Float)
    data class Word(val text: String, val box: Rect)
    data class Result(val text: String, val lines: List<Line>, val words: List<Word>, val meanConfidence: Int)

    private val mutex = Mutex()
    private val engines = HashMap<String, TessBaseAPI>()

    private fun dataDir(): File {
        val root = File(context.filesDir, "tesseract")
        val tessdata = File(root, "tessdata").apply { mkdirs() }
        for (name in context.assets.list("tessdata").orEmpty()) {
            val target = File(tessdata, name)
            if (target.exists() && target.length() > 0) continue
            val tmp = File(tessdata, "$name.tmp")
            context.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(target)
        }
        return root
    }

    private fun engine(lang: String): TessBaseAPI {
        engines[lang]?.let { return it }
        val api = TessBaseAPI()
        if (!api.init(dataDir().absolutePath, lang, TessBaseAPI.OEM_LSTM_ONLY)) {
            api.recycle()
            throw IllegalStateException("تعذر تشغيل محرك OCR للغة $lang")
        }
        engines[lang] = api
        return api
    }

    /**
     * Recognizes [bitmap]. [lang] "ara+eng" reads Arabic labels best, "eng+ara" reads Latin names/numbers best.
     */
    suspend fun recognize(
        bitmap: Bitmap,
        lang: String = "ara+eng",
        psm: Int = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
        withWords: Boolean = false,
    ): Result = withContext(Dispatchers.Default) {
        mutex.withLock {
            val api = engine(lang)
            api.setPageSegMode(psm)
            api.setImage(bitmap)
            val text = api.getUTF8Text().orEmpty()
            val lines = ArrayList<Line>()
            val words = ArrayList<Word>()
            val iter = api.getResultIterator()
            if (iter != null) {
                try {
                    iter.begin()
                    do {
                        val t = iter.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                        if (!t.isNullOrBlank()) {
                            lines += Line(t.trim(), iter.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE), iter.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                        }
                    } while (iter.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                    if (withWords) {
                        iter.begin()
                        do {
                            val w = iter.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                            if (!w.isNullOrBlank()) words += Word(w.trim(), iter.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                        } while (iter.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                    }
                } finally {
                    iter.delete()
                }
            }
            val conf = api.meanConfidence()
            api.clear()
            Result(text, lines, words, conf)
        }
    }

    fun release() {
        engines.values.forEach { runCatching { it.recycle() } }
        engines.clear()
    }
}

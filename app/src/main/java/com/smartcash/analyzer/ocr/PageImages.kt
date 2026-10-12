package com.smartcash.analyzer.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** Turns receipt files (PDF pages or images) into white-background bitmaps suitable for OCR and AI vision. */
object PageImages {

    /** ~200 dpi for an A4 page: the resolution at which Tesseract reads Sham Cash receipts reliably. */
    const val OCR_WIDTH = 1654

    fun isPdf(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()

    fun renderPdf(file: File, targetWidth: Int = OCR_WIDTH, maxPages: Int = 3): List<Bitmap> {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = min(renderer.pageCount, maxPages)
                return (0 until count).map { i ->
                    renderer.openPage(i).use { page ->
                        val scale = targetWidth.toFloat() / page.width
                        val w = targetWidth
                        val h = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE) // PDF pages render on a transparent background
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }
    }

    fun decodeImage(bytes: ByteArray, maxWidth: Int = 2000): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        // flatten transparency onto white
        val out = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, 0f, 0f, null)
        }
        if (out !== decoded) decoded.recycle()
        return out
    }

    /** Loads pages from a stored file (PDF or image). */
    fun load(file: File): List<Bitmap> {
        val head = file.inputStream().use { s -> ByteArray(5).also { s.read(it) } }
        return if (isPdf(head)) renderPdf(file) else listOfNotNull(decodeImage(file.readBytes()))
    }

    /** JPEG bytes for sending to the AI (smaller than PNG, still sharp enough for text). */
    fun toJpeg(bitmap: Bitmap, maxWidth: Int = 1400, quality: Int = 88): ByteArray {
        val scaled = if (bitmap.width > maxWidth) {
            val h = (bitmap.height * (maxWidth.toFloat() / bitmap.width)).roundToInt()
            Bitmap.createScaledBitmap(bitmap, maxWidth, h, true)
        } else bitmap
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled !== bitmap) scaled.recycle()
            out.toByteArray()
        }
    }
}

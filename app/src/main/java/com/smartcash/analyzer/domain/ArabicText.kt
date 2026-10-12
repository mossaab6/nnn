package com.smartcash.analyzer.domain

import java.text.Normalizer

/**
 * Text helpers for mixed Arabic / Latin content coming from OCR, AI and notifications.
 *
 * Display text is only cleaned (invisible bidi marks removed, whitespace collapsed) so names keep
 * every letter, dot and digit exactly as printed. Matching uses [nameKey], a separate normalized form.
 */
object ArabicText {

    private val invisible = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF\\u061C\\u00AD]")
    private val diacritics = Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val presentationForms = Regex("[\\uFB50-\\uFDFF\\uFE70-\\uFEFE]")
    private val whitespace = Regex("[\\s\\u00A0]+")
    private val edgePunctuation = Regex("^[\\s.,:;،؛\\-_'\"«»()\\[\\]]+|[\\s.,:;،؛\\-_'\"«»()\\[\\]]+$")

    fun stripInvisible(s: String): String = s.replace(invisible, "")

    /** Converts Arabic-Indic and Eastern Arabic-Indic digits (and separators) to ASCII. */
    fun normalizeDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                '٫' -> sb.append('.') // Arabic decimal separator
                '٬' -> sb.append(',') // Arabic thousands separator
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Cleans text for display while preserving the exact spelling of names. */
    fun clean(s: String?): String? {
        if (s == null) return null
        var t = stripInvisible(s)
        if (presentationForms.containsMatchIn(t)) {
            // PDF/OCR sometimes emits Arabic "presentation forms" (contextual glyphs); fold them back to letters.
            t = Normalizer.normalize(t, Normalizer.Form.NFKC)
        }
        t = t.replace(whitespace, " ").trim()
        return t.ifEmpty { null }
    }

    /** Like [clean] but also trims stray punctuation that OCR leaves around a value. */
    fun cleanValue(s: String?): String? = clean(s)?.replace(edgePunctuation, "")?.trim()?.ifEmpty { null }

    /** Cleans a person name: removes invisible marks, edge punctuation (but keeps inner dots like "Ahmad.77"). */
    fun cleanName(s: String?): String? {
        val c = clean(s) ?: return null
        // keep trailing digits/letters; only strip punctuation hanging on the edges (e.g. ": abu_ahmad")
        val stripped = c.replace(Regex("^[\\s:;،؛\\-_'\"«»()\\[\\]]+|[\\s:;،؛\\-'\"«»()\\[\\]]+$"), "").trim()
        return stripped.ifEmpty { null }
    }

    /**
     * Normalized key used to group the same person and to compare names:
     * lower-case, no diacritics/tatweel, unified alef/yaa/taa-marbuta forms, collapsed spaces.
     */
    fun nameKey(s: String?): String {
        val c = clean(s) ?: return ""
        var t = normalizeDigits(c).lowercase()
        t = t.replace(diacritics, "").replace("ـ", "")
        val sb = StringBuilder(t.length)
        for (ch in t) {
            sb.append(
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    'ة' -> 'ه'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    else -> ch
                }
            )
        }
        return sb.toString().replace(edgePunctuation, "").replace(whitespace, " ").trim()
    }

    /** Only letters and digits of [nameKey]; tolerant to OCR punctuation noise. */
    fun looseKey(s: String?): String = nameKey(s).filter { it.isLetterOrDigit() }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    /** 0..1 similarity of two strings (1 = identical). */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val max = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / max
    }

    /** True when two names very likely refer to the same person (tolerates small OCR differences). */
    fun sameName(a: String?, b: String?): Boolean {
        val ka = looseKey(a)
        val kb = looseKey(b)
        if (ka.isEmpty() || kb.isEmpty()) return false
        if (ka == kb) return true
        if (ka.length >= 4 && kb.length >= 4 && (ka.contains(kb) || kb.contains(ka))) return true
        return similarity(ka, kb) >= 0.85
    }

    fun containsArabic(s: String): Boolean = s.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }

    /** Digits only (after normalizing Arabic digits). */
    fun digits(s: String?): String = if (s == null) "" else normalizeDigits(s).filter { it in '0'..'9' }

    /** Wraps text in left-to-right marks so amounts like "-193 $" render correctly inside Arabic text. */
    fun ltr(s: String): String = "‎$s‎"
}

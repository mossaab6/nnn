package com.smartcash.analyzer.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

object Money {
    val supportedCurrencies = listOf("USD", "SYP", "EUR", "TRY")

    private val symbols = mapOf(
        "USD" to "$",
        "SYP" to "ل.س",
        "EUR" to "€",
        "TRY" to "₺",
    )

    private val names = mapOf(
        "USD" to "دولار أمريكي",
        "SYP" to "ليرة سورية",
        "EUR" to "يورو",
        "TRY" to "ليرة تركية",
    )

    fun symbol(currency: String): String = symbols[currency] ?: currency
    fun displayName(currency: String): String = names[currency] ?: currency

    fun round2(v: Double): Double = round(v * 100.0) / 100.0

    fun format(amount: Double): String {
        val df = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US))
        return df.format(round2(amount))
    }

    /** "193 $" */
    fun withCurrency(amount: Double, currency: String): String = "${format(amount)} ${symbol(currency)}"

    /** "-193 $" for sent, "+100 $" for received. */
    fun signed(amount: Double, currency: String, type: TxType): String {
        val sign = if (type == TxType.SENT) "-" else "+"
        return "$sign${withCurrency(abs(amount), currency)}"
    }
}

/** Parses amounts such as "$ 193", "1,250.50 USD", "٥٠٠ ل.س", "193$" and detects the currency. */
object AmountParser {

    data class Amount(val value: Double, val currency: String?)

    private data class CurrencyMarker(val code: String, val regex: Regex)

    // Order matters: more specific markers first (e.g. Turkish lira before generic "ليرة").
    private val markers = listOf(
        CurrencyMarker("TRY", Regex("(?i)(\\bTRY\\b|\\bTL\\b|₺|ليرة\\s*تركية|ليره\\s*تركيه)")),
        CurrencyMarker("EUR", Regex("(?i)(\\bEUR\\b|€|يورو)")),
        CurrencyMarker("USD", Regex("(?i)(\\bUSD\\b|US\\$|\\$|دولار|دولاراً|دولارا)")),
        CurrencyMarker("SYP", Regex("(?i)(\\bSYP\\b|\\bS\\.?P\\b|ل\\.?\\s?س|ليرة|ليره|ل\\.س\\.)")),
    )

    private val number = Regex(
        "(?<![\\d.,])(" +
            "\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?" + // 1,250,000.50
            "|\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?" + // 1.250.000,50
            "|\\d{1,3}(?:\\s\\d{3})+(?:[.,]\\d+)?" + // 1 250 000
            "|\\d+(?:[.,]\\d+)?" +
            ")(?!\\d)"
    )

    fun detectCurrency(text: String): String? {
        val t = ArabicText.stripInvisible(text)
        var best: Pair<Int, String>? = null
        for (m in markers) {
            val match = m.regex.find(t) ?: continue
            if (best == null || match.range.first < best.first) best = match.range.first to m.code
        }
        return best?.second
    }

    fun parseNumber(raw: String): Double? {
        var s = ArabicText.normalizeDigits(raw).trim().replace(" ", " ")
        if (s.isEmpty()) return null
        s = s.replace(" ", "")
        val hasComma = s.contains(',')
        val hasDot = s.contains('.')
        s = when {
            hasComma && hasDot -> if (s.lastIndexOf(',') > s.lastIndexOf('.')) {
                s.replace(".", "").replace(',', '.') // 1.250,50
            } else {
                s.replace(",", "") // 1,250.50
            }
            hasDot && s.count { it == '.' } > 1 -> s.replace(".", "") // 1.250.000
            hasComma -> {
                val parts = s.split(',')
                if (parts.size > 1 && parts.drop(1).all { it.length == 3 }) s.replace(",", "") else s.replace(',', '.')
            }
            else -> s
        }
        return s.toDoubleOrNull()
    }

    /** Finds the first amount in [text]. Currency is detected anywhere in the same text. */
    fun parse(text: String?): Amount? {
        if (text.isNullOrBlank()) return null
        val t = ArabicText.normalizeDigits(ArabicText.stripInvisible(text))
        val m = number.find(t) ?: return null
        val value = parseNumber(m.value) ?: return null
        return Amount(value, detectCurrency(t))
    }

    /** All amounts with their position, used by notification parsing. */
    fun findAll(text: String): List<Pair<IntRange, Double>> {
        val t = ArabicText.normalizeDigits(ArabicText.stripInvisible(text))
        return number.findAll(t).mapNotNull { m -> parseNumber(m.value)?.let { m.range to it } }.toList()
    }

    /** Maps a free-form currency string coming from AI or user input to a code. */
    fun normalizeCurrency(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val up = raw.trim().uppercase()
        if (up in Money.supportedCurrencies) return up
        return detectCurrency(raw) ?: up.takeIf { it.length == 3 && it.all { c -> c in 'A'..'Z' } }
    }
}

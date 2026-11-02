package com.dmarket.p2p.tracker.engine

/**
 * Whether a Steam persona name poses as DMarket: look-alike letters, leetspeak, separators and one-typo variants of
 * the brand. Pure; [SuspiciousOfferCheck] uses it on the partners of active sent offers.
 */
object DmarketImpersonation {
    private val LEET = mapOf('0' to 'o', '1' to 'l', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's')

    private val CONFUSABLES: Map<Char, Char> = buildMap {
        // Cyrillic and Greek letters drawn like Latin ones, lower then upper case.
        "аеорсктмхԁɗαεοκτμ".zip("aeopcktmxddaeoktm").forEach { (from, to) -> put(from, to) }
        "АЕОРСКТМХВΑΕΟΚΤΜΒ".zip("aeopcktmxbaeoktmb").forEach { (from, to) -> put(from, to) }
    }

    /** Precomposed letters with diacritics, folded to their base letter (the part of NFKD a name check needs). */
    private val DIACRITICS: Map<Char, Char> = buildMap {
        mapOf(
            'a' to "àáâãäåāăąǎ", 'A' to "ÀÁÂÃÄÅĀĂĄǍ", 'c' to "çćĉċč", 'C' to "ÇĆĈĊČ", 'd' to "ďđ", 'D' to "ĎĐ",
            'e' to "èéêëēĕėęě", 'E' to "ÈÉÊËĒĔĖĘĚ", 'g' to "ĝğġģ", 'G' to "ĜĞĠĢ", 'h' to "ĥħ", 'H' to "ĤĦ",
            'i' to "ìíîïĩīĭįı", 'I' to "ÌÍÎÏĨĪĬĮİ", 'j' to "ĵ", 'J' to "Ĵ", 'k' to "ķ", 'K' to "Ķ", 'l' to "ĺļľŀł",
            'L' to "ĹĻĽĿŁ", 'n' to "ñńņňŉ", 'N' to "ÑŃŅŇ", 'o' to "òóôõöøōŏőǒ", 'O' to "ÒÓÔÕÖØŌŎŐǑ", 'r' to "ŕŗř",
            'R' to "ŔŖŘ", 's' to "śŝşš", 'S' to "ŚŜŞŠ", 't' to "ţťŧ", 'T' to "ŢŤŦ", 'u' to "ùúûüũūŭůűųǔ",
            'U' to "ÙÚÛÜŨŪŬŮŰŲǓ", 'w' to "ŵ", 'W' to "Ŵ", 'y' to "ýÿŷ", 'Y' to "ÝŸŶ", 'z' to "źżž", 'Z' to "ŹŻŽ",
            'е' to "ё", 'Е' to "Ё",
        ).forEach { (base, variants) -> variants.forEach { put(it, base) } }
    }

    private val BRAND = listOf("dmarket", "dmarketbot", "dmarketp2p", "dmarkettrade", "dmarketsupport", "dmarketofficial")

    /**
     * Lower-case `[a-z0-9]` skeleton of [name] with leetspeak and look-alike letters mapped to Latin.
     * shortcut: NFKD is approximated by full-width ASCII, combining marks U+0300..036F and [DIACRITICS] only, so
     * other compatibility forms (math alphanumerics, ligatures) are dropped rather than folded; use a real
     * normalizer if they show up in scam names.
     */
    fun normalizeName(name: String): String = buildString {
        for (raw in name) {
            val wide = if (raw in '\uFF01'..'\uFF5E') raw - 0xFEE0 else raw
            if (wide in '\u0300'..'\u036F') continue
            val c = (CONFUSABLES[wide] ?: DIACRITICS[wide]?.let { CONFUSABLES[it] ?: it } ?: wide).lowercaseChar()
            if (c in 'a'..'z' || c in '0'..'9' || c == '@' || c == '$') append(LEET[c] ?: c)
        }
    }

    /** Optimal string alignment distance: insertions, deletions, substitutions and adjacent swaps. */
    fun damerauLevenshtein(a: String, b: String): Int {
        val d = Array(a.length + 1) { i ->
            IntArray(b.length + 1) { j ->
                if (i == 0) {
                    j
                } else if (j == 0) {
                    i
                } else {
                    0
                }
            }
        }
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
        return d[a.length][b.length]
    }

    /**
     * Short names DMarket's own trading bots go by ("DM Bot"). A partner with one of them in its name is matched
     * as a substring, like the brand: a lookalike of our bot is exactly what a seller is told to trust.
     */
    private val BOT_NAMES = listOf("dmbot")

    /**
     * True when [name] contains the brand or a DMarket bot name, or starts with `d` and is one edit from a brand
     * name (so `market` is not).
     */
    fun impersonates(name: String): Boolean {
        val n = normalizeName(name)
        return "dmarket" in n ||
            BOT_NAMES.any { it in n } ||
            (n.startsWith("d") && BRAND.any { damerauLevenshtein(n, it) <= 1 })
    }
}

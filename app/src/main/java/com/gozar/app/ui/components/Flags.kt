package com.gozar.app.ui.components

/**
 * Aggregator config names almost always carry a location hint — either a flag
 * emoji already, or a bare country code. Surfacing it turns an unreadable list
 * of hostnames into something scannable.
 */
object Flags {

    // A flag emoji is two regional-indicator symbols, each a surrogate pair.
    private val FLAG_EMOJI = Regex("[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]")

    // A standalone two-letter code: "US | fast", "[DE] node", "node-NL-03".
    private val COUNTRY_CODE = Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])")

    private val KNOWN = setOf(
        "AE", "AL", "AM", "AR", "AT", "AU", "AZ", "BE", "BG", "BH", "BR", "BY",
        "CA", "CH", "CL", "CN", "CO", "CY", "CZ", "DE", "DK", "EE", "EG", "ES",
        "FI", "FR", "GB", "GE", "GR", "HK", "HR", "HU", "ID", "IE", "IL", "IN",
        "IQ", "IR", "IS", "IT", "JP", "KR", "KW", "KZ", "LT", "LU", "LV", "MD",
        "MX", "MY", "NL", "NO", "NZ", "OM", "PH", "PL", "PT", "QA", "RO", "RS",
        "RU", "SA", "SE", "SG", "SI", "SK", "TH", "TR", "TW", "UA", "US", "UZ",
        "VN", "ZA",
    )

    /** @return a flag emoji for the name, or null when no location is evident. */
    fun forName(name: String): String? {
        FLAG_EMOJI.find(name)?.let { return it.value }
        val code = COUNTRY_CODE.findAll(name)
            .map { it.groupValues[1] }
            .firstOrNull { it in KNOWN }
            ?: return null
        return code.map { char ->
            // 'A' maps to U+1F1E6; the offset turns letters into indicators.
            String(Character.toChars(0x1F1E6 + (char - 'A')))
        }.joinToString("")
    }

    /** The name with any leading flag emoji and separator stripped. */
    fun stripFlag(name: String): String =
        FLAG_EMOJI.replace(name, "").trim().trimStart('|', '-', '_', ' ').ifBlank { name }
}

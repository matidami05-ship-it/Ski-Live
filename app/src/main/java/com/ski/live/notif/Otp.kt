package com.ski.live.notif

/** Finds one-time codes ("123456 is your verification code") in notification text. */
object Otp {
    private val keyword = Regex(
        "(?i)(\\bcode\\b|\\bOTP\\b|passcode|pass code|verif|one[- ]time|\\b2FA\\b|two[- ]factor|" +
            "security|log ?in|sign[- ]?in|\\bPIN\\b|token|authenticat|confirm|c[oó]digo|\\bcódigo\\b)",
    )

    private val candidate = Regex("(?<![\\w$£€.,/:])(?<!\\d-)(\\d{3}[- ]\\d{3}|\\d{4,8}|[A-Z0-9]{6,8})(?![\\w%/:]|-\\d)")

    fun find(text: String): String? {
        if (text.isBlank()) return null
        val keys = keyword.findAll(text).map { it.range.first }.toList()
        if (keys.isEmpty()) return null
        val found = candidate.findAll(text).mapNotNull { m ->
            val raw = m.value
            val code = raw.replace("-", "").replace(" ", "")
            val digits = code.count { it.isDigit() }
            val ok = when {
                code.all { it.isDigit() } -> code.length in 4..8
                else -> digits >= 2 && code.any { it.isLetter() } // e.g. "AB12CD"
            }
            if (!ok) null else m.range.first to code
        }.toList()
        if (found.isEmpty()) return null
        // Drop 4-digit years when there is anything better.
        val pool = found.filterNot { (_, c) -> c.length == 4 && c.toInt() in 1900..2099 }.ifEmpty { found }
        // Prefer all-digit codes, then the one nearest a keyword.
        return pool.minWith(
            compareBy<Pair<Int, String>> { (_, c) -> if (c.all(Char::isDigit)) 0 else 1 }
                .thenBy { (pos, _) -> keys.minOf { k -> kotlin.math.abs(k - pos) } },
        ).second
    }
}

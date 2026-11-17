package com.example.data.crypto

object PhoneNormalizer {
    private val stripRegex = Regex("[\\s\\-.()]")
    private val e164Regex = Regex("^\\+[1-9]\\d{6,14}$")
    private val domainRegex = Regex("^[a-zA-Z0-9.-]+(:\\d{1,5})?$")

    fun normalize(raw: String): String? {
        val stripped = raw.trim().replace(stripRegex, "")
        if (stripped.isEmpty()) return null
        val candidate = when {
            stripped.startsWith("+") -> stripped
            stripped.length == 11 && stripped.startsWith("8") && stripped.all { it.isDigit() } ->
                "+7" + stripped.substring(1)
            stripped.all { it.isDigit() } -> "+$stripped"
            else -> return null
        }
        return if (e164Regex.matches(candidate)) candidate else null
    }

    fun isValidE164(phone: String): Boolean = e164Regex.matches(phone)

    fun normalizeDomain(raw: String): String? {
        var d = raw.trim()
        if (d.startsWith("https://", ignoreCase = true)) d = d.substring(8)
        if (d.startsWith("http://", ignoreCase = true)) d = d.substring(7)
        if (d.startsWith("wss://", ignoreCase = true)) d = d.substring(6)
        if (d.startsWith("ws://", ignoreCase = true)) d = d.substring(5)
        val slashIdx = d.indexOf('/')
        if (slashIdx >= 0) d = d.substring(0, slashIdx)
        d = d.trimEnd('/')
        if (d.isEmpty() || !d.contains('.')) return null
        return if (domainRegex.matches(d)) d else null
    }
}

package com.ohmyjapan.smsrelay

data class TriggerRule(
    val type: String,    // "sender_exact", "body_contains", "sender_contains", "all"
    val pattern: String,
    var enabled: Boolean = true,
    val label: String = ""  // "KB입금", "우리입금", etc.
) {
    fun matches(body: String, sender: String): Boolean {
        if (!enabled) return false
        return when (type) {
            "sender_exact" -> {
                val normalized = sender.replace("-", "").replace("+82", "0").replace(" ", "")
                val target = pattern.replace("-", "").replace(" ", "")
                normalized.contains(target)
            }
            "body_contains" -> body.contains(pattern, ignoreCase = true)
            "sender_contains" -> sender.contains(pattern, ignoreCase = true)
            "all" -> true
            else -> false
        }
    }

    fun displayName(): String = when (type) {
        "sender_exact" -> "${label.ifEmpty { "Phone" }}: $pattern"
        "body_contains" -> "Body: $pattern"
        "sender_contains" -> "Sender: $pattern"
        "all" -> "All SMS"
        else -> pattern
    }
}

package com.ohmyjapan.smsrelay

data class TriggerRule(
    val type: String,    // "body_contains", "sender_contains", "all"
    val pattern: String, // match text (ignored for "all")
    var enabled: Boolean = true
) {
    fun matches(body: String, sender: String): Boolean {
        if (!enabled) return false
        return when (type) {
            "body_contains" -> body.contains(pattern, ignoreCase = true)
            "sender_contains" -> sender.contains(pattern, ignoreCase = true)
            "all" -> true
            else -> false
        }
    }

    fun displayName(): String = when (type) {
        "body_contains" -> "Body: $pattern"
        "sender_contains" -> "Sender: $pattern"
        "all" -> "All SMS"
        else -> pattern
    }
}

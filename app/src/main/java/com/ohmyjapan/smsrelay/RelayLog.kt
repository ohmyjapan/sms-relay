package com.ohmyjapan.smsrelay

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class LogEntry(
    val timestamp: Long,
    val sender: String,
    val bodySnippet: String,
    val matchedRule: String,
    var status: String, // "delivered", "retrying", "failed"
    var attempts: Int = 1
)

object RelayLog {
    private const val NAME = "sms_relay_log"
    private const val KEY_ENTRIES = "log_entries"
    private const val KEY_TODAY_COUNT = "today_count"
    private const val KEY_TODAY_DATE = "today_date"
    private const val KEY_RETRY_COUNT = "retry_count"
    private const val KEY_FAIL_COUNT = "fail_count"
    private const val MAX_ENTRIES = 20

    private val gson = Gson()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun addEntry(ctx: Context, entry: LogEntry) {
        val entries = getEntries(ctx).toMutableList()
        entries.add(0, entry)
        if (entries.size > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size).clear()
        }
        prefs(ctx).edit().putString(KEY_ENTRIES, gson.toJson(entries)).apply()
        incrementTodayCount(ctx)
    }

    fun updateStatus(ctx: Context, timestamp: Long, status: String, attempts: Int) {
        val entries = getEntries(ctx).toMutableList()
        val entry = entries.find { it.timestamp == timestamp }
        if (entry != null) {
            entry.status = status
            entry.attempts = attempts
            prefs(ctx).edit().putString(KEY_ENTRIES, gson.toJson(entries)).apply()

            if (status == "failed") {
                val fails = prefs(ctx).getInt(KEY_FAIL_COUNT, 0)
                prefs(ctx).edit().putInt(KEY_FAIL_COUNT, fails + 1).apply()
            }
        }
    }

    fun getEntries(ctx: Context): List<LogEntry> {
        val json = prefs(ctx).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<LogEntry>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getTodayCount(ctx: Context): Int {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val savedDate = prefs(ctx).getString(KEY_TODAY_DATE, "")
        if (savedDate != today) {
            prefs(ctx).edit()
                .putString(KEY_TODAY_DATE, today)
                .putInt(KEY_TODAY_COUNT, 0)
                .putInt(KEY_RETRY_COUNT, 0)
                .putInt(KEY_FAIL_COUNT, 0)
                .apply()
            return 0
        }
        return prefs(ctx).getInt(KEY_TODAY_COUNT, 0)
    }

    fun getRetryCount(ctx: Context): Int = prefs(ctx).getInt(KEY_RETRY_COUNT, 0)
    fun getFailCount(ctx: Context): Int = prefs(ctx).getInt(KEY_FAIL_COUNT, 0)

    fun incrementRetryCount(ctx: Context) {
        val count = prefs(ctx).getInt(KEY_RETRY_COUNT, 0)
        prefs(ctx).edit().putInt(KEY_RETRY_COUNT, count + 1).apply()
    }

    private fun incrementTodayCount(ctx: Context) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val savedDate = prefs(ctx).getString(KEY_TODAY_DATE, "")
        if (savedDate != today) {
            prefs(ctx).edit()
                .putString(KEY_TODAY_DATE, today)
                .putInt(KEY_TODAY_COUNT, 1)
                .apply()
        } else {
            val count = prefs(ctx).getInt(KEY_TODAY_COUNT, 0)
            prefs(ctx).edit().putInt(KEY_TODAY_COUNT, count + 1).apply()
        }
    }
}

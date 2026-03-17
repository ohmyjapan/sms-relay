package com.ohmyjapan.smsrelay

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object Prefs {
    private const val NAME = "sms_relay_prefs"
    private const val KEY_URL = "server_url"
    private const val KEY_TRIGGERS = "trigger_rules"
    private const val KEY_ENABLED = "relay_enabled"

    private val gson = Gson()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getServerUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_URL, "http://100.79.23.111:3100/api/bank/transfer") ?: ""

    fun setServerUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_URL, url).apply()
    }

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getTriggerRules(ctx: Context): MutableList<TriggerRule> {
        val json = prefs(ctx).getString(KEY_TRIGGERS, null) ?: return getDefaultRules()
        return try {
            val type = object : TypeToken<MutableList<TriggerRule>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            getDefaultRules()
        }
    }

    fun setTriggerRules(ctx: Context, rules: List<TriggerRule>) {
        prefs(ctx).edit().putString(KEY_TRIGGERS, gson.toJson(rules)).apply()
    }

    private fun getDefaultRules(): MutableList<TriggerRule> = mutableListOf(
        TriggerRule("body_contains", "[KB]", true),
        TriggerRule("body_contains", "[우리]", true),
        TriggerRule("body_contains", "[하나]", true),
        TriggerRule("body_contains", "[신한]", true),
        TriggerRule("body_contains", "[NH]", true),
    )
}

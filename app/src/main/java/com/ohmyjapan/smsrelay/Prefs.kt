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
    private const val KEY_TRIGGER_VERSION = "trigger_version"
    private const val CURRENT_TRIGGER_VERSION = 4
    private const val KEY_SIM_LABEL_1 = "sim_label_1"
    private const val KEY_SIM_LABEL_2 = "sim_label_2"

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
        val version = prefs(ctx).getInt(KEY_TRIGGER_VERSION, 0)
        val json = prefs(ctx).getString(KEY_TRIGGERS, null)

        // Fresh install or version upgrade: use new defaults
        if (json == null || version < CURRENT_TRIGGER_VERSION) {
            val defaults = getDefaultRules()
            setTriggerRules(ctx, defaults)
            prefs(ctx).edit().putInt(KEY_TRIGGER_VERSION, CURRENT_TRIGGER_VERSION).apply()
            return defaults
        }

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

    fun getSimLabel(ctx: Context, simSlot: Int): String =
        prefs(ctx).getString(if (simSlot == 1) KEY_SIM_LABEL_1 else KEY_SIM_LABEL_2, "") ?: ""

    fun setSimLabel(ctx: Context, simSlot: Int, label: String) {
        prefs(ctx).edit().putString(
            if (simSlot == 1) KEY_SIM_LABEL_1 else KEY_SIM_LABEL_2, label
        ).apply()
    }

    private fun getDefaultRules(): MutableList<TriggerRule> = mutableListOf(
        TriggerRule("sender_exact", "16449999", true, "KB입금"),
    )
}

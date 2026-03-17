package com.ohmyjapan.smsrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs.isEnabled(context)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val rules = Prefs.getTriggerRules(context)

        // Group message parts by sender (multi-part SMS)
        val grouped = mutableMapOf<String, StringBuilder>()
        for (msg in messages) {
            val sender = msg.originatingAddress ?: "unknown"
            grouped.getOrPut(sender) { StringBuilder() }.append(msg.messageBody ?: "")
        }

        for ((sender, bodyBuilder) in grouped) {
            val body = bodyBuilder.toString()
            val matchedRule = rules.firstOrNull { it.matches(body, sender) }

            if (matchedRule != null) {
                Log.d(TAG, "SMS matched rule: ${matchedRule.displayName()} from $sender")
                enqueueRelay(context, body, sender, matchedRule)
            }
        }
    }

    private fun enqueueRelay(context: Context, body: String, sender: String, rule: TriggerRule) {
        val timestamp = System.currentTimeMillis()

        // Log the entry immediately as "retrying"
        val snippet = if (sender.length > 20) sender.substring(0, 20) else sender
        RelayLog.addEntry(context, LogEntry(
            timestamp = timestamp,
            sender = snippet,
            bodySnippet = if (body.length > 50) body.substring(0, 50) + "…" else body,
            matchedRule = "${rule.type}:${rule.pattern}",
            status = "retrying"
        ))

        val data = workDataOf(
            "body" to body,
            "sender" to sender,
            "timestamp" to timestamp,
            "matched_rule" to "${rule.type}:${rule.pattern}",
            "server_url" to Prefs.getServerUrl(context)
        )

        val request = OneTimeWorkRequestBuilder<SmsRelayWorker>()
            .setInputData(data)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        try {
            WorkManager.getInstance(context).enqueue(request)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue relay work: ${e.message}")
        }
    }
}

package com.ohmyjapan.smsrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
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

        // Detect SIM slot from subscription ID
        val subscriptionId = intent.getIntExtra("subscription", -1)
        var simLabel = ""
        if (subscriptionId >= 0 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val subInfo = subManager?.getActiveSubscriptionInfo(subscriptionId)
                val slotIndex = subInfo?.simSlotIndex ?: -1
                if (slotIndex >= 0) {
                    simLabel = Prefs.getSimLabel(context, slotIndex + 1) // 1-based
                    Log.d(TAG, "SMS from SIM slot ${slotIndex + 1}, label: $simLabel")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get SIM slot info: ${e.message}")
            }
        }

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
                enqueueRelay(context, body, sender, matchedRule, simLabel)
            }
        }
    }

    private fun enqueueRelay(context: Context, body: String, sender: String, rule: TriggerRule, simLabel: String = "") {
        val timestamp = System.currentTimeMillis()

        // Resolve label priority: SIM label > rule label
        val resolvedLabel = if (simLabel.isNotEmpty()) simLabel else rule.label

        // Show label in log if available, otherwise phone number
        val displaySender = if (resolvedLabel.isNotEmpty()) resolvedLabel else sender
        val snippet = if (displaySender.length > 20) displaySender.substring(0, 20) else displaySender

        RelayLog.addEntry(context, LogEntry(
            timestamp = timestamp,
            sender = snippet,
            bodySnippet = if (body.length > 50) body.substring(0, 50) + "..." else body,
            matchedRule = "${rule.type}:${rule.pattern}",
            status = "retrying"
        ))

        val data = workDataOf(
            "body" to body,
            "sender" to sender,
            "timestamp" to timestamp,
            "matched_rule" to "${rule.type}:${rule.pattern}",
            "label" to resolvedLabel,
            "rule_url" to rule.url,
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

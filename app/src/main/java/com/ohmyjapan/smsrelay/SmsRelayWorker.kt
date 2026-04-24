package com.ohmyjapan.smsrelay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SmsRelayWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SmsRelayWorker"
        private const val MAX_ATTEMPTS = 10
        private const val WORKER_CHANNEL_ID = "relay_worker"
        private const val WORKER_NOTIFICATION_ID = 2
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Required for setExpedited() on API 29-30 — WorkManager uses foreground service fallback
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = NotificationChannel(
            WORKER_CHANNEL_ID, "SMS Relay Worker", NotificationManager.IMPORTANCE_LOW
        )
        (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(applicationContext, WORKER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Forwarding SMS...")
            .setOngoing(true)
            .build()
        return ForegroundInfo(WORKER_NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val body = inputData.getString("body") ?: return@withContext Result.failure()
        val sender = inputData.getString("sender") ?: return@withContext Result.failure()
        val timestamp = inputData.getLong("timestamp", 0)
        val matchedRule = inputData.getString("matched_rule") ?: ""
        val label = inputData.getString("label") ?: ""
        val ruleUrl = inputData.getString("rule_url")
        val serverUrl = if (!ruleUrl.isNullOrEmpty()) ruleUrl else inputData.getString("server_url") ?: return@withContext Result.failure()

        if (runAttemptCount >= MAX_ATTEMPTS) {
            Log.e(TAG, "Max attempts ($MAX_ATTEMPTS) reached for SMS from $sender")
            RelayLog.updateStatus(applicationContext, timestamp, "failed", runAttemptCount)
            return@withContext Result.failure()
        }

        if (runAttemptCount > 0) {
            RelayLog.incrementRetryCount(applicationContext)
            RelayLog.updateStatus(applicationContext, timestamp, "retrying", runAttemptCount + 1)
        }

        val payload = mapOf(
            "body" to body,
            "from" to sender,
            "timestamp" to timestamp,
            "source" to "sms-relay",
            "matchedRule" to matchedRule,
            "label" to label
        )

        val json = Gson().toJson(payload)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = json.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(serverUrl)
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    Log.d(TAG, "SMS relayed successfully (attempt ${runAttemptCount + 1})")
                    RelayLog.updateStatus(applicationContext, timestamp, "delivered", runAttemptCount + 1)
                    Result.success()
                } else {
                    Log.w(TAG, "Server returned ${it.code}, will retry (attempt ${runAttemptCount + 1})")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network error: ${e.message}, will retry (attempt ${runAttemptCount + 1})")
            Result.retry()
        }
    }
}

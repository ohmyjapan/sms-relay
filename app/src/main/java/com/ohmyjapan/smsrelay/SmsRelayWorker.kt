package com.ohmyjapan.smsrelay

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
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
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val body = inputData.getString("body") ?: return@withContext Result.failure()
        val sender = inputData.getString("sender") ?: return@withContext Result.failure()
        val timestamp = inputData.getLong("timestamp", 0)
        val matchedRule = inputData.getString("matched_rule") ?: ""
        val serverUrl = inputData.getString("server_url") ?: return@withContext Result.failure()

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
            "matchedRule" to matchedRule
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

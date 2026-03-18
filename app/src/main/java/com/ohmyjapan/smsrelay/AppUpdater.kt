package com.ohmyjapan.smsrelay

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    val assets: List<GitHubAsset>,
    val body: String?
)

data class GitHubAsset(
    val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String,
    val size: Long
)

class AppUpdater(private val activity: Activity) {

    companion object {
        private const val TAG = "AppUpdater"
        private const val RELEASES_URL = "https://api.github.com/repos/ohmyjapan/sms-relay/releases/tags/latest"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun checkAndPrompt(onResult: (updateAvailable: Boolean, message: String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        onResult(false, "Failed to check: HTTP ${response.code}")
                    }
                    response.close()
                    return@launch
                }

                val json = response.body?.string() ?: ""
                response.close()

                val release = Gson().fromJson(json, GitHubRelease::class.java)
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }

                if (apkAsset == null) {
                    withContext(Dispatchers.Main) {
                        onResult(false, "No APK in latest release")
                    }
                    return@launch
                }

                // Compare: check if the release body contains a commit hash different from ours
                val currentVersion = BuildConfig.VERSION_NAME // git short hash
                val releaseBody = release.body ?: ""
                val isNewer = !releaseBody.contains(currentVersion)

                withContext(Dispatchers.Main) {
                    if (isNewer) {
                        onResult(true, "Update available")
                    } else {
                        onResult(false, "Already up to date ($currentVersion)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResult(false, "Check failed: ${e.message}")
                }
            }
        }
    }

    fun downloadAndInstall(onProgress: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) { onProgress("Checking...") }

                // Get download URL
                val releaseReq = Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                val releaseResp = client.newCall(releaseReq).execute()
                val json = releaseResp.body?.string() ?: ""
                releaseResp.close()

                val release = Gson().fromJson(json, GitHubRelease::class.java)
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    ?: throw Exception("No APK found")

                withContext(Dispatchers.Main) { onProgress("Downloading...") }

                // Download APK
                val dlRequest = Request.Builder()
                    .url(apkAsset.downloadUrl)
                    .header("Accept", "application/octet-stream")
                    .build()
                val dlResponse = client.newCall(dlRequest).execute()

                if (!dlResponse.isSuccessful) {
                    throw Exception("Download failed: HTTP ${dlResponse.code}")
                }

                val apkFile = File(activity.cacheDir, "update.apk")
                dlResponse.body?.byteStream()?.use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                dlResponse.close()

                withContext(Dispatchers.Main) { onProgress("Installing...") }

                // Trigger install
                val uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)

            } catch (e: Exception) {
                Log.e(TAG, "Update failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onProgress("Failed")
                    Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

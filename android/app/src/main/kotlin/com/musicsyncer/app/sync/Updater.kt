package com.musicsyncer.app.sync

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Numeric dot-segment compare: "0.10.0" > "0.9.0" (lexical compare would say otherwise). */
fun isNewerVersion(server: String, current: String): Boolean {
    val a = server.split(".").mapNotNull { it.toIntOrNull() }
    val b = current.split(".").mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

class Updater(private val context: Context) {
    private val client = OkHttpClient()

    /** GET {baseUrl}/version; returns the server's version string, or null on any failure. */
    suspend fun check(baseUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/version").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.string()?.let { body ->
                    JSONObject(body).optString("version").takeIf { it.isNotBlank() }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** GET {baseUrl}/apk → cacheDir/update.apk → ACTION_VIEW install intent. */
    suspend fun downloadAndInstall(baseUrl: String): Result<Unit> {
        val bytes: ByteArray
        try {
            bytes = withContext(Dispatchers.IO) {
                val req = Request.Builder().url("$baseUrl/apk").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    resp.body?.bytes() ?: throw IOException("empty body")
                }
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val apkFile = File(context.cacheDir, "update.apk")
        try {
            withContext(Dispatchers.IO) {
                apkFile.writeBytes(bytes)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return withContext(Dispatchers.Main) {
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
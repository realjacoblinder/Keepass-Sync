package com.keepasssync.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit

data class StatusResponse(val lastUpdated: Date?)
data class SyncResult(val success: Boolean, val message: String)

object SyncApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val OCTET_MEDIA = "application/octet-stream".toMediaType()

    /**
     * Quick connectivity check — calls /api/status and returns true if the server responds.
     */
    suspend fun checkServer(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("masterName", "master")
            .toString()
            .toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url("$baseUrl/api/status")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check the last-modified timestamp of a master database.
     */
    suspend fun getStatus(baseUrl: String, masterName: String): StatusResponse =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("masterName", masterName)
                .toString()
                .toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$baseUrl/api/status")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
                val json = JSONObject(response.body?.string() ?: "{}")
                val lastUpdated = if (json.isNull("lastUpdated")) {
                    null
                } else {
                    try {
                        val instant = java.time.Instant.parse(json.getString("lastUpdated"))
                        Date.from(instant)
                    } catch (e: Exception) {
                        null
                    }
                }
                StatusResponse(lastUpdated)
            }
        }

    /**
     * Upload a .kdbx file and merge it with the master on the server.
     */
    suspend fun syncDatabase(
        baseUrl: String,
        masterName: String,
        password: String,
        fileBytes: ByteArray,
        fileName: String
    ): SyncResult = withContext(Dispatchers.IO) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("masterName", masterName)
            .addFormDataPart("password", password)
            .addFormDataPart("dbFile", fileName, fileBytes.toRequestBody(OCTET_MEDIA))
            .build()

        val request = Request.Builder()
            .url("$baseUrl/api/sync")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: "{}")
            if (response.isSuccessful) {
                SyncResult(true, json.optString("message", "Sync successful"))
            } else {
                SyncResult(false, json.optString("error", "Sync failed (${response.code})"))
            }
        }
    }

    /**
     * Download the master .kdbx file as raw bytes.
     */
    suspend fun downloadDatabase(baseUrl: String, masterName: String): ByteArray =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("masterName", masterName)
                .toString()
                .toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$baseUrl/api/download")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    val msg = try {
                        JSONObject(errorBody ?: "{}").optString("error", "Download failed")
                    } catch (e: Exception) {
                        "Download failed (${response.code})"
                    }
                    throw IOException(msg)
                }
                response.body?.bytes() ?: throw IOException("Empty response body")
            }
        }
}

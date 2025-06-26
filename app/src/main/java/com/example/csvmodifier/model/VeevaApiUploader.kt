package com.example.csvmodifier.model

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

// Enum to represent the API action type
enum class VeevaActionType {
    CREATE,
    UPDATE,
    UPSERT
}

class VeevaApiUploader {

    private val client = OkHttpClient()
    private val TAG = "VeevaApiUploader"

    private fun sanitizeDns(dns: String): String {
        return dns.trim().removePrefix("https://").removePrefix("http://").removeSuffix("/")
    }

    fun authenticate(dns: String, user: String, pass: String, callback: (Result<String>) -> Unit) {
        val sanitizedDns = sanitizeDns(dns)
        val request = Request.Builder()
            .url("https://$sanitizedDns/api/v25.1/auth")
            .post("username=$user&password=$pass".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
            .build()

        Log.d(TAG, "Attempting to authenticate with Vault: $sanitizedDns")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Authentication network request failed", e)
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                try {
                    if (response.isSuccessful && body != null) {
                        val json = JSONObject(body)
                        if (json.optString("responseStatus") == "SUCCESS") {
                            val sessionId = json.getString("sessionId")
                            Log.d(TAG, "Authentication successful. Session ID obtained.")
                            callback(Result.success(sessionId))
                        } else {
                            val error = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message", "Unknown API error") ?: "Unknown API error"
                            Log.e(TAG, "Authentication API error: $error")
                            callback(Result.failure(Exception("API Error: $error")))
                        }
                    } else {
                        Log.e(TAG, "Authentication failed with code: ${response.code}, body: $body")
                        callback(Result.failure(Exception("Authentication failed: ${response.message} (Code: ${response.code})")))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse auth response", e)
                    callback(Result.failure(e))
                }
            }
        })
    }

    fun uploadCsv(
        dns: String,
        sessionId: String,
        objectName: String,
        csvData: String,
        action: VeevaActionType,
        callback: (Result<String>) -> Unit
    ) {
        val sanitizedDns = sanitizeDns(dns)

        // NEW: Log the data we are about to send
        Log.d(TAG, "--- CSV Data for Upload ---")
        Log.d(TAG, csvData)
        Log.d(TAG, "--- End CSV Data (Length: ${csvData.length}) ---")

        val requestBuilder = Request.Builder()
            .url("https://$sanitizedDns/api/v25.1/vobjects/$objectName")
            .addHeader("Authorization", sessionId)
            .addHeader("Content-Type", "text/csv")

        when (action) {
            VeevaActionType.CREATE, VeevaActionType.UPSERT -> {
                requestBuilder.post(csvData.toRequestBody("text/csv".toMediaTypeOrNull()))
            }
            VeevaActionType.UPDATE -> {
                requestBuilder.put(csvData.toRequestBody("text/csv".toMediaTypeOrNull()))
            }
        }

        val request = requestBuilder.build()

        Log.d(TAG, "Attempting to upload CSV data with action: $action")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Upload network request failed", e)
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                Log.d(TAG, "Veeva Upload Response Body: $body") // Log full response
                try {
                    if (response.isSuccessful && body != null) {
                        val json = JSONObject(body)
                        if (json.optString("responseStatus") == "SUCCESS") {
                            Log.d(TAG, "Upload successful according to API response!")
                            callback(Result.success("Successfully loaded data to Vault."))
                        } else {
                            val error = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message", "Unknown API error") ?: "Unknown API error"
                            Log.e(TAG, "Upload API error: $error")
                            callback(Result.failure(Exception("API Error: $error")))
                        }
                    } else {
                        Log.e(TAG, "Upload failed with code: ${response.code}, body: $body")
                        callback(Result.failure(Exception("Upload failed: ${response.message} (Code: ${response.code})")))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse upload response", e)
                    callback(Result.failure(e))
                }
            }
        })
    }
}

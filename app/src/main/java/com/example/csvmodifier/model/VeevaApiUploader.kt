package com.example.csvmodifier.model

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

enum class VeevaActionType { CREATE, UPDATE, UPSERT, DELETE }

data class VaultObject(val label: String, val name: String)


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
            override fun onFailure(call: Call, e: IOException) { callback(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                try {
                    if (response.isSuccessful && body != null) {
                        val json = JSONObject(body)
                        if (json.optString("responseStatus") == "SUCCESS") {
                            callback(Result.success(json.getString("sessionId")))
                        } else {
                            val error = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message", "Unknown API error") ?: "Unknown API error"
                            callback(Result.failure(Exception("API Error: $error")))
                        }
                    } else {
                        callback(Result.failure(Exception("Authentication failed: ${response.message} (Code: ${response.code})")))
                    }
                } catch (e: Exception) { callback(Result.failure(e)) }
            }
        })
    }

    /**
     * UPDATED: Uploads the CSV and immediately parses the synchronous response.
     * Returns a summary string on success.
     */
    fun uploadCsv(
        dns: String, sessionId: String, objectName: String, csvData: String,
        action: VeevaActionType, keyField: String?, callback: (Result<String>) -> Unit
    ) {
        val sanitizedDns = sanitizeDns(dns)
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host(sanitizedDns)
            .addPathSegments("api/v25.1/vobjects/$objectName")

        if (action == VeevaActionType.UPDATE || action == VeevaActionType.UPSERT || action == VeevaActionType.DELETE) {
            if (!keyField.isNullOrBlank()) { urlBuilder.addQueryParameter("idParam", keyField) }
            else { callback(Result.failure(IllegalArgumentException("Key Field is required for $action action."))); return }
        }

        val requestBody = csvData.toRequestBody("text/csv".toMediaTypeOrNull())
        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .addHeader("Authorization", sessionId)
            .addHeader("Content-Type", "text/csv")

        when (action) {
            VeevaActionType.CREATE, VeevaActionType.UPSERT -> requestBuilder.post(requestBody)
            VeevaActionType.UPDATE -> requestBuilder.put(requestBody)
            VeevaActionType.DELETE -> requestBuilder.post(requestBody) // Delete also uses POST with CSV of IDs
        }

        Log.d(TAG, "Attempting to upload CSV data with action: $action to URL: ${requestBuilder.build().url}")

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                Log.d(TAG, "Veeva Upload Response Body: $body")
                try {
                    if (response.isSuccessful && body != null) {
                        val json = JSONObject(body)
                        if (json.optString("responseStatus") == "SUCCESS") {
                            // The response contains an array of results for each row
                            val dataArray = json.optJSONArray("data")
                            if (dataArray != null) {
                                var successCount = 0
                                var failureCount = 0
                                var firstError = ""
                                for (i in 0 until dataArray.length()) {
                                    val itemResult = dataArray.getJSONObject(i)
                                    if (itemResult.optString("responseStatus") == "SUCCESS") {
                                        successCount++
                                    } else {
                                        failureCount++
                                        if (firstError.isEmpty()) {
                                            firstError = itemResult.optJSONArray("errors")?.optJSONObject(0)?.optString("message", "Unknown row error.") ?: ""
                                        }
                                    }
                                }
                                val summary = "Upload Complete. Success: $successCount, Failures: $failureCount."
                                if (failureCount > 0) {
                                    callback(Result.failure(Exception("$summary First error: $firstError")))
                                } else {
                                    callback(Result.success(summary))
                                }
                            } else {
                                // Fallback for responses that don't have a 'data' array but are SUCCESS
                                callback(Result.success("Job submitted successfully (no detailed row response)."))
                            }
                        } else {
                            val error = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message", "Unknown API error") ?: "Unknown API error"
                            callback(Result.failure(Exception("API Error: $error")))
                        }
                    } else {
                        callback(Result.failure(Exception("Upload failed: ${response.message}")))
                    }
                } catch (e: Exception) { callback(Result.failure(e)) }
            }
        })
    }


    fun fetchObjects(dns: String, sessionId: String, callback: (Result<List<VaultObject>>) -> Unit) {
        val sanitizedDns = sanitizeDns(dns)

        val initialUrl = "https://$sanitizedDns/api/v25.1/metadata/objects"
        val initialRequest = Request.Builder().url(initialUrl).addHeader("Authorization", sessionId).get().build()

        Log.d(TAG, "Step 1: Fetching metadata directory from URL: $initialUrl")

        client.newCall(initialRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) {
                    callback(Result.failure(Exception("Step 1 failed: ${response.message}"))); return
                }

                try {
                    val json = JSONObject(body)
                    if (json.optString("responseStatus") != "SUCCESS") {
                        callback(Result.failure(Exception("Step 1 API response was not SUCCESS"))); return
                    }

                    val vobjectsUrl = json.getJSONObject("values").getString("vobjects")
                    Log.d(TAG, "Step 2: Fetching actual objects from URL: $vobjectsUrl")

                    val finalRequest = Request.Builder().url(vobjectsUrl).addHeader("Authorization", sessionId).get().build()
                    client.newCall(finalRequest).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { callback(Result.failure(e)) }
                        override fun onResponse(call: Call, finalResponse: Response) {
                            val finalBody = finalResponse.body?.string()
                            try {
                                if (finalResponse.isSuccessful && finalBody != null) {
                                    val finalJson = JSONObject(finalBody)
                                    if (finalJson.optString("responseStatus") == "SUCCESS") {
                                        val objectsArray = finalJson.getJSONArray("objects")
                                        val vaultObjects = mutableListOf<VaultObject>()
                                        for (i in 0 until objectsArray.length()) {
                                            val obj = objectsArray.getJSONObject(i)

                                            // CORRECTED: Removed the strict filter. Add all objects.
                                            vaultObjects.add(VaultObject(label = obj.getString("label"), name = obj.getString("name")))
                                        }
                                        Log.d(TAG, "Successfully fetched ${vaultObjects.size} objects.")
                                        callback(Result.success(vaultObjects.sortedBy { it.label }))
                                    } else {
                                        val error = finalJson.optJSONArray("errors")?.optJSONObject(0)?.optString("message", "Unknown API error") ?: "Unknown API error"
                                        callback(Result.failure(Exception("API Error in Step 2: $error")))
                                    }
                                } else {
                                    callback(Result.failure(Exception("Fetch objects (Step 2) failed: ${finalResponse.message}")))
                                }
                            } catch (e: Exception) { callback(Result.failure(e)) }
                        }
                    })
                } catch (e: Exception) {
                    callback(Result.failure(e))
                }
            }
        })
    }
}

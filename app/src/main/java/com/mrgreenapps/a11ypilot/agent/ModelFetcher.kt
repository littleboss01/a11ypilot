package com.mrgreenapps.a11ypilot.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches available model names from an OpenAI-compatible /v1/models endpoint.
 */
object ModelFetcher {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val url = if (baseUrl.isNotBlank()) {
                baseUrl.trimEnd('/') + "/v1/models"
            } else {
                "https://api.openai.com/v1/models"
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val text = resp.body?.string().orEmpty()
                val root = json.parseToJsonElement(text).jsonObject
                val data = root["data"]?.jsonArray ?: return@withContext emptyList()
                data.mapNotNull { item ->
                    item.jsonObject["id"]?.jsonPrimitive?.content
                }.sorted()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

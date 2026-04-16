package com.mrgreenapps.a11ypilot.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal client for Anthropic's /v1/messages tool-use API.
 * Caches the system block + tools so subsequent turns hit the prompt cache.
 */
class AnthropicClient(
    private val apiKey: String,
    private val model: String,
    private val maxOutputTokens: Int = 1024
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** A turn-message used by the caller to maintain conversation history. */
    sealed class Message {
        data class User(val content: List<JsonObject>) : Message()
        data class Assistant(val content: List<JsonObject>) : Message()
    }

    data class StopBlock(val type: String, val payload: JsonObject)

    data class Reply(
        val stopReason: String,
        /** All assistant content blocks, suitable for round-tripping back to the API. */
        val assistantContent: List<JsonObject>,
        /** Convenience: just the tool_use blocks the loop needs to dispatch. */
        val toolUses: List<ToolUse>,
        val inputTokens: Int,
        val cachedInputTokens: Int,
        val cacheCreationInputTokens: Int,
        val outputTokens: Int
    )

    data class ToolUse(val id: String, val name: String, val input: JsonObject)

    suspend fun complete(history: List<Message>): Reply = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxOutputTokens)
            putJsonArray("system") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", Prompts.SYSTEM)
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                })
            }
            // Inject cache_control on the final tool so the entire tools block is cacheable.
            val tools = Prompts.anthropicTools()
            putJsonArray("tools") {
                tools.forEachIndexed { idx, t ->
                    val obj = t.jsonObject
                    if (idx == tools.size - 1) {
                        add(buildJsonObject {
                            obj.forEach { (k, v) -> put(k, v) }
                            putJsonObject("cache_control") { put("type", "ephemeral") }
                        })
                    } else {
                        add(obj)
                    }
                }
            }
            putJsonArray("messages") {
                history.forEach { m ->
                    add(buildJsonObject {
                        when (m) {
                            is Message.User -> {
                                put("role", "user")
                                putJsonArray("content") { m.content.forEach { add(it) } }
                            }
                            is Message.Assistant -> {
                                put("role", "assistant")
                                putJsonArray("content") { m.content.forEach { add(it) } }
                            }
                        }
                    })
                }
            }
        }

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Anthropic API ${resp.code}: $text")
            }
            val root = json.parseToJsonElement(text).jsonObject
            val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "end_turn"
            val contentArr: JsonArray = root["content"]?.jsonArray ?: JsonArray(emptyList())
            val blocks = contentArr.map { it.jsonObject }
            val toolUses = blocks.mapNotNull { b ->
                if (b["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                    ToolUse(
                        id = b["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        name = b["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        input = b["input"]?.jsonObject ?: JsonObject(emptyMap())
                    )
                } else null
            }
            val usage = root["usage"]?.jsonObject
            Reply(
                stopReason = stopReason,
                assistantContent = blocks,
                toolUses = toolUses,
                inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull() ?: 0,
                cachedInputTokens = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull() ?: 0,
                cacheCreationInputTokens = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull() ?: 0,
                outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull() ?: 0
            )
        }
    }

    /**
     * One result for a single tool_use. If [imageBase64] is set, the tool_result is emitted with
     * a content array containing an image block followed by the text — required when the model
     * needs to see pixels (screenshot tool).
     */
    data class ToolResult(
        val toolUseId: String,
        val text: String,
        val isError: Boolean = false,
        val imageBase64: String? = null,
        val imageMimeType: String? = null
    )

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Helper: build a user content list with a single text block. */
        fun userText(text: String): List<JsonObject> = listOf(buildJsonObject {
            put("type", "text"); put("text", text)
        })

        /** Helper: build a user content list of tool_result blocks (text- or image-bearing). */
        fun userToolResults(results: List<ToolResult>): List<JsonObject> =
            results.map { r ->
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", r.toolUseId)
                    if (r.imageBase64 != null) {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", r.imageMimeType ?: "image/jpeg")
                                    put("data", r.imageBase64)
                                }
                            })
                            if (r.text.isNotEmpty()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", r.text)
                                })
                            }
                        }
                    } else {
                        put("content", r.text)
                    }
                    if (r.isError) put("is_error", true)
                }
            }
    }
}

private fun JsonPrimitive.intOrNull(): Int? = contentOrNull?.toIntOrNull()

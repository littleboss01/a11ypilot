package com.mrgreenapps.a11ypilot.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client for OpenAI-compatible /v1/chat/completions API.
 * Converts between Anthropic-style tool format and OpenAI format transparently.
 */
class OpenAIClient(
    private val apiKey: String,
    private val model: String,
    private val maxOutputTokens: Int = 1024,
    private val baseUrl: String = ""
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    sealed class Message {
        data class User(val content: List<JsonObject>) : Message()
        data class Assistant(val content: List<JsonObject>) : Message()
    }

    data class Reply(
        val stopReason: String,
        val assistantContent: List<JsonObject>,
        val toolUses: List<ToolUse>,
        val inputTokens: Int,
        val cachedInputTokens: Int,
        val cacheCreationInputTokens: Int,
        val outputTokens: Int
    )

    data class ToolUse(val id: String, val name: String, val input: JsonObject)

    data class ToolResult(
        val toolUseId: String,
        val text: String,
        val isError: Boolean = false,
        val imageBase64: String? = null,
        val imageMimeType: String? = null
    )

    private fun convertToolsToOpenAI(anthropicTools: JsonArray): JsonArray = buildJsonArray {
        for (tool in anthropicTools) {
            val t = tool.jsonObject
            val name = t["name"]?.jsonPrimitive?.content ?: continue
            val desc = t["description"]?.jsonPrimitive?.content ?: ""
            val inputSchema = t["input_schema"]?.jsonObject
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", name)
                    put("description", desc)
                    if (inputSchema != null) {
                        put("parameters", inputSchema)
                    }
                })
            })
        }
    }

    private fun convertHistoryToOpenAI(history: List<Message>, systemPrompt: String): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("role", "system")
            put("content", systemPrompt)
        })
        for (m in history) {
            when (m) {
                is Message.User -> {
                    val contentArr = m.content
                    val toolResults = contentArr.filter {
                        it["type"]?.jsonPrimitive?.contentOrNull == "tool_result"
                    }
                    val textBlocks = contentArr.filter {
                        it["type"]?.jsonPrimitive?.contentOrNull == "text"
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        if (toolResults.isNotEmpty()) {
                            val allText = buildString {
                                for (tr in toolResults) {
                                    val content = tr["content"]
                                    val text = when {
                                        content == null -> ""
                                        content is kotlinx.serialization.json.JsonArray -> {
                                            content.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                                                .joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
                                        }
                                        else -> content.jsonPrimitive.contentOrNull ?: ""
                                    }
                                    if (text.isNotBlank()) {
                                        if (isNotEmpty()) append("\n")
                                        append(text)
                                    }
                                }
                            }
                            put("content", allText)
                        } else {
                            put("content", textBlocks.joinToString("\n") {
                                it["text"]?.jsonPrimitive?.content ?: ""
                            })
                        }
                    })
                }
                is Message.Assistant -> {
                    val textContent = m.content.firstOrNull {
                        it["type"]?.jsonPrimitive?.contentOrNull == "text"
                    }?.get("text")?.jsonPrimitive?.contentOrNull
                    val toolCallBlocks = m.content.filter {
                        it["type"]?.jsonPrimitive?.contentOrNull == "tool_use"
                    }
                    add(buildJsonObject {
                        put("role", "assistant")
                        if (textContent != null && toolCallBlocks.isEmpty()) {
                            put("content", textContent)
                        } else if (toolCallBlocks.isNotEmpty()) {
                            put("content", buildJsonArray {
                                if (textContent != null) {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", textContent)
                                    })
                                }
                            })
                            put("tool_calls", buildJsonArray {
                                for (tc in toolCallBlocks) {
                                    add(buildJsonObject {
                                        put("id", tc["id"]?.jsonPrimitive?.content ?: "")
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", tc["name"]?.jsonPrimitive?.content ?: "")
                                            put("arguments", json.encodeToString(
                                                JsonObject.serializer(),
                                                tc["input"]?.jsonObject ?: JsonObject(emptyMap())
                                            ))
                                        })
                                    })
                                }
                            })
                        } else {
                            put("content", textContent ?: "")
                        }
                    })
                }
            }
        }
    }

    suspend fun complete(
        history: List<Message>,
        anthropicTools: JsonArray,
        systemPrompt: String
    ): Reply = withContext(Dispatchers.IO) {
        val openAITools = convertToolsToOpenAI(anthropicTools)
        val openAIMessages = convertHistoryToOpenAI(history, systemPrompt)

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxOutputTokens)
            put("messages", openAIMessages)
            if (openAITools.isNotEmpty()) {
                put("tools", openAITools)
            }
        }

        val endpoint = if (baseUrl.isNotBlank()) {
            baseUrl.trimEnd('/') + "/v1/chat/completions"
        } else {
            "https://api.openai.com/v1/chat/completions"
        }

        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("OpenAI API ${resp.code}: $text")
            }
            val root = json.parseToJsonElement(text).jsonObject
            val choicesElement = root["choices"]
            val choices = when {
                choicesElement is kotlinx.serialization.json.JsonArray && choicesElement.isNotEmpty() ->
                    choicesElement[0].jsonObject
                choicesElement is JsonObject ->
                    choicesElement
                else -> null
            }
            val message = choices?.get("message")?.jsonObject
            val finishReason = choices?.get("finish_reason")?.jsonPrimitive?.contentOrNull ?: "stop"

            val toolCalls = message?.get("tool_calls")?.let {
                if (it is kotlinx.serialization.json.JsonArray) it else JsonArray(emptyList())
            } ?: JsonArray(emptyList())
            val assistantBlocks = mutableListOf<JsonObject>()

            val textContent = message?.get("content")?.jsonPrimitive?.contentOrNull
            if (!textContent.isNullOrBlank()) {
                assistantBlocks.add(buildJsonObject {
                    put("type", "text")
                    put("text", textContent)
                })
            }

            val toolUses = toolCalls.map { tc ->
                val tcObj = tc.jsonObject
                val func = tcObj["function"]?.jsonObject
                val name = func?.get("name")?.jsonPrimitive?.content ?: ""
                val argsStr = func?.get("arguments")?.jsonPrimitive?.content ?: "{}"
                val args = json.parseToJsonElement(argsStr).jsonObject

                val toolUseId = tcObj["id"]?.jsonPrimitive?.content ?: "call_${name}"
                assistantBlocks.add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", toolUseId)
                    put("name", name)
                    put("input", args)
                })

                ToolUse(id = toolUseId, name = name, input = args)
            }

            val usage = root["usage"]?.jsonObject
            val promptDetails = usage?.get("prompt_tokens_details")?.jsonObject
            Reply(
                stopReason = if (toolUses.isNotEmpty()) "tool_use" else finishReason,
                assistantContent = assistantBlocks,
                toolUses = toolUses,
                inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                cachedInputTokens = promptDetails?.get("cached_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                cacheCreationInputTokens = 0,
                outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            )
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun userText(text: String): List<JsonObject> = listOf(buildJsonObject {
            put("type", "text"); put("text", text)
        })

        fun userToolResults(results: List<ToolResult>): List<JsonObject> =
            results.map { r ->
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", r.toolUseId)
                    if (r.imageBase64 != null) {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", r.imageMimeType ?: "image/jpeg")
                                    put("data", r.imageBase64)
                                })
                            })
                            if (r.text.isNotEmpty()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", r.text)
                                })
                            }
                        })
                    } else {
                        put("content", r.text)
                    }
                    if (r.isError) put("is_error", true)
                }
            }
    }
}

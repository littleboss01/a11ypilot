package com.mrgreenapps.a11ypilot.agent

import com.mrgreenapps.a11ypilot.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * MCP client that connects to an MCP server over HTTP,
 * discovers tools, and calls them on behalf of the agent.
 */
class McpClient(
    private val url: String,
    private val token: String = ""
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    data class McpTool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject
    )

    private var sessionId: String? = null

    private fun buildRequest(body: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        return builder
    }

    private fun post(body: String): String {
        val req = buildRequest(body)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(req).execute().use { resp ->
            resp.header("Mcp-Session-Id")?.let { sessionId = it }
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("MCP ${resp.code}: $text")
            }
            return text
        }
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "init-1")
                put("method", "initialize")
                put("params", buildJsonObject {
                    put("protocolVersion", "2025-03-26")
                    put("capabilities", buildJsonObject {})
                    put("clientInfo", buildJsonObject {
                        put("name", "a11ypilot")
                        put("version", "1.0.0")
                    })
                })
            })
            val resp = post(body)
            val root = json.parseToJsonElement(resp).jsonObject
            root["result"] != null
        } catch (t: Throwable) {
            EventLog.append("mcp client> init failed: ${t.message}")
            false
        }
    }

    suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "tools-1")
                put("method", "tools/list")
            })
            val resp = post(body)
            val root = json.parseToJsonElement(resp).jsonObject
            val result = root["result"]?.jsonObject ?: return@withContext emptyList()
            val tools = result["tools"]?.jsonArray ?: return@withContext emptyList()
            tools.map { t ->
                val obj = t.jsonObject
                McpTool(
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    description = obj["description"]?.jsonPrimitive?.content ?: "",
                    inputSchema = obj["inputSchema"]?.jsonObject ?: JsonObject(emptyMap())
                )
            }
        } catch (t: Throwable) {
            EventLog.append("mcp client> listTools failed: ${t.message}")
            emptyList()
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "call-$name")
                put("method", "tools/call")
                put("params", buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                })
            })
            val resp = post(body)
            val root = json.parseToJsonElement(resp).jsonObject
            val result = root["result"]?.jsonObject ?: return@withContext "error: no result"
            val content = result["content"]?.jsonArray ?: return@withContext "error: no content"
            content.joinToString("\n") { block ->
                val obj = block.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> obj["text"]?.jsonPrimitive?.content ?: ""
                    else -> obj.toString()
                }
            }
        } catch (t: Throwable) {
            "mcp error: ${t.message}"
        }
    }

    /**
     * Convert MCP tools to Anthropic tool format for the API.
     */
    fun toolsToAnthropicFormat(tools: List<McpTool>, prefix: String = "mcp_"): JsonArray = buildJsonArray {
        for (tool in tools) {
            add(buildJsonObject {
                put("name", prefix + tool.name)
                put("description", "[MCP] ${tool.description}")
                put("input_schema", tool.inputSchema)
            })
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

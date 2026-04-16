package com.mrgreenapps.a11ypilot.mcp

import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.agent.Prompts
import com.mrgreenapps.a11ypilot.agent.ToolExecutor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal MCP JSON-RPC 2.0 dispatcher. Supports `initialize`, `tools/list`, `tools/call`, `ping`.
 * Reuses [Prompts.anthropicTools] for tool schemas and routes `tools/call` through [ToolExecutor],
 * so the in-app agent and remote MCP clients share the same tool surface.
 */
class JsonRpc(private val executor: ToolExecutor) {

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_NAME = "accessbility-test-app"
        const val SERVER_VERSION = "0.1.0"
    }

    suspend fun handle(request: JsonObject): JsonObject {
        val id: JsonElement = request["id"] ?: JsonNull
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: return error(id, -32600, "Missing 'method'")
        val params = (request["params"] as? JsonObject) ?: JsonObject(emptyMap())

        return try {
            when (method) {
                "initialize" -> ok(id, initialize())
                "ping" -> ok(id, JsonObject(emptyMap()))
                "tools/list" -> ok(id, toolsList())
                "tools/call" -> ok(id, toolsCall(params))
                "notifications/initialized",
                "notifications/cancelled" -> JsonObject(emptyMap())
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (t: Throwable) {
            EventLog.append("mcp> handler error on $method: ${t.message}")
            error(id, -32603, t.message ?: "internal error")
        }
    }

    private fun initialize(): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        putJsonObject("capabilities") {
            putJsonObject("tools") { put("listChanged", false) }
        }
        putJsonObject("serverInfo") {
            put("name", SERVER_NAME)
            put("version", SERVER_VERSION)
        }
    }

    private fun toolsList(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            // MCP expects `inputSchema` (camelCase); Anthropic's tool defs use `input_schema`.
            Prompts.anthropicTools().forEach { t ->
                val obj = t.jsonObject
                add(buildJsonObject {
                    obj.forEach { (k, v) ->
                        when (k) {
                            "input_schema" -> put("inputSchema", v)
                            else -> put(k, v)
                        }
                    }
                })
            }
        }
    }

    private suspend fun toolsCall(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing 'name'")
        val args = (params["arguments"] as? JsonObject) ?: JsonObject(emptyMap())

        fun int(key: String, default: Int? = null): Int =
            args[key]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
                ?: default
                ?: throw IllegalArgumentException("Missing int '$key'")
        fun str(key: String, default: String? = null): String =
            args[key]?.jsonPrimitive?.contentOrNull
                ?: default
                ?: throw IllegalArgumentException("Missing string '$key'")
        fun bool(key: String, default: Boolean? = null): Boolean =
            args[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: default
                ?: throw IllegalArgumentException("Missing bool '$key'")

        EventLog.append("mcp> tools/call $name")

        val result = when (name) {
            "dump_screen" -> executor.dumpScreen()
            "screenshot" -> executor.screenshot()
            "click" -> executor.click(int("id"))
            "long_click" -> executor.longClick(int("id"))
            "set_text" -> executor.setText(int("id"), str("value"))
            "scroll" -> executor.scroll(int("id"), str("direction"))
            "tap" -> executor.tap(int("x"), int("y"))
            "swipe" -> executor.swipe(int("x1"), int("y1"), int("x2"), int("y2"), int("duration_ms", 300).toLong())
            "global" -> executor.global(str("action"))
            "launch_app" -> executor.launchApp(str("package"))
            "wait" -> executor.wait(int("ms"))
            "done" -> executor.done(bool("success"), str("summary"))
            else -> ToolExecutor.Result.Err("Unknown tool: $name")
        }

        return when (result) {
            is ToolExecutor.Result.Ok -> mcpContent(
                isError = false,
                text = "foreground: ${result.foregroundApp}\n${result.screen}",
                imageBase64 = result.imageBase64,
                imageMimeType = result.imageMimeType
            )
            is ToolExecutor.Result.Err -> mcpContent(isError = true, text = result.message)
            is ToolExecutor.Result.Done -> mcpContent(
                isError = false,
                text = "done success=${result.success}: ${result.summary}"
            )
        }
    }

    private fun mcpContent(
        isError: Boolean,
        text: String,
        imageBase64: String? = null,
        imageMimeType: String? = null
    ): JsonObject = buildJsonObject {
        putJsonArray("content") {
            if (imageBase64 != null) {
                add(buildJsonObject {
                    put("type", "image")
                    put("data", imageBase64)
                    put("mimeType", imageMimeType ?: "image/jpeg")
                })
            }
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        put("isError", isError)
    }

    private fun ok(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", JsonPrimitive(code))
            put("message", message)
        }
    }
}

package com.mrgreenapps.a11ypilot.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Single source of truth for the system prompt + tool schemas, reused by both AgentEngine
 * (for the Anthropic /v1/messages tools field) and the MCP server (for tools/list).
 */
object Prompts {

    const val SYSTEM = """You drive an Android phone via accessibility APIs. Each turn you see a compact view of the current screen and decide ONE tool call. Repeat until the user's instruction is satisfied, then call `done`.

SCREEN FORMAT
- One line per element: `[id] Class "text"` optionally `?hint` and flags `*` (checked/selected) `!` (disabled) `…` (editable).
- Indentation = parent/child. IDs are valid only for the current turn — call dump_screen if unsure.
- Foreground app and screen size appear above the tree.

VISION
- The accessibility tree is your primary signal — cheap and structured. Prefer it.
- Call `screenshot` ONLY when the tree is empty/insufficient: canvas-based UIs, games, video, charts, image content, or to verify a visual state you can't infer from text. Vision tokens are ~10× the text tree, so don't dump on every turn.

POLICY
1. Prefer id-based actions (click, set_text, scroll) over coordinates. Use tap/swipe only when no node matches.
2. If the target isn't visible, scroll the nearest scrollable ancestor before guessing.
3. After set_text, submit explicitly (click submit button, or use `global` back to dismiss IME and click) — don't assume auto-submit.
4. After every action you'll receive the new screen. Re-read it. Don't repeat a failing action; try a different element or scroll.
5. If you cannot proceed (locked, missing permission, ambiguous), call `done(success=false, summary=reason)`.
6. Hard cap 25 tool calls — bail with `done(success=false)` if approaching it.

STYLE: no prose, no chain-of-thought; just call a tool."""

    /**
     * Build the effective system prompt.
     * If [customPrompt] is non-empty, append it to the default prompt.
     */
    fun systemPrompt(customPrompt: String = ""): String {
        if (customPrompt.isBlank()) return SYSTEM
        return "$SYSTEM\n\n--- CUSTOM INSTRUCTIONS ---\n$customPrompt"
    }

    /** Tool definitions in the format the Anthropic /v1/messages API expects. */
    fun anthropicTools(mcpTools: List<McpClient.McpTool> = emptyList()): JsonArray = buildJsonArray {
        addTool(this, "dump_screen", "Re-read and return the current screen tree.", emptyMap())
        addTool(this, "screenshot",
            "Take a JPEG screenshot of the current display and return it alongside the screen tree. " +
                "Expensive — use only when the tree is empty/insufficient (canvas UIs, games, video, image content) or to verify a visual state.",
            emptyMap())
        addTool(this, "click", "Click the node with the given id from the latest screen dump.", mapOf(
            "id" to schemaInt("Numeric id from the latest screen dump.")
        ), required = listOf("id"))
        addTool(this, "long_click", "Long-click the node with the given id.", mapOf(
            "id" to schemaInt("Numeric id from the latest screen dump.")
        ), required = listOf("id"))
        addTool(this, "set_text", "Replace the text in an editable node.", mapOf(
            "id" to schemaInt("Editable node id."),
            "value" to schemaStr("Text to set.")
        ), required = listOf("id", "value"))
        addTool(this, "scroll", "Scroll the given node (or its nearest scrollable ancestor).", mapOf(
            "id" to schemaInt("Node id inside or equal to a scrollable container."),
            "direction" to schemaEnum("up", "down", "left", "right")
        ), required = listOf("id", "direction"))
        addTool(this, "tap", "Tap raw screen coordinates. Use only when no node id matches.", mapOf(
            "x" to schemaInt("X pixel."),
            "y" to schemaInt("Y pixel.")
        ), required = listOf("x", "y"))
        addTool(this, "swipe", "Swipe from (x1,y1) to (x2,y2).", mapOf(
            "x1" to schemaInt("Start X."),
            "y1" to schemaInt("Start Y."),
            "x2" to schemaInt("End X."),
            "y2" to schemaInt("End Y."),
            "duration_ms" to schemaInt("Duration in ms (50–3000, default 300).", optional = true)
        ), required = listOf("x1", "y1", "x2", "y2"))
        addTool(this, "global", "System-level navigation.", mapOf(
            "action" to schemaEnum("back", "home", "recents", "notifications")
        ), required = listOf("action"))
        addTool(this, "launch_app", "Launch an app by its package name.", mapOf(
            "package" to schemaStr("Android package name, e.g. com.android.chrome.")
        ), required = listOf("package"))
        addTool(this, "wait", "Wait for the screen to update (max 3000 ms).", mapOf(
            "ms" to schemaInt("Milliseconds to wait (≤3000).")
        ), required = listOf("ms"))
        addTool(this, "done", "Terminate the agent loop.", mapOf(
            "success" to schemaBool("True if the user instruction has been satisfied."),
            "summary" to schemaStr("One-sentence summary of what was done or why it failed.")
        ), required = listOf("success", "summary"))
        // Append MCP tools with mcp_ prefix
        for (mcpTool in mcpTools) {
            add(buildJsonObject {
                put("name", "mcp_" + mcpTool.name)
                put("description", "[MCP] ${mcpTool.description}")
                put("input_schema", mcpTool.inputSchema)
            })
        }
    }

    private fun addTool(
        builder: kotlinx.serialization.json.JsonArrayBuilder,
        name: String,
        description: String,
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList()
    ) {
        builder.add(buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    properties.forEach { (k, v) -> put(k, v) }
                }
                if (required.isNotEmpty()) {
                    put("required", buildJsonArray { required.forEach { add(it) } })
                }
                put("additionalProperties", JsonPrimitive(false))
            }
        })
    }

    private fun schemaInt(description: String, optional: Boolean = false): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }
    private fun schemaStr(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }
    private fun schemaBool(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }
    private fun schemaEnum(vararg values: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { values.forEach { add(it) } })
    }
}

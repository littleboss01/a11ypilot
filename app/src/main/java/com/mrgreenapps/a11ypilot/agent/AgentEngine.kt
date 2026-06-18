package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import android.content.Intent
import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drives the conversational tool-use loop:
 *   instruction → snapshot → Claude → tool_use → ToolExecutor → tool_result → Claude → … → done
 */
class AgentEngine(
    appContext: Context
) {
    private val ctx = appContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executor = ToolExecutor(ctx, excludeOwnPackage = false)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _usage = MutableStateFlow(Usage())
    val usage: StateFlow<Usage> = _usage.asStateFlow()

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    private var runJob: Job? = null

    sealed class State {
        data object Idle : State()
        data class Running(val step: Int, val last: String) : State()
        data class Done(val success: Boolean, val summary: String, val steps: Int) : State()
        data class Error(val message: String, val steps: Int) : State()
    }

    /** Aggregate token totals across all API turns of the current run. */
    data class Usage(
        val turns: Int = 0,
        val input: Int = 0,
        val cacheRead: Int = 0,
        val cacheCreation: Int = 0,
        val output: Int = 0
    ) {
        val billedInput: Int get() = input + cacheCreation
        val totalTokens: Int get() = input + cacheRead + cacheCreation + output
    }

    /** Per-API-turn details so the UI can show what each step cost. */
    data class Turn(
        val turn: Int,
        val tools: List<String>,
        val input: Int,
        val cacheRead: Int,
        val cacheCreation: Int,
        val output: Int
    )

    fun cancel() {
        runJob?.cancel()
        runJob = null
        if (_state.value is State.Running) {
            _state.value = State.Error("cancelled", (_state.value as State.Running).step)
        }
        AgentOverlay.hide()
        bringAppToFront()
    }

    fun run(instruction: String) {
        cancel()
        _usage.value = Usage()
        _turns.value = emptyList()
        AgentOverlay.show(ctx, onStop = { cancel() })
        AgentOverlay.update("starting…", usageLine())
        runJob = scope.launch { runLoop(instruction) }
    }

    private fun usageLine(): String {
        val u = _usage.value
        return "T${u.turns}  in ${u.input}  cR ${u.cacheRead}  cC ${u.cacheCreation}  out ${u.output}"
    }

    private fun overlayStatus(): String = when (val s = _state.value) {
        AgentEngine.State.Idle -> "idle"
        is AgentEngine.State.Running -> "step ${s.step} — ${s.last}"
        is AgentEngine.State.Done -> if (s.success) "✓ ${s.summary}" else "✗ ${s.summary}"
        is AgentEngine.State.Error -> "error: ${s.message}"
    }

    private suspend fun runLoop(instruction: String) {
        val settings = AgentSettings.snapshot(ctx)
        if (settings.apiKey.isBlank()) {
            _state.value = State.Error("No API key set — open Settings to add one.", 0)
            AgentOverlay.hide()
            bringAppToFront()
            return
        }
        EventLog.append("agent> START: $instruction (${settings.provider})")
        _state.value = State.Running(0, "starting")
        AgentOverlay.update(overlayStatus(), usageLine())

        val maxSteps = settings.maxSteps
        val history = mutableListOf<AnthropicClient.Message>()

        // Seed: instruction + initial screen.
        val initial = executor.dumpScreen()
        val initialUserText = buildString {
            append("INSTRUCTION: ").append(instruction).append("\n\n")
            when (initial) {
                is ToolExecutor.Result.Ok -> {
                    append("CURRENT SCREEN:\n").append(initial.screen)
                }
                is ToolExecutor.Result.Err -> {
                    append("WARNING: ").append(initial.message)
                }
                is ToolExecutor.Result.Done -> { /* unreachable */ }
            }
        }
        history.add(AnthropicClient.Message.User(AnthropicClient.userText(initialUserText)))

        val anthropicTools = Prompts.anthropicTools()
        val isOpenAI = settings.provider == "openai"

        var step = 0
        while (true) {
            if (!scope.isActive || runJob?.isCancelled == true) {
                _state.value = State.Error("cancelled", step)
                AgentOverlay.hide()
                bringAppToFront()
                return
            }
            if (step >= maxSteps) {
                _state.value = State.Error("hit max steps ($maxSteps)", step)
                EventLog.append("agent> aborted: max steps")
                AgentOverlay.hide()
                bringAppToFront()
                return
            }

            val reply = try {
                if (isOpenAI) {
                    val openaiClient = OpenAIClient(
                        apiKey = settings.apiKey,
                        model = settings.model,
                        baseUrl = settings.baseUrl
                    )
                    val openaiHistory = history.map { m ->
                        when (m) {
                            is AnthropicClient.Message.User -> OpenAIClient.Message.User(m.content)
                            is AnthropicClient.Message.Assistant -> OpenAIClient.Message.Assistant(m.content)
                        }
                    }
                    val openaiReply = openaiClient.complete(openaiHistory, anthropicTools, Prompts.SYSTEM)
                    AnthropicClient.Reply(
                        stopReason = openaiReply.stopReason,
                        assistantContent = openaiReply.assistantContent,
                        toolUses = openaiReply.toolUses.map {
                            AnthropicClient.ToolUse(it.id, it.name, it.input)
                        },
                        inputTokens = openaiReply.inputTokens,
                        cachedInputTokens = openaiReply.cachedInputTokens,
                        cacheCreationInputTokens = openaiReply.cacheCreationInputTokens,
                        outputTokens = openaiReply.outputTokens
                    )
                } else {
                    val anthropicClient = AnthropicClient(
                        apiKey = settings.apiKey,
                        model = settings.model,
                        baseUrl = settings.baseUrl
                    )
                    anthropicClient.complete(history)
                }
            } catch (t: Throwable) {
                EventLog.append("agent> API error: ${t.message}")
                _state.value = State.Error(t.message ?: "API error", step)
                AgentOverlay.hide()
                bringAppToFront()
                return
            }
            EventLog.append("agent> turn ${_usage.value.turns + 1}  in=${reply.inputTokens} cache_read=${reply.cachedInputTokens} cache_create=${reply.cacheCreationInputTokens} out=${reply.outputTokens}")
            recordTurn(reply)
            AgentOverlay.update(overlayStatus(), usageLine())

            // Persist assistant turn so the next API call can echo it back with tool_results.
            history.add(AnthropicClient.Message.Assistant(reply.assistantContent))

            if (reply.toolUses.isEmpty()) {
                val text = reply.assistantContent
                    .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    ?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: "(no tool call, no text)"
                EventLog.append("agent> end_turn (no tool): $text")
                _state.value = State.Done(success = true, summary = text, steps = step)
                AgentOverlay.hide()
                bringAppToFront()
                return
            }

            // We expect ONE tool call per turn per the system prompt; handle all defensively.
            val results = mutableListOf<AnthropicClient.ToolResult>()
            var doneSeen: ToolExecutor.Result.Done? = null
            for (use in reply.toolUses) {
                step++
                _state.value = State.Running(step, "${use.name}(…)")
                AgentOverlay.update(overlayStatus(), usageLine())
                val result = dispatch(use.name, use.input)
                when (result) {
                    is ToolExecutor.Result.Ok -> {
                        val content = buildString {
                            append("foreground: ").append(result.foregroundApp).append('\n')
                            append(result.screen)
                        }
                        results.add(AnthropicClient.ToolResult(
                            toolUseId = use.id,
                            text = content,
                            imageBase64 = result.imageBase64,
                            imageMimeType = result.imageMimeType
                        ))
                    }
                    is ToolExecutor.Result.Err -> {
                        results.add(AnthropicClient.ToolResult(use.id, result.message, isError = true))
                    }
                    is ToolExecutor.Result.Done -> {
                        doneSeen = result
                        results.add(AnthropicClient.ToolResult(use.id, "ok"))
                    }
                }
            }
            history.add(AnthropicClient.Message.User(AnthropicClient.userToolResults(results)))

            if (doneSeen != null) {
                _state.value = State.Done(doneSeen.success, doneSeen.summary, step)
                AgentOverlay.hide()
                bringAppToFront()
                return
            }
        }
    }

    private fun recordTurn(reply: AnthropicClient.Reply) {
        val tools = reply.toolUses.map { it.name }
        val u = _usage.value
        val turnIdx = u.turns + 1
        _usage.value = u.copy(
            turns = turnIdx,
            input = u.input + reply.inputTokens,
            cacheRead = u.cacheRead + reply.cachedInputTokens,
            cacheCreation = u.cacheCreation + reply.cacheCreationInputTokens,
            output = u.output + reply.outputTokens
        )
        _turns.value = (_turns.value + Turn(
            turn = turnIdx,
            tools = tools,
            input = reply.inputTokens,
            cacheRead = reply.cachedInputTokens,
            cacheCreation = reply.cacheCreationInputTokens,
            output = reply.outputTokens
        )).takeLast(50)
    }

    private fun bringAppToFront() {
        try {
            val intent = Intent(ctx, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            EventLog.append("agent> bring-to-front failed: ${t.message}")
        }
    }

    private suspend fun dispatch(name: String, input: JsonObject): ToolExecutor.Result {
        fun int(key: String, default: Int? = null): Int =
            input[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: default
                ?: throw IllegalArgumentException("Missing int '$key'")
        fun str(key: String, default: String? = null): String =
            input[key]?.jsonPrimitive?.contentOrNull
                ?: default
                ?: throw IllegalArgumentException("Missing string '$key'")
        fun bool(key: String, default: Boolean? = null): Boolean =
            input[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: default
                ?: throw IllegalArgumentException("Missing bool '$key'")
        return try {
            when (name) {
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
                else -> ToolExecutor.Result.Err("unknown tool: $name")
            }
        } catch (t: Throwable) {
            ToolExecutor.Result.Err(t.message ?: "dispatch error")
        }
    }
}

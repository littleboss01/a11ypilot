package com.mrgreenapps.a11ypilot.mcp

import android.content.Context
import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.agent.ToolExecutor
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Embedded Ktor (CIO) server bound to 0.0.0.0:[port], exposing MCP-over-HTTP at `POST /mcp`
 * with bearer-token auth. Behavior mirrors the in-app agent because both sides call into the
 * same [ToolExecutor].
 */
class McpServer(
    private val appContext: Context,
    private val port: Int,
    private val bearerToken: String
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val executor = ToolExecutor(appContext, excludeOwnPackage = true)
    private val rpc = JsonRpc(executor)

    @Volatile
    private var engine: ApplicationEngine? = null

    fun start() {
        if (engine != null) return
        EventLog.append("mcp> starting on 0.0.0.0:$port")
        LAST_STATUS = "Starting on 0.0.0.0:$port…"
        try {
            engine = embeddedServer(CIO, host = "0.0.0.0", port = port) {
                install(ContentNegotiation) { json(json) }
                routing {
                    get("/health") { call.respondText("ok") }
                    post("/mcp") {
                        val auth = call.request.headers["Authorization"]
                        if (auth != "Bearer $bearerToken") {
                            call.respond(HttpStatusCode.Unauthorized, "missing/invalid bearer")
                            return@post
                        }
                        val body = call.receiveText()
                        val request = try {
                            json.parseToJsonElement(body) as? JsonObject
                                ?: throw IllegalArgumentException("expected JSON object")
                        } catch (t: Throwable) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                json.encodeToString(JsonObject.serializer(), parseError(t.message))
                            )
                            return@post
                        }
                        val response = rpc.handle(request)
                        call.respondText(
                            json.encodeToString(JsonObject.serializer(), response),
                            io.ktor.http.ContentType.Application.Json
                        )
                    }
                    get("/mcp") {
                        call.respond(HttpStatusCode.MethodNotAllowed, "POST JSON-RPC to this endpoint")
                    }
                }
            }.also { it.start(wait = false) }
            // Verify the bind by polling /health on localhost up to ~3s.
            Thread {
                var ok: kotlin.Result<String>? = null
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline) {
                    val r = selfTest(port)
                    if (r.isSuccess) { ok = r; break }
                    Thread.sleep(150)
                }
                if (ok != null) {
                    EventLog.append("mcp> bound OK, /health=${ok.getOrNull()}")
                    LAST_STATUS = "Listening on 0.0.0.0:$port"
                } else {
                    val last = selfTest(port).exceptionOrNull()
                    val why = "${last?.javaClass?.simpleName}: ${last?.message}"
                    EventLog.append("mcp> bind FAILED after 3s: $why")
                    LAST_STATUS = "Bind failed: $why"
                }
            }.apply { name = "mcp-bind-probe"; isDaemon = true }.start()
        } catch (t: Throwable) {
            EventLog.append("mcp> start exception: ${t.javaClass.simpleName}: ${t.message}")
            LAST_STATUS = "Start exception: ${t.message}"
            engine = null
        }
    }

    fun stop() {
        engine?.let {
            EventLog.append("mcp> stopping")
            it.stop(500, 1000)
        }
        engine = null
    }

    private fun parseError(msg: String?): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", "")
        putJsonObject("error") {
            put("code", -32700)
            put("message", "parse error: ${msg ?: "unknown"}")
        }
    }

    companion object {
        @Volatile
        var LAST_STATUS: String = "Stopped"
            internal set

        /**
         * Synchronous local probe of `http://127.0.0.1:<port>/health`.
         * Use to confirm the server is actually bound from within the app or UI tests.
         */
        fun selfTest(port: Int): kotlin.Result<String> = runCatching {
            val url = java.net.URL("http://127.0.0.1:$port/health")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                requestMethod = "GET"
            }
            try {
                val code = conn.responseCode
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                "$code $body"
            } finally {
                conn.disconnect()
            }
        }
    }
}

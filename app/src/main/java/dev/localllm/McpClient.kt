package dev.localllm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.util.concurrent.TimeUnit

@Serializable
data class ServerCfg(val url: String, val token: String = "")

class McpTool(val name: String, val description: String, val schema: JsonObject)

/** Minimal MCP client for the Streamable HTTP transport (JSON or SSE replies, session ids, bearer auth). */
class McpClient(private val url: String, private val bearer: String?) {
    companion object {
        const val PROTOCOL = "2025-06-18"
        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        private val JSON_MT = "application/json".toMediaType()
    }

    private var session: String? = null
    private var negotiated: String? = null
    private var nextId = 1
    var tools: List<McpTool> = emptyList(); private set

    suspend fun connect() {
        val init = rpc("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL)
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", "local-llm-android"); put("version", "0.1") })
        }).jsonObject
        negotiated = init["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: PROTOCOL
        notify("notifications/initialized")

        val all = mutableListOf<McpTool>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        do {
            val r = rpc("tools/list", cursor?.let { c -> buildJsonObject { put("cursor", c) } }).jsonObject
            val page = r["tools"] as? JsonArray ?: error("tools/list missing tools array")
            page.forEach { t ->
                val o = t.jsonObject
                val name = o["name"]!!.jsonPrimitive.content
                check(seenNames.add(name)) { "Duplicate tool '$name' from $url" }
                all += McpTool(
                    name,
                    o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    o["inputSchema"] as? JsonObject ?: error("Tool missing inputSchema")
                )
            }
            cursor = r["nextCursor"]?.jsonPrimitive?.contentOrNull
            cursor?.let { check(seenCursors.add(it)) { "Repeated tools/list cursor" } }
        } while (cursor != null)
        tools = all
    }

    suspend fun callTool(name: String, args: JsonObject): String {
        val r = rpc("tools/call", buildJsonObject { put("name", name); put("arguments", args) }).jsonObject
        val text = r["content"]?.jsonArray?.joinToString("\n") { c ->
            val o = c.jsonObject
            val type = o["type"]?.jsonPrimitive?.contentOrNull
            if (type == "text") o["text"]?.jsonPrimitive?.contentOrNull ?: "" else "[$type content omitted]"
        } ?: r.toString()
        val out = if (r["isError"]?.jsonPrimitive?.booleanOrNull == true) "ERROR: $text" else text
        // small models drown in big tool outputs; keep the context lean
        return if (out.length > 4000) out.take(4000) + "\n…[truncated]" else out
    }

    private suspend fun rpc(method: String, params: JsonObject?): JsonElement = withContext(Dispatchers.IO) {
        val id = nextId++
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method)
            if (params != null) put("params", params)
        }
        post(body).use { resp ->
            val ct = resp.header("Content-Type").orEmpty()
            val msg: JsonObject =
                if (ct.startsWith("text/event-stream")) readSse(resp.body!!.source(), id)
                else Json.parseToJsonElement(resp.body!!.string()).jsonObject
            msg["error"]?.let { throw IllegalStateException("MCP error: $it") }
            msg["result"] ?: JsonNull
        }
    }

    private suspend fun notify(method: String) = withContext(Dispatchers.IO) {
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }).close()
    }

    private fun post(body: JsonObject): Response {
        val rb = Request.Builder().url(url)
            .post(body.toString().toRequestBody(JSON_MT))
            .header("Accept", "application/json, text/event-stream")
        session?.let { rb.header("Mcp-Session-Id", it) }
        negotiated?.let { rb.header("MCP-Protocol-Version", it) }
        bearer?.takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
        val resp = http.newCall(rb.build()).execute()
        if (!resp.isSuccessful) {
            val code = resp.code; resp.close()
            throw IllegalStateException("HTTP $code from $url")
        }
        resp.header("Mcp-Session-Id")?.let { session = it }
        return resp
    }

    private fun readSse(src: BufferedSource, id: Int): JsonObject {
        val data = StringBuilder()
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (line.startsWith("data:")) {
                data.append(line.removePrefix("data:").trimStart()).append('\n')
            } else if (line.isEmpty() && data.isNotEmpty()) {
                val o = runCatching { Json.parseToJsonElement(data.toString()).jsonObject }.getOrNull()
                data.clear()
                if (o != null && o["id"]?.jsonPrimitive?.intOrNull == id) return o
            }
        }
        throw IllegalStateException("SSE stream ended without a response")
    }
}

/** Complete catalog, distinct aliases, and atomic replacement. */
class McpHub {
    private data class Route(val client: McpClient, val originalName: String)
    private data class Catalog(val tools: List<McpTool>, val routes: Map<String, Route>)
    private val mutex = Mutex()
    @Volatile private var catalog = Catalog(emptyList(), emptyMap())
    val tools: List<McpTool> get() = catalog.tools
    suspend fun connect(servers: List<ServerCfg>): List<String> = mutex.withLock {
        val sources = mutableListOf<ToolSource>(); val clients = mutableMapOf<Int, McpClient>()
        val errors = mutableListOf<String>()
        for ((index, server) in servers.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                val client = McpClient(server.url, server.token); client.connect()
                clients[index] = client; sources += ToolSource(index, server.url, client.tools)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { errors += "${server.url}: ${e.message}" }
        }
        currentCoroutineContext().ensureActive()
        val bindings = ToolRegistry.build(sources)
        catalog = Catalog(bindings.map { it.tool }, bindings.associate {
            it.tool.name to Route(clients.getValue(it.index), it.originalName)
        })
        errors
    }
    suspend fun call(name: String, args: JsonObject): String = mutex.withLock {
        val route = catalog.routes[name] ?: return@withLock "ERROR: unknown tool '$name'"
        try { route.client.callTool(route.originalName, args) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { "ERROR: ${e.message}" }
    }
}

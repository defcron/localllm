package dev.localllm

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class UiMsg(val role: String, text: String) { var text by mutableStateOf(text) }

private class ToolCall(val name: String?, val args: JsonObject?, val raw: String)

private val CALL_RE = Regex("<tool_call>\\s*(.*?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)

private fun parseToolCalls(text: String): List<ToolCall> = CALL_RE.findAll(text).map { m ->
    val raw = m.groupValues[1]
    runCatching {
        val o = Json.parseToJsonElement(raw).jsonObject
        val a = o["arguments"] ?: o["parameters"]
        val args = when (a) {
            is JsonObject -> a
            is JsonPrimitive -> if (a.isString) Json.parseToJsonElement(a.content).jsonObject else JsonObject(emptyMap())
            else -> JsonObject(emptyMap())
        }
        ToolCall(o["name"]!!.jsonPrimitive.content, args, raw)
    }.getOrElse { ToolCall(null, null, raw) }
}.toList()

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val engine = LlamaEngine()
    private val hub = McpHub()
    private val modelStore = ModelStore(app)
    private val history = mutableListOf<Msg>()
    private var sys = ""                       // cached: a byte-stable prefix keeps the KV cache warm
    private var job: Job? = null
    private var connectJob: Job? = null
    private var connectVersion = 0
    @Volatile private var stopped = false

    val messages = mutableStateListOf<UiMsg>()
    var status by mutableStateOf("No model loaded"); private set
    var busy by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var connecting by mutableStateOf(false); private set
    var activeBackend by mutableStateOf("No model loaded"); private set
    private var loadedSettings: GpuSettings? = null
    private var loadedContext = 0
    var gpuSettings by mutableStateOf(GpuSettings.fromStored(
        prefs.getString("gpuBackend", null), prefs.getInt("gpuLayers", DEFAULT_GPU_LAYERS).let { if (it == 99) DEFAULT_GPU_LAYERS else it }
    )); private set
    val hasSavedModel: Boolean get() = prefs.getString("model", null) != null
    val settingsPending: Boolean get() = modelReady && (loadedSettings != gpuSettings || loadedContext != nCtx)
    var modelReady by mutableStateOf(false); private set
    var toolCount by mutableIntStateOf(0); private set
    var toolsSent by mutableIntStateOf(0); private set
    var nCtx by mutableIntStateOf(prefs.getInt("nCtx", DEFAULT_CONTEXT_SIZE).let { if (it == 4096) DEFAULT_CONTEXT_SIZE else it })
    var servers by mutableStateOf(
        runCatching { Json.decodeFromString<List<ServerCfg>>(prefs.getString("servers", "[]")!!) }
            .getOrDefault(emptyList())
    ); private set

    init {
        reportPreviousCrash()
        prefs.getString("model", null)?.let { loadModel(Uri.parse(it), persist = false) }
        if (servers.isNotEmpty()) connectServers()
    }

    /** On launch: if the previous process crashed, post what happened into the chat (once per crash). */
    private fun reportPreviousCrash() {
        val wasGenerating = prefs.getBoolean("generating", false)
        prefs.edit().putBoolean("generating", false).apply()
        val app = getApplication<Application>()
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val exit = am.getHistoricalProcessExitReasons(app.packageName, 0, 5).firstOrNull {
            it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_CRASH
        } ?: return
        if (exit.timestamp <= prefs.getLong("crashReported", 0L)) return
        prefs.edit().putLong("crashReported", exit.timestamp).apply()
        val trace = exit.traceInputStream?.use { s ->
            s.bufferedReader().readText().lineSequence()
                .filter { Regex("signal|Abort|backtrace|#\\d+ pc|Cause|Fatal|Exception").containsMatchIn(it) }
                .take(30).joinToString("\n")
        }.orEmpty()
        val kind = if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "native crash" else "Java crash"
        val during = if (wasGenerating) " while generating a reply" else ""
        val hint = "Backend ${gpuSettings.backend.label}, -ngl ${gpuSettings.gpuLayers}. " +
            "To check whether the GPU is involved, set GPU layers to 0 (CPU) and reload the model."
        val details = trace.ifBlank { exit.description.orEmpty() }
        messages += UiMsg("assistant", "⚠️ The previous run had a $kind$during. $hint" +
            if (details.isBlank()) "" else "\n\n$details")
    }

    fun loadModel(uri: Uri, persist: Boolean = true) {
        if (busy || loading) return
        loading = true; modelReady = false
        val selected = gpuSettings
        val selectedContext = nCtx
        status = "Preparing ${selected.backend.label} · -ngl ${selected.gpuLayers}…"
        activeBackend = "No model loaded"
        viewModelScope.launch {
            try {
                engine.free()
                val app = getApplication<Application>()
                if (persist) runCatching {
                    app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                status = if (persist) "Importing GGUF to private storage…" else "Preparing cached GGUF…"
                val file = modelStore.prepare(uri, reimport = persist)
                status = "Loading ${selected.backend.label} · -ngl ${selected.gpuLayers}…"
                val error = engine.load(file.absolutePath, selectedContext, selected, app.cacheDir.absolutePath)
                check(error.isEmpty()) { error.trim() }
                activeBackend = engine.backendInfo()
                loadedSettings = selected; loadedContext = selectedContext
                prefs.edit().putString("model", uri.toString()).putInt("nCtx", selectedContext).apply()
                modelStore.retainOnly(file)
                newChat(); modelReady = true
                status = "Ready · $activeBackend · ctx $selectedContext"
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { status = "Load failed: ${e.message}" }
            finally { loading = false }
        }
    }
    fun applyGpuSettings(settings: GpuSettings) {
        if (busy || loading) return
        gpuSettings = settings
        prefs.edit().putString("gpuBackend", settings.backend.id).putInt("gpuLayers", settings.gpuLayers).apply()
    }
    fun reloadModel() {
        if (busy || loading) return
        prefs.getString("model", null)?.let { loadModel(Uri.parse(it), persist = false) }
    }

    fun saveServers(list: List<ServerCfg>) {
        if (busy || loading) return
        servers = list
        prefs.edit().putString("servers", Json.encodeToString(list)).apply()
        connectServers()
    }

    fun refreshTools() { if (!busy && !loading) connectServers() }
    private fun connectServers() {
        connectJob?.cancel(); connecting = true
        val version = ++connectVersion
        val configured = servers.toList()
        connectJob = viewModelScope.launch {
            try {
                val errors = hub.connect(configured)
                toolCount = hub.tools.size; sys = ""; systemPrompt()
                status = "MCP: $toolCount tools · all $toolsSent included" +
                    if (errors.isEmpty()) "" else " · " + errors.joinToString("; ")
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { status = "MCP failed: ${e.message}" }
            finally { if (version == connectVersion) connecting = false }
        }
    }
    fun newChat() { if (!busy) { history.clear(); messages.clear() } }
    fun stop() { stopped = true; engine.cancel() }
    private fun systemPrompt(): String {
        if (sys.isNotEmpty()) return sys
        toolsSent = hub.tools.size
        return ToolPrompt.build(hub.tools).also { sys = it }
    }

    fun send(text: String) {
        if (busy || loading || connecting || !modelReady || text.isBlank()) return
        stopped = false; busy = true
        messages += UiMsg("user", text); history += Msg("user", text)
        job = viewModelScope.launch {
            try {
                var round = 0
                while (round < 6) {            // cap agent loop: model -> tool -> model ...
                    round++
                    val prompt = engine.template(listOf(Msg("system", systemPrompt())) + history)
                    if (prompt.isEmpty()) { status = "This model's chat template isn't supported"; break }

                    val ui = UiMsg("assistant", ""); messages += ui
                    val sb = StringBuilder(); var last = 0L
                    val toolClose = "</tool_call>"
                    var toolCloseTail = ""
                    var toolCloseSeen = false
                    ui.text = "Reading prompt…"
                    val poll = launch {                        // live prefill progress (slow on phones)
                        while (true) {
                            val p = engine.progress()
                            val done = (p shr 32).toInt(); val tot = (p and 0xffffffffL).toInt()
                            if (tot > 0 && done < tot && (ui.text.isEmpty() || ui.text.startsWith("Reading prompt")))
                                ui.text = "Reading prompt… $done / $tot tokens"
                            delay(300)
                        }
                    }
                    prefs.edit().putBoolean("generating", true).commit()   // survives a native crash
                    val n = try {
                        engine.generate(prompt, 1024, 0.7f) { piece ->
                            sb.append(piece)
                            if (!toolCloseSeen) {
                                val probe = toolCloseTail + piece
                                toolCloseSeen = probe.contains(toolClose)
                                toolCloseTail = probe.takeLast(toolClose.length - 1)
                            }
                            val now = SystemClock.uptimeMillis()
                            if (now - last > 40) {
                                val snapshot = sb.toString()
                                viewModelScope.launch(Dispatchers.Main) { ui.text = snapshot }
                                last = now
                            }   // throttle recomposition
                            !stopped && !toolCloseSeen
                        }
                    } finally { poll.cancel(); prefs.edit().putBoolean("generating", false).apply() }
                    ui.text = sb.toString()
                    if (n >= 0) status = "Ready · $activeBackend · " + engine.stats()
                    if (n >= 0 && sb.isEmpty() && !stopped) {
                        ui.text = "(no output: the model ended its turn immediately, wrong chat template or model?)"
                        break
                    }

                    if (n == -2 && history.size > 2) {         // context full: drop the oldest exchange, retry
                        messages.remove(ui); repeat(2) { history.removeAt(0) }; round--; continue
                    }
                    if (n < 0) {
                        ui.text = "⚠️ " + engine.lastError() + if (n == -2)
                            "\nAll $toolsSent MCP tools retained. Increase context and reload the model." else ""
                        status = "Generation failed"; break
                    }

                    history += Msg("assistant", sb.toString())
                    if (stopped) break
                    val calls = parseToolCalls(sb.toString())
                    if (calls.isEmpty()) break

                    val resp = StringBuilder()
                    for (c in calls) {
                        val name = c.name
                        val result = if (name == null) "ERROR: could not parse tool call JSON"
                                     else hub.call(name, c.args ?: JsonObject(emptyMap()))
                        messages += UiMsg("tool", "🔧 ${name ?: "invalid call"}(${c.args ?: c.raw})\n→ ${result.take(600)}")
                        resp.append("<tool_response>\n").append(result).append("\n</tool_response>\n")
                    }
                    history += Msg("user", resp.toString().trimEnd())   // ChatML models expect tool output as a user turn
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                status = "Chat failed: ${e.message}"
                messages += UiMsg("assistant", "⚠️ ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() { engine.cancel() }
}
